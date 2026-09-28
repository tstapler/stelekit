# Research: Prior Art & Edge Cases — LLM Tag Suggestion Download-Stall Fix

## 1. Existing "wait for background thing, then auto-retry" patterns

### `GitSyncService` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`) — closest prior art, two distinct sub-patterns

**a) Self-scheduling one-shot retry (`scheduleRateLimitRetry`, lines ~90-100)**
```kotlin
@kotlin.concurrent.Volatile private var rateLimitRetryJob: Job? = null

private fun scheduleRateLimitRetry(graphId: String, retryAfterSeconds: Int?, retryOperation: suspend (String) -> Unit) {
    rateLimitRetryJob?.cancel()
    rateLimitRetryJob = scope.launch {
        delay((retryAfterSeconds ?: DEFAULT_RATE_LIMIT_RETRY_SECONDS) * 1000L)
        rateLimitRetryJob = null   // cleared BEFORE invoking, so a re-entrant cancel-at-top doesn't self-cancel
        retryOperation(graphId)
    }
}
```
Explicit `delay`-based job on a service-owned scope (`CoroutineScope(SupervisorJob() + PlatformDispatcher.IO + exceptionHandler)`, never `rememberCoroutineScope()`). `shutdown()` cancels the whole scope, so this job is never leaked. This is the direct model for FR-3's manual-retry-after-`retryable`-signal path.

**b) Repeating poll loop (`startPeriodicSync`/`stopPeriodicSync`, lines 559-574)**
```kotlin
fun startPeriodicSync(graphId: String, intervalMinutes: Int) {
    stopPeriodicSync()
    if (intervalMinutes <= 0) return
    periodicSyncJob = scope.launch {
        while (true) {
            delay(intervalMinutes * 60_000L)
            fetchOnly(graphId)
        }
    }
}
fun stopPeriodicSync() { periodicSyncJob?.cancel(); periodicSyncJob = null }
```
This is the direct model for FR-0's 3-5s poll loop: explicit `while(true) { delay(...); check(...) }` on an owned scope, `@Volatile` job reference, idempotent stop. **Gap vs. this feature's needs**: it has no bounded deadline (FR-2) and no caption escalation (FR-2's "taking longer" at ~45s) — those must be added, there's no existing precedent for a *bounded* poll loop with a deadline branch in this codebase. Recommend modeling the new loop as `while (elapsed < DEFAULT_POLL_DEADLINE_MS) { delay(pollIntervalMs); ...; if (elapsed > CAPTION_ESCALATION_MS) updateCaption() }` then falling out to a terminal "taking longer than expected" state — no existing helper for this, will need to be written fresh.

**Concurrency pattern takeaway**: prefer the explicit delay-loop pattern (used twice in `GitSyncService`) over a `StateFlow`-collector pattern for this feature, since the underlying signal (`checkStatus()`) is a poll-based suspend function, not a push-based `Flow` — there's no `Flow<FeatureStatus>` to `collect`. `LlmProvider.checkAvailability()` must be called imperatively in a loop; no adaptation of a reactive collector pattern applies here.

### `GraphLoader.externalFileChanges` / `DiskConflict` (push-based Flow, NOT applicable pattern)
`GraphFileWatcher.externalFileChanges: SharedFlow<ExternalFileChange>` (surfaced via `GraphLoader`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt:444`) is push-based — the OS file-watcher emits on actual change, no polling. `DiskConflictDialog`/`DiskConflictFullScreen` just collect and react. This is a StateFlow/SharedFlow-collector pattern, but it doesn't transfer to this feature because there is no equivalent OS-level push signal for AICore download completion — `checkStatus()` must be polled.

### `QrTransferCoordinator` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/qrcode/QrTransferCoordinator.kt`)
Coordinates a multi-chunk transfer session state machine but is driven by incoming scanned frames (external events), not a wait-then-poll loop — not a close analog for this feature; ruled out as prior art.

### `ProcessLifecycleOwner` observers (`SafChangeDetector`, `AndroidCameraProvider`, `AndroidCameraPreviewBinder`)
`SafChangeDetector` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt`) registers a `DefaultLifecycleObserver` on `ProcessLifecycleOwner` and fires work on `ON_START` (app foregrounded). This is the established codebase pattern for "pause background work while backgrounded, resume on foreground" — relevant prior art if the poll loop needs to pause while Android backgrounds the app (see edge case 3 below). No existing code currently wires this into the LLM/tag path.

## 2. Edge cases in `TagSuggestionViewModel` (read in full: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt`)

