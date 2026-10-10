# Build vs Buy: HikariCP vs PooledJdbcSqliteDriver

**Date**: 2026-07-01
**Author**: Research agent (Phase 2)
**Scope**: Evaluate replacing or supplementing our custom pool with HikariCP or another library.

---

## Baseline: What Our Pool Does Today

`PooledJdbcSqliteDriver` (167 lines, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/`) provides:

- Fixed-size `ArrayBlockingQueue`, poolSize connections pre-created at construction
- File DBs: non-blocking `poll()` with overflow connection creation on exhaustion
- In-memory DBs: blocking `poll(50 ms)` loop — no overflow, because each JDBC connection would get a separate empty schema
- Listener registry for SQLDelight reactive invalidation (`ConcurrentHashMap` + `CopyOnWriteArrayList`)
- `drainPoolWaitStats()` / `PoolWaitSnapshot` for wait-time telemetry
- `withPinnedConnection` for holding one connection across a multi-statement block
- **No** connection validation, **no** leak detection, **no** autoCommit state reset on return, **no** health check/eviction

Why it was built (commit `e4001e9600`, April 2026): `JdbcSqliteDriver`'s `ThreadedConnectionManager` creates one JDBC connection per OS thread. With `Dispatchers.IO` (up to 64 threads), a graph-load burst triggers 64 `JDBC.connect` calls at once. This pool pre-creates exactly `poolSize` connections so `JDBC.connect` is called once per connection, ever.

---

## Option 1: Adopt HikariCP

### Facts

| Property | Value |
|---|---|
| Latest version | 7.0.2 (released 2025-08-19) |
| License | Apache 2.0 |
| JAR size | 172,312 bytes (~168 KB) |
| Runtime dependency | `slf4j-api` (required); `javassist` (optional, proxy generation); `micrometer-core` (optional, metrics) |
| Java requirement | Java 11+ |

### Features we would get

- Connection validation (`Connection.isValid()`) on checkout and return
- Leak detection: `ProxyLeakTask` scheduled at checkout, cancelled on return, logs stack trace after `leakDetectionThreshold` ms
- `resetConnectionState()` on return: resets `autoCommit`, `readOnly`, `transactionIsolation`, `catalog` to pool defaults
- `connectionInitSql`: runs one SQL statement after every new connection creation
- HouseKeeper background thread: evicts connections older than `maxLifetime` (default 30 min), fills pool to min-idle
- Micrometer/Dropwizard metrics integration (optional)
- Dynamic pool sizing (`minimumIdle` / `maximumPoolSize`)

### SQLite-specific compatibility problems

**WAL mode via `addDataSourceProperty` does not work.** Issue #493 (open since 2015): passing `journal_mode=WAL` via `addDataSourceProperty` causes the error `"batch entry 0: query returns results"`. Root cause: xerial applies PRAGMA properties during connection creation; `PRAGMA journal_mode=WAL` returns a result set, which HikariCP's internal validation path interprets as an error. The fix is `connectionInitSql`, but `connectionInitSql` accepts only a single SQL statement. Our pool sets 10 PRAGMAs (`journal_mode`, `synchronous`, `transaction_mode`, `busy_timeout`, `cache_size`, `temp_store`, `wal_autocheckpoint`, `mmap_size`, `analysis_limit`, `foreign_keys`). Getting all of them applied would require a wrapper `DataSource` or a semicolon-separated trick that many drivers don't support.

**In-memory databases are incompatible with HikariCP's lifecycle assumptions.** For `:memory:` databases we must use exactly one connection forever (each JDBC connection gets a separate empty schema). HikariCP's HouseKeeper evicts connections after `maxLifetime` (default 30 min) and recreates them. A recreated `:memory:` connection returns a fresh schema-less database, producing "no such table" errors. Disabling `maxLifetime` (set to 0) and `keepaliveTime` works around this but then file databases also lose eviction benefit, and HikariCP still creates a minimum of `minimumIdle` connections — requiring careful per-database configuration with no compile-time enforcement.

**`Connection.isValid()` is now supported in sqlite-jdbc.** The 2015 issue #393 (sqlite-jdbc not implementing `isValid`) is resolved; sqlite-jdbc has implemented `JDBC4Connection.isValid()` since 2015 and 3.51.3.0 supports it. Validation works but adds one SELECT per checkout, which is latency with zero benefit: SQLite file connections never go stale (no network, same process, file is always accessible).

**Connection max-lifetime is irrelevant for SQLite.** The 30-minute eviction cycle exists to prevent network socket rot in RDBMS environments. SQLite file connections never rot. The only effect here would be periodic unnecessary reconnects, adding `JDBC.connect` latency during eviction.

**HouseKeeper background thread runs forever.** HikariCP's HouseKeeper thread polls every 30 seconds regardless of pool idle time. For an embedded desktop app, an always-on background thread for connection management adds noise without benefit.

**Dynamic pool sizing is unwanted.** Our fixed 8-connection design is intentional: predictable memory (8 × 32 MB page cache = 256 MB), bounded connection count, no surge behavior. HikariCP's `min-idle` / `max-pool-size` dynamic sizing adds complexity we explicitly rejected (see Rabbit Holes in requirements).

**Android: not applicable, but documented.** HikariCP uses Javassist for runtime proxy generation. Android's ART runtime does not support this. Our pool is `jvmMain` only, so this doesn't block adoption, but it means HikariCP can never be shared with `androidMain` if platform-level pooling is ever needed there.

**slf4j is a new transitive dependency.** We currently use `java.util.logging`. Adding HikariCP pulls in `slf4j-api`. Minor, but it adds an artifact to the build graph.

### Verdict on Option 1

**Reject.** The friction points are structural, not configuration: WAL PRAGMA delivery requires a workaround with no compile-time guard; in-memory database lifecycle is incompatible without per-instance configuration; connection validation and max-lifetime are anti-features for local SQLite; the HouseKeeper thread is dead weight. We'd spend more effort configuring HikariCP around SQLite's constraints than we'd gain from its features. The two features worth having (leak detection, autoCommit reset) can be ported individually for ~120 lines.

---

## Option 2: Port Specific HikariCP Patterns

### Applicable patterns (evaluate each)

#### autoCommit state reset on connection return — ADOPT NOW

**What it does in HikariCP.** `PoolBase.resetConnectionState()` is called from `ProxyConnection.close()`. It compares the connection's current `autoCommit` to the pool's configured value; if they differ, it calls `conn.setAutoCommit(poolAutoCommit)`. This prevents a connection returned mid-transaction from poisoning the next borrower.

**Why it matters here.** If `JdbcDriver`'s `ThreadLocal<Transaction>` is ever out of sync — e.g., a coroutine cancellation during a transaction that prevents `endTransaction()` from running — the connection goes back to the pool with `autoCommit=false`. The next borrower issues queries silently inside a ghost transaction. The migration deadlock that motivated this audit (BEGIN on connection A, COMMIT on connection B via `pool.take()`) is a variant of this class of bug.

**Implementation cost.** ~20 lines. In `closeConnection()`, before calling `pool.offer()`, add:
```kotlin
if (!connection.autoCommit) {
    log.warning("Connection returned with autoCommit=false; rolling back and resetting.")
    runCatching { connection.rollback() }
    connection.autoCommit = true
}
```
No new dependencies. No background thread.

**SQLite note.** `setAutoCommit(true)` on a sqlite-jdbc connection that has an open transaction issues an implicit ROLLBACK. This is correct behavior — a leaked write transaction should be rolled back, not committed.

#### Connection leak detection — ADOPT NOW

**What it does in HikariCP.** When `leakDetectionThreshold > 0`, HikariCP schedules a `ProxyLeakTask` (a `Runnable` submitted to a `ScheduledThreadPoolExecutor`) at checkout time. The task captures the checkout thread name and stack trace at the moment of scheduling. If the connection is still checked out after `leakDetectionThreshold` ms, the task fires and logs "Apparent connection leak detected" with the captured stack trace. The task is cancelled (via `Future.cancel()`) when the connection is returned.

**Why it matters here.** Our pool is fixed at 8 connections. When the pool drains completely on file databases, the ninth caller silently creates an overflow connection — masking the leak. On in-memory databases, the pool blocks indefinitely. Either way, a leaked connection is hard to diagnose without a stack trace pointing to the borrower. Leak detection converts a silent hang or mystery overflow into an actionable log line.

**Implementation cost.** ~80-100 lines including:
- A `ScheduledThreadPoolExecutor` (1 daemon thread, created lazily or at construction)
- A `LeakDetectionTask` (captures checkout `Exception` for stack trace, stores `ScheduledFuture`)
- Changes to `getConnection()` (schedule the task) and `closeConnection()` (cancel it)
- A configurable `leakThresholdMs: Long` parameter (0 = disabled, default 0 in tests, 5000 ms suggested for production)

Apache 2.0 license — the pattern can be ported with attribution in a comment.

#### Connection validation (isValid) — NOT APPLICABLE

`conn.isValid(timeout)` tests whether a JDBC connection is still live. For network RDBMS, this catches TCP socket rot. For SQLite file connections, the file is always accessible from the same process — connections never go stale. Adding a `SELECT 1` on every checkout adds ~0.1-0.5 ms of latency per operation with zero safety benefit. Explicitly excluded.

#### connectionInitSql — NOT APPLICABLE

We already apply all PRAGMAs via `Properties` at `DriverManager.getConnection()` time. This is equivalent to `connectionInitSql` and covers all 10 PRAGMAs in one step. No change needed.

#### Max-lifetime eviction — NOT APPLICABLE

Evicting and reconnecting healthy SQLite connections adds reconnection latency with no benefit. SQLite file connections never rot. Explicitly excluded.

#### Keep-alive SELECT 1 — NOT APPLICABLE

Same rationale as validation. SQLite connections don't idle-out.

#### Dynamic pool sizing (min-idle / max-pool-size) — NOT APPLICABLE

Fixed 8-connection design is intentional. See Rabbit Holes in requirements.

#### Micrometer metrics — ADOPT LATER (if telemetry layer expands)

HikariCP exposes `poolSize`, `activeConnections`, `idleConnections`, `pendingThreads`, `maxConnections`, `minConnections`, connection acquisition time, and usage time via Micrometer. We currently track `drainPoolWaitStats()` / `PoolWaitSnapshot` (total wait ms + call count, drained every 5 s). If SteleKit adds a Micrometer registry (e.g. for a JVM health endpoint), we could publish these counters then. Not worth the dependency now.

### Cost summary for Option 2

| Pattern | Lines | Dependency | Priority |
|---|---|---|---|
| autoCommit reset on return | ~20 | none | Adopt now |
| Leak detection | ~80-100 | none (reuse existing scheduler or add 1 daemon thread) | Adopt now |
| Connection validation | n/a | n/a | Not applicable |
| Max-lifetime eviction | n/a | n/a | Not applicable |
| Keep-alive | n/a | n/a | Not applicable |
| Micrometer metrics | ~30 | `micrometer-core` | Adopt later |

Total incremental code for "adopt now" items: ~120 lines. Pool grows from 167 → ~290 lines, still fully hand-owned, no new mandatory dependencies.

---

## Option 3: Use SQLDelight's JdbcSqliteDriver + Configure It Better

`JdbcSqliteDriver` (SQLDelight `sqlite-driver:2.3.2`) uses `ThreadedConnectionManager` internally. The `ThreadedConnectionManager` maintains a `ThreadLocal<Connection>` and creates a new JDBC connection for each new thread that calls `getConnection()`. With `Dispatchers.IO` (capped at 64 threads in Kotlin coroutines), a graph-load burst opens up to 64 connections simultaneously — exactly the problem our pool solves.

There is no API on `JdbcSqliteDriver` to configure a fixed pool size. The `dataSource` constructor overload accepts a `DataSource`, which could be a third-party pool, but that circles back to Option 1 or a custom pool.

**Verdict: Not viable.** The ThreadedConnectionManager's connection-per-thread model is fundamentally incompatible with our fixed-pool requirement. No configuration can address this.

---

## Option 4: Lightweight Alternatives

| Library | JAR size | Active? | SQLite issues | Verdict |
|---|---|---|---|---|
| c3p0 | ~600 KB | Effectively stale (last release 2019) | Same PRAGMA delivery issues as HikariCP | Reject |
| Apache DBCP2 | ~300 KB | Maintained | Same; also heavier than HikariCP | Reject |
| Vibur DBCP | ~200 KB | Low activity | Untested with SQLite WAL at our scale | Reject |

None offer a meaningful advantage over HikariCP for our use case, and all carry the same fundamental mismatch: they are designed for network RDBMS with dynamic pool sizing, connection validation via network ping, and socket lifecycle management.

---

## Recommendation: Option 2 — Port Two Patterns

**Stay custom; add autoCommit reset + leak detection.**

Our 167-line pool is a correct fit for its constraints: fixed-size, SQLite WAL-aware, in-memory-safe, zero transitive dependencies. The two gaps that genuinely matter for correctness and debuggability — stale autoCommit state and silent connection leaks — can each be addressed with ~20 and ~80 lines respectively, with no new dependencies and no background machinery beyond one daemon scheduler thread.

HikariCP (Option 1) would force us to work around WAL PRAGMA delivery, in-memory database lifecycle, connection validation overhead, and max-lifetime eviction noise — we'd spend more effort disabling HikariCP features than we'd gain from enabling them. The two features worth having are portable patterns, not inseparable from the library.

### Prioritized backlog

1. **Adopt now — autoCommit reset**: In `closeConnection()`, check `conn.autoCommit`; if `false`, log + rollback + reset. Prevents ghost transactions from leaked write connections. ~20 lines, zero deps.
2. **Adopt now — leak detection**: Add `leakThresholdMs` parameter to `PooledJdbcSqliteDriver`. At `getConnection()`, schedule a daemon task that logs checkout stack trace after threshold. Cancel on `closeConnection()`. Converts silent pool exhaustion into an actionable log line. ~80-100 lines, one daemon thread.
3. **Adopt later — Micrometer metrics**: If SteleKit adds a health/telemetry endpoint, expose pool stats (`activeConnections`, `pendingThreads`, `acquisitionTimeMs`) via Micrometer. ~30 lines + optional dep.
4. **Not applicable — connection validation, max-lifetime, keep-alive, dynamic sizing**: Explicitly excluded; document rationale in `PooledJdbcSqliteDriver.kt` KDoc to prevent future re-proposals.

---

## Sources

- HikariCP GitHub: https://github.com/brettwooldridge/HikariCP
- HikariCP 7.0.2 Maven Central: https://central.sonatype.com/artifact/com.zaxxer/HikariCP/7.0.2
- HikariCP 7.0.2 JAR (172 312 bytes): https://repo1.maven.org/maven2/com/zaxxer/HikariCP/7.0.2/
- SQLite JDBC issue #393 (isValid): https://github.com/brettwooldridge/HikariCP/issues/393
- SQLite WAL mode issue #493 (PRAGMA batch error): https://github.com/brettwooldridge/HikariCP/issues/493
- sqlite-jdbc isValid implementation: https://github.com/xerial/sqlite-jdbc/pull/27/files
- HikariCP leak detection internals: https://deepwiki.com/brettwooldridge/HikariCP/3.5-leak-detection
- SQLDelight JdbcSqliteDriver multi-thread issue #1453: https://github.com/cashapp/sqldelight/issues/1453
- Spring JDBC + SQLite WAL mode walkthrough: https://jilles.me/setting-up-spring-jdbc-and-sqlite-with-write-ahead-logging-mode/
