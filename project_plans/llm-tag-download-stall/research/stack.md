# Research: Stack — libraries, coroutine patterns, test infra

## 0. Important context: some of the requirements' groundwork already exists

The requirements doc describes `LlmProviderAvailability` and `checkAvailability()` as if
they need to be introduced. **They already exist on this branch** (or at least in this
worktree) — likely landed in a prior increment:

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderAvailability.kt` — the
  tri-state sealed interface (`Available` / `Preparing(detail)` / `Unavailable(reason, retryable)`)
  is fully implemented, with a doc comment explicitly describing the exact bug this project
  is about (`checkEligible()` vs `format()` disagreeing on DOWNLOADABLE/DOWNLOADING).
- `LlmProvider.checkAvailability(): LlmProviderAvailability` (commonMain interface,
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProvider.kt:33`) — implemented by
  `AndroidOnDeviceLlmProvider`, `IosOnDeviceLlmProvider`, `CustomOpenAiCompatibleLlmProvider`,
  `RemoteLlmProvider`.
- `MlKitLlmFormatterProvider.checkAvailability()` (androidMain,
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt:43`) —
  calls `model.checkStatus()` and delegates to the pure, testable
  `mapMlKitFeatureStatus(statusCode: Int?)` in
  `kmp/src/commonMain/.../voice/MlKitAvailabilityMapping.kt` (SDK-independent, unit-testable
  from businessTest/jvmTest without an Android SDK dependency — mirrors `mapGenAiErrorCode`'s
  shape).
- iOS equivalent: `mapShimCodeToAvailability` in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/IosAvailabilityMapping.kt`.

**What is still genuinely missing** (confirmed by reading `TagSuggestionViewModel.kt` and
`LlmTagProvider.kt` in full):

1. `LlmTagProvider.suggestTags()` maps `LlmResult.Failure.OnDeviceUnavailable` to
   `DomainError.NetworkError.RequestFailed(result.reason)` and **drops `result.retryable`**
   entirely (`LlmTagProvider.kt:59-61`). This is the FR-3 gap, confirmed exactly as the
   requirements describe.
2. `MlKitLlmFormatterProvider.format()` still does a single `model.checkStatus()` call and
   returns immediately on `DOWNLOADABLE`/`DOWNLOADING` (`format()`, lines 55-88) — no polling,
   no wait. `checkAvailability()` exists as a *separate* method but nothing calls it in a loop
   yet. This is the FR-0/FR-1/FR-2 gap.
3. `TagSuggestionViewModel.requestSuggestions()` sets `llmError` once from
   `err.message` and never re-triggers (`TagSuggestionViewModel.kt:104-112`) — no poll loop,
   no `retryable` flag on `TagSuggestionState.Ready` (`TagSuggestionState.kt` has `llmError:
   String?` and `llmPending: Boolean` but nothing like `llmRetryable: Boolean`).
4. `DomainError.NetworkError.RequestFailed` (used generically) has no `retryable` field at all
   — confirm this in the plan phase by reading `error/DomainError.kt`, but `LlmTagProvider.kt`
   only ever constructs `RequestFailed(String)`, single-arg.

The plan phase should treat "does `LlmProviderAvailability`/`checkAvailability()` exist"
as **done groundwork to build on**, not build from scratch — the real work is (a) threading
`retryable` through, (b) adding the poll loop in the `format()`/`checkAvailability()` call
path or the ViewModel, (c) new `TagSuggestionState` fields for the terminal/retry captions.

## 1. Existing bounded-deadline polling idiom in this repo

The canonical pattern to mirror is `GitHubDeviceFlowClient.pollForToken()`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClient.kt:94-140`):

```kotlin
suspend fun pollForToken(
    deviceCode: String,
    expiresIn: Int,
    initialInterval: Int,
    onStateChange: (DeviceFlowPollState) -> Unit,
): Either<DomainError.GitError, String> {
    var intervalMs = initialInterval * 1000L
    val deadline = Clock.System.now().toEpochMilliseconds() + expiresIn * 1000L

    while (Clock.System.now().toEpochMilliseconds() < deadline) {
        delay(intervalMs)
        // ... poll, call onStateChange(...) for intermediate states, `continue` or `return`
    }
    return DomainError.GitError.AuthFailed("Device flow expired").left()
}
```

Key properties worth copying for the tag-suggestion poll loop:
- **Wall-clock deadline via `kotlin.time.Clock.System.now().toEpochMilliseconds()`**, not
  `withTimeout`/`Duration` — this repo's idiom computes an absolute deadline once and checks
  `now() < deadline` each iteration, rather than wrapping the whole loop in `withTimeout`.
  `TagSuggestionViewModel.kt` already imports `kotlin.time.Clock` for the unrelated
  `scanEntries()` timestamp use (`Clock.System.now().toEpochMilliseconds()` at line 150) — same
  API, so no new import class needed.
- **State callback for intermediate states** (`onStateChange`), separate from the terminal
  `Either` return — directly analogous to updating `TagSuggestionState.Ready` mid-loop for the
  "still preparing" / "taking longer than expected" captions (FR-2).
- **Stateless service, caller owns the `CoroutineScope`** — `GitHubDeviceFlowClient` has no
  internal scope; whoever calls `pollForToken` launches it. `TagSuggestionViewModel` already
  owns its own `SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler` scope
  (`TagSuggestionViewModel.kt:43-51`) and already launches `suggestionJob` there — the natural
  place to run the poll loop is inside that same `launch` block in `requestSuggestions()`,
  not a new scope.

Two more polling idioms exist but are less directly analogous:
- `DiskConflictBlockMatcher.kt:27` — `while (true)` with internal break conditions (no deadline,
  event-driven).
- `GraphFileWatcher.kt:119-121` — `while (isActive) { delay(pollIntervalMs); ... }` — infinite
  poll gated by scope cancellation, plus `withTimeoutOrNull(200L)` elsewhere in the same file for
  a one-shot bounded wait. Good precedent for "isActive-gated loop" if the design prefers that
  over a deadline check, but doesn't show the deadline/escalating-caption pattern.

## 2. Test-friendly virtual time — already present, but with a real gotcha

`kotlinx-coroutines-test:1.10.2` is already a dependency in all four test source sets
(businessTest, jvmTest, androidUnitTest, wasmJsTest — `kmp/build.gradle.kts:147,333,355,385`).
No new dependency needed.

**However**, `TagSuggestionViewModelTest.kt` (existing, `kmp/src/businessTest/kotlin/dev/stapler/
stelekit/tags/TagSuggestionViewModelTest.kt`) demonstrates the trap that will bite this
feature directly:

> `TagSuggestionViewModel` owns its own `CoroutineScope` with real `Dispatchers.Default`
> (see `TagSuggestionViewModel.kt:43-51`, `Dispatchers.Default`, not a test dispatcher).
> `kotlinx.coroutines.test.runTest`'s virtual-time scheduler (`advanceUntilIdle`,
> `advanceTimeBy`) **only** controls coroutines dispatched through the test dispatcher it
> creates — it has no effect on `Dispatchers.Default`. So the existing test suite does NOT
> get virtual-time `delay()` skipping for anything `TagSuggestionViewModel` launches
> internally.

The existing workaround, already in the test file, is a **real-wall-clock spin-poll helper**:

```kotlin
private suspend fun TagSuggestionViewModel.awaitState(
    timeoutMs: Long = 5000,
    predicate: (TagSuggestionState) -> Boolean,
): TagSuggestionState {
    val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
    while (Clock.System.now().toEpochMilliseconds() < deadline) {
        val s = state.value
        if (predicate(s)) return s
        delay(20)
    }
    error("State ${state.value} never satisfied predicate within ${timeoutMs}ms")
}
```

This is fine for a handful of seconds, but **directly conflicts with NFR-3** ("no test should
sleep through the real deadline") once `DEFAULT_POLL_DEADLINE_MS` is on the order of tens of
seconds to minutes (see §4 — likely candidate range is 60-180s for a first AICore download).
A test asserting the FR-2 "taking longer than expected" terminal state would otherwise have to
really sleep that long.

**Implication for the plan phase**: to satisfy NFR-3, the poll-loop deadline math and the
interval `delay()` calls need to be injectable/mockable independently of
`TagSuggestionViewModel`'s `Dispatchers.Default` scope — options to evaluate in planning:
- Inject a `Clock` (already `kotlin.time.Clock`, has a fake/test implementation pattern
  elsewhere? — check `LlmProviderRegistryTest.kt` / `AndroidOnDeviceFallbackTest.kt`, not yet
  read in this pass) and an injectable `delay` function (e.g. a small
  `suspend fun delay(ms: Long)` seam) so the loop can be driven deterministically in a unit
  test without real sleeps.
- Or extract the poll loop into its own stateless function/class (mirroring
  `GitHubDeviceFlowClient`, which takes no scope and is trivially tested with `runTest` because
  it has no competing real dispatcher) that `TagSuggestionViewModel` calls from within its
  existing `launch` — then that extracted function can be unit tested directly with
  `runTest(StandardTestDispatcher())` + `advanceTimeBy`/`advanceUntilIdle`, sidestepping the
  `Dispatchers.Default` problem entirely because the test calls it directly rather than through
  the ViewModel's scope.
- The second option is cleaner and matches this repo's existing precedent (`GitHubDeviceFlowClient`
  is exactly this shape: stateless, scope-agnostic, deadline-based, directly unit-testable).

Other tests in `tags`/`llm` worth checking as secondary precedent (not yet fully read):
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistryTest.kt`,
`AndroidOnDeviceFallbackTest.kt`, `StelekitViewModelLlmSuggestionTest.kt` — all use
`runTest`/`TestScope`/`UnconfinedTestDispatcher` per the earlier grep; worth a follow-up read in
the planning phase to see if any already solved the "own-scope-vs-virtual-time" problem for a
different feature.

## 3. `com.google.mlkit:genai-prompt` — confirmed version, and a bigger finding

- `kmp/build.gradle.kts:296`: `implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")`,
  androidMain source set only.
- Transitively resolves `com.google.mlkit:genai-common:1.0.0-beta3` (confirmed via
  `~/.gradle/caches/modules-2/files-2.1/com.google.mlkit/genai-common/` — only beta3 is
  present in the local cache, i.e. the version actually on this project's classpath).

**Finding that changes the design space**: the research question's premise — "there likely
isn't a progress callback" — is **false**. Decompiling `GenerativeModel` (via `javap` on the
AAR's `classes.jar`) shows it exposes, beyond `checkStatus(): Int` / `FeatureStatus`:

```kotlin
public abstract kotlinx.coroutines.flow.Flow<com.google.mlkit.genai.common.DownloadStatus> download();
```

And `com.google.mlkit.genai.common.DownloadStatus` (in `genai-common:1.0.0-beta3`) is a sealed
class with real progress data:

```kotlin
sealed class DownloadStatus {
    data class DownloadStarted(val bytesToDownload: Long) : DownloadStatus()
    data class DownloadProgress(val totalBytesDownloaded: Long) : DownloadStatus()
    data class DownloadFailed(val e: GenAiException) : DownloadStatus()
    object DownloadCompleted : DownloadStatus()
}
```

There's also a callback-based twin, `DownloadCallback` (`onDownloadStarted(bytesToDownload:
Long)`, `onDownloadProgress(totalBytesDownloaded: Long)`, `onDownloadCompleted()`,
`onDownloadFailed(GenAiException)`), and a `GenerativeModel.zzc(DownloadCallback):
ListenableFuture` internal bridge — but `download(): Flow<DownloadStatus>` is the public
Kotlin-native entry point and is what a coroutine-based caller should use.

**This is not currently called anywhere in the codebase** (confirmed by grep — only
`checkStatus()` and `generateContent()` are invoked in `MlKitLlmFormatterProvider`).

**Decision to flag explicitly for the planning phase**: the requirements (FR-0) specify
"background status polling (3-5s interval)" as the mechanism, written under the assumption that
`checkStatus()` is the only signal available. Given `download(): Flow<DownloadStatus>` exists
and gives real byte-level progress plus a terminal `DownloadCompleted`/`DownloadFailed` signal
(no polling needed — it's a suspending Flow that completes/fails), the plan phase should
explicitly decide between:
  (a) implement FR-0 literally as spec'd — dumb interval polling of `checkAvailability()`
      (simplest, platform-uniform since iOS has no equivalent progress API to my knowledge —
      out of scope per requirements anyway), or
  (b) on Android specifically, collect `model.download()` for real progress/completion signals
      and fall back to interval polling only pre-download-start or on other platforms.
Option (b) is more correct and gives a real progress caption instead of a generic "still
preparing" one, but is Android-only special-casing inside `MlKitLlmFormatterProvider`
(androidMain) and adds scope — likely bigger than this bug-fix-shaped project wants. Recording
it here so it's a conscious scope decision in `sdd:3-plan`, not a missed opportunity.

`GenAiException` (thrown from `DownloadFailed.e`) is the same exception type already handled
in `MlKitLlmFormatterProvider.format()`'s catch block via `mapGenAiErrorCode()`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/GenAiErrorMapping.kt`) — that mapping
function could be reused if option (b) is chosen.

