# HikariCP Architecture Research
**Date**: 2026-07-01  
**Sources**: HikariCP `dev` branch — [ConcurrentBag.java](https://github.com/brettwooldridge/HikariCP/blob/dev/src/main/java/com/zaxxer/hikari/util/ConcurrentBag.java), [ProxyConnection.java](https://github.com/brettwooldridge/HikariCP/blob/dev/src/main/java/com/zaxxer/hikari/pool/ProxyConnection.java), [HikariPool.java](https://github.com/brettwooldridge/HikariCP/blob/dev/src/main/java/com/zaxxer/hikari/pool/HikariPool.java), [Down the Rabbit Hole wiki](https://github.com/brettwooldridge/HikariCP/wiki/Down-the-Rabbit-Hole)

---

## 1. HikariCP Internal Architecture

HikariCP has two major internal components: **ConcurrentBag** (the connection store) and **ProxyConnection** (the connection wrapper). The pool entry wrapping each connection is `PoolEntry extends IConcurrentBagEntry`, which stores the raw `java.sql.Connection` plus lifecycle metadata.

### Overall checkout flow

```
getConnection(hardTimeout)
  → connectionBag.borrow(timeout, MILLISECONDS)       // see §2
  → validate: isMarkedEvicted or idleTooLong → isConnectionDead()
  → poolEntry.createProxyConnection(leakTask)         // see §3
  → return ProxyConnection to caller
```

On return (`connection.close()` on the proxy):
```
ProxyConnection.close()
  → closeStatements()                   // close any open Statement handles
  → leakTask.cancel()                   // cancel scheduled leak alert
  → if (isCommitStateDirty) rollback()  // see §3
  → if (dirtyBits != 0) resetConnectionState(dirtyBits)
  → delegate.clearWarnings()
  → delegate = ClosedConnection.CLOSED_CONNECTION   // sentinel, blocks re-use
  → poolEntry.recycle() → connectionBag.requite()   // return to bag
```

---

## 2. ConcurrentBag — Internal Data Structure

ConcurrentBag is a purpose-built lock-free collection for connection pools. It holds three stores:

```java
private final CopyOnWriteArrayList<T> sharedList;          // all entries
private final ThreadLocal<List<Object>> threadLocalList;   // per-thread cache
private final SynchronousQueue<T> handoffQueue;            // waiter rendezvous
private final AtomicInteger waiters;
```

### Entry states

```java
interface IConcurrentBagEntry {
    int STATE_NOT_IN_USE = 0;   // idle, can be borrowed
    int STATE_IN_USE     = 1;   // checked out to a caller
    int STATE_REMOVED    = -1;  // permanently removed (evicted)
    int STATE_RESERVED   = -2;  // locked by housekeeping; not borrowable, not yet closed
}
```

State transitions are all CAS (`compareAndSet`), never locks.

### borrow() — three-tier checkout

```java
public T borrow(long timeout, TimeUnit timeUnit) throws InterruptedException {
    // TIER 1: thread-local list — tail scan (most recently used → least contention)
    final var list = threadLocalList.get();
    for (var i = list.size() - 1; i >= 0; i--) {
        final T bagEntry = ...list.remove(i)...;          // remove from TL immediately
        if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE))
            return bagEntry;                              // O(1), zero cross-thread work
    }

    // TIER 2: scan sharedList with CAS per entry — no global lock
    final var waiting = waiters.incrementAndGet();
    try {
        for (T bagEntry : sharedList) {
            if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                if (waiting > 1) listener.addBagItem(waiting - 1); // may have stolen another waiter's slot
                return bagEntry;
            }
        }

        // TIER 3: block on SynchronousQueue — notify pool to add a connection
        listener.addBagItem(waiting);
        timeout = timeUnit.toNanos(timeout);
        do {
            final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
            if (bagEntry == null || bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE))
                return bagEntry;  // null = timed out
            timeout -= elapsed;
        } while (timeout > 10_000);
        return null; // timed out
    } finally {
        waiters.decrementAndGet();
    }
}
```

### requite() — return path

```java
public void requite(final T bagEntry) {
    bagEntry.setState(STATE_NOT_IN_USE);
    // If any thread is waiting, try direct handoff (bypasses sharedList entirely)
    for (int i = 1, waiting = waiters.get(); waiting > 0; i++, waiting = waiters.get()) {
        if (bagEntry.getState() != STATE_NOT_IN_USE || handoffQueue.offer(bagEntry)) return;
        else Thread.yield(); // or parkNanos for virtual threads
    }
    // No waiters: park in thread-local list for next use by THIS thread
    if (threadLocalEntries.size() < 16) threadLocalEntries.add(bagEntry);
}
```

### add() — new connection added to pool

```java
public void add(final T bagEntry) {
    sharedList.add(bagEntry);
    // spin-offer to handoff queue while waiters exist
    while (waiters.get() > 0 && bagEntry.getState() == STATE_NOT_IN_USE && !handoffQueue.offer(bagEntry))
        Thread.yield();
}
```

### "Stolen" vs "borrowed"

- **Borrowed (TL hit)**: thread reclaims a connection it previously used — no cross-thread interaction, entirely local.
- **Stolen (sharedList scan)**: thread grabs a connection that another thread last used. When `waiting > 1`, pool is notified via `addBagItem(waiting - 1)` because the other waiter now has no candidate.

### Why not ArrayBlockingQueue?

| Property | ArrayBlockingQueue | ConcurrentBag |
|---|---|---|
| Fast-path (recently used, same thread) | Lock acquisition required | Thread-local list → lock-free O(1) |
| Slow-path (all connections busy) | `take()` → blocks on Condition | `handoffQueue.poll()` → rendezvous |
| Return path | Lock acquisition + signal | CAS state change + optional handoff offer |
| Contention under concurrent reads | All threads compete for the same lock | CAS per entry; TL hit avoids all contention |
| False sharing | Single array with head/tail pointers | TL list is per-thread; sharedList is COWAL |

For a fixed pool of 8 with many concurrent readers (e.g., 16 Dispatchers.IO threads doing SQLDelight reads), ConcurrentBag's thread-local tier means the 8 connections quickly become "owned" by 8 threads across repeated read cycles — checkout becomes a tail-scan of a 1-element list with no cross-thread work. ABQ.poll() always acquires a ReentrantLock regardless.

---

## 3. ProxyConnection Pattern

HikariCP wraps every `java.sql.Connection` in an abstract `ProxyConnection` before handing it to the caller. The concrete subclass is generated by Javassist at startup (not reflection — `invokestatic` not `invokevirtual`). 

### Dirty-bit state tracking

Six 1-bit flags track which connection properties the caller mutated:

```java
static final int DIRTY_BIT_READONLY   = 0b000001;
static final int DIRTY_BIT_AUTOCOMMIT = 0b000010;
static final int DIRTY_BIT_ISOLATION  = 0b000100;
static final int DIRTY_BIT_CATALOG    = 0b001000;
static final int DIRTY_BIT_NETTIMEOUT = 0b010000;
static final int DIRTY_BIT_SCHEMA     = 0b100000;

private int dirtyBits;
private boolean isCommitStateDirty;   // true if statements ran without commit/rollback
private boolean isAutoCommit;         // cached value set at checkout
```

When the caller calls `setAutoCommit(false)` → `dirtyBits |= DIRTY_BIT_AUTOCOMMIT`. On proxy `close()`, if `dirtyBits != 0`, `resetConnectionState(this, dirtyBits)` in `PoolBase` is called. It resets in this order: readOnly → autoCommit → transactionIsolation → catalog → networkTimeout.

**Double-check optimization**: each property is only restored via a JDBC call if (a) the bit is set AND (b) the current value differs from the pool's configured baseline. A caller that sets `autoCommit(false)` then `autoCommit(true)` results in the bit being set but no JDBC call on return (current value already matches). This makes the reset near-zero-cost in steady state for connections that weren't actually mutated.

### isCommitStateDirty rollback

```java
final void markCommitStateDirty() {
    if (!isAutoCommit) isCommitStateDirty = true;
}

// Called inside close():
if (isCommitStateDirty && !isAutoCommit) {
    delegate.rollback();
    // logs: "Executed rollback on connection X due to dirty commit state on close()"
}
```

This is the **critical safety net** for dirty transactions: if a caller opens a transaction and fails to commit/rollback, the proxy rolls it back on return, preventing the next caller from inheriting stale transaction state.

### Statement tracking + close-interception

The proxy tracks all created Statement objects in a `FastList<Statement>`:

```java
private <T extends Statement> T trackStatement(final T statement) {
    openStatements.add(statement); return statement;
}
```

On proxy `close()`, any unclosed Statement is force-closed before the connection is recycled. This prevents `ORA-01000: maximum open cursors exceeded` (and SQLite equivalent) from accumulating across pool cycles.

### Leak detection

At checkout time:
```java
poolEntry.createProxyConnection(leakTaskFactory.schedule(poolEntry))
```

`leakTaskFactory.schedule()` returns a `ScheduledFuture` that fires after `leakDetectionThreshold` ms. If `ProxyConnection.close()` hasn't been called by then, the task logs a warning including the **stack trace captured at checkout time** — making it trivial to find who leaked the connection. The task is cancelled in `close()`.

`ClosedConnection.CLOSED_CONNECTION` is a sentinel proxy (JDK `Proxy`) that throws `SQLException("Connection is closed")` on any method call except `isClosed()`, `isValid()`, `abort()`, `close()`. Setting `delegate = CLOSED_CONNECTION` in `close()` means double-close is harmless and use-after-close is immediately visible.

### Exception-triggered eviction (`checkException`)

SQL states that trigger immediate eviction (connection marked dead, not returned to pool):
- States starting with `"08"` — ANSI SQL connection exception class
- `"57P01"`, `"57P02"`, `"57P03"` — PostgreSQL admin shutdown
- `"01002"` — SQL92 disconnect
- `"JZ0C0"`, `"JZ0C1"` — Sybase disconnect

For SQLite via sqlite-jdbc, irrecoverable errors (DB file deleted, FS full) surface as `SQLException` with driver-specific codes, not these ANSI states. Eviction for SQLite would need to check `connection.isClosed()` or catch specific xerial error messages. This is a porting consideration, not a direct lift.

### `isValid()` in sqlite-jdbc 3.51.3.0 (bytecode verified)

`JDBC4Connection.isValid(int timeout)` disassembles to:
```java
if (isClosed()) return false;
Statement stmt = createStatement();
boolean result = stmt.execute("select 1");  // runs a real SQL query
stmt.close();
return result;
```

**Implication for our pool**: `isValid()` is correctly implemented and functional. However, calling it on every checkout (HikariCP's default behavior) would add a `SELECT 1` query to every `getConnection()` call — every non-transactional SQLDelight read already calls `getConnection()` once before executing its real query. The overhead is unnecessary for SQLite since local file connections don't go stale the way TCP sockets do. The `isClosed()` check inside `isValid()` is the only useful part; we can call `conn.isClosed()` directly in `closeConnection()` as a guard without paying for the `SELECT 1`.

---

## 4. Connection State Contract at Checkout

When `getConnection()` returns, HikariCP guarantees:

1. `autoCommit` = pool's configured value (default: `true`)
2. No open transaction — any dirty transaction from previous caller was rolled back
3. No open Statements — all were force-closed
4. Connection is "alive": either validated recently (within `aliveBypassWindowMs`, default 500ms) or passed `connection.isValid(max(1s, validationTimeout))` (JDBC4 path) or a custom `connectionTestQuery` (legacy path)
5. `readOnly`, `transactionIsolation`, `catalog`, `schema`, `networkTimeout` are restored to pool baseline if mutated by previous caller

Violation is structurally impossible: the proxy intercepts `close()` and enforces all of (1)–(3) before returning to the pool. (4) is enforced at checkout, not return. (5) is enforced by `resetConnectionState`.

---

## 5. HikariCP for SQLite WAL: Essential vs Irrelevant

### Essential / Directly Applicable

| Pattern | Why it applies |
|---|---|
| **isCommitStateDirty rollback on return** | Our pool does nothing on return. If a caller leaves `autoCommit=false` and a transaction open (e.g., exception mid-flight), the next connection user inherits that stale transaction. This is a real correctness gap. |
| **autoCommit dirty-bit reset** | `JdbcDriver.beginTransaction()` sets `autoCommit=false` via the ThreadLocal transaction. If `closeConnection()` is called before `endTransaction()`, the connection re-enters the pool with `autoCommit=false`. We have no reset. |
| **Statement tracking + force-close on return** | Less critical in SQLite (no cursor limit), but leaking PreparedStatement handles over many cycles is a memory issue and can hold read locks in WAL. |
| **Leak detection (scheduled task at checkout)** | With only 8 slots, a leaked connection drains 12.5% of capacity and is invisible until the pool is exhausted. A scheduled task + captured stack trace would surface it immediately. |
| **`STATE_RESERVED` for housekeeping** | Our `withPinnedConnection` can race with pool `close()` — a RESERVED state would let us atomically mark a connection "being evicted" without removing it from the pool while it's in use. |
| **`connectionInitSql` equivalence (per-connection PRAGMA)** | We use `Properties` applied at connection creation, which is the correct equivalent. No change needed here. |

### Not Applicable / Rabbit Holes

| Pattern | Why it doesn't apply |
|---|---|
| **maxLifetime eviction** | Designed for network-socket rot (TCP keepalive, DB server session timeouts). Local SQLite file connections have no network layer and don't rot. Applying this would just churn connections for no benefit. |
| **keepAlive (`SELECT 1` heartbeat)** | Same reason — SQLite has no idle connection timeout. A file-backed JDBC connection is valid as long as the file exists. |
| **Dynamic pool sizing (minIdle/maxPoolSize)** | Our fixed-8 design is intentional (predictable connection count, bounded cache_size memory). |
| **ConcurrentBag vs ArrayBlockingQueue** | For our specific workload (DatabaseWriteActor serializes all writes; reads are concurrent but pool is pre-sized to match concurrency budget), ABQ with its simple poll() is adequate. ConcurrentBag's TL caching benefit materializes at high thread counts with short-lived operations; our read paths hold connections only for the duration of `executeAsList()`, making TL affinity less valuable. Assess only if pool wait metrics show contention. |
| **Bytecode-generation for proxy** | Worth the complexity only if profiling shows proxy overhead is measurable. Our current `getConnection()`/`closeConnection()` is already minimal. |

---

## 6. SQLDelight JdbcDriver ThreadLocal Interaction

SQLDelight's `JdbcDriver` stores the active transaction connection in a `ThreadLocal<Transaction>`. `beginTransaction()` calls `getConnection()` and pins it for the life of the transaction. `endTransaction()` calls `closeConnection()`. This is compatible with the ProxyConnection pattern — the proxy wraps whatever `getConnection()` returns.

**Bytecode-verified contract (jdbc-driver 2.3.2):** `JdbcDriver.beginTransaction()` disassembles to:

```
getConnection()  // retrieves pooled connection
if (autoCommit == false) → throw IllegalStateException(
    "Expected autoCommit to be true by default. For compatibility with SQLDelight
     make sure it is set to true when returning a connection from JdbcDriver.getConnection()")
setAutoCommit(false)  // mutates connection to begin transaction
```

This means a connection that re-enters our pool with `autoCommit=false` is not silently corrupted — it will throw loudly on the next `newTransaction()` call. However, the throw happens at the point of *use* by the next caller, not at the point of *return* by the guilty caller. The corrupt connection still occupies a pool slot and will keep throwing until the pool is closed and recreated. The error is also attributed to the innocent next caller, not the leaker.

`endTransaction()` and `rollbackTransaction()` both call `commit()/rollback()` then `setAutoCommit(true)` then `closeConnection()` — in that order. If `rollback()` throws (broken connection), `closeConnection()` is never called, silently leaking a pool slot. For a fixed-8 pool, a single such leak reduces usable capacity to 7 with no diagnostic signal.

**Connection flow in our pool per transaction:**
```
newTransaction()
  → if (threadLocal has existing transaction) → reuse its connection (nested tx)
  → else → getConnection() from pool → beginTransaction(conn) → setAutoCommit(false)
execute() / executeQuery()
  → connectionAndClose():
      if (threadLocal.transaction != null) → return (transaction.conn, NO-OP close)
      else                                 → return (pool.getConnection(), { closeConnection(it) })
endTransaction() / rollbackTransaction()
  → commit()/rollback()
  → setAutoCommit(true)
  → closeConnection(conn) → pool.offer(conn)
```

**Critical implication**: if we add proxy-based state tracking, the proxy's `close()` must call `poolEntry.recycle()` which calls `connectionBag.requite()`. That is already how `JdbcDriver.closeConnection()` works in our stack — `closeConnection(connection)` just calls `pool.offer(connection)`. A proxy-based design would require `closeConnection()` to call `proxyConnection.close()` instead of `pool.offer(rawConnection)` directly.

**The `withPinnedConnection` + migration case**: the deadlock that triggered this audit (`BEGIN` on connection A, `COMMIT` on connection B because `execute()` got a different connection each call) is orthogonal to HikariCP's patterns. HikariCP solves this at the application layer — callers use the same proxy for the entire transaction scope because they never call the pool directly. Our `withPinnedConnection` addresses the same structural issue without requiring a proxy.

---

---

## 7. PoolEntry — Per-Connection State Envelope

```java
final class PoolEntry implements IConcurrentBagEntry {
    Connection connection;                      // the real JDBC connection
    volatile int state;                         // NOT_IN_USE=0, IN_USE=1, REMOVED=-1, RESERVED=-2
    volatile boolean evict;                     // set by markEvicted(); discarded at next borrow
    volatile long lastAccessed;                 // nanoTime when returned via recycle()
    long lastBorrowed;                          // nanoTime when checked out (for leak detection)
    ScheduledFuture<?> endOfLife;               // fires at maxLifetime to evict
    ScheduledFuture<?> keepalive;               // fires at keepaliveTime to run isValid()
    final FastList<Statement> openStatements;   // all Statement objects from this connection
    final boolean isReadOnly, isAutoCommit;     // pool's configured baseline values
}
```

`getMillisSinceBorrowed()` (from `lastBorrowed`) is what `ProxyLeakTask` uses. `evict` is set via `markEvicted()` — not closed immediately, discarded at next borrow.

---

## 8. ProxyLeakTask — Leak Detection

```java
class ProxyLeakTask implements Runnable {
    private Exception exception;   // captured at BORROW time (not when timeout fires)
    private String threadName;
    private boolean isLeaked;

    ProxyLeakTask(final PoolEntry poolEntry) {
        this.exception = new Exception("Apparent connection leak detected");
        // Strip top 5 frames (HikariCP internals) to leave only application frames
        StackTraceElement[] s = exception.getStackTrace();
        exception.setStackTrace(Arrays.copyOfRange(s, 5, s.length));
        this.threadName = Thread.currentThread().getName();
    }

    public void run() {
        isLeaked = true;
        log.warn("Connection leak detection triggered for {} on thread {}, stack trace follows",
            connectionName, threadName, exception);
    }

    void cancel() {
        scheduledFuture.cancel(false);
        if (isLeaked) {
            log.info("Previously reported leaked connection {} on thread {} was returned to the pool (unleaked)",
                connectionName, threadName);
        }
    }
}
```

Stack trace is captured **at borrow time** so the log entry names the borrower even when the timeout fires minutes later. `leakDetectionThreshold=0` (default) disables it entirely — no `ScheduledFuture` created.

**SQLite applicability**: worth a lightweight version — record `borrowTime` in `getConnection()`, check on `closeConnection()`, log a warning if held > 5 seconds. Avoids background threads entirely. An 8-slot pool losing one connection to a leak is a 12.5% capacity drop with no current diagnostic.

---

## 9. HouseKeeper — Background Maintenance

Runs every 30 seconds:
- **Clock anomaly detection**: if system clock jumps backward or forward > 128s, soft-evicts all connections (marks `evict=true`)
- **Idle eviction**: removes connections idle > `idleTimeout` toward `minimumIdle` — only for dynamic pools (`minIdle < maxPoolSize`)
- **Pool fill**: creates connections toward `minimumIdle` — again only for dynamic pools

Uses `STATE_RESERVED` (CAS from `NOT_IN_USE`) to atomically lock an entry for eviction before closing it.

**SQLite applicability**: none. Clock anomalies don't corrupt local file connections. Idle eviction and fill are for dynamic sizing. Our fixed-8 pool has no use for any of this.

---

## 10. Connection Validation: aliveBypassWindowMs + isValid()

### aliveBypassWindowMs — 500ms bypass

```java
// in getConnection():
if (poolEntry.isMarkedEvicted() ||
    (elapsedMillis(poolEntry.lastAccessed, now) > aliveBypassWindowMs &&
     isConnectionDead(poolEntry.connection))) {
    closeConnection(poolEntry, "..."); continue;
}
```

Default 500ms (system property `com.zaxxer.hikari.aliveBypassWindowMs`). Connections returned < 500ms ago skip `isConnectionDead()` entirely. Under continuous load, validation almost never fires.

### isConnectionDead() — JDBC4 isValid() preferred

```java
boolean isConnectionDead(final Connection connection) {
    return !connection.isValid(Math.max(1000L, validationTimeout) / 1000);
    // fallback: stmt.execute(config.getConnectionTestQuery())
}
```

### sqlite-jdbc 3.51.x isValid() — confirmed working

`isValid()` was added to `JDBC4Connection` via PR #27 (2015). Current implementation:

```java
public boolean isValid(int timeout) throws SQLException {
    if (isClosed()) return false;
    Statement statement = createStatement();
    try {
        return statement.execute("select 1");
    } finally {
        statement.close();
    }
}
```

`sqlite-jdbc` 3.51.3.0 fully supports `Connection.isValid()`. Old HikariCP issues (#393) claiming SQLite doesn't support it predate this implementation and are obsolete. We can use `connection.isValid(1)` at checkout without a `connectionTestQuery` workaround.

**SQLite applicability of validation**: local file connections don't go stale (no TCP socket). The 500ms bypass window is the right default. Checkout validation is a safety net for file descriptor errors or OS-level issues, not for network timeouts. If we add it, it fires rarely.

### connectionInitSql — new-connection hook only (not per-borrow)

Runs once when a connection is created, before it enters the pool. Our `Properties`-based PRAGMA setup in `buildMainDbConnectionProps()` is the equivalent. No change needed.

### keepaliveTime / maxLifetime / idleTimeout

All designed for TCP-socket databases. SQLite file connections have no network layer, don't rot, and our pool is fixed-size. None of these apply.

---

## Summary

- **ConcurrentBag**: three-tier lock-free store (ThreadLocal → CopyOnWriteArrayList CAS scan → SynchronousQueue rendezvous). Four states (`NOT_IN_USE`, `IN_USE`, `REMOVED`, `RESERVED`). Fast path is O(1) lock-free TL hit; slow path is a blocking handoff. Superior to ABQ under high concurrency; marginal benefit for a fixed-8 pool with bounded concurrency. Not worth adopting without pool-wait metric evidence of contention.

- **ProxyConnection**: wraps every connection with dirty-bit state tracking for 6 properties, `isCommitStateDirty` rollback-on-return, Statement tracking + force-close, and `ClosedConnection` sentinel after `close()`. These are the **correctness-critical** patterns our pool is missing.

- **For SQLite WAL specifically**: `keepAlive`, `maxLifetime`, `idleTimeout`, and `HouseKeeper` are noise (file connections, fixed pool). High-value targets: **(a)** `autoCommit` reset + `isCommitStateDirty` rollback on return, **(b)** borrow-timestamp hold-time warning on `closeConnection()` as lightweight leak detection, **(c)** optional `isValid(1)` at checkout for connections idle > 500ms — works in sqlite-jdbc 3.51.x via `SELECT 1`.
