# Research: Build vs. Buy — LLM Tag Suggestion Download-Stall Polling

Agent 6 output for `project_plans/llm-tag-download-stall/`.

## 1. Existing OSS library / framework for "poll until condition, with deadline"

**Candidate found in-repo: `arrow.resilience.CircuitBreaker`** (`io.arrow-kt:arrow-resilience:2.2.1.1`, already a
declared dependency — `kmp/build.gradle.kts:91`). Used today in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/LlmProviderSupport.kt:75-80` as the shared
`defaultCircuitBreaker()` for `ClaudeLlmFormatterProvider` / `OpenAiLlmFormatterProvider` /
`GeminiLlmFormatterProvider`.

- **Pros**: Zero new dependency (Arrow already on the classpath); battle-tested; the repo already has a
  convention/precedent for using it.
- **Cons**: Solves the *opposite* problem. `CircuitBreaker` protects a caller from a **remote, failure-prone**
  operation by tripping open after N consecutive failures and backing off exponentially before allowing another
  attempt — it exists to stop hammering a struggling HTTP endpoint. The tag-suggestion download check is a
  **local, side-effect-free status read** (`checkAvailability(): LlmProviderAvailability`) that never "fails" in
  the HTTP sense — it just returns `Preparing` until the on-device model finishes downloading. There's no failure
  count to trip on, no remote service to protect, and no backoff-on-error semantics to reuse. Forcing this through
  `CircuitBreaker` would mean wrapping a non-failing status check in fake `Either`/exception failures just to
  drive the breaker state machine — inverted control flow for no benefit.
- **Arrow's `Schedule` (`arrow.resilience.Schedule`)** was also checked (part of the same `arrow-resilience`
  artifact, so it's a zero-cost addition dependency-wise) — it's a general repeat/retry combinator
  (`Schedule.spaced(interval) and Schedule.recurs(n)`, `.doWhile { }`, etc.) and is structurally closer to what's
  needed. It is not used anywhere else in the codebase currently.
- **Verdict: Not recommended.** `CircuitBreaker` doesn't fit the problem shape. `Schedule` is a **Viable but
  unnecessary** option — see §4, the codebase already has a simpler, in-house precedent for exactly this "poll on
  an interval, bounded, from a caller-owned scope" pattern that requires no new API surface to learn.

No other general-purpose "poll until true" Kotlin-coroutines library (Resilience4j-kt, kotlin-retry, etc.) is
present in `build.gradle.kts` or worth adding — see §3.

## 2. SaaS / managed API

**Not applicable.** The condition being polled — `MlKitLlmFormatterProvider` / `AndroidOnDeviceLlmProvider`'s
`checkAvailability()` — is a local, synchronous call into ML Kit / AICore's on-device SDK
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/llm/AndroidOnDeviceLlmProvider.kt:24`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt`). There is no network
request, no remote service, and nothing a managed polling/webhook SaaS (e.g. a job-status API) could sit in front
of. This entire feature is client-side state observation of a device-local model download. Moving on.

## 3. LLM-generated implementation vs. battle-tested library

**Polling loop itself**: hand-write it. At ~15 lines (`while (elapsed < deadline) { delay(interval); check
availability }`), the loop is trivial, has no tricky edge cases beyond "cancel cleanly" and "respect a deadline,"
and the repo already hand-rolls the structurally identical pattern in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt:111-138` (see §4 for the exact excerpt).
Pulling in a library (even Arrow's `Schedule`) for this would add an abstraction layer and a new API surface for
future contributors to learn, for a construct that's shorter than its own KDoc. **Verdict: Recommended (hand-write
it)**, consistent with repo convention.

**FR-6's timing measurement methodology**: this is the one place a rigorous approach matters, but the rigor needed
is "capture real timestamps from a real device," not "build a benchmark harness."

- `scripts/benchmark-local.sh` and the JFR/async-profiler infrastructure documented in
  `kmp/CLAUDE.md` (`jvmTestProfile` Gradle task, `.jfr`/`.collapsed` output, flamegraphs) are built for
  **JVM CPU/allocation profiling of desktop graph-load workloads** — they instrument the JVM's own coroutine
  pool and cannot observe a black-box Android system service (`AICoreDownloadService` / ML Kit's internal
  download manager) running outside the app process. **Not the right tool for FR-6.**
- The right instrumentation is much simpler and already implied by existing code conventions:
  1. **App-side**: log a timestamped line at every `LlmProviderAvailability` transition
     (`Unavailable(retryable) → Preparing → Available`) the first time a real device exercises the
     `DOWNLOADABLE`/`DOWNLOADING` path — a few `logger.info(...)` calls in
     `AndroidOnDeviceLlmProvider`/`MlKitLlmFormatterProvider` during manual QA, following the existing
     `Logger(...)` pattern used throughout `db/` and `voice/`.
  2. **System-side cross-check**: `adb logcat` already surfaces AICore/ML Kit GenAI download lifecycle events
     natively (tag families like `AiCoreService`, `GenerativeAIService`, `DownloadManager` on API 31+ devices with
     AICore) — capturing `adb logcat -s AiCoreService:* GenerativeAIService:*` (exact tags to be confirmed against
     the physical test device at measurement time) alongside the app-side timestamps gives an independent
     corroborating source without writing any new instrumentation.
  3. Run this on the actual target physical hardware (the requirement explicitly excludes emulator/synthetic
     timing), record wall-clock delta from `DOWNLOADABLE` first-seen to `AVAILABLE`, and set
     `DEFAULT_POLL_DEADLINE_MS` with headroom above the observed first-download time (the mid-wait caption change
     at ~45s implies the deadline should be comfortably larger, e.g. several minutes, pending the real number).
