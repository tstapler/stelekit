# Adversarial Review: libsql-mvcc Plan

**Verdict**: BLOCKED

---

## Blockers (must fix before implementation)

### 1. MVCC local mode is unverified and the pitfalls agent directly contradicts the plan's premise

**Issue**: The pitfalls research (`research/pitfalls.md` §3) states unambiguously: "`BEGIN CONCURRENT`
is NOT available in the standard local file-based mode offered by the `libsql` Rust crate." It cites
the libsql C fork architecture, open issue #1553, and Turso's ground-up Rust rewrite as convergent
evidence. The ADR (ADR-002) then contradicts this by asserting "The pitfalls research confirmed that
`BEGIN CONCURRENT` is available in local embedded mode." This is a direct misrepresentation of the
pitfalls document. The pitfalls document says the opposite.

**Why it blocks**: The entire benchmark goal (≥10% P99 improvement) is premised on `BEGIN CONCURRENT`
delivering concurrent writes. The plan's fallback path (drop to WAL + `BEGIN`) serializes writes just
like the existing `PooledJdbcSqliteDriver` does today. If local MVCC does not work, the success
criterion SC-3 is structurally unachievable — not just unlikely.

**Required fix**: Before writing any code, run an empirical probe: open a libsql 0.9.30 local file
database via `Builder::new_local`, issue `PRAGMA journal_mode='mvcc'`, and verify with
`PRAGMA journal_mode` that the mode is actually set to `'mvcc'` (not silently ignored or silently
remaining `'wal'`). Then launch two concurrent `BEGIN CONCURRENT` transactions and commit both — if
either returns `SQLITE_BUSY_SNAPSHOT` correctly or both commit, MVCC is available. If `PRAGMA
journal_mode='mvcc'` returns `'wal'` instead, the plan must be reframed before implementation
begins (Option 3 from pitfalls: WAL with a Rust JNI connection pool, no MVCC). The ADR must be
rewritten to reflect actual empirical findings.

---

### 2. No test explicitly asserts MVCC is active — the fallback silently defeats the benchmark

**Issue**: Story 1.4 adds `isDatabaseMvccEnabled()` and Story 5.10 tests `SQLITE_BUSY_SNAPSHOT`
surfacing, but no test asserts that `isDatabaseMvccEnabled()` returns `true` on the target libsql
version before the benchmark runs. The benchmark test (Story 6.1.3) calls `assumeTrue(libsqlNativeAvailable())` but does not call `assumeTrue(isDatabaseMvccEnabled(driver))`. If MVCC silently falls back to WAL, the benchmark will run comparing two WAL drivers — and the assertion `libsqlP99 <= jdbcP99 * 0.90` is already wrapped in `@Ignore` (Story 6.1.5). The result: the benchmark always passes, MVCC is never confirmed active, and SC-3 is never actually verified.

**Why it blocks**: Success criterion SC-3 requires a ≥10% improvement. If the benchmark is structured
to always pass regardless of MVCC being active, the criterion is unmeasurable.

**Required fix**: Add `assumeTrue(driver.isDatabaseMvccEnabled()) { "MVCC journal mode not active — benchmark meaningless" }` to the benchmark test. Remove the `@Ignore` wrapper from the P99 assertion when MVCC is confirmed active; keep it only for the WAL-fallback path. Add a dedicated test `mvccPragma_isActiveAfterOpen` that opens a driver and asserts `isDatabaseMvccEnabled() == true` — this test must not be skippable.

---

### 3. `SQLITE_BUSY_SNAPSHOT` detection relies on string parsing of an error code that may not appear in libsql error messages

**Issue**: Story 1.5.1 says: check if `e.to_string()` "contains `"517"` or the substring `"SQLITE_BUSY_SNAPSHOT"`". The pitfalls document (risk table row 8) itself flags this as fragile. The libsql Rust error type (`libsql::Error`) is an enum wrapping various backends; the `Display` output of the error is not part of libsql's public API and can change between patch releases. The plan's own risk register acknowledges this: "add a dedicated `isErrorBusySnapshot(conn_handle: Long): Boolean` JNI function that checks the raw SQLite extended error code (`sqlite3_extended_errcode`) for value 517." Story 1.5 does not implement this safer path — it implements exactly the fragile string-parsing approach the risk register says to avoid.

