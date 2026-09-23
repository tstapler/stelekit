# Stack Research: HikariCP vs PooledJdbcSqliteDriver

**Date**: 2026-07-01
**Feature**: hikari-pool-lessons
**Phase**: 2 — Stack Research

---

## 1. HikariCP Feature Inventory

Current stable version: **7.1.0** (released June 2026). Apache 2.0 license.

### Core lifecycle features

| Feature | Config property | Description |
|---|---|---|
| Connection validation (JDBC4) | `connectionTestQuery` / `isValid()` | For JDBC4-compliant drivers (sqlite-jdbc qualifies), HikariCP calls `Connection.isValid(validationTimeout)` before handing a connection to a caller. A bypass window (`aliveBypassWindowMs`, default 500ms) skips the check for recently returned connections to avoid per-borrow overhead. For legacy non-JDBC4 drivers a `connectionTestQuery` SQL string is used instead. |
| Leak detection | `leakDetectionThreshold` | When a borrowed connection is not returned within this many ms, HikariCP logs a WARNING containing the full stack trace of the borrowing call site. Min 2000ms; 0 disables. The proxy cancels the task when the connection is returned, so no false positives if the caller simply takes a long time. |
| Max lifetime | `maxLifetime` | Connections are forcibly closed and replaced after this lifetime (default 30 min). As of 6.1.0 each connection gets a random jitter in the range 75–100% of `maxLifetime` (was 97.5–100% before 6.1.0) to avoid thundering-herd mass extinction. |
| Keep-alive | `keepaliveTime` | Added in 4.0.0; default changed to 2 min in 6.2.1. Periodically removes idle connections from the pool, calls `isValid()` or `connectionTestQuery`, then returns them. Purpose: prevent server-side timeout from closing connections that HikariCP believes are still open. |
| Idle timeout | `idleTimeout` | Idle connections beyond `minimumIdle` are evicted after this period (default 10 min). Not applied when `minimumIdle == maximumPoolSize`. |
| Acquisition timeout | `connectionTimeout` | Max wait to borrow a connection before throwing `SQLTransientConnectionException` (default 30 s). |
| Init SQL | `connectionInitSql` | SQL executed on every new physical connection before it enters the pool. Used to set session-level settings (e.g., `PRAGMA journal_mode=WAL`). |
| Init fail timeout | `initializationFailTimeout` | How long to block startup waiting for at least one pooled connection. `-1` disables the wait; `0` is fail-fast (default). |

### State-reset features (proxy layer)

HikariCP wraps every borrowed connection in a `ProxyConnection`. On `close()` (return to pool) the proxy resets:

| State | Action on return |
|---|---|
| `autoCommit` | Reset to the configured value (`autoCommit` config, default `true`). |
| Open transaction (dirty state) | Issues `rollback()` if the connection has uncommitted work. Dirty state is tracked via a `commitStateDirty` flag flipped on `commit()`, `rollback()`, `setAutoCommit()`, `getMetaData()`, `Savepoint` ops. |
| Read-only | Reset to configured value. |
| Transaction isolation | Reset if altered. |
| Network timeout | Reset via `setNetworkTimeout()`. |
| Catalog / Schema | Reset if altered. |
| Open `Statement` objects | Closed on return. |

This proxy reset is the primary safety net HikariCP provides against caller mistakes. Without it, a connection returned with `autoCommit=false` or an open transaction silently corrupts the next caller.

### Observability features

| Feature | Description |
|---|---|
| JMX (`HikariPoolMXBean`) | Runtime pool stats: active, idle, pending, total connection counts. Supports soft-evict at runtime. |
| Dropwizard Metrics | Histograms for connection acquisition time, usage time, creation time; meter for timeout rate. Optional dependency. |
| Micrometer | Same metrics, Micrometer API. Optional dependency. |
| Prometheus | Histogram/gauge export. Optional dependency. |
| Health checks | `ConnectivityCheck` (can the pool get a connection?) and `Connection99Percent` (99th-percentile acquisition time within threshold?). Dropwizard HealthCheck integration. |
| Debug logging | Pool state logged periodically at `DEBUG` level: size, active, idle, waiting. On connection timeout, the log includes pool stats and connection creation latency. |

