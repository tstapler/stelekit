# Requirements: Auto-Link + Tag-Suggest for Share-Sheet Capture

**Status**: Draft | **Phase**: 1 — Ideation (non-interactive, generated from backlog item)
**Created**: 2026-08-10
**Backlog item**: `7df64972-cf2d-40d4-a1e6-b5fe6ededc6e`
**Type**: feature

## Problem Statement

`CaptureActivity`/`CaptureViewModel` — the quick-capture sheet launched from the Android
share sheet, home-screen widget, and Quick Settings tile — writes shared text as a single
raw `Block` straight to today's journal (`CaptureViewModel.performSave()`,
[`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`](../../androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt)).
It never runs the content through any linking or tagging pipeline, even though SteleKit
already has that machinery:

- **`ImportService.scan(rawText, matcher, existingNames)`**
  ([`kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ImportService.kt`](../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ImportService.kt))
  — pure function, already shipped. Converts existing-page-name mentions to `[[wiki links]]`
  via `AhoCorasickMatcher` and returns local heuristic `TopicSuggestion`s
  (`domain/TopicSuggestion.kt`, `domain/TopicExtractor.kt`) for candidate new pages, deduped
  against `existingNames`.
- **`ClaudeTopicEnricher`** (`domain/ClaudeTopicEnricher.kt`) — optional LLM enhancement tier
  over the local `TopicExtractor` suggestions, already shipped for the in-app Import screen.
- **`LlmFormatterProvider`** family (`voice/LlmFormatterProvider.kt`,
  `ClaudeLlmFormatterProvider`, `OpenAiLlmFormatterProvider`, `GeminiLlmFormatterProvider`
  for on-device ML Kit) — the provider abstraction `llm-service` is unifying provider
  selection behind (`project_plans/llm-service/`, refactor in progress/landed — verify
  current state in research phase).
- **`ImportViewModel`** (`ui/screens/ImportViewModel.kt`) and `ImportScreen` — reference
  implementation of the accept/dismiss suggestion-chip UX this item should mirror, per
  `ADR-004-suggestion-chip-tray-ux.md`
  (`project_plans/import-topic-suggestions/decisions/ADR-004-suggestion-chip-tray-ux.md`).

Sharing a web page or article into SteleKit today produces an unlinked, untagged journal
blob — the fastest capture path is also the one that contributes least to the graph.

## Why This Matters

- Share-to-capture is the highest-frequency, lowest-friction entry point (no context switch
  back into the app) — it should get the *most* automatic enrichment, not the least.
- Avoids duplicating matching/suggestion logic: this is "point the existing Import +
  topic-suggestion pipeline at `CaptureActivity`'s text before it's saved," not a new
  algorithm.

## Success Criteria

- Text captured via share sheet/widget/tile is scanned against `PageNameIndex` before the
  block is written; existing-page mentions are auto-converted to `[[wiki links]]`, matching
  in-app Import behavior.
- Local heuristic topic-suggestion candidates (via `TopicExtractor`) are surfaced as
  dismissible chips in the capture bottom sheet; accepting a chip creates a stub page
  (mirrors `ImportViewModel.confirmImport()`/`ADR-004` chip-tray UX) — no silent page
  creation, ever.
- If an LLM provider is configured (any tier, including on-device), the opt-in enhancement
  pass runs and its suggestions replace/augment the local heuristic tier in the same chip
  tray. Zero-API-key/no-provider-configured users still get the local heuristic tier
  unchanged.
- The initial `Save` action is never blocked waiting on enrichment: if the scan/suggestion
  pass can't complete within the sheet's existing responsiveness budget (import-topic-
  suggestions targets <500ms/10KB locally), the raw text saves as today and enrichment is
  skipped for that capture (no partial/stalled saves).
- Capture sheet's existing perceived responsiveness (time to first frame, time to editable
  text field) is not regressed by wiring in the matcher/index lookup.

## Scope

### Must Have (MoSCoW)
- Run captured text through `ImportService.scan()` (existing `AhoCorasickMatcher` +
  `PageNameIndex` for the active graph) before `performSave()` writes the block; write the
  `linkedText` result instead of the raw `text`.
- Surface `topicSuggestions` from the local heuristic tier as dismissible chips in
  `CaptureScreen`, consistent with `ADR-004-suggestion-chip-tray-ux.md`.
- Accepting a suggestion chip creates a stub page via the same `GraphWriter.savePage` path
  `ImportViewModel.confirmImport()` uses, and (if the accept happens before save) folds the
  new `[[wiki link]]` into the saved block content.
- Reuse whichever LLM provider is configured via the `llm-service` abstraction (including
  on-device ML Kit/Gemini Nano tiers) for the opt-in enhancement pass — no new provider code.