**Why it blocks**: `DatabaseWriteActor` MVCC retry (a post-driver milestone) depends on correctly
identifying `SQLITE_BUSY_SNAPSHOT`. If story 1.5 ships with string parsing, any libsql patch that
changes the error message format silently breaks the retry path — but tests will not catch it because
Story 5.10's test just asserts the exception type, not that the underlying error code was correctly
identified from the SQLite layer.

**Required fix**: Replace the string-parsing approach in Story 1.5.1 with the `sqlite3_extended_errcode`
approach described in the risk register. In `libsql-sys` (the underlying FFI layer), `sqlite3_extended_errcode` is accessible via raw FFI. The Rust function should call `libsql_sys::sqlite3_extended_errcode(db_ptr)` after a failed `execute` and compare against `517i32`. If libsql does not expose the raw sqlite3 pointer at the public Rust API level, use `e.to_string().contains("517")` as a temporary measure but mark it `// FIXME: fragile — replace with errcode JNI function` and track it as a known debt item with a corresponding failing test that will break if the error string format changes.

---

### 4. `panic = "unwind"` with `AssertUnwindSafe` is potentially unsound for libsql's internal types

**Issue**: Story 1.3.3 says "Wrap the raw-pointer capture in `AssertUnwindSafe` with a safety comment." `AssertUnwindSafe` is a promise to the compiler that the wrapped value will not leave the program in an inconsistent state if a panic unwinds through it. The plan claims the "pool-exclusivity invariant" makes this sound. But libsql's `Connection` type wraps a C SQLite database handle that has internal state (transaction state, statement handles, B-tree page locks). If a panic fires inside `conn.execute(...).await` inside `block_on`, the libsql `Connection` has been partially advanced — its internal C-level state may be mid-transaction, with SQLite page locks held. `catch_unwind` stops the unwind but the `ConnHandle` (with its inconsistent `Connection`) is still in the pool and will be returned to the next caller.

**Why it blocks**: This is latent undefined behavior. A panicking Rust future that partially mutates the SQLite C state, then is `AssertUnwindSafe`-caught, leaves a `Connection` in the pool whose internal C state is undefined. The next caller gets that connection and executes queries against a potentially corrupted handle. The pitfalls document covers UB from panics but does not address the case where catch_unwind rescues a partially-executed async libsql call.

**Required fix**: After a `catch_unwind` fires in a JNI entry point that holds a `conn_handle`, the connection must be quarantined — removed from the pool and closed via `LibsqlJni.closeConnection`, not returned. Add a `panicked: AtomicBool` field to `ConnHandle` that `catch_unwind` sets to `true` before returning the sentinel. The Kotlin pool must check this flag on `releaseConn` and close instead of returning the connection to the pool. Document the invariant: a connection that has experienced a Rust panic is never reused.

---

### 5. `close()` is not thread-safe and has no drain latch — connections can escape after close

**Issue**: The current `JvmLibsqlDriver.close()` (line 193–198 of `JvmLibsqlDriver.kt`) does: `listeners.clear()`, `pool.drainTo(handles)`, then closes handles, then closes the database. Story 3.2 adds `closed.set(true)` as the first statement. But there is a TOCTOU window: thread A checks `!closed.get()` (false), passes the guard, calls `acquireConn()`, gets a handle from the pool. Concurrently thread B enters `close()`, sets `closed = true`, `pool.drainTo(handles)` — the handle is no longer in the pool (thread A holds it) — `pool.drainTo` returns zero, thread B calls `closeDatabase(dbHandle)`. Thread A now holds a `connHandle` whose underlying database has been freed, and calls `LibsqlJni.executeStatement(conn, stmt)` — use-after-free in Rust.

**Why it blocks**: The pitfalls document (§5) explicitly requires "drain all checked-out connections before returning, using a `CountDownLatch` or `Semaphore` to wait for in-flight queries to complete." Story 3.2 does not implement this. The `AtomicBoolean closed` guard alone is insufficient — it only prevents new acquisitions, not in-flight ones.