### Advanced / correctness features

| Feature | Description |
|---|---|
| `SQLExceptionOverride` | Hook (added 6.1.0) allowing callers to override the default eviction logic for specific `SQLException` codes. Useful to avoid evicting connections on expected transient errors. |
| `HikariCredentialsProvider` | Added 7.0.0. Allows dynamic credential rotation without pool restart. |
| Pool suspend / resume | Temporarily blocks `getConnection()` calls; useful for failover scripting. Exposed via JMX. |
| Virtual-thread fix | 7.1.0 fixes a yield-spin in `ConcurrentBag` that caused poor performance under Project Loom (JDK 21 virtual threads). |
| `beginRequest` / `endRequest` | 6.0.0 adds support for JDBC 4.3 connection request boundaries, required by some Oracle features. Not relevant for SQLite. |

---

## 2. sqlite-jdbc Driver Capabilities

### `Connection.isValid(timeout)` — source-verified

`JDBC4Connection.isValid(int timeout)` in xerial/sqlite-jdbc master:

```java
public boolean isValid(int timeout) throws SQLException {
    if (isClosed()) {
        return false;
    }
    Statement statement = createStatement();
    try {
        return statement.execute("select 1");
    } finally {
        statement.close();
    }
}
```

Key facts:
- **The `timeout` parameter is silently ignored.** If the SQLite file is on a slow/busy filesystem and the `SELECT 1` hangs, it will not be interrupted after `validationTimeout` ms. HikariCP's leak detection and connection timeout guarantees are therefore weaker for SQLite than for network drivers that honor the timeout.
- The validation query is a real `SELECT 1` statement, not a native ping. It executes in the current `autoCommit` state of the connection.
- The implementation has been unchanged since PR #27 (April 2015).
- sqlite-jdbc is a JDBC4 driver, so HikariCP will prefer `isValid()` over a `connectionTestQuery` by default.

### `autoCommit` state

- Default: `autoCommit = true` per JDBC spec; sqlite-jdbc honors this.
- `JdbcDriver.beginTransaction()` calls `conn.setAutoCommit(false)`; `endTransaction()` / `rollbackTransaction()` restore it to `true`.
- In `PooledJdbcSqliteDriver`, this is handled by `JdbcDriver`'s `ThreadLocal<Transaction>` stack. If a non-transactional `execute()` call leaves autoCommit altered (e.g., an exception path that skips `endTransaction`), the **next borrower of that connection inherits the dirty state** — there is no reset on return.
- HikariCP's proxy would detect and correct this via its `resetConnectionState()` call on return.

### Connection properties / PRAGMAs

sqlite-jdbc applies `Properties` entries as `PRAGMA` statements alphabetically at connection open time (see `SQLiteConfig`). The current `PooledJdbcSqliteDriver` relies on this to set WAL mode, busy_timeout, etc. This approach is equivalent to HikariCP's `connectionInitSql` but runs only once at connection creation, not on reconnect — which is fine since SQLite PRAGMAs set during connection open are per-connection.

---

## 3. HikariCP Features vs SQLite WAL Semantics

### Features designed for network RDBMS — limited or zero value for SQLite

