# UX Research: HikariCP Developer Experience vs. PooledJdbcSqliteDriver

## Scope

This document covers developer experience (DX) and operational observability for connection
pool monitoring and debugging — specifically what HikariCP provides and what gaps exist in
SteleKit's `PooledJdbcSqliteDriver`.

---

## 1. HikariCP Metrics Exposure

### Interfaces

HikariCP exposes metrics through two mechanisms:

**JMX (lightweight, no deps)**

Enable with `registerMbeans=true`. Exposes `HikariPoolMXBean` at
`com.zaxxer.hikari:type=Pool (poolName)`. Methods: `getActiveConnections()`,
`getIdleConnections()`, `getTotalConnections()`, `getThreadsAwaitingConnection()`.
Also exposes `HikariConfigMXBean` for live config changes (e.g. `setMaximumPoolSize`).
Accessible from JConsole or VisualVM without code changes.

**Micrometer (full histogram support)**

Set via `setMetricsTrackerFactory(MicrometerMetricsTrackerFactory(registry))`.
`MicrometerMetricsTracker` registers:

| Metric name | Instrument | What it measures |
|---|---|---|
| `hikaricp.connections` | Gauge | Total connections in pool |
| `hikaricp.connections.active` | Gauge | Connections currently checked out |
| `hikaricp.connections.idle` | Gauge | Connections sitting in the pool |
| `hikaricp.connections.pending` | Gauge | Threads blocked waiting for a connection |
| `hikaricp.connections.max` | Gauge | `maximumPoolSize` config value |
| `hikaricp.connections.acquire` | Timer | Time from `getConnection()` call to checkout (wait time) |
| `hikaricp.connections.usage` | Timer | Time a connection is held before `closeConnection()` (work time) |
| `hikaricp.connections.creation` | Timer | Time to open a new physical JDBC connection |
| `hikaricp.connections.timeout.total` | Counter | Total connection acquisition timeouts |

The `acquire` and `usage` metrics are Micrometer `Timer` instances. When a
`DistributionStatisticConfig` with percentiles is wired in via `MeterFilter`, these
automatically publish p50/p95/p99 buckets to Prometheus, Datadog, CloudWatch, etc.

`recordConnectionAcquiredNanos` feeds `acquire`; `recordConnectionUsageMillis` feeds `usage`.

### What matters for SQLite/embedded use

In an embedded single-process deployment (no separate DB server, no network), the
metrics that matter change:

- `connections.active` + `connections.pending` are the health canary: pending > 0 means
  the pool is saturated.
- `connections.acquire` (wait time) is the direct signal for reader starvation.
- `connections.usage` (checkout duration) diagnoses *why* the pool is saturated — if
  p99 usage is high, slow queries or held transactions are the root cause, not pool
  size.
- `connections.creation` is near-zero for embedded SQLite (no network round-trip) and
  irrelevant for a fixed pre-created pool; skip it.
- JMX is appropriate for an embedded app: zero external dependencies, accessible from
  JConsole if a developer attaches to the process.

---

## 2. Leak Detection Developer Experience

### How HikariCP implements it

When `leakDetectionThreshold` (default: disabled) is set, HikariCP schedules a
`ProxyLeakTask` on every `getConnection()` call. The task captures the current thread's
stack trace at checkout time and schedules itself to fire after the threshold expires.
On `closeConnection()` the task is cancelled. If the task fires, the connection is still
outstanding — that is the leak.

### What the developer sees

```
2024-03-10T13:28:12.895Z WARN [HikariPool-1 housekeeper] c.z.h.pool.ProxyLeakTask -
  Connection leak detection triggered for org.sqlite.Conn@8dff50a on thread
  DefaultDispatcher-worker-3, stack trace follows
  java.lang.Exception: Apparent connection leak detected
      at dev.stapler.stelekit.db.SqlDelightPageRepository.getPageByUuid(SqlDelightPageRepository.kt:47)
      at dev.stapler.stelekit.ui.StelekitViewModel$navigateTo$1.invokeSuspend(StelekitViewModel.kt:112)
      ...
```

Three pieces of information arrive together:
1. **Thread name** — exactly which coroutine worker held the connection.
2. **Stack trace from acquisition site** — the line of code that called `getConnection()`.
3. **Timing** — it fires *before* a timeout, giving the developer a window to investigate
   a live process.

This is highly actionable: the developer can grep the stack trace for their own package
and find the offending repository method immediately, without reproducing the bug.

### Relevance to SteleKit