- **Verdict**: Ad hoc but *structured* manual capture (app logs + `adb logcat`) — **Recommended**. A full benchmark
  harness is **Not recommended**: this is a one-time device measurement to seed a constant, not a regression gate
  that needs to run in CI (no CI runner has AICore-capable hardware/model downloads available).

## 4. Fork or adapt — existing "wait for async readiness with escalating UI" patterns

Searched: `GraphLoader`/`FileRegistry` disk-watch retry, `QrTransferCoordinator`, Android `WorkManager` backoff
(`WorkManagerSyncScheduler.kt`).

**Best match: `GraphFileWatcher.kt:49-138`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt`).
This class already implements the exact skeleton FR-0/FR-5 need:

```kotlin
// GraphFileWatcher.kt:60  — owns its own scope, never accepts a caller-supplied one
private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

// GraphFileWatcher.kt:111-138 (startWatching)
watcherJob = scope.launch {
    launch {
        while (isActive) {
            try {
                delay(pollIntervalMs)          // 5-second polling fallback
                checkDirectoryForChanges(pagesDir)
                checkDirectoryForChanges(journalsDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Error in graph watcher", e)
            }
        }
    }
    // ...platform-native fast path launched alongside...
}
```

This is directly adaptable: same `delay(interval) → check → repeat` shape, same `while (isActive)` + rethrow-on-
`CancellationException` + swallow-and-log-on-`Exception` guard, same "class owns its own `SupervisorJob` scope"
rule that `TagSuggestionViewModel` already follows (`scope = CoroutineScope(SupervisorJob() + Dispatchers.Default
+ CoroutineExceptionHandler {...})`, `TagSuggestionViewModel.kt:43-45`). The one addition FR-2 needs that
`GraphFileWatcher` doesn't have is a wall-clock deadline — trivial to add as
`val deadline = TimeSource.Monotonic.markNow() + DEFAULT_POLL_DEADLINE_MS` (or a passed-in `Clock`) checked each
loop iteration, emitting a distinct terminal state once exceeded.

- `FileRegistry.kt` / `GraphLoader.kt:351` (`pollIntervalMs = watcherPollIntervalMs`) — same underlying pattern,
  just the caller-configured interval; no additional insight beyond `GraphFileWatcher` itself.
- `QrTransferCoordinator.kt` — checked, no polling/retry loop; it's a synchronous chunk-assembly state machine
  for QR frame transfer, not an async-readiness-wait pattern. Not a fit.
- `WorkManagerSyncScheduler.kt` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt`)
  — Android `WorkManager` backoff is for scheduling **deferred background jobs** (git sync) that can survive
  process death and run outside app-foreground time; it's OS-scheduler-level, not an in-memory coroutine polling
  loop tied to a live Compose screen's lifecycle. Wrong layer for a foreground bottom-sheet UI that needs
  sub-5-second UI feedback — WorkManager's minimum periodic interval is 15 minutes and it isn't built for
  "observe from a `StateFlow` while a sheet is open."

- **Verdict: Recommended — adapt `GraphFileWatcher`'s loop shape**, not the class itself (different lifecycle:
  `GraphFileWatcher` is unbounded/graph-lifetime, this needs a bounded per-suggestion-request loop owned by
  `TagSuggestionViewModel`'s existing scope), but same idiom: `while (isActive) { delay(interval); check();
  ... } ` wrapped in `try/catch (CancellationException) { throw e } catch (Exception) { log }`, plus a deadline
  check per iteration for FR-2, and testable via `runTest`/`advanceUntilIdle` per the NFR-3 precedent already
  established in `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/StelekitViewModelLlmSuggestionTest.kt:39-40,185`
  (virtual-time coroutine testing is a live convention in this exact package, not a new pattern to introduce).

## Summary table

| Option | Verdict |
|---|---|
| Arrow `CircuitBreaker` (existing dep, existing usage) | Not recommended — wrong problem (remote failure protection vs. local status polling) |
| Arrow `Schedule` (same dep, unused elsewhere) | Viable but unnecessary — repo already has a simpler in-house idiom |
| SaaS / managed polling API | N/A — no remote service involved |
| Hand-write the ~15-line poll loop | Recommended |
| Benchmark harness (`benchmark-local.sh`/JFR) for FR-6 | Not recommended — instruments JVM CPU, can't see AICore's out-of-process download |
| Structured manual capture (app log transitions + `adb logcat`) for FR-6 | Recommended |
| Adapt `GraphFileWatcher.kt:111-138` loop idiom | Recommended — closest existing precedent, cite as reference implementation |
| `QrTransferCoordinator` | Not a fit — no async-wait pattern present |
| `WorkManager` backoff | Not recommended — wrong layer (background job scheduler, 15-min minimum interval, not foreground-UI-coupled) |
