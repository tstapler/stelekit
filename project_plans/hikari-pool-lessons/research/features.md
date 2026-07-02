# HikariCP Feature Landscape — Research

**Date**: 2026-07-01
**Scope**: Understand what HikariCP does at each stage of the connection lifecycle, evaluate applicability to `PooledJdbcSqliteDriver`.

Sources: HikariCP `dev` branch source — `PoolBase.java`, `HikariPool.java`, `ProxyConnection.java`, `ProxyLeakTask.java`.

---

## 1. HikariCP Connection Lifecycle

### 1.1 Connection Creation / Initialization

`PoolBase.setupConnection()` runs once when a new `Connection` is obtained from the `DataSource`:

1. Probes `Connection.setNetworkTimeout()` support (stores result; skips on every later call if unsupported — avoids repeated exception overhead).
2. Sets `readOnly`, `autoCommit`, `transactionIsolation`, `catalog`, `schema` to the pool-configured values.
3. Runs `connectionInitSql` (configurable SQL string) — allows per-connection PRAGMAs or session state.
4. Checks `isValid()` / `connectionTestQuery` support on the **first** connection created; caches result (flag `isValidChecked`).
5. Detects and caches the driver's default `transactionIsolation` level.

The initialization is done exactly once per physical connection. Post-creation, all state is tracked via the proxy's dirty-bit mechanism (see §2).

HikariCP also attaches per-connection timers at creation time:
- **MaxLifetimeTask**: scheduled at `maxLifetime - variance` ms in the future; fires `softEvictConnection()` when the connection ages out.
- **KeepaliveTask**: if `keepaliveTime > 0`, scheduled repeatedly at `keepaliveTime - variance` ms; validates the connection while it is idle (see §1.4).

### 1.2 Connection Checkout

`HikariPool.getConnection()` borrow loop:

1. Borrows a `PoolEntry` from `ConcurrentBag` (lock-free, thread-local affinity).
2. If the entry is marked evicted (by MaxLifetimeTask or user eviction), discards it and loops.
3. If the connection has been idle longer than `aliveBypassWindowMs` (default 500 ms): calls `isConnectionDead()` to validate. If dead, discards and loops.
4. If alive, wraps the raw `Connection` in a `ProxyConnection` (generated at build time — not a JDK `Proxy`).
5. Schedules a `ProxyLeakTask` on the housekeeper executor (see §4).

Key insight: validation happens **only if the connection was idle > 500 ms**. Connections returned and immediately re-borrowed skip the validation call entirely. This keeps the common-path cost at zero.

### 1.3 Connection Return (State Cleanup)

`ProxyConnection.close()` — this is the caller's `conn.close()` call:

1. Closes any tracked open `Statement` objects (in creation order). If closing a statement throws, the connection is evicted.
2. Cancels the `ProxyLeakTask` for this checkout.
3. If `isCommitStateDirty && !isAutoCommit`: calls `connection.rollback()`. This fires if any DML was executed without a subsequent commit or rollback. If the caller left an open transaction, it is rolled back automatically. A warning is logged at DEBUG.
4. If `dirtyBits != 0`: calls `resetConnectionState()` to restore each dirty field to the pool's configured value. The fields tracked are: `readOnly`, `autoCommit`, `isolation`, `catalog`, `networkTimeout`, `schema`. Only fields that differ from the pool config are actually SET — so a caller that set `autoCommit=false` but also called `commit()` will still have `dirtyBits |= DIRTY_BIT_AUTOCOMMIT` and `resetConnectionState` will restore `autoCommit=true`.
5. Calls `connection.clearWarnings()`.
6. Sets `delegate = ClosedConnection.CLOSED_CONNECTION` (sentinel proxy — any method call after this throws "Connection is closed").
7. Calls `poolEntry.recycle()` → `connectionBag.requite()` returns the slot to the bag.

**autoCommit is not unconditionally set to `true`**: HikariCP compares the proxy's tracked autoCommit state to the pool config value and only calls `setAutoCommit()` if they differ. For a pool configured with `autoCommit=true` (the default), if the caller set `autoCommit=false`, the dirty bit fires and `resetConnectionState` restores `true`.