`PooledJdbcSqliteDriver` has no equivalent. If a `withContext(PlatformDispatcher.DB)`
block holds a connection longer than expected (e.g., a `flow { }` that never emits,
a suspend fun that calls another suspend fun inside a DB context), there is no warning.
The pool silently creates overflow connections (non-memory path) or stalls (memory path)
with no log evidence.

---

## 3. Pool Health Monitoring Patterns

### What "healthy" looks like

- `pending` = 0 consistently. Any non-zero value, even transiently, indicates the pool
  is sized at or below demand.
- `active` is well below `total` during normal operation. Sustained `active == total`
  with no pending threads means the pool is fully utilized but not exhausted yet — it is
  at risk.
- `acquire` p99 < 1 ms for an embedded SQLite pool (no network). Values > 5 ms indicate
  contention. Values > 50 ms indicate a serious problem.
- `usage` p99 tracks query duration. A rising p99 usage while `pending` is also rising
  means slow queries are holding connections and starving other threads.

### Engineer's "first look" checklist when something goes wrong

1. Is `pending` > 0? If yes, pool exhaustion.
2. Is `active == total`? Who is holding those connections? (Needs checkout tracking.)
3. Is `usage` p99 elevated? Long-held connections suggest slow queries or unreturned
   transactions.
4. Is `acquire` p99 elevated without pending? Intermittent contention — pool size may
   be marginal.
5. Any recent `connections.creation` spikes? (Irrelevant for fixed pre-created pools.)

### HikariCP's periodic log

At DEBUG level, HikariCP logs pool state every 30 seconds:

```
DEBUG [HikariPool-1 housekeeper] c.z.h.p.HikariPool -
  Pool stats (total=8, idle=6, active=2, waiting=0)
```

On a connection timeout (when `getConnection()` blocks past `connectionTimeout`):

```
WARN [HikariPool-1 connection adder] c.z.h.p.HikariPool -
  Timeout failure pool stats (total=8, active=8, idle=0, waiting=3)
```

The timeout log is the single most important line when diagnosing production pool
exhaustion: it shows that all 8 connections were held (`active=8`) and 3 threads were
blocked (`waiting=3`). This is machine-readable and easily parsed by log aggregators.

---

## 4. Diagnosing "Pool Exhausted" — What Developers Need to See

When a pool drains completely (all N connections checked out), the standard diagnostic
workflow requires:

**Minimum viable information (what HikariCP provides at WARN):**
- Total connections (`total=8`)
- Active connections at the time of failure (`active=8`)
- Idle connections (`idle=0`)
- Threads waiting at the time of failure (`waiting=3`)

**Richer information (requires leak detection or checkout tracking):**
- *Which threads* hold the active connections — thread names or coroutine names.
- *How long* each active connection has been checked out — checkout duration histogram.
- *Stack trace* showing where each connection was acquired — from `leakDetectionThreshold`.

Without checkout duration tracking, a developer seeing `active=8` cannot tell whether
all 8 connections are held by normal short-lived operations (pool undersized) or whether
1 or 2 long-running operations are starving the rest (transaction leak / slow query).
These two root causes have completely different fixes.

**Thread dump as a fallback:** When metrics are unavailable, a thread dump (`kill -3` or
JVM attach) shows threads blocked on `ArrayBlockingQueue.poll()` — this identifies the
*waiters* but not the *holders* unless the holder threads are still blocking on SQL.

---

## 5. PoolWaitSnapshot Enhancement

### Current state

```kotlin
data class PoolWaitSnapshot(val totalWaitMs: Long, val count: Long)
```

Provides: average wait time per call (`totalWaitMs / count`). This is sufficient only
to detect that wait time increased between two 5-second intervals.

### What would make it actionable

**Near-term additions (cheap, no reservoir sampling):**

| Field | How to compute | What it tells you |
|---|---|---|
| `maxWaitMs: Long` | track `AtomicLong maxWait` with `updateAndGet { max(it, newWait) }` | Single worst-case wait in the interval; catches spikes that average masks |
| `overflowCount: Long` | increment when `pool.poll()` returns null (non-memory path) | Number of times the pool was fully exhausted and a new connection was created |

**Medium-term additions (reservoir sampling or ring buffer):**

| Field | Approach | What it tells you |
|---|---|---|
| `p99WaitMs: Long` | 256-slot reservoir sample updated atomically | Whether the long tail is degrading independently of average |
| `maxCheckoutMs: Long` | record checkout start time per connection slot; update `maxCheckout` on return | How long the slowest active connection has been held; key for distinguishing leak from undersizing |