- Time-box the enrichment pass so it never delays the initial save past the sheet's existing
  responsiveness budget; on timeout/failure, fall back to the unenriched raw-text save
  (today's behavior).

### Should Have
- Visual/haptic distinction between "matched existing page" links (auto-applied, no
  confirmation) and "new page" suggestion chips (require explicit accept) — mirrors the
  existing Import review-stage distinction.

### Out of Scope (v1)
- New matching/suggestion algorithms — reuse `import-topic-suggestions`'s `TopicExtractor`/
  `ClaudeTopicEnricher` as-is.
- Auto-accepting suggestions without user review (no silent stub creation, no silent
  auto-linking beyond existing-page-name matches which already auto-apply in in-app Import
  today).
- Image-attachment enrichment — this item is scoped to the shared-text path only; the
  `[image: ...]` capture path (Bug 2 mitigation in `CaptureActivity.parseShareIntent`) is
  unaffected.
- Building/finishing the `llm-service` provider-selection UI if it is not yet complete —
  this item consumes whatever provider is currently wired for tag suggestion; it does not
  block on `llm-service` reaching its own "done."

## Constraints

- **Tech stack**: Kotlin Multiplatform, Android-only entry point (`CaptureActivity` is
  `androidApp` module) — no new runtimes/dependencies; reuse `kmp/commonMain` domain code.
- **Responsiveness budget**: capture sheet must stay fast — this is the same <500ms/10KB
  bar `import-topic-suggestions` set for local heuristics; LLM-tier enhancement is
  best-effort and must not block save.
- **No silent writes**: every new page creation and every LLM-tier suggestion requires
  explicit user acceptance (`ADR-004` chip-tray pattern) — matches existing Import UX and
  the `llm-service` "approval-gated edit" principle.
- **Offline-first**: matcher/local-heuristic tier must work fully offline (already true for
  `ImportService.scan()`); LLM enhancement is an optional layer only.

## Context

### Existing Work (reused, not rebuilt)

| System | File | Relevance |
|--------|------|-----------|
| `ImportService.scan()` | `kmp/src/commonMain/.../domain/ImportService.kt` | Matcher + suggestion entry point to call from `CaptureViewModel` |
| `TopicExtractor` / `TopicSuggestion` | `kmp/src/commonMain/.../domain/TopicExtractor.kt`, `TopicSuggestion.kt` | Local heuristic candidate generation |
| `ClaudeTopicEnricher` | `kmp/src/commonMain/.../domain/ClaudeTopicEnricher.kt` | Optional LLM enhancement over local candidates |
| `PageNameIndex` / `AhoCorasickMatcher` | `kmp/src/commonMain/.../domain/PageNameIndex.kt` | Existing-page match source; must be available per active graph in `CaptureViewModel`'s scope |
| `ImportViewModel` / `ImportScreen` | `kmp/src/commonMain/.../ui/screens/` | Reference UX for chip accept/dismiss + `confirmImport()` stub-page creation flow |
| `ADR-004-suggestion-chip-tray-ux.md` | `project_plans/import-topic-suggestions/decisions/` | UX pattern to mirror in `CaptureScreen` |
| `LlmFormatterProvider` family + `llm-service` | `kmp/src/commonMain/.../voice/`, `project_plans/llm-service/` | Provider selection this item should consume, not reimplement |
| `CaptureViewModel.performSave()` | `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt` | Write path to modify: scan before block creation |
| `CaptureActivity`/`CaptureScreen` | `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt` | UI surface for suggestion chips |

### Stakeholders
- Solo user / knowledge worker (primary and only user of this app today)

## Research Dimensions Needed

- [ ] Stack — how to get a `PageNameIndex`/`AhoCorasickMatcher` and the active LLM provider
  into `CaptureViewModel` (an `AndroidViewModel` outside the main app's Compose navigation
  graph) without duplicating `GraphManager`/`RepositorySet` wiring; whether the matcher is
  already built per-graph and cached, or needs building on each capture
- [ ] Features — how `ImportViewModel` currently builds/caches its matcher and calls
  `ClaudeTopicEnricher`, to mirror the pattern rather than diverge
- [ ] Architecture — where the scan call belongs in `performSave()` relative to the existing
  bug-mitigation ordering (writeActor race guard, GraphWriter autosave flush); how suggestion
  chip state should be threaded through `CaptureViewModel.SaveState`/a new state without
  breaking the existing `Idle → Saving → Saved/Error` flow; timeout/cancellation mechanism
  for the enrichment pass
- [ ] Pitfalls — matcher-build cost on a large graph inside a translucent overlay activity
  (must not visibly delay `focusRequester.requestFocus()`); race between accepting a
  suggestion chip and the user tapping Save/dismissing the sheet; current state of
  `llm-service`'s provider abstraction (is it landed, partially landed, or still `voice/`-only
  per-feature providers?) — determines whether this item can consume a unified provider or
  must call `ClaudeTopicEnricher`'s existing dependency directly
- [ ] Testing — existing `CaptureViewModel`/`CaptureActivity` test coverage patterns to extend