### 1.4 Idle Eviction

`HouseKeeper` runs every 30 seconds (configurable via `com.zaxxer.hikari.housekeeping.periodMs`):

- **idleTimeout**: if `idleTimeout > 0` and `minimumIdle < maximumPoolSize`, connections idle longer than `idleTimeout` are evicted via `softEvictConnection()`. Pool is then filled back up to `minimumIdle`.
- **Retrograde clock detection**: if system time goes backwards > 128ms (NTP adjustment), all connections are soft-evicted to prevent stale `lastAccessed` timestamps from hiding idle connections permanently.
- **maxLifetime**: handled by per-connection `MaxLifetimeTask` (not the housekeeper). Fires at `maxLifetime - randomVariance` ms after creation. Variance is up to 25% of `maxLifetime`, preventing a mass die-off of all pool connections at once ("thundering herd" eviction).
- **keepaliveTime**: per-connection `KeepaliveTask`. Acquires a `ConcurrentBag` reservation (takes the slot without a full borrow), runs `isConnectionDead()`, releases if alive or soft-evicts if dead.

### 1.5 Dead-Connection Handling

Two mechanisms:

**On SQLException** (`ProxyConnection.checkException()`): inspects the `SQLState` prefix and error codes. Fatal states (prefix `08` — connection failures, plus a set of vendor-specific states for Sybase, Postgres admin shutdown, etc.) trigger `poolEntry.evict()`. The proxy `delegate` is replaced with `ClosedConnection.CLOSED_CONNECTION`; the pool immediately removes the `PoolEntry` and creates a replacement.

**On keepalive check** or **on checkout after idle**: `isConnectionDead()` calls `connection.isValid(validationSeconds)` (or `connectionTestQuery` if `isValid()` is not supported). A thrown exception → `lastConnectionFailure` → eviction.

### 1.6 Leak Detection

See §4 for full detail. Summary: time-delay scheduled task. Zero overhead when disabled. Captures checkout stack trace, fires a WARN log after `leakDetectionThreshold` ms if the connection has not been returned.

---

## 2. autoCommit Reset Pattern — Detail

HikariCP tracks connection state changes through `dirtyBits` in `ProxyConnection`:

```java
static final int DIRTY_BIT_READONLY   = 0b000001;
static final int DIRTY_BIT_AUTOCOMMIT = 0b000010;
static final int DIRTY_BIT_ISOLATION  = 0b000100;
static final int DIRTY_BIT_CATALOG    = 0b001000;
static final int DIRTY_BIT_NETTIMEOUT = 0b010000;
static final int DIRTY_BIT_SCHEMA     = 0b100000;
```

Each `setXxx()` call on the proxy sets the corresponding bit. On `close()`, `resetConnectionState()` iterates these bits and calls the JDBC `setXxx()` method **only when the proxy's tracked value differs from the pool's configured value**. This avoids unnecessary JDBC round-trips when the caller did not change state.

**Separate from dirty bits**: `isCommitStateDirty` tracks whether any DML-like operation occurred without a subsequent commit/rollback. It is set by `markCommitStateDirty()` (called from `executeUpdate`, `executeBatch`, `getMetaData`, etc.) and cleared by `commit()` and `rollback()`. If dirty on close, a `rollback()` fires automatically. This is the guard against "caller forgot to commit or rollback."

**Key gap in our pool**: `PooledJdbcSqliteDriver` returns raw `Connection` objects directly. `JdbcDriver.endTransaction()` / `beginTransaction()` set `autoCommit` on the connection but only within the SQLDelight transaction mechanism (ThreadLocal). If a coroutine bypasses SQLDelight's transaction abstraction and sets `autoCommit=false` directly, that state is returned to the pool silently. The next caller gets a connection mid-transaction. HikariCP's proxy layer makes this structurally impossible.

---

## 3. Connection Validation Approaches