**What HikariCP teaches about the acquire/usage split:**

HikariCP separates *acquire time* (wait in queue) from *usage time* (time held by caller).
Knowing both lets you answer: "Is the pool slow to give out connections, or are callers
holding them too long?" Our `PoolWaitSnapshot` only has acquire time. Adding a parallel
`PoolUsageSnapshot` (checkout duration histogram) would complete the picture.

### Gap specific to PooledJdbcSqliteDriver

The non-memory path (`pool.poll() ?: DriverManager.getConnection(url, properties)`)
currently records **zero metrics** — no wait time, no overflow count, nothing. The
overflow creation is silent. This is the production code path for file-backed graphs.
The `PoolWaitSnapshot` metrics that do exist are only collected in the `isMemory` branch
(the test/in-memory path).

---

## 6. HikariCP Logging Practices

### Log events by level

**INFO (startup):**
- Pool name, configured max size, JDBC URL (truncated), `connectionTimeout`, `idleTimeout`,
  `maxLifetime`. Logged once at pool creation.
- Example: `HikariPool-1 - Start completed.`

**WARN (operational alerts):**
- `Thread starvation or clock leap detected (housekeeper delta=NNNms)` — fires when the
  internal housekeeper task is delayed significantly; usually means the JVM is under GC
  pressure or the thread pool is starved.
- `Apparent connection leak detected` — fires from `ProxyLeakTask` with full stack trace.
- Connection validation failure (when `connectionTestQuery` or `keepaliveTime` is set):
  `Failed to validate connection ... (No operations allowed after connection closed)`.
- `Timeout failure stats (total=N, active=N, idle=0, waiting=N)` — emitted just before
  throwing `SQLTimeoutException`.

**DEBUG (development / troubleshooting):**
- `Pool stats (total=N, idle=N, active=N, waiting=N)` every 30 seconds.
- Per-connection lifecycle: `Added connection`, `Closing evicted connection`.

### Patterns worth adopting

1. **Log pool stats on timeout.** The most actionable single line. When a caller cannot
   get a connection, log total/active/idle/waiting at WARN before throwing. This survives
   to log aggregators even if DEBUG is off in production.

2. **Log overflow creation at WARN.** When `pool.poll()` returns null and we fall back to
   `DriverManager.getConnection()`, log the event. This is a pool saturation signal.
   HikariCP's equivalent: adding connections beyond `minimumIdle` triggers a logged
   "Added connection" at DEBUG; a pool that is always at `maximumPoolSize` will never
   log these, which is itself a signal (no idle capacity).

3. **Log a startup summary at INFO.** Pool name, URL, pool size. Lets developers confirm
   the pool initialized with the expected configuration without reading source code.

4. **Housekeeper-style periodic heartbeat.** A periodic DEBUG log with current pool state
   (active/idle/overflow-count since last log) lets developers see "what was the pool
   doing during the 30 seconds before the crash" in a log dump. The ~30-second interval
   is a HikariCP convention; 5 seconds would align with the existing span-flush interval
   in SteleKit.

5. **WARN on clock leap / GC stall.** HikariCP's housekeeper measures wall-clock delta
   between runs; if the delta is wildly out of range, it logs a WARN about potential
   thread starvation. For SteleKit, this pattern could be applied: if a pool wait
   exceeds a warning threshold (e.g., 500 ms in a single drain interval), emit a WARN
   with the snapshot, rather than silently accumulating it.

---

## Summary Gap Table

| Capability | HikariCP | PooledJdbcSqliteDriver | Gap |
|---|---|---|---|
| Active / idle / pending counts | Yes (Micrometer gauges + JMX) | No | Missing |
| Acquire time average | Yes | Yes (memory path only) | Missing for file path |
| Acquire time p99 | Yes (Micrometer Timer) | No | Missing |
| Max single acquire time | Derivable from timer histogram | No | Missing |
| Checkout duration (usage time) | Yes (Micrometer Timer) | No | Missing entirely |
| Overflow connection count | Implied (pool never overflows by design in HikariCP) | No (silent overflow in file path) | Need to count + log |
| Leak detection with stack trace | Yes (`leakDetectionThreshold`) | No | Missing |
| Timeout log with pool state | Yes (WARN before throw) | No | Missing (no timeout in file path — silent overflow instead) |
| Periodic health heartbeat | Yes (DEBUG every 30s) | No | Missing |
| Startup summary log | Yes (INFO) | No | Missing |
| JMX / in-process access | Yes (MXBean) | No | Accessible via existing interface; expose as JMX bean for JConsole attach |
