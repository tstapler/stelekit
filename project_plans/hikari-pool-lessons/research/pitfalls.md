# Pitfalls Research: PooledJdbcSqliteDriver vs. HikariCP

Evidence base: PooledJdbcSqliteDriver.kt (full source), JdbcDriver 2.3.2 bytecode
(javap), ConnectionManager$Transaction bytecode, DriverFactory.jvm.kt,
MigrationRunner.kt. All claims below are derived from the actual code paths.

---

## 1. No Connection Validation on Checkout

### What HikariCP Does
HikariCP calls `Connection.isValid(validationTimeout)` before every checkout (or
executes a configured `connectionTestQuery`). If the connection is broken it is
evicted and a fresh connection is created. The caller never sees a dead connection.

### What Our Pool Does
```kotlin
return pool.poll() ?: DriverManager.getConnection(url, properties)
```
No health check. The returned connection goes directly to the caller.

### Failure Modes
- **Disk-full mid-operation**: SQLite returns `SQLITE_FULL`. The connection stays in
  the pool. The next caller gets it and the very first statement fails with the same
  error. There is no indication that the connection was previously the site of an error.
- **WAL corruption or `SQLITE_NOTADB`**: A connection that hit a fatal I/O error may
  be in an undefined state. Returning it to the pool propagates the damage to unrelated
  callers.
- **OS-level file lock** (`SQLITE_IOERR_LOCK`): the connection is not intrinsically
  broken (the error is transient) but a caller has no way to distinguish a broken
  connection from a transient error.

### Detection Approach for SQLite
`connection.isValid(timeoutSeconds)` calls `sqlite_db_status()` internally (xerial
implements it with `SELECT 1`). This is cheap (no disk I/O on a healthy connection)
and catches any connection that SQLite itself has marked as broken.

The right place is `closeConnection()` — validate before re-enqueuing, not before
checkout, so the pool evicts eagerly and immediately recreates:
```kotlin
override fun closeConnection(connection: Connection) {
    val isHealthy = runCatching { connection.isValid(1) }.getOrDefault(false)
    if (isHealthy && !pool.offer(connection)) {
        connection.close()
    } else if (!isHealthy) {
        runCatching { connection.close() }
        // Optionally: replace with a fresh connection
    }
}
```
Validating on return rather than on checkout keeps the hot path fast.

---

## 2. autoCommit Not Reset on `closeConnection()`

### What HikariCP Does
On every connection return (via its `close()` proxy), HikariCP:
1. Calls `rollback()` if there is an open transaction.
2. Calls `connection.setAutoCommit(true)` if it was changed.
3. Resets any other connection properties (read-only, isolation level, catalog).

### What Our Pool Does
```kotlin
override fun closeConnection(connection: Connection) {
    if (!pool.offer(connection)) {
        connection.close()
    }
}
```
The connection is returned as-is. `autoCommit` state is whatever the previous caller
left it.

