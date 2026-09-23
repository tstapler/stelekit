# Requirements: On-Device Model Download Status Stall

## Correction — this is NOT the reported bug (verified against the actual screenshot)

This document was written from code investigation only, without viewing the backlog item's
attached screenshot directly (an oversight — the attachment is a local file readable with the
`Read` tool: `/home/tstapler/.stapler-squad/backlog-attachments/1784963641220-407350947-1000165201.png`).
Having now viewed it: the screenshot shows a **"Scan entries for tag suggestions"** header and a
**"Suggested tags for this block"** bottom sheet with the caption **"Downloading on-device model
— this may take a few minutes"** — this is the **tag-suggestion feature**
(`SuggestionBottomSheet`/`LlmTagProvider`), not the Settings → AI Providers screen this document
targets. The correct, verified triage for this backlog item lives in
**`project_plans/llm-tag-download-stall/`** (written concurrently by a parallel session that did
view the screenshot). This document's own root-cause findings remain independently valid — the
Settings AI Providers row has the two real, separate defects described below — but they are not
what the user reported. Recommend filing this as its own follow-up bug rather than folding it
into `llm-tag-download-stall`'s scope.

## Source

Backlog item `505fb733-9621-4621-b7fc-27712e36d084`, title "Device model download kinda of
sucks". Description: screenshot showing the download UI "stuck here and never improves."

## Complexity

**2** (contained bug fix touching one commonMain screen + one androidMain provider; async state
+ missing trigger, no schema/migration/new infra) — research should run all 6 dimensions since
the fix touches UX copy, an SDK integration pitfall class, and an existing architectural pattern
(`LlmProviderAvailability`/`checkAvailability`) other providers share.

## Background

