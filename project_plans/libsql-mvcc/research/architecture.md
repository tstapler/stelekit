# Architecture Research: Rust JNI Bridge Design + Async/Blocking Patterns

## 1. Global `tokio::runtime::Runtime` with `block_on` — Safety and Alternatives

**Is it safe?** Yes, for the SteleKit use case. The key invariant: `block_on` may not be called from inside an existing Tokio runtime context (it panics with "Cannot start a runtime from within a runtime"). Java threads that call JNI functions are OS threads — they are never Tokio tasks — so this panic cannot occur.

**The current implementation** (`native/libsql/src/lib.rs` lines 34–41) uses `once_cell::sync::Lazy<Runtime>`:
```rust
static RT: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("stelekit-libsql")
        .enable_all()
        .build()
        .expect("Failed to create Tokio runtime for libsql JNI bridge")
});
```
This is the accepted pattern in the `jni` crate ecosystem. The runtime is initialized exactly once on first use via `Lazy`, is thread-safe (`Sync`), and lives for the process lifetime.

**Alternatives and trade-offs:**

| Approach | Pros | Cons | Verdict |
|---|---|---|---|
| Global `Lazy<Runtime>` (current) | Simple, shared thread pool, no per-connection overhead | Runtime lives forever; panics on nested `block_on` (impossible here since callers are Java threads) | **Recommended** |
| Per-database `Runtime` (stored in `DbHandle`) | Isolated failure domains | Expensive: N runtimes × thread pool per connection, complex drop ordering | Overkill for embedded use |
| Per-thread `Runtime` via `thread_local!` | True isolation | Creates a new runtime on every Java thread, including short-lived IO threads — resource explosion with `Dispatchers.IO` (64-thread pool) | Rejected |
| `tokio::task::spawn_blocking` | Allows calling from async context | Requires an outer runtime already running — circular for JNI bootstrap | Not applicable |

**Worker thread count**: 2 is correct for embedded I/O. libsql's internal async work (WAL framing, network for future sync) is I/O-bound. Adding more workers wastes memory without throughput gains for local-file workloads. The Kotlin side (8 JVM connections, `Dispatchers.IO`) is the true concurrency bottleneck.

**No nested-runtime panic risk**: The Kotlin `DatabaseWriteActor` uses `suspend fun` and coroutines, but JNI calls from Kotlin are ordinary blocking calls from the JVM's perspective — they land on a JVM thread, not a Tokio task. `RT.block_on(...)` is safe at every call site.

---

## 2. Box-as-Handle Pattern — Soundness Requirements

**Pattern** (`lib.rs` lines 79–96):
```rust
fn alloc<T>(val: T) -> jlong {
    Box::into_raw(Box::new(val)) as jlong
}
unsafe fn deref_mut<T>(handle: jlong) -> &'static mut T {
    &mut *(handle as *mut T)
}
unsafe fn free<T>(handle: jlong) {
    drop(Box::from_raw(handle as *mut T));
}
```

**Soundness requirements** — all three must be satisfied, and the current design satisfies them:

1. **Exclusive ownership per JNI call**: Each `deref_mut` call creates a `&'static mut` reference. Rust's aliasing rules require that no two `&mut` references to the same allocation exist simultaneously. This is enforced at the Kotlin level by the connection pool (`ArrayBlockingQueue`): `acquireConn()` removes a handle from the queue, giving exclusive ownership to one caller; `releaseConn()` returns it. Two JVM threads cannot simultaneously hold `&mut` to the same `ConnHandle`.

2. **No concurrent mutation**: `Database::connect` is called via `deref_mut::<DbHandle>` in `openConnection`. If `Database::connect` takes `&self` internally (as is common for `Arc`-wrapped state), this is fine. If it takes `&mut self`, concurrent calls from multiple JVM threads (each calling `openConnection` simultaneously) would be UB. Mitigation: pool all connections at startup in a single-threaded context (the `PooledJdbcSqliteDriver` constructor pattern), avoiding concurrent `openConnection` calls during steady-state operation.

