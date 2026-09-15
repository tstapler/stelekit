# Stack Research: On-Device Model Download Status Stall

## 1. ML Kit GenAI dependency versions in use

`kmp/build.gradle.kts:296`:
```kotlin
implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
```
This is a Google Play services-adjacent AICore/Gemini Nano client, Android-only
(`androidMain`). No other `genai-*` artifacts (`genai-summarization`, `genai-rewriting`, etc.)
are declared — only the Prompt API.

Resolved transitive dependency (checked via `~/.gradle/caches/modules-2/files-2.1/com.google.mlkit/`):
`com.google.mlkit:genai-common:1.0.0-beta3` — this is the artifact that actually defines
`FeatureStatus`, `DownloadStatus`, `DownloadCallback`, `GenAiException`, `StreamingCallback`.
`genai-prompt:1.0.0-beta2` depends on a **newer** beta of `genai-common` than its own version
number; this is normal for ML Kit's split "feature API + shared common" versioning but worth
knowing if you ever pin `genai-common` explicitly (don't — let it resolve transitively).

**Current/recommended version**: `1.0.0-beta2` is confirmed still the latest published
`genai-prompt` release as of the official docs (page footer "Last updated 2026-07-15 UTC",
still lists `1.0.0-beta2` as the dependency coordinate in "Get started"). **The repo is already
on the latest available version** — no version bump is needed or available. The whole Prompt API
is explicitly labeled **Beta** in Google's own nav (`Prompt (Beta)`), meaning: API surface can
still change across beta releases, and Google's own docs warn devices can misreport status during
AICore's post-setup/post-reset initialization window (see §4 below, already reflected in this
repo's `mapMlKitFeatureStatus` kdoc).

## 2. The missing API: `GenerativeModel.download(): Flow<DownloadStatus>`

This is the central finding. **The current code's core assumption — stated in
`requirements.md`'s Non-Goals ("ML Kit's Prompt API does not expose byte-level/percentage
download progress") — is incorrect.** The SDK does expose real progress; the current code (and
apparently the person who wrote requirements.md) just isn't calling the API that provides it.
Decompiling the AAR (`genai-prompt-1.0.0-beta2.aar` → `classes.jar`,
`com/google/mlkit/genai/prompt/GenerativeModel.class`) and cross-referencing Google's official
"Get started with Prompt API" doc (developers.google.com/ml-kit/genai/prompt/android/get-started,
confirmed current) shows the full interface:

```
public interface com.google.mlkit.genai.prompt.GenerativeModel {
    ...
    public abstract java.lang.Object checkStatus(Continuation<? super Integer>);
    public abstract kotlinx.coroutines.flow.Flow<DownloadStatus> download();
    ...
}
```

`com.google.mlkit.genai.common.DownloadStatus` (in `genai-common-1.0.0-beta3.aar`) is a sealed
class with four cases (decompiled via `javap`):

- `DownloadStatus.DownloadStarted(bytesToDownload: Long)` — download kicked off, total size known
- `DownloadStatus.DownloadProgress(totalBytesDownloaded: Long)` — incremental progress, **this is
  the byte-level progress the requirements doc says doesn't exist**
- `DownloadStatus.DownloadCompleted` — terminal success (singleton object)
- `DownloadStatus.DownloadFailed(e: GenAiException)` — terminal failure, carries the SDK exception

There is also a callback-based sibling for Java callers, `DownloadCallback`
(`onDownloadStarted(Long)` / `onDownloadProgress(Long)` / `onDownloadCompleted()` /
`onDownloadFailed(GenAiException)`), and `GenerativeModelFutures.download(DownloadCallback):
ListenableFuture<Void>` for non-coroutine callers — irrelevant here since this codebase is
Kotlin/coroutines throughout.

**Google's own documented pattern** (verbatim from the current get-started guide, Kotlin tab) is
exactly the fix this project needs:

```kotlin
val status = generativeModel.checkStatus()
when (status) {
    FeatureStatus.DOWNLOADABLE -> {
        generativeModel.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> Log.d(TAG, "starting download")
                is DownloadStatus.DownloadProgress -> Log.d(TAG, "${status.totalBytesDownloaded} bytes downloaded")
                DownloadStatus.DownloadCompleted -> { /* modelDownloaded = true */ }
                is DownloadStatus.DownloadFailed -> Log.e(TAG, "download failed ${status.e.message}")
            }
        }
    }
    FeatureStatus.DOWNLOADING -> { /* already downloading */ }
    ...
}
```

Implication for the fix: `MlKitLlmFormatterProvider.checkAvailability()` (or a new sibling
method) can call `model.download()` directly instead of the current `generateContent()`-as-side-
effect hack (which throws a `GenAiException` on purpose and discards it — see
`MlKitLlmFormatterProvider.kt:69-77`). `download()` is the documented, intended API for this;
it is idempotent to call when status is already `DOWNLOADING` (Google's own docs table shows
`DOWNLOADABLE` triggers a call to `.download()` and doesn't warn against calling it again once
already downloading — but the safe, requirements-satisfying design is to gate the `.download()`
call behind `FeatureStatus.DOWNLOADABLE` specifically, matching Google's own `when` branch
structure, and only *observe* — not re-trigger — while `DOWNLOADING`).