| HikariCP feature | Why less applicable to SQLite |
|---|---|
| `keepaliveTime` | SQLite is in-process, not over a network. There is no server-side idle-connection timeout that would close the JDBC handle. A SQLite connection can sit idle for hours with no risk of being killed externally. Zero-value for file SQLite. |
| `maxLifetime` | Network RDBMS connections accumulate server-side state and can be closed by the server (firewalls, `wait_timeout`). A SQLite file handle does not age out. Still provides minor defense against memory fragmentation or WAL accumulation on very long-lived processes, but not a priority. |
| `networkTimeout` (`Connection.setNetworkTimeout()`) | SQLite is local. No network latency to bound. HikariCP sets this on connections where the driver supports it; SQLite driver throws `SQLFeatureNotSupportedException` so HikariCP silently skips it. |
| `idleTimeout` | Only relevant if you want to shrink the pool below `maximumPoolSize` during low load. With a fixed-size pool (`minimumIdle == maximumPoolSize`), HikariCP does not evict idle connections regardless of `idleTimeout`. Matches current PooledJdbcSqliteDriver's fixed-pool design. |
| `beginRequest` / `endRequest` | Oracle-specific JDBC 4.3 feature for multiplexed connections. No SQLite relevance. |
| `HikariCredentialsProvider` | SQLite files have no credentials. Not applicable. |
| Pool suspend / resume | Useful for cross-host failover. SQLite files don't fail over via pool suspend. |

### Features that apply universally — directly relevant to SteleKit

| HikariCP feature | Value for PooledJdbcSqliteDriver |
|---|---|
| **autoCommit reset on return** (proxy) | HIGH. `JdbcDriver`'s `endTransaction()` resets autoCommit, but if an exception escapes a transaction (e.g., in `MigrationRunner` or `withPinnedConnection`) without going through `JdbcDriver`'s path, the returned connection carries `autoCommit=false`. The next borrower then runs reads inside an uncommitted transaction, which is invisible until a write arrives and hits SQLITE_BUSY_SNAPSHOT. |
| **Transaction rollback on dirty return** (proxy) | HIGH. Same scenario — a half-committed migration or failed batch write leaves an open transaction. The current pool has no rollback-on-return. |
| **Leak detection** | HIGH. If any coroutine escapes `getConnection()` without calling `closeConnection()` — e.g., an uncaught CancellationException before `finally` — the pool shrinks silently. After 8 connections are lost the file DB stalls on overflow connections indefinitely. |
| **Connection validation on borrow** | MEDIUM. SQLite in-process connections don't go stale from network events, but they can be invalidated by `driver.close()` (GraphManager.shutdown). Currently a borrowed connection from a closed driver throws `IllegalStateException` which crashes the coroutine. A validation check on borrow would degrade gracefully instead. |
| **Metrics / observability** | MEDIUM. Current pool has `drainPoolWaitStats()` (custom). HikariCP would provide standardized Micrometer/Prometheus export covering acquisition time, active count, pending count. |
| **`connectionTimeout`** | MEDIUM. Currently the in-memory pool blocks with 50ms poll loops. For file DBs, `getConnection()` returns an overflow connection immediately, masking pool exhaustion from callers. A `connectionTimeout` + exception on exhaustion surfaces pool pressure explicitly. |
| **`initializationFailTimeout`** | LOW. SteleKit already handles schema creation failures in `DriverFactory.createDriver()`. Aligning with HikariCP's fail-fast model would remove custom error handling. |

---

## 4. Dependency Evaluation

### Version and size

- **Latest stable**: 7.1.0 (June 2026)
- **JAR size**: ~170 KB (self-contained; proxy classes generated at build time, not requiring runtime Javassist)
- **License**: Apache 2.0 — compatible with SteleKit's use

### Transitive dependencies (runtime)

From the 7.1.0 POM:

| Dependency | Scope | Notes |
|---|---|---|
| `org.slf4j:slf4j-api:2.0.17` | compile | Only mandatory runtime dep. SteleKit uses `java.util.logging`; an SLF4J → JUL bridge or no-op impl would be needed |
| `org.javassist:javassist` | optional | Used only for proxy class generation — this now happens at build time so runtime Javassist is NOT required |
| `io.micrometer:micrometer-core` | optional | Only needed if metrics integration is used |
| Dropwizard metrics, Prometheus | optional/provided | Only needed if metrics integration is used |
| All others | test | Not transitive to callers |