Settings → "AI Providers" (`LlmProviderSettings.kt`, opened via
`StelekitViewModel.openLlmProviderSettings()` from `SettingsDialog.kt:286`) lists each configured
`LlmProvider` via `LlmProviderListScreen.kt`. The on-device row (`LlmProviderKind.ON_DEVICE`,
backed by ML Kit's Prompt API / Gemini Nano via AICore on supported Android devices) resolves its
status through `LlmProviderRow`'s `produceState(provider) { value = provider.checkAvailability() }`
(`LlmProviderListScreen.kt:118-120`), rendered by `ProviderStatusIndicator`
(`LlmProviderListScreen.kt:167-195`). On Android, `checkAvailability()` delegates to
`MlKitLlmFormatterProvider.checkAvailability()` (`kmp/src/androidMain/.../voice/MlKitLlmFormatterProvider.kt:43-53`),
which maps ML Kit's `FeatureStatus` via the pure function `mapMlKitFeatureStatus()`
(`kmp/src/commonMain/.../voice/MlKitAvailabilityMapping.kt:44-62`). Both `FeatureStatus.DOWNLOADABLE`
and `FeatureStatus.DOWNLOADING` collapse to the identical copy: *"On-device model is downloading —
this can take 15–30 minutes on first use"* (`MlKitAvailabilityMapping.kt:47-50`) — this literal
string is almost certainly what the attached screenshot shows, and it directly matches the
backlog title's "device model download."

## Root Cause (confirmed by direct code read)

1. **The download is frequently never actually triggered, even though the UI claims it's in
   progress.** `MlKitLlmFormatterProvider.checkAvailability()` (line 43-53) calls only
   `model.checkStatus()` — a pure read. Per the code's own comment inside `format()` (lines 69-77):
   *"`generateContent()` triggers the AICore model download as a side effect... Without this call
   the download never starts."* `checkAvailability()` never calls `generateContent()`. So when
   ML Kit reports `FeatureStatus.DOWNLOADABLE` (download not yet started), the Settings row still
   renders "On-device model is downloading" — actively misleading, because nothing has been
   kicked off. Confirmed by git history: commit `19efb39f35` ("fix(tags): trigger ML Kit model
   download and include block content in prompt") applied exactly this
   generateContent()-as-download-trigger workaround to the tag-suggestion path, but
   `checkAvailability()` — the function this Settings screen calls — was never updated with the
   same fix. A user who opens Settings before ever using voice formatting or tag suggestions (the
   only two features that legitimately call `format()`/trigger a real download) sees "downloading"
   indefinitely with the download never having started.
2. **Once a download is genuinely in progress, the status never refreshes on its own.**
   `LlmProviderRow`'s `produceState(provider) { ... }` (`LlmProviderListScreen.kt:118-120`)
   re-invokes `checkAvailability()` only when the `provider` parameter's *identity* changes —
   which does not happen while the row stays composed. There is no polling `Flow`, timer, or
   manual refresh affordance for the `Preparing` state: `ProviderStatusIndicator`'s `onRetry`
   parameter is wired only for the `Unavailable` branch (`LlmProviderListScreen.kt:186-192`); the
   `Preparing` branch (lines 182-185) has no retry/refresh control at all. The only way to get an
   updated status is to fully close and reopen the AI Providers dialog, forcing `LlmProviderRow`
   to leave and re-enter composition — most users will not discover this, and even those who do
   must repeat it manually every time they want to check progress.
3. **No timeout or escalation.** Even a download that is progressing normally (ML Kit gives no
   percentage/byte-level progress API — only the four-state `FeatureStatus`) shows the identical
   static "15–30 minutes" copy indefinitely; there is no mechanism to distinguish "still within
   the expected window" from "something is actually wrong" after 30+ minutes.

Note: `project_plans/depth-model-download-stall/` — an existing, extensively researched and
planned project from a prior session — targets a *different* model download (the Depth Anything
V2 ONNX model used by the photo-annotation "AI scale estimation" feature,
`DepthModelDownloader.kt`, androidMain). That investigation is real and its findings are
independently valid, but it is very unlikely to be the source of this bug report: its own
plan.md documents that `DepthEstimationPanel` is never wired up in the shipped app
(`ScreenRouter.kt`'s `AnnotationEditorScreen(...)` call passes `null` for both
`onDownloadDepthModel` and `onEstimateDepth`, confirmed the sole call site) — so no user could
have seen that screen's download UI to screenshot it. `project_plans/tag-suggestion-model-stall/`
is an empty stub with no content. This project (`ondevice-model-download-stall`) targets the
Settings → AI Providers on-device row instead, which **is** reachable today, whose exact
"downloading" copy is a near-verbatim match for "device model download," and which has two
confirmed, code-verified defects matching "stuck here and never improves." Recommend a human
decide separately whether to resume/close out `depth-model-download-stall` as an unrelated,
already-planned bug fix.

## Problem Statement

A user opening Settings → AI Providers sees the on-device (Gemini Nano) row stuck on "On-device
model is downloading — this can take 15–30 minutes on first use" indefinitely, with no visible
progress, no way to know whether a download is actually happening, and — in the common case where
the user hasn't yet triggered a real download via voice formatting or tag suggestions — the
message is simply false: no download was ever started.

## Acceptance Criteria

1. Opening Settings → AI Providers on a device with `FeatureStatus.DOWNLOADABLE` (model not yet
   downloading) either (a) actually triggers the download so the displayed "downloading" status
   becomes true, or (b) displays accurate copy distinguishing "not yet downloaded — will start on
   first use" from "downloading now" — the two states must not render identical, misleading text.
2. Once a real download is in progress (`FeatureStatus.DOWNLOADING`), the Settings row status
   updates periodically (e.g. on a reasonable poll interval) without requiring the user to close
   and reopen the AI Providers dialog.
3. The user has a visible way to manually refresh the on-device provider's status on demand (a
   refresh affordance on the `Preparing` state, matching the existing `Unavailable` state's retry
   button pattern).
4. A download that has been in the `Preparing`/downloading state for an unreasonably long time
   (well beyond the stated "15-30 minutes") surfaces some differentiated signal to the user
   (e.g. updated copy, a way to check again, or a path to fall back to a remote provider) rather
   than displaying the identical static message forever.
5. No regression to the existing `Available`/`Unavailable`/retry behavior, or to the
   voice-formatting/tag-suggestion features that already correctly trigger a real download via
   `generateContent()`.

## Non-Goals

- ~~ML Kit's Prompt API does not expose byte-level/percentage download progress~~ — **correction
  from research/stack.md**: the already-declared `genai-prompt:1.0.0-beta2` dependency *does*
  expose `GenerativeModel.download(): Flow<DownloadStatus>` with real byte-level progress
  (`DownloadStarted`/`DownloadProgress(totalBytesDownloaded)`/`DownloadCompleted`/`DownloadFailed`),
  confirmed against the AAR and Google's current official docs. This is not a Non-Goal — planning
  should prefer this documented SDK API over the current `generateContent()`-as-side-effect hack
  for triggering + observing the download; a real progress indicator is in scope if planning finds
  it a small addition on top of the correctness fix (AC1-AC3), though it's not itself a hard
  requirement.
- `project_plans/depth-model-download-stall`'s scope (the photo-annotation depth model) is
  explicitly out of scope for this project — see Root Cause note above.
- Broader on-device-vs-remote provider selection UX/strategy is out of scope; this fixes the
  status display and download trigger only.
