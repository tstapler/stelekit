# Build vs. Buy: On-Device Model Download Status Fix

## Scope recap

Two confirmed defects, both in code this project owns:
1. `MlKitLlmFormatterProvider.checkAvailability()` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt:43-53`) never triggers a download — it only calls the pure-read `model.checkStatus()`.
2. `LlmProviderRow`'s `produceState(provider) { ... }` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LlmProviderListScreen.kt:118-120`) never re-runs after first composition — no poll, no manual refresh on the `Preparing` branch.

Dependency pinned today: `com.google.mlkit:genai-prompt:1.0.0-beta2` (`kmp/build.gradle.kts:296`).

## 1. Existing OSS library/framework — does ML Kit itself already solve this?

**Yes.** `GenerativeModel` (the same class this codebase already builds via `Generation.getClient()`) exposes a documented `download()` method that this codebase is not calling anywhere:

```kotlin
val status = generativeModel.checkStatus()
when (status) {
    FeatureStatus.DOWNLOADABLE -> {
        generativeModel.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted   -> { /* totalBytesToDownload */ }
                is DownloadStatus.DownloadProgress  -> { /* totalBytesDownloaded  */ }
                DownloadStatus.DownloadCompleted    -> { /* ready */ }
                is DownloadStatus.DownloadFailed    -> { /* GenAiException */ }
            }
        }
    }
}
```