**Single global job, not per-block — this already answers "does polling leak across blocks."**
The ViewModel tracks exactly one `suggestionJob: Job?` and one `activeBlockUuid: String?` for the whole ViewModel instance (there is exactly one `TagSuggestionViewModel` instantiated app-wide, constructed in `ui/App.kt` — confirmed via grep, no per-block or per-sheet instances). `requestSuggestions()`'s cancel-then-launch logic:
```kotlin
val cached = cache[blockUuid]
if (cached != null) {
    _state.value = cached
    if (cached.llmPending && activeBlockUuid == blockUuid) return   // same block, already running — don't restart
    if (!cached.llmPending) return                                    // fully resolved — nothing to do
    // else: falls through — cached but job isn't the active one (was cancelled by a block switch)
}
suggestionJob?.cancel()      // unconditional — despite the comment "only if it's for a different block",
activeBlockUuid = blockUuid  // the differentiation actually happens above via early-return, not here
suggestionJob = scope.launch { ... }
```
**Finding**: switching from block A (suggestion/poll pending) to block B **unconditionally cancels A's in-flight job**, including whatever poll loop A was running. `cache[A]` is left with `llmPending = true` forever (the cancelled coroutine never reaches the code that flips it to `false`) until the user navigates back to block A and calls `requestSuggestions(A)` again — at which point the "cached but not active" branch above deliberately re-runs it from scratch. **Implication for this feature**: this existing architecture already prevents cross-block poll leakage (FR-5 is satisfied "for free" if the new poll loop lives inside the same `suggestionJob` coroutine body) — but it means block A's download-wait/poll is *not* resumed silently in the background when switching away; it's fully restarted when the user returns. The plan should decide explicitly whether that's acceptable (matches current one-job-at-a-time design) or whether background persistence across block switches is actually wanted — nothing in the requirements says otherwise, so restart-on-return is consistent with FR-5 as written but should be called out as a deliberate, not incidental, choice in the plan.

**Reopen-same-block mid-download is already cache-coherent.**
`dismiss()` intentionally does **not** cancel `suggestionJob` — comment: "let the LLM finish in the background and cache the result." So if the sheet is dismissed and reopened for the *same* block while the poll loop is still running, `requestSuggestions()` hits the `cached.llmPending && activeBlockUuid == blockUuid` branch and just restores state from cache without restarting anything. This is exactly the behavior the new poll loop should preserve — no changes needed to this part of the re-entry logic, just make sure the poll loop keeps `cache[blockUuid]` and `_state` in sync as it escalates captions/resolves, the same way the existing LLM-result branch does.

**Multiple blocks "simultaneously" triggering suggestions cannot happen today.**
Because there is one global `suggestionJob`, at most one block's suggestion/poll can be in flight at any moment — a second `requestSuggestions()` call for a different block always cancels the first. So the scenario in the research question ("N redundant `checkStatus()` polls from multiple blocks") is structurally impossible with the current single-job design and will remain impossible after this fix *unless* the plan changes the architecture to per-block jobs — worth stating explicitly in the plan as a non-goal, since fixing it would be a bigger architectural change than this bug fix needs.

**`scanEntries()` bulk path (FR-7) is a separate code path already.**
`scanEntries()` uses its own `scanJob`, its own `_scanState`, and calls `engine.llmSuggest()` directly per entry with no polling logic at all — it does not touch `suggestionJob`/`activeBlockUuid`/the interactive `cache`. Today it already gets "fail-fast" behavior for free (an `OnDeviceUnavailable` failure from a bulk-scanned entry just gets skipped: `ifLeft = { /* skip — continue to next entry */ }`). The risk is only introduced if the new polling logic is added *inside* `TagSuggestionEngine.llmSuggest()` / `LlmTagProvider.suggestTags()` (shared by both paths) rather than in the ViewModel — in that case an explicit `allowPolling: Boolean` parameter must be threaded from `scanEntries()` down to `suggestTags()` (as FR-7 specifies) so the bulk path keeps its current one-shot-then-skip timing. Confirms FR-7's premise: `TagSuggestionEngine.llmSuggest()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt:57`) is the single call site both the interactive and bulk paths share.

## 3. Does `LlmProviderAvailability.Preparing` already carry a differentiating detail?

Yes, partially. `mapMlKitFeatureStatus()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/MlKitAvailabilityMapping.kt:44-62`) already collapses `DOWNLOADABLE` and `DOWNLOADING` into one `Preparing` case with a **static** detail string:
```kotlin
MLKIT_FEATURE_STATUS_DOWNLOADABLE, MLKIT_FEATURE_STATUS_DOWNLOADING ->
    LlmProviderAvailability.Preparing(
        "On-device model is downloading — this can take 15–30 minutes on first use"
    )
```
This string already tells the user "first use" is why it's slow — the unstated user need ("why is this slow — one-time download or a persistent problem?") is *already partly answered* by this copy. But two gaps remain relevant to the requirements:
1. `DOWNLOADABLE` (not yet started downloading — `generateContent()` is what triggers the actual download as a side effect, per the comment in `MlKitLlmFormatterProvider.format()` lines 69-78) and `DOWNLOADING` (actively downloading) are merged into the same message — there's no way today to tell the user "still queued" vs. "actively pulling bytes." Not required by the FRs, but worth noting as a follow-on gap, not something FR-2's "taking longer than expected" caption needs to solve.
2. The `Unavailable(reason="Not yet available — check back in a few minutes", retryable=true)` catch-all (statusCode `null` or unrecognized — i.e., `checkStatus()` itself threw, or returned something outside the four known constants) is a *different* case from `Preparing` and currently has no distinguishing detail either — this is the case that should probably map to the new "taking longer than expected" / manual-retry terminal state in FR-2/FR-3, since it's explicitly documented (comment lines 35-42) as "genuinely unknown right now" rather than a normal download-in-progress state.