**Required fix**: Add a `Semaphore(poolSize)` (permit count = number of connections that can be outstanding simultaneously). `acquireConn()` acquires a permit; `releaseConn()` releases it. `close()` acquires all `poolSize` permits before proceeding to `pool.drainTo` and `closeDatabase`. This blocks close until all in-flight queries have returned their connections. Alternatively, a `ReentrantReadWriteLock` with readers being callers and the write lock being `close()` achieves the same effect.

---

### 6. Android NDK cross-compilation is not addressed — the plan removes it from CI but does not provide a working path

**Issue**: Story 2.3.5 says "Remove the `aarch64-apple-ios` cross-compilation target from `extra_target_triples` for this milestone." The requirements list `AndroidLibsqlDriver` as in-scope and the scope table includes "Android driver" as a deliverable. But the plan does not include any stories for building `aarch64-linux-android` / `x86_64-linux-android` native libraries. The pitfalls document (§4) identifies `libgcc`/`libunwind` replacement, the `rules_android_ndk` migration, and `-lpthread` issues as concrete blockers. These are not addressed anywhere in the plan.

**Why it blocks**: `AndroidLibsqlDriver` is listed as in-scope but the plan provides no mechanism to produce a working `.so` for Android ABI targets. Without the native library, `AndroidLibsqlDriver` cannot be tested or used. Either the Android driver must be explicitly moved to a future milestone (removing it from the success criteria), or the plan must add Android cross-compilation stories.

**Required fix**: Either (a) explicitly scope out `AndroidLibsqlDriver` and add it to the non-goals list, updating requirements accordingly; or (b) add an Epic 2.5 covering NDK r25c setup in Bazel 9.1.1 with `rules_android_ndk`, the `libgcc`/`libunwind` workaround, `-lpthread`/`-lrt` override, and CI verification that `aarch64-linux-android` and `x86_64-linux-android` builds produce loadable `.so` files.

---

## Concerns (should address)

### 1. Tokio `block_on` from Java threads — the safety argument is incomplete

**Issue**: The plan (AD-3) and the Rust doc comment in `lib.rs` assert "Java threads are never Tokio tasks, so nested-runtime panics cannot occur." This is true for JVM threads that are not Tokio pool workers. But the plan does not address a subtler scenario: if a dependency of libsql (e.g., `tokio-rusqlite` or a custom Tokio hook in libsql-sys) spawns a task that calls back into JNI on a Tokio worker thread, that worker thread would then call `block_on` on the same runtime — which panics with "Cannot start a runtime from within a Tokio runtime." The plan claims this cannot happen but provides no analysis.