3. **Freed exactly once**: Kotlin's `close()` drains the pool and calls `LibsqlJni.closeConnection(handle)` for each. The `free::<T>` function calls `Box::from_raw`, which transfers ownership back to Box and drops it. Calling this twice would be a double-free (UB). The `closed: AtomicBoolean` guard (from `PooledJdbcSqliteDriver`) pattern should be replicated in `JvmLibsqlDriver` to prevent `close()` being called twice.

**Can two JNI functions holding `&mut` to the same handle run on different JVM threads simultaneously?** No, and the pool enforces this. A handle removed from `ArrayBlockingQueue` is exclusively owned by one thread until `releaseConn` puts it back. The pool is the synchronization primitive — it is not just a performance optimization.

**`&'static mut` lifetime annotation**: This is necessary because Rust cannot verify the lifetime of a raw pointer. The `'static` is a white lie to the compiler that is made sound by the exclusivity guarantee above. Alternative: use `unsafe { &mut *ptr }` without a named lifetime (equivalent but slightly less readable).

---

## 3. `libsql::Connection::query` is `&mut self` — The Current Solution

**The problem**: `Connection::query` takes `&mut self`, but the code needs to share a raw pointer across an `async` block boundary. A `&mut ConnHandle` borrow would not satisfy Rust's ownership rules inside `RT.block_on(async { ... })` because the borrow must be `'static` to cross the async boundary.

**Current solution** (`lib.rs` lines 385–389):
```rust
let conn_ptr = conn_handle as *mut ConnHandle;
let result = RT.block_on(async {
    // SAFETY: conn_ptr valid; no concurrent mutation (enforced by Kotlin connection pool).
    let conn = unsafe { &(*conn_ptr).conn };
    let mut rows = conn.query(&sql, libsql::params_from_iter(params)).await?;
    ...
});
```

The `let conn = unsafe { &(*conn_ptr).conn }` creates a shared reference `&Connection` from a raw pointer. This works **only if** `Connection::query` is actually `&self` despite its signature appearing as `&mut self` in some versions. In recent libsql versions (0.6.x), `Connection` is `Arc`-wrapped internally and `query` may be `&self`. If `query` is truly `&mut self`, this is technically UB — a shared reference is used where an exclusive reference is required.

**Safer alternatives:**
- **`Mutex<Connection>` in `ConnHandle`**: Wrap `conn: Mutex<Connection>`. Inside the async block: `let mut conn = conn_handle.conn.lock().await; conn.query(...)`. This is sound but adds async locking overhead.
- **`UnsafeCell<Connection>`**: Allows interior mutation through a shared reference, placing the invariant enforcement on the caller (the pool already provides this guarantee). More explicit about the intent.
- **Clone SQL + params, use `block_on` with `&mut`**: Extract `sql` and `params` before entering `block_on`, then use `deref_mut` inside synchronously — but `block_on` takes a `Future`, so the `&mut conn` would need to be `'static` or the future must not be `Send`. Using `block_on` with a local `!Send` future is valid since the runtime is multi-threaded but `block_on` pins to the calling thread.

**Recommendation**: Verify libsql 0.6.x's actual `Connection::query` signature. If it is `&self` (likely, since `Connection` is `Arc<RawConn>`), the current code is sound. If `&mut self`, switch to `UnsafeCell<Connection>` with a doc comment explaining the pool exclusivity invariant.

---

## 4. `RegisterNatives` vs `#[no_mangle]` — Is the Switch Worth It?

**Current approach** (`lib.rs`): `#[no_mangle] pub extern "system" fn Java_dev_stapler_stelekit_db_libsql_LibsqlJni_*` — JVM resolves by C symbol name following JNI name mangling.