## 4. `kotlin.time.Clock` / wall-clock deadline math — pattern confirmed

`kotlin.time.Clock` (not `kotlinx-datetime`'s old `Clock` — this is the newer stdlib
`kotlin.time.Clock` API, confirmed via the import in both `TagSuggestionViewModel.kt:18` and
`GitHubDeviceFlowClient.kt:29`) is the established pattern for deadline math throughout this
repo:

```kotlin
import kotlin.time.Clock
val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
while (Clock.System.now().toEpochMilliseconds() < deadline) { ... }
```

No `kotlinx-datetime` `Clock` import found in `tags`/`llm`/`voice` — `kotlin.time.Clock` is the
only clock type in play here, consistent across `TagSuggestionViewModel`,
`GitHubDeviceFlowClient`, and the existing `TagSuggestionViewModelTest.awaitState`/`awaitMatcher`
helpers. Use the same import for any new poll-loop code — do not introduce
`kotlinx.datetime.Clock` as a second clock type.

## 5. `DEFAULT_POLL_DEADLINE_MS` — no existing constant

Confirmed via grep: `DEFAULT_POLL_DEADLINE_MS` does not exist anywhere in the codebase yet —
it is new. No existing `PlatformDispatcher`-adjacent timeout constant to crib a magnitude from
in this specific domain (`GitHubDeviceFlowClient`'s device-flow deadline comes from GitHub's
own `expires_in`, not a hardcoded constant, so it's not a numeric precedent either). FR-6
explicitly requires this to come from a real on-device AICore first-download timing
measurement — that measurement has not happened yet and is a hard prerequisite the plan phase
must schedule before implementation, not something this research pass can substitute for.

## Summary of concrete file touch points for the plan phase

| File | Role |
|---|---|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/LlmTagProvider.kt` | Thread `retryable` through instead of dropping it at line 59-61; `suggestTags` signature/return type likely needs a `retryable` carrier |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt` | Add poll loop (probably inside the existing `suggestionJob` launch in `requestSuggestions()`), new terminal/retry state transitions, `allowPolling` param on `scanEntries()` (FR-7) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionState.kt` | New field(s) for retryable + "taking longer than expected" terminal state + escalating caption |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt` | `format()`'s DOWNLOADABLE/DOWNLOADING branch — decide whether polling loop lives here (provider-level) or purely in the ViewModel using existing `checkAvailability()` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderAvailability.kt` | Already correct — reuse `Unavailable.retryable`, no changes expected |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClient.kt` | Pattern reference only — no changes |
| `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt` | Existing `awaitState` helper shows the virtual-time gotcha; new poll-loop tests need either an injected clock/delay seam or an extracted stateless poll function to avoid NFR-3 violations |