**Risk if ignored**: If any transitive async callback crosses the Tokio/JNI boundary on a worker thread, the process aborts with a panic that `catch_unwind` cannot catch (because `panic = "unwind"` + `catch_unwind` does catch it, but only if the panic is the library type — Tokio's "Cannot call block_on" panic is a deliberate `panic!` that unwinds normally, so it would be caught). However, catching a "nested block_on" panic leaves the runtime in an undefined state.

**Recommended fix**: Add an explicit check at the top of each JNI entry point: `assert!(!tokio::runtime::Handle::try_current().is_ok(), "JNI function called from a Tokio context")`. This converts the latent panic into an immediate, diagnosable assertion failure. Document the invariant in the `lib.rs` module doc.

---

### 2. `Cargo.lock` determinism: the plan generates the lock file but does not pin it against CI drift

**Issue**: Story 2.2.1 says "run `cargo generate-lockfile` and commit `native/libsql/Cargo.lock`." The `crate.from_cargo` Bazel extension reads this file, but `crates_repository` (and `crate.from_cargo`) resolves crate versions at `bazel sync` time using the lockfile as a hint, not a hard constraint. If `bazel sync` is run on CI with a network that resolves to different versions than the developer's machine (e.g., a new crate patch was published between the two runs), the build graph will differ.

**Risk if ignored**: Non-reproducible builds; test passes locally but fails on CI due to a transitive crate update.

**Recommended fix**: Add `--lockfile_mode=error` to the CI Bazel invocation so that if `Cargo.lock` is out of date or resolves differently, the build fails immediately rather than silently using different versions. Document this flag in the CI workflow comments.

---

### 3. Resource extraction race in `LibsqlJni.loadNativeLibrary()` is not fixed by the plan

**Issue**: Story 3.4 adds versioned filenames but does not add synchronization. The `loadNativeLibrary()` function checks `if (!tmpLib.exists())` then writes to it — classic TOCTOU. Two JVM processes starting simultaneously on the same machine will both see the file as absent and both attempt to write it. On Linux the file write will succeed for both (overwriting each other), but `System.load()` may fail if the file is partially written by the other process.

The risk register mentions: "Synchronize `loadNativeLibrary()` on a class-level lock; or use `synchronized(LibsqlJni::class.java)`." Story 3.4 does not include this synchronization.

**Risk if ignored**: Flaky test failures in multi-process test environments (e.g., parallel Gradle test workers).

**Recommended fix**: Wrap the existence-check-and-write block in `synchronized(LibsqlJni::class.java) { ... }`. This prevents concurrent extraction within the same JVM process. For cross-process safety (two separate JVM instances), use a file lock: `FileChannel.open(lockPath, CREATE, WRITE).lock()` around the extraction.

---

### 4. `MigrationRunner` savepoint compatibility with `BEGIN CONCURRENT` is not tested at the right layer

**Issue**: Story 5.9 tests `MigrationRunner.applyAll(driver)` on a fresh DB (where it is a no-op). Story 5.5 tests nested savepoints within a `BEGIN CONCURRENT` transaction, which is the right structure. However, `MigrationRunner` opens its own transactions (not nested within a caller's `BEGIN CONCURRENT`) and uses savepoints internally. If `MigrationRunner` issues `BEGIN` followed by `SAVEPOINT sp_1`, and a concurrent `BEGIN CONCURRENT` from another connection is outstanding, the `SAVEPOINT` inside the `BEGIN` is SQLite standard behavior — not inside a `BEGIN CONCURRENT`. The concern is whether libsql's MVCC journal mode changes the semantics of a plain `BEGIN` / `SAVEPOINT` sequence.

**Risk if ignored**: Migration failures on existing databases when MVCC is enabled, or data corruption if savepoint semantics differ under the MVCC journal mode.

**Recommended fix**: Story 5.9 must test `MigrationRunner.applyAll` on a database that already has data (not just fresh) with the libsql driver and `isDatabaseMvccEnabled() == true`. Add a second migration (even a no-op `ALTER TABLE ADD COLUMN IF NOT EXISTS`) to verify the transaction+savepoint path runs correctly under MVCC.

---

### 5. Benchmark uses `DatabaseWriteActor` (single-writer serial) — it cannot demonstrate concurrent MVCC benefit

**Issue**: Story 6.1.3 runs 4 coroutines each calling `actor.saveBlock(block)`. `DatabaseWriteActor` is a single-coroutine actor that serializes all writes. With a single-writer actor, all 4 coroutines queue behind the actor's single dispatch loop — there is never more than one concurrent `BEGIN CONCURRENT` transaction outstanding. This is structurally equivalent to the current `PooledJdbcSqliteDriver` path and cannot demonstrate MVCC's benefit.

**Risk if ignored**: The benchmark will show negligible improvement (or possibly a regression due to JNI overhead) and the team will falsely conclude MVCC is not helping, when the actual problem is the benchmark architecture.

**Recommended fix**: The benchmark must bypass `DatabaseWriteActor` and directly use `driver.newTransaction()` from 4 concurrent threads simultaneously — each thread calling `newTransaction()`, performing inserts, then `endTransaction(true)` — with all 4 transactions overlapping in time. Only this structure will exercise `BEGIN CONCURRENT` concurrency. Add a second benchmark variant using `DatabaseWriteActor` to establish the production baseline.

---

### 6. The plan does not address `PooledJdbcSqliteDriver` remaining as the default in `DriverFactory`

**Issue**: The requirements constraint states "No breaking changes to existing `DriverFactory` or `DatabaseWriteActor` interfaces." ADR-002 states "The existing `PooledJdbcSqliteDriver` remains the default; `JvmLibsqlDriver` is opt-in." The plan adds `createLibsqlDriver()` as a separate method (Epic 4), which is correct. But the plan does not include a test that verifies `DriverFactory().createDriver(jdbcUrl)` (the existing default path) still works after the libsql changes are applied. Without this, a class-loading side effect from the new code (e.g., `LibsqlJni.loadNativeLibrary()` being triggered by class initialization) could break the JDBC path in `ciCheck`.

**Risk if ignored**: `./gradlew ciCheck` breaks because `LibsqlJni`'s `init` block triggers `loadNativeLibrary()` when the `libsql` native is absent, throwing an error that propagates to the existing test suite.

**Recommended fix**: Ensure `LibsqlJni`'s `init` / `loadNativeLibrary()` is not eagerly triggered unless `JvmLibsqlDriver` is explicitly instantiated. Move the `loadNativeLibrary()` call out of the `init` block and into `JvmLibsqlDriver`'s constructor, or make it lazy via `by lazy { }`. Add an explicit regression test: `existingDriverFactory_createDriver_worksWithoutLibsqlNative` that constructs `DriverFactory` and calls `createDriver(jdbcUrl)` on a machine without the native library present.

---

### 7. `execute()` return value semantics are wrong for INSERT — plan perpetuates the existing bug

**Issue**: Looking at `JvmLibsqlDriver.execute()` (lines 124–140): if `executeStatement` returns a negative value (error), the code falls through and returns `connectionLastInsertRowId()` instead. This masks execute errors — a failed INSERT will appear to succeed with whatever the last successful INSERT's rowid was. The comment says "changed < 0 signals error, but we fall through so the caller gets the rowid instead of a negative value" — this is explicitly wrong behavior. The plan (Story 3.2, 3.3) does not address this bug.

**Risk if ignored**: Silent data loss — a failed INSERT returns the previous rowid, `DatabaseWriteActor` considers it successful, the write is never retried.

**Recommended fix**: When `executeStatement` returns -1, check `connectionLastError` and throw `RuntimeException("libsql execute failed: $error sql=$sql")`. Only call `connectionLastInsertRowId()` after a successful execute (`changed >= 0`). Add a test `execute_failedInsert_throwsException` that inserts a duplicate primary key and asserts an exception is thrown.

---

## LGTM

**Architecture fundamentals are sound**: The handle model (`Box::into_raw` → `jlong`), eager cursor collection, and `block_on` pattern for bridging sync JNI callers into async libsql are the correct approaches. The `ThreadLocal<TxState>` for per-thread transaction state is idiomatic for JDBC-style drivers. The `CopyOnWriteArrayList` listener registry is correct for the read-heavy, write-rare listener pattern.

**`params_from_iter` fix is correct**: Story 1.1 correctly identifies that `libsql::params_from_iter` does not exist in 0.9.30 and replaces it with `Vec<Value>` which implements `IntoParams`. The existing `lib.rs` has `libsql::params_from_iter(params)` on lines 360 and 389 — the fix is necessary and the plan handles it correctly.

**Dependency version updates are correct**: jni 0.21 → 0.22, libsql 0.6 → 0.9, Rust toolchain 1.82 → 1.85 are all the right moves per the stack research.

**`closed: AtomicBoolean` guard design is correct in principle**: The direction (set before drain, check before acquire) is right, even though the implementation is incomplete without the drain latch (Blocker 5).

**Test coverage breadth is good**: The plan covers rollback, nested savepoints, listener notification, pool overflow, close idempotency, and BUSY_SNAPSHOT surfacing. The test structure (gate on `libsqlNativeAvailable()`, temp file harness) is practical.

**`DriverFactory` isolation via separate method is correct**: Adding `createLibsqlDriver()` as a distinct JVM-only method rather than overriding the existing `createDriver()` ensures existing codepaths are untouched.

**Bazel build scoping is pragmatic**: Restricting the initial milestone to host-platform (Linux x86-64) and deferring cross-compilation is a reasonable risk reduction, provided Android is explicitly descoped from success criteria (see Blocker 6).

---

## Re-review (pass 2)

**Verdict**: CONCERNS

---

### Prior Blockers — Resolution Status

All 6 blockers from pass 1 are resolved in the patched plan.

| Blocker | Status | Evidence |
|---------|--------|----------|
| 1. MVCC local mode unverified | RESOLVED | Story 1.0 adds `probe_mvcc_local_mode` `#[test]` with concrete 7-step body; gates all downstream Epic 1 work. 1.0.3 documents `BEGIN CONCURRENT` is raw SQL only at every call site. |
| 2. No MVCC-active test | RESOLVED | Story 5.11 adds 4 tests with concrete method names: `mvccPragma_isActiveAfterOpen` (non-skippable on the MVCC assertion), plus 5.11.2–5.11.4 covering concurrent commits, BUSY_SNAPSHOT retriability, and read-during-write non-blocking. |
| 3. Fragile BUSY_SNAPSHOT detection | RESOLVED | Story 1.5b adds `connectionExtendedErrcode` JNI function returning `jint`, `SQLITE_BUSY_SNAPSHOT = 517` constant, and `LibsqlBusySnapshotException` in `commonMain`. String parsing removed from 1.5.1 and 3.6.1. |
| 4. catch_unwind connection poisoning | RESOLVED | `ConnHandle.poisoned: AtomicBool` in Story 1.3.4 (Rust); Story 3.2b adds `isConnectionPoisoned` JNI function and `releaseConn` quarantine path (Kotlin). |
| 5. close() TOCTOU | RESOLVED | `CountDownLatch closeLatch(1)` + `AtomicInteger inFlightCount` added in Story 3.2.2–3.2.6; mirrored in `AndroidLibsqlDriver` via Story 3.3. |
| 6. Android NDK | RESOLVED | Story 2.5 adds NDK r25c registration, `aarch64-linux-android` + `x86_64-linux-android` Rust toolchain targets, `libgcc`/`libunwind` workaround, `-lpthread` fix, CI step, jniLibs layout, and `copyAndroidLibsqlNative` Gradle task. |

---

### New Issues Found in the Patch

**3 new concerns identified; 0 new blockers.**

---

#### New Concern A: `close()` called twice causes a double-free on the Rust DB handle

**Issue**: Story 3.2.3 allocates `CountDownLatch(1)`. The latch counts down to zero during the first `close()` (either immediately in step 3.2.6-2, or when the last in-flight connection is returned). The second `close()` call (tested by Story 5.8) proceeds as follows:

1. `closed.set(true)` — no-op, already true.
2. `inFlightCount.get() == 0` — true (pool was drained on first close). Calls `closeLatch.countDown()` — no-op since count is already 0.
3. `closeLatch.await(5, TimeUnit.SECONDS)` — returns immediately (count is 0).
4. `pool.drainTo(handles)` — drains nothing (already empty).
5. **`closeDatabase(dbHandle)`** — called a second time on the same `dbHandle` raw pointer. On the Rust side, `Box::from_raw(db_handle as *mut DbHandle)` is called, freeing memory that was already freed. This is undefined behavior — at best a segfault, at worst silent heap corruption in the Tokio allocator.

Story 5.8 asserts "no exception is thrown" — it will pass (the double-free is a native crash, not a Kotlin exception), or it will crash the JVM, neither of which constitutes a clean pass. The existing `PooledJdbcSqliteDriver` avoids this by checking `if (closed.compareAndSet(false, true))` — only proceeding with teardown on the first `close()`.

**Required fix**: In `close()`, replace `closed.set(true)` with `if (!closed.compareAndSet(false, true)) return`. This makes `close()` a strict no-op after the first call. Apply identically to `AndroidLibsqlDriver` (Story 3.3). One line fix in both classes.

---

#### New Concern B: Benchmark still uses `DatabaseWriteActor` — cannot demonstrate MVCC concurrency benefit

**Issue**: This is pass-1 Concern #5, carried forward unaddressed. Story 6.1.3 instructs: "Launch 4 coroutines on `Dispatchers.Default`, each calling `actor.saveBlock(block)`." `DatabaseWriteActor` is a single-coroutine actor — all 4 callers queue behind one dispatch loop. At no point are two `BEGIN CONCURRENT` transactions outstanding simultaneously. The benchmark measures actor queue overhead, not MVCC write concurrency. The comparison against `PooledJdbcSqliteDriver` is structurally equivalent (both serial).

There is also an internal contradiction: Story 5.11.2 tests `twoWritersDifferentRows_bothCommit` by launching 2 threads that each call `driver.newTransaction()` directly — bypassing the actor. The benchmark must use the same pattern if it is to demonstrate the MVCC benefit that 5.11.2 proves exists.

**Required fix**: The benchmark must bypass `DatabaseWriteActor` and use `driver.newTransaction()` directly from 4 concurrent threads with overlapping transaction lifetimes, identical to Story 5.11.2's structure. Add a second benchmark variant using `actor.saveBlock()` to represent the production path — but label it clearly as "actor-gated" and do not use it for the SC-3 assertion.

---

#### New Concern C: Story 1.5b.1 fallback retains string parsing for `sqlite3_extended_errcode` inaccessibility

**Issue**: Story 1.5b.1 states: "If the raw pointer is inaccessible through the libsql public API, fall back to parsing `conn.last_error_string()` for `"517"`" — marked `// FIXME: fragile`. This conditional fallback means the fragile path from pass-1 Blocker 3 can still ship if the libsql internal API does not expose a raw `*mut sqlite3`. The test in Story 5.10.1 step 8 asserts that the error code path was used ("verify `connectionLastError(connHandle)` does NOT necessarily contain the string `SQLITE_BUSY_SNAPSHOT` but the exception was still thrown") — but this assertion only holds when `sqlite3_extended_errcode` is actually callable. If the FIXME fallback is taken, the test assertion in 5.10.1-8 passes vacuously (the fallback does parse the string, so the exception is thrown regardless, but the assertion is written to prove the non-string path was used, which it wasn't).

The test as written cannot distinguish "errcode path taken" from "string-parse fallback path taken." If the FIXME fallback fires silently, Blocker 3 is effectively re-introduced at runtime on any libsql version that changes its error string format.

**Required fix**: Add a second assertion in Story 5.10.1: directly call `LibsqlJni.connectionExtendedErrcode(connHandle)` from the test and assert it equals `517` — this proves the JNI function returns the correct integer regardless of which internal Rust path fetched it. If `sqlite3_extended_errcode` is inaccessible and the FIXME fallback must ship, that must be a named ADR decision with a follow-up ticket, not a silent `// FIXME`.

---

### Carried Forward from Pass 1 (unaddressed concerns, not new)

The patch does not address pass-1 Concerns #3, #6, and #7. These remain open:

- **Concern #3** (`loadNativeLibrary()` extraction race): Story 3.4 adds versioned filenames but still no `synchronized(LibsqlJni::class.java)` block around the existence-check-and-write path. Two parallel JVM processes can both detect the file absent and interleave writes. Low-probability in practice; deferred risk is acceptable if tracked.
- **Concern #6** (`DriverFactory().createDriver()` regression test absent): No test `existingDriverFactory_createDriver_worksWithoutLibsqlNative` was added. The risk is `LibsqlJni.loadNativeLibrary()` triggering on class initialization and breaking `ciCheck`. Deferred risk is acceptable if `loadNativeLibrary()` is lazy (verify this during implementation).
- **Concern #7** (`execute()` error masking — failed INSERT returns previous rowid): Not addressed. Silent data-loss risk on any failed INSERT. Should be fixed alongside Story 3.2 rather than deferred.

---

### Summary

- **Prior blockers resolved**: 6/6
- **New blockers**: 0
- **New concerns**: 3 (A: `close()` double-free — one-line fix; B: benchmark serial architecture cannot prove SC-3; C: BUSY_SNAPSHOT errcode test cannot distinguish fallback from primary path)
- **Carried-forward concerns**: 3 (from pass 1, unaddressed)

New Concern A is the highest priority — it is a one-line fix (`compareAndSet`) that prevents undefined behavior in a test the plan explicitly covers. New Concern B undermines the SC-3 success criterion structurally and should be addressed before the benchmark milestone. New Concern C is a test-correctness gap that should be addressed in Story 5.10.1 before the story is implemented.