**Net result**: If SteleKit adds HikariCP, the only new transitive dependency is `slf4j-api`. SteleKit would need `slf4j-simple` or `slf4j-jdk14` on the classpath to route HikariCP logs to `java.util.logging`. This is a one-line addition.

### SQLite JDBC compatibility

HikariCP uses `DataSource` or `Driver` + URL mode. sqlite-jdbc provides `SQLiteDataSource` which works directly. Known compatibility: widely used by Minecraft plugin authors for SQLite + HikariCP pools. No SQLite-specific configuration is required — HikariCP treats SQLite like any other JDBC driver.

Key configuration note: For WAL mode with `DatabaseWriteActor` serializing writes, `maximumPoolSize=8` and `minimumIdle=8` (fixed pool) matches the current design. The `autoCommit=true` default aligns with JdbcDriver's expectations.

One gotcha: HikariCP wraps connections in `ProxyConnection`. SQLDelight's `JdbcDriver` accesses the underlying connection via `Connection.unwrap(Connection.class)` in some paths. HikariCP's proxy implements `isWrapperFor`/`unwrap` correctly (returning the underlying connection when asked), so this should work — but requires integration testing.

---

## 5. Recent HikariCP Version History

| Version | Notable changes |
|---|---|
| **7.1.0** (Jun 2026) | Fixed virtual-thread yield spin in `ConcurrentBag` — important for JDK 21 virtual threads (Kotlin coroutines on `Dispatchers.IO` use thread pools, but if SteleKit ever moves to virtual threads this matters) |
| **7.0.0** | `HikariCredentialsProvider` for dynamic credentials; bail-out on thread interrupt during pool fill |
| **6.3.0** | `keepaliveTime` variance increased 10% → 20%; duration string config (`"2m"`, `"30s"`) |
| **6.2.1** | `keepaliveTime` default set to 2 min (was unlimited / 0) |
| **6.2.0** | `SQLExceptionOverride` extended to all exceptions; better zero-pool logging |
| **6.1.0** | `maxLifetime` jitter increased 2.5% → 25% to eliminate thundering-herd expiry dips; `SQLExceptionOverride` for all eviction decisions |
| **6.0.0** | `Savepoint` rollbacks mark connection dirty; fixed double-close on try-with-resources; `beginRequest`/`endRequest` support |
| **5.0.0** | Rewrote connection elide/add code to fix pool-drains-to-zero race |
| **4.0.0** | Added `keepaliveTime` property; pre-generated proxy classes (no runtime Javassist) |

The most impactful improvements for SteleKit's use case are:
1. **6.1.0**: `maxLifetime` jitter increase — relevant if max-lifetime is ever used for SQLite
2. **7.1.0**: Virtual-thread ConcurrentBag fix — relevant if JVM 21 virtual threads are adopted
3. **6.2.0**: Better zero-pool size logging — helps diagnose connection exhaustion

---

## Summary Gap Table: PooledJdbcSqliteDriver vs HikariCP

| Safety net | HikariCP | PooledJdbcSqliteDriver | Risk without it |
|---|---|---|---|
| `autoCommit` reset on return | Yes (proxy) | No | Next borrower runs queries inside open transaction |
| Rollback dirty transaction on return | Yes (proxy) | No | Uncommitted work silently persists |
| Leak detection (stack trace logging) | Yes (threshold configurable) | No | Pool drains to 0; no diagnostic |
| Connection validation on borrow | Yes (isValid / bypass window) | No | Closed/invalid connections throw mid-query |
| Open statement cleanup on return | Yes (proxy closes all) | No | Statement handles accumulate |
| Observability (Micrometer/Prometheus) | Full suite | drainPoolWaitStats() only | Limited production visibility |
| Acquisition timeout (pool exhaustion) | Yes (configurable, throws) | File DB: overflow; in-memory: 50ms loop | File DB: masks exhaustion; in-memory: may hang |

The highest-priority gaps are the first three rows. They represent correctness failures (not just performance or observability gaps) that can cause silent data corruption or pool exhaustion without any error surfaced to the caller.