### 3.1 HikariCP's approach

**Primary method**: `Connection.isValid(timeoutSeconds)` — JDBC 4.0 standard. HikariCP attempts this during pool startup on the first connection. If it throws or is not supported, falls back to `connectionTestQuery`.

**Fallback**: `connectionTestQuery` — user-configured SQL, typically `SELECT 1`. HikariCP executes it via `createStatement().execute()`. If `isolateInternalQueries=true` and `autoCommit=false`, it wraps the validation in a rollback so it doesn't dirty the connection's transaction state.

**aliveBypassWindow (500ms default)**: validation is skipped for connections returned and re-borrowed within 500ms. This is the key performance optimization — validation overhead only applies to connections that have actually been sitting idle long enough that a network/process disruption could have occurred.

### 3.2 SQLite-specific evaluation

**`isValid()` with sqlite-jdbc (xerial)**: supported. The xerial `JDBC4Connection.isValid()` checks `!isClosed() && db.getHandle() != 0` — a purely in-process check. It does NOT send any bytes to a remote server. Cost: one native call to check the SQLite handle, essentially free.

**`SELECT 1` as connectionTestQuery**: also works, but unnecessarily parses and executes SQL. `isValid()` is strictly cheaper for SQLite.

**When would validation matter for SQLite?**

SQLite file connections do NOT go stale due to network disruption (there is no network). However, connections CAN become dead in two scenarios:
1. The database file was deleted or moved while the pool held connections (rare in normal operation, possible during test teardown or graph deletion).
2. The OS ran out of file descriptors and the SQLite driver closed the connection internally.
3. The database was closed by `driver.close()` while a concurrent thread still held a borrowed connection (our `close()` races with active checkouts).

For our fixed-size pool with 8 pre-created connections, scenario 3 is the only common case. Our current `close()` drains only connections that are in the pool queue — connections currently checked out are NOT closed, and callers of `getConnection()` that have not yet called `closeConnection()` continue using stale connections after `driver.close()`. HikariCP marks the pool as `POOL_SHUTDOWN` and uses `Connection.abort()` to forcibly close in-use connections.

**Verdict**: `isValid()` would add `~O(1ns)` overhead per checkout (a JNI check of the handle) if validated on every borrow. With an aliveBypassWindow, it only fires for idle connections. Worth adopting for the shutdown-race protection alone.

---

## 4. Leak Detection Mechanism

### 4.1 How ProxyLeakTask works

When a connection is checked out (`getConnection()`), HikariCP creates a `ProxyLeakTask`:

```java
// In ProxyLeakTask constructor (called on checkout thread):
this.exception = new Exception("Apparent connection leak detected");  // captures stack trace HERE
this.threadName = Thread.currentThread().getName();
this.connectionName = poolEntry.connection.toString();
```

The task is then scheduled via `houseKeepingExecutorService.schedule(task, leakDetectionThreshold, MILLISECONDS)`.

**The checkout stack trace is captured at task construction time** — before any async work happens — so the WARN log includes the exact `getConnection()` call site.

When the connection is returned (`ProxyConnection.close()`), `leakTask.cancel()` is called, which calls `scheduledFuture.cancel(false)`. If the task already fired (connection was held > threshold), the cancel path logs an INFO message indicating the "leaked" connection was eventually returned.

**Cost when disabled** (`leakDetectionThreshold = 0`): `ProxyLeakTaskFactory.schedule()` returns `ProxyLeakTask.NO_LEAK` — a singleton with no-op `schedule()`, `run()`, and `cancel()`. No scheduler interaction, no object allocation per checkout.

**Cost when enabled**: one `ScheduledFuture` allocation per checkout, one `Exception` construction (stack trace capture), one scheduler submission, one cancellation on return. The scheduler task only *fires* if the threshold is exceeded — in normal operation it is always cancelled before firing.

### 4.2 Why it helps for our pool