(Kotlin `download(): Flow<DownloadStatus>`, `com.google.mlkit.genai.common.DownloadStatus` sealed class; Java equivalent is `GenerativeModelFutures.downloadFeature(DownloadCallback)` with `onDownloadStarted(long)` / `onDownloadProgress(long)` / `onDownloadCompleted()` / `onDownloadFailed(GenAiException)`.) Source: [Get started with Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started), [GenerativeModel reference](https://developers.google.com/android/reference/kotlin/com/google/mlkit/genai/prompt/GenerativeModel).

This is a strictly better primitive than what the codebase currently does (`runCatching { model.generateContent(systemPrompt) }` as a side-effect hack to kick off the download, per the comment at `MlKitLlmFormatterProvider.kt:70-72`). `download()`:
- **Actually and explicitly triggers the download** (fixes defect 1 directly — no more relying on an inference call whose real purpose is text generation).
- **Is itself a cold `Flow`** that emits as the download progresses, i.e. it *is* the "poll" mechanism for the `DOWNLOADING` window — no hand-rolled `delay()` loop needed for that portion.
- **Exposes real byte-level progress** (`totalBytesToDownload` / `totalBytesDownloaded`) — the requirements doc's Non-Goals section assumes no progress API exists ("ML Kit's Prompt API does not expose byte-level/percentage download progress"). That assumption is **incorrect** for the current SDK — worth flagging back to planning, since a real "X of Y MB" or percentage readout is now in-budget as a stretch goal, not just differentiated copy.
- Version fit: 1.0.0-beta2 (pinned here) is documented as the current available version of `genai-prompt`, and `download()` appears on the current reference page for that artifact — no version bump required, only new call sites.

**Pros**: first-party, no new dependency, replaces a documented workaround with the documented mechanism, unlocks real progress data, single `Flow` collect naturally satisfies AC1 (real trigger) + AC2 (periodic updates) without a manual poll loop.
**Cons**: `download()` is Kotlin/coroutines-Flow-shaped — needs a small adapter to fit `LlmProviderAvailability`/`checkAvailability()`'s existing suspend-function contract, and the `Preparing` UI copy/state model needs a new field (bytes or percent) if progress is surfaced. Still Android-only (as today — `MlKitLlmFormatterProvider` already lives in `androidMain`, so no new platform-boundary work).
**Verdict: Recommended.** This should be the primary fix for defect 1, and it materially simplifies defect 2 (the `Preparing` state can `collect` this flow directly instead of needing an externally-driven poll timer for the "download in progress" case specifically).

## 2. SaaS/managed API

Not applicable. This is a fully on-device SDK integration (Gemini Nano via AICore, no network call, no external status endpoint to poll). Confirmed by reading `MlKitLlmFormatterProvider.kt` — everything routes through the local `GenerativeModel` client. No further evaluation needed.

## 3. LLM-generated poll+StateFlow plumbing vs. an existing repo utility

Searched for a reusable "poll an external status into a Flow/StateFlow" helper (`grep -rn "while (true)"` / `"delay("` across `commonMain`/`androidMain`/`jvmMain`). None of the ~30 hits is a generic, reusable polling utility — each is bespoke to its own subsystem (`SloChecker`, `HistogramRetentionJob`, `DesktopSyncScheduler`, `GraphFileWatcher`, etc.). There is no shared `pollAsFlow()`/`poll { }` helper to reach for.

However, `download()`'s own `Flow<DownloadStatus>` (see §1) removes most of the need for a poll loop in the first place — it already emits on progress and terminates on `DownloadCompleted`/`DownloadFailed`. The remaining poll-shaped need is narrower than the requirements doc assumed:
- **AC3** (manual refresh button on `Preparing`) — trivial, no poll needed: reuse the existing `onRetry` callback wiring already present for the `Unavailable` branch (`ProviderStatusIndicator`, `LlmProviderListScreen.kt:186-192`), just also pass it into the `Preparing` branch (currently unwired, lines 182-185) and have it re-invoke `checkAvailability()`/re-subscribe to `download()`.
- **AC4** (stale-download escalation after an unreasonable time) — this *is* a bounded, timeout-style poll, and this codebase has a directly relevant precedent (§4 below) rather than needing a new generic utility.

`kotlinx.coroutines`' own `flow { while (true) { delay(...); emit(...) } }` idiom is the right level of abstraction if anything beyond `download()`'s own Flow is still needed (e.g. re-checking `checkStatus()` after `download()` completes, or driving the `LlmProviderRow` `produceState` outer loop) — this is standard library usage, not something to hand-roll as a "framework," and not something worth extracting into a shared helper for a single call site.

**Verdict: Recommended (idiomatic stdlib, not build-a-framework).** Do not invent a generic polling utility for this fix — one call site doesn't justify it, and `download()`'s Flow already covers the highest-value case.

## 4. Fork/adapt an existing correct pattern in this codebase

Two directly relevant precedents were found:

- **`GooglePhotosPickerLauncher.kt:96-103`** (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/google/GooglePhotosPickerLauncher.kt`) — a **bounded poll-with-timeout** against an external status exactly matching AC4's shape:
  ```kotlin
  var attempts = 0
  while (!latestSession.mediaItemsSet && attempts < MAX_POLL_ATTEMPTS) {
      delay(POLL_INTERVAL_MS)
      attempts++
      val polled = apiClient.getPickerSession(session.id).getOrNull() ?: break
      latestSession = polled
  }
  if (!latestSession.mediaItemsSet) { /* timeout path, distinct from success */ }
  ```
  This is the pattern to copy for AC4 ("unreasonably long" escalation): count elapsed poll cycles (or wall-clock time) against a threshold derived from the existing "15–30 minutes" copy, and branch to a distinct `Unavailable`/escalated `Preparing` state past that threshold — same shape as this existing, already-reviewed code, same module (`androidMain`), same author conventions.

- **`SyncStatusBadge.kt:252-258`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt`) — a **Compose `LaunchedEffect(key) { delay(...); mutateState() }`** pattern for a status badge that changes appearance over time without external re-composition triggers. This is the direct analog for the UI-side half of defect 2: `LlmProviderRow` needs the same shape — a `LaunchedEffect` (or the `produceState` block extended into a loop) keyed on the provider, looping/delaying/re-checking instead of running exactly once. Note this component is `commonMain`, confirming the *UI-side* refresh-loop pattern is not Android-specific — only the underlying `download()` call is.

Neither existing repo pattern is itself a drop-in library for the *download-trigger* half of the bug (§1's `download()` API is the right tool there) — but both are exact-shape precedents for the *UI polling/refresh* half (§3/AC2/AC3/AC4), and the plan should explicitly cite and mirror them rather than design a new polling shape from scratch.

**Verdict: Recommended — adapt both.** `GooglePhotosPickerLauncher`'s bounded-attempts loop for the AC4 timeout escalation; `SyncStatusBadge`'s `LaunchedEffect(key) { delay }` for the AC2/AC3 UI refresh loop in `LlmProviderRow`.

## Summary recommendation

Build vs. buy is not really the axis here — the "buy" option is a first-party SDK method (`GenerativeModel.download()`) that is already on the classpath via the pinned `genai-prompt:1.0.0-beta2` dependency and simply isn't called yet. Recommended approach:

1. Replace the `generateContent()`-as-download-trigger hack and the passive `checkAvailability()` with `GenerativeModel.download()`, collecting its `Flow<DownloadStatus>` to drive both the trigger (defect 1) and the live progress/refresh (most of defect 2 / AC1, AC2).
2. Wire the existing `onRetry` pattern (already used for `Unavailable`) into the `Preparing` branch of `ProviderStatusIndicator` for AC3, and extend `LlmProviderRow`'s `produceState` into a `LaunchedEffect`-driven loop mirroring `SyncStatusBadge.kt`'s pattern.
3. Add a bounded elapsed-time/attempts check mirroring `GooglePhotosPickerLauncher.kt`'s poll-with-timeout for AC4's "unreasonably long" escalation.
4. No new dependency, no new generic polling framework, no SaaS integration — everything needed is either already a documented method on the existing SDK class or an existing pattern already reviewed and shipped elsewhere in this codebase.