`format()` in `MlKitLlmFormatterProvider.kt` (lines 69-84) does **not** reuse `mapMlKitFeatureStatus()` — it duplicates its own inline messages for `DOWNLOADABLE` ("Downloading on-device model — this may take a few minutes") vs. `DOWNLOADING` ("On-device model is downloading — try again in a moment"), which are *more* differentiated than `checkAvailability()`'s collapsed message but still static, not time-aware. Neither path currently escalates the caption over elapsed time (FR-2's "must change at least once at ~45s" requirement) — that logic doesn't exist anywhere in the codebase today and will need to be written from scratch, most naturally as a UI/state-layer concern in `TagSuggestionViewModel`/`TagSuggestionState`, not in the platform-specific mapping functions (keeps `mapMlKitFeatureStatus` a pure/testable function per its existing doc comment, per NFR-3).

## 4. What happens today across app background/foreground cycles or process restart during a download?

**Nothing in this codebase tracks "have I seen DOWNLOADABLE before" persistently — confirmed by exhaustive grep** (`DOWNLOADABLE`, `hasSeenDownload`, `modelDownload`, `aicore`/`AICore` case-insensitive across `kmp/src`, excluding tests/build) — the only matches are the five source files already covered above (`AndroidOnDeviceLlmProvider.kt`, `MlKitLlmFormatterProvider.kt`, `LlmProviderAvailability.kt`, `MlKitAvailabilityMapping.kt`, `LlmTagProvider.kt`). No DataStore/SharedPreferences/DB row anywhere records prior download-attempt state. Every `checkAvailability()`/`checkStatus()` call is a fresh, stateless, live query to ML Kit's `GenerativeModel` — by design, per `LlmProvider.checkAvailability()`'s own doc comment: "Live availability check — always re-evaluated, never a cached snapshot. On-device eligibility can flip mid-session."

**Implication**: the AICore download itself is understood to be managed entirely by Google Play services / AICore outside this app's process — this app has no visibility into or control over whether a download resumes after the app is backgrounded, killed, and restarted; it can only re-poll `checkStatus()` and get whatever the OS-level component currently reports (`DOWNLOADABLE` again if the download hadn't started, `DOWNLOADING` if in progress, `AVAILABLE` if it completed while the app was gone). This means:
- If the process is killed and relaunched, the poll loop (an in-memory `Job` scoped to the `TagSuggestionViewModel`'s `CoroutineScope`) is gone entirely — a fresh `TagSuggestionViewModel` starts with an empty `cache`, so the user simply sees the suggestion sheet as if for the first time; if `checkStatus()` now returns `AVAILABLE` (download finished while backgrounded), the fast path just works with no special-cased "resume" logic needed. This is actually the easy/self-healing case.
- If the app is only backgrounded (not killed) mid-poll, the `TagSuggestionViewModel`'s `scope` (`Dispatchers.Default`) is unaffected by Android backgrounding by itself — coroutines keep running unless the process is frozen/killed by the OS, which Android can do to background apps without foreground service exemption. There is **no existing lifecycle-aware pause/resume wiring** for this ViewModel (unlike `SafChangeDetector`'s `ProcessLifecycleOwner` observer pattern noted above). Two known AICore-specific constraints make this matter more than a generic poll: `GenAiException` mapping in `MlKitLlmFormatterProvider` already has a dedicated, expected, retryable case for `BACKGROUND_USE_BLOCKED` (see the comment at lines 92-99 referencing "foreground-only inference") — i.e., **inference calls are already known to fail when backgrounded**, but this is about the actual `generateContent()` inference call, not `checkStatus()`. The requirements text explicitly asks whether "polling itself" needs to pause vs. only the inference call — nothing in the existing code answers this for `checkStatus()` specifically (no evidence it's blocked in the background), so the plan should treat "does `checkStatus()` itself throw/block when backgrounded" as an open question needing either a real-device check or a defensive catch (which already exists generically via the `catch (e: Exception)` in `checkAvailability()`, returning `Unavailable(retryable=true)` on any unexpected throw — so worst case, a background-blocked `checkStatus()` degrades to a retryable-unavailable poll result rather than crashing).