### JdbcDriver's Normal Path (No Risk Under Normal Use)
Inspecting JdbcDriver 2.3.2 bytecode: `rollbackTransaction(connection)` always calls
`rollback()` → `setAutoCommit(true)` → `closeConnection()`. SQLDelight's `transaction
{ }` block wraps in a try/finally that ensures `rollbackTransaction` is called on any
exception. Under normal SQLDelight usage `autoCommit` is always restored before
`closeConnection` is called.

### Failure Modes That Bypass the Safety
1. **Thread interruption or `OutOfMemoryError` inside `endTransaction()`**: If an
   `Error` (not `Exception`) is thrown between `commit()` and `setAutoCommit(true)`,
   `closeConnection()` is NOT called (the exception escapes the connection management
   code entirely). The connection stays checked out but the thread's `ThreadLocal`
   transaction reference is cleared. The connection is leaked from the pool.
2. **Direct `driver.execute()` usage with manual transaction wrapping**: Any caller
   that bypasses SQLDelight's `transaction { }` and issues raw `BEGIN`/`COMMIT` SQL
   without a try/finally on the connection will leave `autoCommit` dirty.
3. **`beginTransaction()` assertion fires**: `JdbcDriver.beginTransaction()` throws
   `IllegalStateException("Expected autoCommit to be true by default")` if it gets a
   connection where `autoCommit == false`. If a previous caller leaked a connection in
   `autoCommit=false` state, the next writer gets an ISE on every write attempt.

### ThreadLocal + Coroutine Thread Hopping
JdbcDriver stores the active transaction in a `ThreadLocal<Transaction>`. If a
coroutine starts a transaction on thread A, suspends (e.g., an `await()`), and resumes
on thread B, `getTransaction()` returns null on thread B. Non-transactional `execute()`
calls then acquire a different pooled connection, executing outside the transaction. The
original connection is still pinned (in the ThreadLocal on thread A) but will only be
released when thread A's ThreadLocal GC-collects the `Transaction` — which may not
happen until thread reuse. This is not a bug in our pool per se, but it's a correctness
risk for any code that mixes SQLDelight transactions with coroutine suspension inside
the transaction body. Current `withContext(PlatformDispatcher.DB)` usage mitigates
this; violations would be subtle and hard to test.

---

## 3. Connection Leak Patterns

### Pool Drain Cascade
With a fixed pool of 8 connections, each leaked connection permanently reduces pool
capacity. After 8 leaks all subsequent `getConnection()` calls for file DBs create
overflow connections (unbounded, see Section 6). For in-memory pools, all callers
block in the spin loop until `close()` is called.

### Common Leak Vectors in This Codebase

**a. Direct `getConnection()` without `withPinnedConnection`:**
The only safe pattern is:
```kotlin
val conn = getConnection()
try { ... }
finally { closeConnection(conn) }
```
Any caller that omits the `try/finally` leaks on exception. In tests:
```kotlin
// PooledJdbcSqliteDriverTest.kt — potential leak if assertion throws before closeConnection
val a = fileDriver.getConnection()
val b = fileDriver.getConnection()
assertNotSame(a, b, "...")  // if this throws: neither a nor b is returned
fileDriver.closeConnection(a)
fileDriver.closeConnection(b)
```
Current tests mostly wrap correctly, but any newly written code that calls
`getConnection()` directly without try/finally leaks on test failure.

**b. JdbcDriver `execute()` exception handling:**
`JdbcDriver.execute()` bytecode shows the `closeConnection()` closure is invoked in a
finally block (exception table: `from 32 to 152, target 163`). This is safe.

**c. `withPinnedConnection` — currently safe:**
```kotlin
val conn = getConnection()
return try { block(conn) } finally { closeConnection(conn) }
```
The `finally` runs on normal return, on exception, and on `CancellationException`. This
is correct. However, `getConnection()` itself is NOT a suspend call — it's a blocking
function. If `CancellationException` is thrown before `getConnection()` returns (by
thread interruption in the in-memory spin loop), no connection was obtained and no
finally is needed. This is correct.

**d. Overflow connection creation during `close()` race (file DB):**
See Section 5 for the concurrent `close()` / `getConnection()` race. The overflow
connection created after `close()` is orphaned and never returned to the pool (the pool
is full with null references after drain). This is a one-time leak per race instance.

---

## 4. `withPinnedConnection` Coroutine Cancellation Safety

### try/finally Is Sufficient for `CancellationException`
Kotlin's `finally` blocks run on `CancellationException`. The current implementation:
```kotlin
suspend fun <T> withPinnedConnection(block: suspend (Connection) -> T): T {
    val conn = getConnection()
    return try { block(conn) } finally { closeConnection(conn) }
}
```
is correct for:
- Normal completion: `closeConnection` is called.
- Exception from `block`: `closeConnection` is called, exception propagates.
- `CancellationException` from `block`: `closeConnection` is called, exception
  propagates (coroutine framework re-throws it).

### One Subtle Gap: Blocking `getConnection()` Is Not Cancellation-Cooperative
For the in-memory path, `getConnection()` is a blocking spin loop:
```kotlin
while (true) {
    if (closed.get()) error("PooledJdbcSqliteDriver is closed")
    val conn = pool.poll(50, TimeUnit.MILLISECONDS) ?: continue
    ...
    return conn
}
```
This loop does NOT check `isActive` or `currentCoroutineContext().job.isCancelled`. If
the parent scope is cancelled while a coroutine is blocked here:

- On `Dispatchers.IO`, JVM will interrupt the blocked thread. `pool.poll(50, MILLIS)`
  throws `InterruptedException`, which propagates out of `getConnection()` before `conn`
  is assigned. Since `conn` was never obtained, the `try/finally` block is never entered
  and there is nothing to return. This is correct behavior.
- On `Dispatchers.Default`, threads are NOT interrupted on cancellation. The spin loop
  continues for up to 50ms per iteration until the pool releases a connection. For tests
  with tight timeouts this causes flakiness. For production this means cancelled
  coroutines silently hold an IO thread for up to 50ms before detecting cancellation at
  the first suspension point inside `block`.

**Mitigation (low priority for SQLite):** Add `ensureActive()` at the top of the spin
loop:
```kotlin
while (true) {
    if (closed.get()) error("PooledJdbcSqliteDriver is closed")
    currentCoroutineContext().ensureActive()  // fast-path cancellation check
    val conn = pool.poll(50, TimeUnit.MILLISECONDS) ?: continue
    ...
}
```
But this requires making `getConnection()` a suspend function.

---

## 5. Thread Safety Gaps

### Gap 1: `addListener` — `getOrPut` Is Not Atomic on `ConcurrentHashMap`

```kotlin
override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
    for (key in queryKeys) {
        listeners.getOrPut(key) { CopyOnWriteArrayList() }.add(listener)
    }
}
```

Kotlin's `getOrPut` on `MutableMap` compiles to:
```kotlin
val value = get(key)
if (value == null) {
    val answer = defaultValue()
    put(key, answer)   // ← returns the OLD value, not checked
    answer
} else {
    value
}
```

**Race**: Two threads call `addListener` for the same new key concurrently.
1. Thread A: `get(key)` → null; creates `list_A`
2. Thread B: `get(key)` → null (before A's `put`); creates `list_B`
3. Thread A: `put(key, list_A)` → null (inserted)
4. Thread A: `list_A.add(listener_a)` ← listener_a is in `list_A`
5. Thread B: `put(key, list_B)` → replaces `list_A` with `list_B`
6. Thread B: `list_B.add(listener_b)` ← listener_b is in `list_B`

Now the map holds `list_B`. `listener_a` is in `list_A` which is no longer referenced
by the map. `notifyListeners("key")` will only notify `listener_b`.

**Fix**: Use `computeIfAbsent` which is atomic on `ConcurrentHashMap`:
```kotlin
listeners.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(listener)
```

**Practical risk level**: Low. SQLDelight Flow collectors call `addListener` in sequence
(one per active `collect { }` call), not concurrently. The race only triggers if two
coroutines start collecting the same query key simultaneously for the first time. The
symptom would be a Flow that occasionally misses invalidation signals — no data
corruption, just stale UI.

### Gap 2: File DB `getConnection()` + `close()` Race

```kotlin
// getConnection() for file DB:
return pool.poll() ?: DriverManager.getConnection(url, properties)