This also means Acceptance Criterion 4 ("no numeric progress bar expected, target correctness/
freshness only") in `requirements.md`'s Non-Goals is more conservative than the SDK strictly
requires — a real byte-progress indicator (or at least a coarse "X MB downloaded" line) is
actually achievable with no additional API surface, should whoever runs `/sdd:3-plan` want to use
it. Flagging this as a build-vs-buy/scope question for the planning phase, not deciding it here.

## 3. Existing idiomatic polling patterns in this codebase

Two directly reusable patterns exist, both accessible from `commonMain` (no Android-only code
needed for the polling shell — the platform-specific part is only what's inside the suspend call):

**A. `produceState` + internal `while (true) { ...; delay(N) }` loop** — the closest analog to
what `LlmProviderRow` needs, already used for a periodic-refresh dashboard tab:

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PerformanceDashboard.kt:231-245`:
```kotlin
val liveSpans by produceState(sqliteSpans, ringBuffer, perfSpans) {
    if (ringBuffer == null) {
        perfSpans.collect { value = it }
        return@produceState
    }
    while (true) {
        val inFlight = ringBuffer.snapshot()
        ...
        value = (sqlite.filterNot { ... } + inFlight).sortedByDescending { it.startEpochMs }
        delay(1000)
    }
}
```
`LlmProviderRow`'s existing `produceState<LlmProviderAvailability?>(initialValue = null, provider)
{ value = provider.checkAvailability() }` (`LlmProviderListScreen.kt:118-120`) is a **single-shot**
variant of the exact same primitive — converting it to a `while (true) { value = ...; delay(N) }`
loop, gated so the loop only re-polls while `value is LlmProviderAvailability.Preparing` (to avoid
pointless polling once `Available`/`Unavailable`), is a minimal, idiomatic, in-pattern change.
`produceState`'s coroutine is automatically cancelled when the composable leaves composition, so
this needs no manual scope/cleanup — consistent with this repo's `rememberCoroutineScope` escape
rules (CLAUDE.md) since `produceState`'s internal scope never escapes the composable.

**B. `LaunchedEffect` + `delay(N)` for a one-shot deferred UI transition** — used for "auto-dismiss
after N seconds" (`SyncStatusBadge.kt:253-256`, `VoiceCaptureButton.kt:152`,
`GlobalUnlinkedReferencesViewModel.kt:203`) — less applicable here since the requirement is
*recurring* polling, not one deferred transition, but useful if the eventual design adds a
"stalled after 30 minutes" one-shot escalation (Acceptance Criterion 4) layered on top of pattern
A — e.g. track elapsed time since `Preparing` was first observed and flip a `stalled: Boolean`
flag via a single `LaunchedEffect(firstPreparingTimestamp) { delay(30.minutes); stalled = true }`.

Both patterns already exist without any codebase-specific polling *utility* (no shared
`pollingState()`/`usePolling()` helper) — each call site inlines its own loop. Precedent
therefore favors inlining a `while` loop directly in `LlmProviderRow` rather than introducing a
new shared abstraction, consistent with the size of this fix (complexity 2, single screen).

`onRetry`/manual-refresh wiring precedent: `ProviderStatusIndicator`'s `Unavailable` branch
already threads an `onRetry: (() -> Unit)?` callback through to an `IconButton` with
`Icons.Default.Refresh` (`LlmProviderListScreen.kt:186-192`). The `Preparing` branch
(`LlmProviderListScreen.kt:182-185`) has no such parameter today — Acceptance Criterion 3 wants
the same affordance added there, which is a direct, mechanical extension of the existing
`Unavailable` pattern (same icon, same `IconButton` shape), not a new UI pattern.

## 4. Beta-status implications / known SDK pitfalls relevant to this fix

From Google's own "Common setup issues" section (same get-started page, current as of
2026-07-15):

- AICore can misreport status for a period after device setup/reset or after AICore itself is
  cleared/reinstalled (`BINDING_FAILURE`, `FEATURE_NOT_FOUND`, `UNKNOWN`/`Unable to resolve host`)
  — "usually takes a few minutes to a few hours" to resolve, "restarting the device can speed up
  the update." This is exactly the scenario `mapMlKitFeatureStatus`'s kdoc already anticipates
  (treating unknown/errored status as *retryable* `Unavailable`, not permanent) — the existing
  mapping design already accounts for this; no change needed there.
- Google's docs explicitly instruct: "Make sure to call `checkFeatureStatus()` or `checkStatus()`
  first before showing any related UI" — the current code already does this
  (`checkAvailability()` calls `model.checkStatus()` first), so this constraint is already
  satisfied; it's only the *next* step (triggering/observing `.download()`) that's missing.
- The API requires Android API level 26+; not gated in the reviewed files but likely handled
  upstream in provider registration (out of scope for this research pass — not verified here).
- `genai-common` beta3 (pulled transitively by `genai-prompt` beta2) is a point ahead of the
  declared `genai-prompt` version — normal for ML Kit's versioning split, not a conflict, no
  action needed.

## Summary of what this means for planning

The fix does not need a new dependency, a version bump, or an external library — `genai-prompt
1.0.0-beta2` (already declared) ships the exact `download(): Flow<DownloadStatus>` API needed to
(a) actually trigger a download when `DOWNLOADABLE` and (b) observe real progress/completion/
failure while `DOWNLOADING`, replacing the current `generateContent()`-as-side-effect workaround
for the availability-check path specifically (the `format()` workaround can stay as a defense in
depth, or be simplified — a planning-phase decision, not made here). The polling-refresh UI shell
should reuse the existing `produceState` + `while (true) { ...; delay(N) }` idiom already
established in `PerformanceDashboard.kt`, and the manual-refresh affordance should mirror the
existing `Unavailable`-branch `onRetry`/`IconButton` pattern already in
`ProviderStatusIndicator` — both are established precedent, not new UI vocabulary.