**`RegisterNatives` approach**: Call `env.register_native_methods(class, &methods)` from `JNI_OnLoad`, providing a `JNINativeMethod` array mapping names to function pointers. The JVM resolves at load time without name lookup.

**Trade-offs:**

| Concern | `#[no_mangle]` | `RegisterNatives` |
|---|---|---|
| Package/class rename safety | Breaks (name in symbol must match package) | Safe — names decoupled from symbol names |
| Binary size | Larger symbol table | Slightly smaller |
| JVM resolution timing | Lazy (on first call) | Eager (at `System.loadLibrary` time) |
| Error detection | ClassNotFoundException at runtime | `RegisterNatives` throws at load time |
| Complexity | None | ~30 lines of `JNI_OnLoad` boilerplate |
| LTO / stripping | Symbols must be exported (limits `strip = true` benefit) | Same — exported for `JNI_OnLoad` |

**Verdict for SteleKit**: `#[no_mangle]` is fine. The `LibsqlJni` object's package (`dev.stapler.stelekit.db.libsql`) is stable and checked into the codebase. `RegisterNatives` becomes worthwhile only when: (a) distributing a native library separately from Java sources where package changes are likely, or (b) obfuscating symbol names for security. Neither applies here. The `#[no_mangle]` approach also makes the relationship between Kotlin declarations and Rust implementations visually obvious.

If `RegisterNatives` were adopted, the `jni` crate's `register_native_methods` API would be used in `JNI_OnLoad`. This is a non-breaking change at runtime but would require keeping both the Kotlin `external fun` signatures and the Rust `NativeMethod` array in sync manually — an additional maintenance burden.

---

## 5. `PooledJdbcSqliteDriver` — What `JvmLibsqlDriver` Must Replicate

**Source**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/PooledJdbcSqliteDriver.kt`

The existing driver is the reference implementation. `JvmLibsqlDriver` (already partially implemented in `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/libsql/JvmLibsqlDriver.kt`) mirrors most of its design. Key behaviors to preserve:

### Connection pool semantics

`PooledJdbcSqliteDriver` uses `ArrayBlockingQueue<Connection>` pre-filled at construction. For **file databases**: non-blocking poll; if empty, create an overflow connection that is closed when the pool is full on return (`pool.offer()`). For **in-memory databases**: blocking poll with 50 ms timeout loop checking `closed` flag — overflow would get a schema-less DB.

`JvmLibsqlDriver` currently uses the same `ArrayBlockingQueue<Long>` pattern with `pool.poll() ?: LibsqlJni.openConnection(dbHandle)` for overflow. This is correct. However, `JvmLibsqlDriver` does not yet have the in-memory vs. file distinction — libsql supports `:memory:` via `Builder::new_local(":memory:")`, and the same blocking-only semantics should apply.

### Listener registry

Both use `ConcurrentHashMap<String, CopyOnWriteArrayList<Query.Listener>>` with deduplication in `notifyListeners` via `LinkedHashSet`. This is correctly replicated in `JvmLibsqlDriver`.

### `closed` guard

`PooledJdbcSqliteDriver` has `private val closed = AtomicBoolean(false)`. `JvmLibsqlDriver` does not. This matters for: (1) preventing double-close, (2) `getConnection()` on an already-closed driver should throw rather than silently succeed.

### Pool metrics

`PooledJdbcSqliteDriver` implements `PoolWaitMetrics` with `AtomicLong` counters for pool wait time, reported as `PoolWaitSnapshot`. `JvmLibsqlDriver` should implement the same interface or an equivalent for benchmark comparison.

### `DriverFactory` integration

`DriverFactory.jvm.kt` calls `SteleDatabase.Schema.create(driver)`, `PRAGMA optimize=0x10002`, and `MigrationRunner.applyAll(driver)` after creating a driver. `createLibsqlDriver(path)` must follow the same initialization sequence. The `DriverFactory` already handles directory creation for JDBC paths — the libsql path constructor should do the same via Kotlin `File(path).parentFile?.mkdirs()`.

### Overflow connection lifecycle

When pool is full on `closeConnection`/`releaseConn`, `PooledJdbcSqliteDriver` calls `connection.close()` on the overflow. `JvmLibsqlDriver` calls `LibsqlJni.closeConnection(handle)`. This is the correct analog — verified in the current implementation.

---

## 6. Test Infrastructure for `SqlDriver` Implementations

### Existing test patterns

**`PooledJdbcSqliteDriverTest`** (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/PooledJdbcSqliteDriverTest.kt`) is the model to replicate for `JvmLibsqlDriver`. It covers:
- Connection reuse (`assertSame` after get/close/get cycle)
- Distinct connections under simultaneous checkout
- Overflow creation and closure when pool exhausted
- In-memory blocking semantics (`CountDownLatch` across threads)
- Listener add/notify/remove/deduplication
- Close clears listeners and closes all connections
- Real SQL round-trip via `SteleDatabase(driver)` — full schema creation, insert, read-back
- Concurrent readers (20 coroutines on 8 threads, `awaitAll`)