// close():
closed.set(true)
listeners.clear()
var conn = pool.poll()
while (conn != null) { conn.close(); conn = pool.poll() }
```

`getConnection()` for file DBs never checks `closed`. After `close()` drains the pool:
- Thread calls `pool.poll()` → null (pool is empty)
- Thread calls `DriverManager.getConnection(url, properties)` → creates new connection
  to the (now-closed) database
- This overflow connection is returned and used. SQL executes against a database whose
  schema is being torn down.
- The overflow connection is never closed (caller calls `closeConnection()`, which tries
  `pool.offer()`, which fails because pool is full of… nothing. Actually the pool is an
  `ArrayBlockingQueue` and `offer` adds it — but `close()` doesn't stop `offer` calls).

Actually: after `close()` drains the pool, `pool.offer(overflowConn)` succeeds (queue
has space). The connection is now back in the pool but nobody drains it in `close()`
again. Result: **one leaked open connection per race instance**.

**Fix**: Check `closed` before creating an overflow connection:
```kotlin
override fun getConnection(): Connection {
    if (!isMemory) {
        if (closed.get()) error("PooledJdbcSqliteDriver is closed")
        return pool.poll() ?: DriverManager.getConnection(url, properties)
    }
    ...
}
```

### Gap 3: `removeListener` / `notifyListeners` During `close()`

`close()` calls `listeners.clear()`. A concurrent `notifyListeners()` call iterates the
map while `clear()` is running. `ConcurrentHashMap.clear()` is thread-safe (atomic for
each bucket), and iterators on `ConcurrentHashMap` are weakly consistent (won't throw
`ConcurrentModificationException`, may or may not see entries deleted by a concurrent
`clear()`). The worst case is spurious notification after shutdown — not a correctness
issue.

---

## 6. Overflow Connection Risks

### Unbounded Overflow Under Load

For file DBs, every thread that calls `getConnection()` while the 8 pooled connections
are checked out creates its own JDBC connection:
```kotlin
return pool.poll() ?: DriverManager.getConnection(url, properties)
```

Each new SQLite connection allocates:
- A file descriptor (2 per WAL database: `.db` and `.db-wal`)
- SQLite's per-connection page cache: **32 MB** (`cache_size=-32768`)
- WAL shared memory (`.db-shm`): small but one per connection

At 8 pool connections already checked out:
- 16 burst threads → 8 overflow connections → 256 MB extra page cache
- 500 burst threads → 492 overflow connections → **15.7 GB page cache** → OOM

`Dispatchers.IO` caps at 64 threads by default, so in practice the worst case is ~56
overflow connections (64 − 8 pool) = 1.8 GB extra page cache. This is survivable on
desktop (JVM has RAM headroom per `CLAUDE.md`) but could cause GC pressure during
graph load bursts.

### Android Is Not Affected by This Code Path

`DriverFactory.android.kt` uses Android's native SQLite driver (not `PooledJdbcSqliteDriver`),
so the overflow logic doesn't apply on Android.

### WAL Reader Starvation from Overflow Connections

Each SQLite connection in WAL mode holds an independent read mark. While a connection
is in a read transaction, the WAL cannot be checkpointed past its read mark. With
`wal_autocheckpoint=1000` pages, checkpoints happen at 4 MB of WAL growth. If 8 pool
connections are all mid-read (e.g., during a 8000-page graph load), the WAL can grow
past 4 MB without a successful checkpoint. Overflow connections extend this window.

If a checkpoint is never able to complete (all connections always hold an old read
mark), the WAL grows without bound until it fills the disk. In practice this is
prevented by the checkpoint on `PRAGMA optimize` at driver creation and by connection
turnover, but it's a long-tail risk on graphs with continuous read traffic.

---

## 7. SQLite-Specific WAL Pitfalls

### Long Read Transactions Block WAL Checkpointing

In WAL mode, SQLite's WAL can be checkpointed (written back to the main DB file) only
when no connection holds a read mark older than the checkpoint target frame. With 8
pooled connections:

- Connection A starts a read inside a SQLDelight Flow collector.
- The collector's `conflate()` + `distinctUntilChanged()` pipeline may hold the
  snapshot for seconds.
- Meanwhile, the write actor performs hundreds of block saves (8000-page graph import).
- The WAL grows by one frame per write. The WAL cannot be reclaimed past connection
  A's read mark.
- Result: WAL grows to N × page_size until connection A's transaction ends.

**`wal_autocheckpoint=1000`** triggers a checkpoint attempt after 1000 pages (4 MB).
The checkpoint *attempt* succeeds only in `PASSIVE` mode (SQLite default): it writes
back whatever frames it can without blocking writers. Frames held by a reader are
skipped. So autocheckpoint doesn't guarantee the WAL shrinks — it only prevents
unbounded growth if readers eventually release their locks.

### `SQLITE_BUSY_SNAPSHOT` Is Already Mitigated

`transaction_mode=IMMEDIATE` (per `buildMainDbConnectionProps`) acquires the write lock
at transaction start, preventing the deferred→immediate upgrade race in WAL mode. This
was the right fix for the original bug.

### `PRAGMA busy_timeout=10000` Under Multiple Writers

With a single `DatabaseWriteActor` serializing all writes, there is at most one
connection in a write transaction at any time. The busy_timeout=10000ms applies to any
other connection that tries to acquire the write lock while the actor holds it. In
practice this is used only during migration (MigrationRunner calls `driver.execute()`
without the actor), and the `require(bad.isEmpty())` guard in `Migration.init {}` now
prevents raw `BEGIN` statements from appearing in migration SQL.

### Connection-Level `cache_size` Means Memory Does Not Shrink

Each pooled connection has a 32 MB page cache (`cache_size=-32768`). When the pool is
idle (no active queries), SQLite holds all 8 × 32 MB = 256 MB of page cache in the
connections' internal B-tree caches. There is no eviction until the connection is
closed. HikariCP's `idleTimeout` would close and recreate connections, releasing this
cache. Our pool keeps connections forever.

**Practical impact**: After a large graph is loaded and the user closes it, 256 MB of
page cache remains allocated until the driver is closed. On desktop this is acceptable;
if this driver were ever used on Android (it's not — Android uses a different path) it
would be a problem.

---

## Summary of Gaps vs. HikariCP

| Guard | HikariCP | Our Pool | Risk Level |
|-------|----------|----------|------------|
| Connection health check before checkout | `isValid()` or test query | None | Medium — broken connections surface as caller errors |
| autoCommit reset on return | Yes (proxy intercepts `close()`) | No | Low under normal SQLDelight use; Medium if error paths bypass `rollbackTransaction()` |
| Open transaction rollback on return | Yes | No | Same as above |
| Leak detection | Background thread, stack trace | None | Medium — 8 leaks = pool starvation |
| `getConnection()` timeout | `SQLTimeoutException` after connectionTimeout | Overflow (file) or spin (memory) | Low (file), Low (memory — 50ms poll exits loop) |
| `getConnection()` respects `closed` (file DB) | N/A | No | Low — transient race, one orphaned connection |
| `addListener` atomic first-put | N/A | `getOrPut` not atomic | Low — rare race, stale UI not corruption |
| Overflow connection bounds | Max pool size | Unbounded (64 IO threads worst case) | Medium — 1.8 GB page cache spike on burst |
| Connection lifetime eviction | `maxLifetime` / `idleTimeout` | Never | Low for desktop; memory stays allocated |
| ThreadLocal + coroutine thread hop | N/A (sync API) | Risk if suspend inside transaction body | Low — mitigated by `withContext(DB)` discipline |

---

## Recommended Fixes (Prioritized)

1. **`computeIfAbsent` in `addListener`** (trivial, fix correctness): Replace
   `listeners.getOrPut(key) { CopyOnWriteArrayList() }` with
   `listeners.computeIfAbsent(key) { CopyOnWriteArrayList() }`.

2. **Guard `getConnection()` for file DB after `close()`** (trivial): Add
   `if (closed.get()) error("PooledJdbcSqliteDriver is closed")` before the `pool.poll()
   ?: DriverManager.getConnection()` line.

3. **Validate connection health in `closeConnection()`** (medium effort): Call
   `connection.isValid(1)` before returning to pool; close and skip re-enqueue on
   failure. Consider recreating a replacement connection to maintain pool size.

4. **Reset `autoCommit` in `closeConnection()`** (small safety net): Add
   `if (!connection.autoCommit) { connection.rollback(); connection.autoCommit = true }`
   before `pool.offer()`. This catches any path that bypasses `JdbcDriver`'s
   `rollbackTransaction()`.

5. **Document the overflow model explicitly** (no code change): The current overflow
   behavior is intentional for file DBs. Add a KDoc note explaining the max-overflow
   scenario (64 IO threads = 56 overflow × 32 MB = 1.8 GB) so future contributors
   don't accidentally increase `cache_size` without reconsidering overflow.