Our pool has no equivalent. A coroutine that holds a `Connection` from `getConnection()` and crashes (exception bypasses `closeConnection()`) silently exhausts the in-memory pool permanently — the slot is never returned. For file-DB pools with overflow connections, the impact is subtler: the overflow connection is closed when `closeConnection()` is called on it, but if it is never called, the overflow connection leaks silently.

**For in-memory DBs with poolSize=1**: a single leaked connection (e.g., from a `withPinnedConnection` block that throws without a `finally`) drains the pool completely and causes the next `getConnection()` to block forever (our 50ms poll loop spinning until `close()` is detected). A leak detector would fire a warning with the checkout stack trace, making the bug immediately diagnosable.

### 4.3 Overhead assessment for SQLite

Our pool does not wrap connections in a proxy object. Adding leak detection without a proxy requires a parallel tracking structure (e.g., `ConcurrentHashMap<Connection, LeakInfo>` keyed by the borrowed connection's identity). This adds O(1) map put/remove per checkout/return — acceptable overhead.

Alternatively, a simpler lightweight version: a `ScheduledFuture` stored in a field alongside each slot in the pool (since our pool is fixed-size), cancelled when the slot is returned. This avoids per-checkout allocation by reusing fixed-size slot state.

---

## 5. Comparable Implementations

### 5.1 Android Room's connection pool

Android Room uses `RoomDatabase` backed by `SupportSQLiteOpenHelper`. For WAL mode, it relies on Android's native `SQLiteConnectionPool` (`android.database.sqlite.SQLiteConnectionPool`) which:
- Maintains 1 write connection + N read connections (N defaults to `SQLiteDatabase.MAX_SQL_CACHE_SIZE / something`; practically 2-4 on typical devices).
- Does connection validation implicitly: each `SQLiteConnection` tracks its `mLastOperationLog` and the pool reaps idle connections after `CONNECTION_POOL_BUSY_WARN_THRESHOLD_MILLIS`.
- Is NOT JDBC-based — not directly comparable. Operates at the NDK `sqlite3_*` API level.
- Provides autoCommit reset via `SQLiteSession`: each session tracks its transaction depth and explicitly manages BEGIN/COMMIT/ROLLBACK. There is no "forgot to commit" hole because the session always ends transactions explicitly at the boundary.

Room's design insight for us: **explicit session semantics prevent stale transaction state** better than cleanup-on-return. Our `DatabaseWriteActor` + SQLDelight's transaction abstraction provides similar structural guarantees for the write path, but read paths are exposed to raw connections.

### 5.2 Apache DBCP2

DBCP2 provides:
- `validationQuery` (same as `connectionTestQuery`), `testOnBorrow`, `testOnReturn`, `testWhileIdle`
- `removeAbandonedTimeout` (leak detection with stack trace capture — same concept as HikariCP)
- `evictionPolicyClassName` — pluggable eviction policy
- `minEvictableIdleTimeMillis` — idle eviction

For SQLite: DBCP2 is more configurable but significantly heavier than HikariCP. Not worth adopting.

### 5.3 Agroal (Quarkus default pool)

Agroal provides:
- `validateOnBorrow`, `reapTimeout` (idle eviction), `leakTimeout` (leak detection)
- `loginTimeout`, `acquisitionTimeout`
- Flush policy (evict all on error)

Similar feature set to HikariCP. Also not SQLite-specific. Not worth adopting.

### 5.4 xerial sqlite-jdbc built-in threading modes

The xerial `sqlite-jdbc` driver exposes SQLite's built-in threading modes via PRAGMA/URL params. Relevant:
- `PRAGMA journal_mode=WAL` — we already use this.
- `jdbc:sqlite:file:/path?cache=shared&mode=rwc` — enables SQLite shared-cache mode. **Avoid**: shared-cache disables WAL and introduces its own locking semantics; incompatible with our per-connection cache design.
- `SQLiteConfig.setJournalMode(JournalMode.WAL)` — the proper API-based way to set WAL; equivalent to our Properties approach.

No SQLite-native connection pool exists in the xerial driver. The driver's `ThreadedConnectionManager` (used by `JdbcSqliteDriver`) creates one connection per thread — exactly the problem we solved with `PooledJdbcSqliteDriver`.

### 5.5 libsql / turso

We already have `JvmLibsqlDriver` as a separate implementation. Its `resetPool()` method addresses the "DDL not visible across connections" problem that triggered the pool-deadlock investigation. The pattern (recreate all connections after DDL) is a valid SQLite-specific technique that HikariCP has no equivalent for.

---

## 6. Edge Cases for Our Design

### 6.1 autoCommit left false — stale transaction on return

**Scenario**: A coroutine calls `getConnection()`, calls `conn.setAutoCommit(false)`, executes some DML, then calls `closeConnection(conn)` without committing or rolling back. Our pool returns this connection to the queue. The next checkout gets a connection in the middle of an uncommitted transaction. Any reads on that connection will be in the snapshot of that abandoned transaction (WAL MVCC), not the current WAL head.

**HikariCP's prevention**: dirty-bit tracking on the proxy + rollback-on-close if `isCommitStateDirty`.

**Our risk**: Low in practice — `DatabaseWriteActor` uses SQLDelight's `newTransaction()` which manages `autoCommit` via `JdbcDriver.beginTransaction()` / `endTransaction()` (ThreadLocal). Direct JDBC `setAutoCommit()` calls would only happen in tests or in `withPinnedConnection` blocks. But `MigrationRunner` does use raw `execute()` calls that could leave connection state dirty if a migration throws mid-execution.

**Mitigation without a proxy**: on `closeConnection()`, check `conn.getAutoCommit()`. If false, call `conn.rollback()` then `conn.setAutoCommit(true)`. One extra JDBC call on the return path, only when `autoCommit` was changed.

### 6.2 Unclosed Statements held by returned connections

**Scenario**: A caller creates a `Statement` or `ResultSet`, fails to close it, and returns the connection. The statement holds a reference to the SQLite cursor. SQLite allows the cursor to remain open even as other operations proceed on the same connection, but the cursor pins memory (the B-tree traversal state). Over time, leaked statements accumulate on pooled connections.

**HikariCP's prevention**: `ProxyConnection` wraps every `createStatement` / `prepareStatement` / `prepareCall` call and adds the result to `openStatements`. On `close()`, `closeStatements()` iterates and closes all tracked statements.

**Our risk**: moderate. SQLite's `sqlite3_finalize()` is called implicitly by the JDBC driver when the `Connection` is garbage-collected, but GC may be delayed. More critically, unclosed cursors can prevent WAL checkpointing (a reader transaction is still open, holding back the WAL truncation point). In an 8-connection pool where all connections are long-lived, leaked cursors on pooled connections can cause the WAL file to grow without bound.

**Our exposure**: JdbcDriver's `JdbcStatementWrapper` does not auto-close on connection return. We have no equivalent of `openStatements` tracking.

### 6.3 Broken connection returned to pool after OS-level failure

**Scenario**: The OS kills the SQLite file descriptor (OOM killer closes all open FDs, or a test deletes the DB file while the pool is live). A connection in the pool becomes invalid. The next caller gets it, executes a query, and gets an opaque `SQLException` (e.g., "database disk image is malformed" or "unable to open database file"). Our pool has no health check — the broken connection stays in the pool and causes failures on every subsequent checkout.

**HikariCP's prevention**: `checkException()` inspects `SQLException.getSQLState()`. SQLite's JDBC driver does populate SQLState codes for some errors (`08` prefix, or driver-specific codes). On a fatal SQLState, the connection is evicted and a replacement is created.

**Our risk**: low in production (the DB file doesn't disappear), but real in tests (temp files are deleted, etc.). Our test setup (`@After tempFile.delete()`) combined with `fileDriver.close()` mitigates this for tests. In production, the main risk is a forceful process restart leaving FDs in a bad state — which closes the JVM anyway.

**Mitigation**: lightweight — check `conn.isClosed()` on checkout from the queue. If closed, drop it and try again. This catches the most common failure mode (connection closed externally) without a full `isValid()` call.

### 6.4 Pool deadlock: N connections, each needing M connections

HikariCP's wiki documents this formula: `pool size ≥ Tn × (Cm - 1) + 1` where `Tn` = max concurrent threads, `Cm` = max connections held simultaneously per thread.

**Our scenario**: `MigrationRunner.applyAll()` uses `driver.execute()` for each migration step. Each `execute()` calls `getConnection()` / `closeConnection()`. Since each step releases its connection before acquiring the next, `Cm = 1` for this path. No deadlock risk from MigrationRunner itself.

However, if `withPinnedConnection` nests inside a code path that also calls `driver.execute()` on the same thread (which calls `getConnection()` inside the pin block), the pool would need at least 2 connections on that thread to avoid deadlock. For in-memory pools (poolSize=1), this would deadlock. The fix: `withPinnedConnection` must not be used in contexts that call `driver.execute()` inside the block. This is enforced by code structure but not by the pool itself.

**The recent migration deadlock (pages_section_id)**: a `BEGIN` on connection A + DDL on connection B. SQLite's WAL mode requires all DDL to acquire a RESERVED lock, which blocks if any other connection has an open write transaction. Our pool's `withPinnedConnection` was added to prevent exactly this cross-connection DDL sequencing, but it requires that the entire DDL sequence run inside the pin block. If any part of the DDL sequence happens outside the pin (e.g., a migration that calls `driver.execute()` separately), a different connection can be assigned.

### 6.5 Pool closed while connections are checked out

`PooledJdbcSqliteDriver.close()` drains only connections present in the `ArrayBlockingQueue`. Connections currently checked out (in `getConnection()` / `closeConnection()` cycles) are not closed. If a coroutine is in the middle of a query when `close()` is called, it continues using the connection. When it calls `closeConnection()`, the pool is full (since `close()` set `closed=true` but didn't drain in-use connections), so `pool.offer()` returns false and the connection is closed. But the query already completed on the connection — so this is safe, just delayed.

**Risk**: if the caller holds the connection across a `close()` call from another coroutine and then calls a DB method, SQLite will return an error because the file may have been remapped. The error propagates as a `SQLException`. Currently we have no equivalent of `POOL_SHUTDOWN` state that would cause `getConnection()` to fail fast after `close()` is called.

---

## 7. Applicability Summary for PooledJdbcSqliteDriver

| HikariCP Feature | Applicability to SQLite pool | Priority |
|---|---|---|
| **autoCommit reset on return** | High — stale transactions on pooled connections are silent data correctness bugs. Detect `autoCommit=false` on return; rollback + reset. | **Adopt now** |
| **Dirty-bit state reset (readOnly, isolation, etc.)** | Medium — SQLite ignores `setReadOnly()` at JDBC level (it is advisory); isolation level is fixed by WAL; catalog/schema not applicable. Only `autoCommit` matters. | Partial adopt (autoCommit only) |
| **Open-statement tracking + close on return** | Medium — leaked SQLite cursors delay WAL checkpoint. Worth tracking and closing. | **Adopt later** |
| **Leak detection (scheduled task + stack trace)** | High for in-memory DBs (pool drain blocks forever). Low for file DBs (overflow connection created). Useful for diagnosing test hangs. | **Adopt now (in-memory), later (file)** |
| **`isClosed()` check on checkout** | Low-cost guard. Check before returning connection from pool. If closed, drop and try next. | **Adopt now** (1 line) |
| **`isValid()` on idle checkout** | Very low cost for SQLite (`isValid()` is a JNI handle check). Protect against file-deletion + shutdown-race scenarios. Use aliveBypassWindow pattern. | **Adopt later** |
| **MaxLifetime eviction** | Not applicable — SQLite file connections have no "network socket rot". There is no benefit to evicting and reconnecting a healthy SQLite connection. | **Not applicable** |
| **IdleTimeout eviction** | Not applicable — our pool is fixed-size by design. We want all 8 connections alive always. Dynamic pool sizing is out of scope. | **Not applicable** |
| **KeepaliveTime / SELECT 1 heartbeat** | Not applicable — SQLite connections do not go stale due to inactivity. A heartbeat query adds latency for zero benefit. | **Not applicable** |
| **connectionInitSql** | Partially applicable — we already apply all PRAGMAs at construction via `Properties`. `connectionInitSql` would be an alternative. Current approach is equivalent and cleaner for our fixed pool. | Not applicable (already covered) |
| **Dead-connection eviction on SQLException** | Low priority — SQLite `SQLException` SQLStates are not standardized across JDBC drivers; the benefit of evicting vs. the next caller getting a good connection is marginal for a local file DB. | **Adopt later** |
| **Pool-shutdown state + abort active connections** | Medium — `close()` should set a flag that causes `getConnection()` to fail fast. `abort()` on active connections. | **Adopt now** |
| **Metrics (Micrometer / Dropwizard)** | We already have `PoolWaitSnapshot`. HikariCP's metrics add: connection creation time, borrow time, usage time (held duration). Useful for profiling. | **Adopt later** |
| **JMX MBeans** | Out of scope for a desktop app. | **Not applicable** |
| **ConcurrentBag (thread-local affinity)** | `ArrayBlockingQueue` is simpler and sufficient for our fixed-size pool. `ConcurrentBag` provides meaningful improvement only at high checkout rates (>10k/sec) with many connections. For 8 connections + coroutine dispatch, the difference is negligible. | **Not applicable** |

---

## 8. Open Questions — Answered

### Does `isValid()` work with sqlite-jdbc 3.51.3.0?

Yes. `JDBC4Connection.isValid()` in xerial's sqlite-jdbc is implemented as a simple in-process check of the native SQLite handle. It does not send SQL to SQLite. It will return `false` if the connection was closed externally. Compatible with our usage.

### Is there a HikariCP pattern for detecting `autoCommit=false` on return?

Yes: `isCommitStateDirty && !isAutoCommit` in `ProxyConnection.close()`. We can implement a simplified version without a proxy: on `closeConnection(conn)`, check `!conn.getAutoCommit()`. If false: rollback + setAutoCommit(true). One JDBC call overhead, only when autoCommit was changed.

### Does `withPinnedConnection` need a timeout for in-memory DBs?

Yes, confirmed. Currently, if the block never completes (infinite loop or deadlock), the pool is drained permanently. HikariCP's equivalent (`connectionTimeout`) throws `SQLTransientConnectionException` after the deadline. We should add a `maxHoldMs` parameter (or use coroutine timeout via `withTimeout`) to `withPinnedConnection`. The default should be large (e.g., 30s) for production, overridable in tests.

### Is pulling HikariCP itself worth it vs. porting patterns?

**No.** HikariCP's most valuable patterns (autoCommit reset, leak detection, `isClosed()` check, pool-shutdown guard) are each 5-30 lines of code. Pulling HikariCP would add ~165KB to the JVM jar, introduce a SLF4J dependency (we use `java.util.logging`), and require configuring it for SQLite-WAL semantics (disable maxLifetime, idleTimeout, keepalive; set connectionTestQuery or rely on isValid()). The marginal patterns (ConcurrentBag, Micrometer integration, JMX) are not relevant. Port the patterns.

---

## Key Sources

- `PoolBase.java` — `setupConnection()`, `resetConnectionState()`, `isConnectionDead()`
- `HikariPool.java` — `getConnection()`, `recycle()`, `HouseKeeper`, `MaxLifetimeTask`, `KeepaliveTask`
- `ProxyConnection.java` — dirty-bit tracking, `close()` state cleanup, `checkException()`, statement tracking
- `ProxyLeakTask.java` — leak detection implementation
- HikariCP wiki "About Pool Sizing" — pool-locking deadlock formula
- `PooledJdbcSqliteDriver.kt` — baseline implementation
- `DriverFactory.jvm.kt` — connection properties, pool configuration context
- `ADR-015-android-wal-connection-pool.md` — Android WAL pool context