**`MigrationTestHarness`** (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/MigrationTestHarness.kt`) provides a reusable harness pattern: `DriverFactory().createDriver("jdbc:sqlite::memory:")` + `SteleDatabase(driver)` + in-memory repositories. A `LibsqlTestHarness` analogue would use `createLibsqlDriver(":memory:")` or a temp file.

**`BlockInsertBenchmarkTest`** (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BlockInsertBenchmarkTest.kt`) shows the concurrent-write benchmark pattern: create a `RepositoryFactoryImpl` with a real driver, run N insert iterations via `BlockStateManager.addBlockToPage`, measure wall-clock P99 with `CountDownLatch` synchronization.

### Test suite for `JvmLibsqlDriver`

Minimum required tests (in `jvmTest` — native library must be loaded; `businessTest`/`commonTest` cannot load JNI):

| Test | What it proves |
|---|---|
| Schema round-trip | `SteleDatabase.Schema.create` + insert + read-back |
| `BEGIN CONCURRENT` commit | Two concurrent writers on disjoint rows, both commit |
| `BEGIN CONCURRENT` conflict (rare) | Simulate page-level conflict, verify `SQLITE_BUSY_SNAPSHOT` surfaces as an exception |
| Transaction rollback | `endTransaction(false)` leaves DB unchanged |
| Nested savepoints | Inner savepoint rollback, outer commit |
| Listener notify | `addListener` / `notifyListeners` fires exactly once per key |
| Pool overflow | Drain pool, acquire extra connection, verify it's usable and closed on full-pool return |
| `close()` idempotency / safety | Double-close does not throw or corrupt handles |
| Migration compatibility | `MigrationRunner.applyAll` succeeds on libsql driver |

### Test isolation: JNI library loading

`LibsqlJni` uses a classpath-resource extraction pattern (extracts to `java.io.tmpdir`). Tests that use `JvmLibsqlDriver` require the native `.so` on the classpath — this means either: (a) the Bazel build is run first and the `.so` is placed in `kmp/src/jvmMain/resources/native/linux-x86_64/`, or (b) tests are `@Ignore`d in CI until the Bazel build is integrated into `ciCheck`. The `PooledJdbcSqliteDriverTest` has no such dependency and runs unconditionally. A `@Assume`/`assumeTrue(libsqlAvailable())` guard should gate `JvmLibsqlDriver` tests.

### Benchmark extension

The concurrent-write benchmark should match `BlockInsertBenchmarkTest`'s methodology: create both a `PooledJdbcSqliteDriver`-backed and a `JvmLibsqlDriver`-backed `RepositoryFactoryImpl` pointed at a temp file, run identical concurrent-write workloads (≥4 writers, 200+ inserts each), compare P99 latency. The requirements specify ≥10% P99 reduction or ≥10% throughput gain as the success criterion.
