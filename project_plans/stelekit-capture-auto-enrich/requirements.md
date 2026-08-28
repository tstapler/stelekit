# Requirements: stelekit-capture-auto-enrich

**Date**: 2026-08-27
**Type**: feature (existing project: SteleKit)
**Source**: [GitHub issue #264](https://github.com/tstapler/stelekit/issues/264), backlog item `7df64972-cf2d-40d4-a1e6-b5fe6ededc6e`

## Problem Statement

`CaptureActivity` (the quick-capture sheet launched from the Android share sheet,
home-screen widget, and Quick Settings tile) writes shared text as one raw `Block`
straight to today's journal — see `performSave()` in
[`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`](../../androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt).
It never runs the content through any linking or tagging pipeline, even though the
in-app Import feature already does exactly this for pasted/URL text.

Share-to-capture is the highest-frequency, lowest-friction entry point (no context
switch back into the app) — today it contributes the least to the graph, because it
produces an unlinked, untagged block.

## Existing machinery to reuse (do not duplicate)

- `dev.stapler.stelekit.domain.ImportService.scan(rawText, matcher, existingNames)` —
  pure function, returns `ScanResult(linkedText, matchedPageNames, topicSuggestions)`.
  Used today by `ImportViewModel.runScan()`
  ([`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt`](../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt)).
- `dev.stapler.stelekit.domain.PageNameIndex(pageRepository, scope)` — builds/rebuilds
  an `AhoCorasickMatcher` reactively from `PageRepository.getPageNameEntries()`
  (names-only projection). Already instantiated once per active graph inside
  `StelekitViewModel` (`val pageNameIndex = PageNameIndex(pageRepository, scope)`,
  exposed there as `suggestionMatcher`). **`CaptureActivity` can be cold-started by the
  share sheet without `StelekitViewModel`/`MainActivity` ever running in this process**,
  so no such instance is guaranteed to exist when capture needs one.
- `dev.stapler.stelekit.domain.TopicExtractor` — local heuristic noun-phrase/concept
  candidate extraction (scored, capped, called from inside `ImportService.scan`).
- `dev.stapler.stelekit.domain.TopicEnricher` (`fun interface`,
  `suspend fun enhance(rawText, localSuggestions): List<TopicSuggestion>`) — the opt-in
  LLM enhancement seam. `NoOpTopicEnricher` is the zero-config default.
- `dev.stapler.stelekit.llm.LlmProviderRegistry` / `LlmProvider` — the unified,
  already-shipped (Epics 1–7 merged) provider abstraction from `project_plans/llm-service/`,
  including the on-device Android tier (ML Kit/AICore `MlKitLlmFormatterProvider`).
  `TagSuggestionEngine` is the existing example of a feature consuming a provider via
  `checkAvailability: (suspend () -> LlmProviderAvailability)?` for non-blocking
  availability probing before invoking inference
  ([`kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt`](../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt)).
- `GraphWriter.savePage(page, blocks, graphPath): Either<DomainError, Unit>` — stub-page
  persistence path, same one `ImportViewModel.confirmImport()` calls via the
  `PageSaver` functional interface.
- `DatabaseWriteActor.saveBlock(block): Either<DomainError, Unit>` — upsert by
  `BlockUuid`; `CaptureViewModel.performSave()` already calls this once per capture.
  A second call with the same `BlockUuid` (content updated) is a normal update, not a
  new code path.

None of this is being redesigned. The task is wiring `CaptureActivity`'s text through
these existing pieces before/around the write that `performSave()` already does, the
way `ImportViewModel` wires them for the in-app Import screen — plus the additional
race-safety, failure-isolation, and post-save-edit constraints below, which are new
requirements specific to the capture context (a short-lived, possibly cold-started
Activity, not a long-lived screen).

## Users / Consumers

- **Human users** sharing a web page, article snippet, or quick thought into SteleKit
  via the Android share sheet, home-screen widget, or Quick Settings tile.
- Applies uniformly regardless of whether an LLM provider is configured — local
  heuristic tier is the floor, not a degraded fallback state.

## In scope (v1)

1. **Auto-link existing pages.** Before the block is written, scan captured text
   against `PageNameIndex`'s matcher (same `ImportService.scan()` call in-app Import
   uses) and save the `linkedText` (matched page names wrapped `[[Page Name]]`)
   instead of the raw text.
2. **Topic-suggestion chip tray.** Local heuristic `TopicSuggestion` candidates
   (`ScanResult.topicSuggestions`) surface as dismissible chips in the capture bottom
   sheet, consistent with `ADR-004-suggestion-chip-tray-ux.md`'s chip anatomy
   (confidence dot, term, dismiss `×`) but sized down for a compact bottom sheet — no
   "Accept All" confirmation dialog is required at this scale (single-item accept only
   is acceptable for v1; the sheet is a quick-capture surface, not the full Import
   review screen). Accepting a chip creates a stub page via `GraphWriter.savePage`
   (same path Import uses) and folds the `[[link]]` into the saved text — never silent,
   always an explicit tap.
3. **Opt-in LLM enhancement tier.** When an `LlmProvider` is configured via
   `llm-service` (including the Android on-device tier), an async enhancement pass
   (mirroring `ImportViewModel`'s Claude-enrichment coroutine: fire-and-forget,
   timeout-bounded, merges into the tray non-destructively) augments the chip tray.
   Zero-provider users get the unchanged local heuristic tier only — this is not a
   degraded state, it's the default state.
4. **Save is never blocked by enrichment.** The scan/suggestion pass runs against a
   responsiveness budget consistent with `import-topic-suggestions`'
   (<500ms/10KB locally). If the matcher isn't ready, the scan hasn't finished, or the
   budget is exceeded, `Save` still writes the text captured so far — auto-linking and
   suggestions are a best-effort enhancement on top of the existing save path, never a
   precondition for it. No partial or stalled saves under any enrichment-path failure.
5. **No responsiveness regression.** Time-to-first-frame and time-to-editable-text-field
   for the capture sheet must not regress from wiring in the matcher/index lookup —
   these must not sit on the sheet's initial-render critical path.
6. **Visual/haptic distinction.** Auto-applied existing-page links vs. new-page
   suggestion chips must be visually distinguishable, consistent with in-app Import's
   review-stage treatment (confidence dot / linked-state chip styling from ADR-004).
7. **Per-suggestion failure isolation.** A `GraphWriter.savePage` failure for one
   accepted suggestion (`Either.Left`) must not abort the block write, the Bug-8
   markdown flush, or any other pending suggestion. The failure must be surfaced (at
   minimum logged) — not silently discarded. (`ImportViewModel.confirmImport()`'s
   current stub-creation loop does *not* isolate failures either — do not copy that
   part of the pattern; this is a new correctness bar for capture, and worth calling
   out as a latent gap in Import too, though fixing Import is out of scope here.)
8. **Race-safe coordinator construction.** Because `CaptureActivity` can cold-start
   without any existing `PageNameIndex` for the active graph, whatever builds one
   on-demand must be single-flight: rapid concurrent text-change events (the user
   typing/pasting quickly) must never construct two `PageNameIndex` (or whatever
   wraps it — referred to in the acceptance criteria as `CaptureEnrichmentCoordinator`)
   instances for one capture session. Use a `Mutex` or a memoized `Deferred`, not a
   plain nullable-field double-checked pattern (that's the exact race being closed).
9. **Post-save chip acceptance.** The capture sheet's post-save state (`Saved` /
   "Done", sheet still visible before `finish()`) still allows accepting a pending
   suggestion chip. Accepting one after save creates the stub page and folds the
   `[[link]]` into the *already-persisted* block via exactly one narrowly-scoped
   second `writeActor.saveBlock()` call on the same `BlockUuid`, using the same
   `writeActor`/`GraphWriter` instance from the original save — not a general
   retroactive-edit mechanism. This narrow exception and its scope boundary (why it
   doesn't reopen general post-save editing) must be written up as this project's own
   ADR-002, since no existing ADR in the repo covers it.

## Out of scope (v1)

- New matching/suggestion algorithms — `TopicExtractor`/`ImportService` are reused
  as-is.
- Auto-accepting suggestions without user review (no silent page creation, ever).
- Fixing the (pre-existing, out-of-scope) lack of per-suggestion failure isolation in
  `ImportViewModel.confirmImport()` — noted above, not fixed here.
- A full "Accept All" bulk-confirm flow in the capture sheet (the Import screen's
  scale; not needed for a quick-capture bottom sheet).
- iOS/Desktop/Web capture entry points — `CaptureActivity` is Android-only.

## Constraints

- **No new abstractions beyond what the task requires.** `ImportService`,
  `PageNameIndex`, `TopicEnricher`, and `LlmProviderRegistry` are reused directly, not
  re-implemented for capture. Any new capture-specific type (e.g. a coordinator class)
  exists only to bind these together per capture session, not to introduce a parallel
  suggestion algorithm or a parallel LLM abstraction.
- **`CaptureViewModel.performSave()`'s existing Bug-1/Bug-8 mitigations
  (`ClosedSendChannelException` handling, markdown auto-flush via `GraphWriter`) must
  keep working unchanged** — enrichment wraps around this method, it does not replace
  it.
- **Backend-agnostic**: works against whatever `RepositoryFactory` backend the active
  graph uses (same as Import/Tag-suggestion today — no capture-specific backend
  assumptions).
- **Offline-first**: local heuristic tier has zero network dependency; the LLM tier is
  a strictly optional layer on top, matching the `llm-service` constraint already
  established for tag suggestion and voice formatting.

## Acceptance Criteria

(Verbatim from the backlog item — the authoritative checklist for `/backlog/done-N`.)

1. Captured text is scanned against `PageNameIndex` before the block is written;
   existing-page mentions are auto-converted to `[[wiki links]]`, matching in-app
   Import behavior.
2. Local heuristic topic-suggestion candidates are surfaced as dismissible chips in
   the capture bottom sheet; accepting a chip creates a stub page via the same
   `GraphWriter.savePage` path Import uses — no silent page creation.
3. When an LLM provider is configured via `llm-service` (including on-device tiers),
   the opt-in enhancement pass augments the chip tray; users with no provider
   configured still get the unchanged local heuristic tier.
4. Save is never blocked by enrichment: if the scan/suggestion pass can't complete
   within the sheet's responsiveness budget (<500ms/10KB locally), the raw text saves
   as today with no partial or stalled saves.
5. The capture sheet's existing perceived responsiveness (time to first frame, time
   to editable text field) is not regressed by wiring in the matcher/index lookup.
6. Auto-applied existing-page links and new-page suggestion chips are
   visually/haptically distinguishable, consistent with in-app Import's review stage.
7. Stub-page creation failures are isolated per-suggestion (one failure degrades only
   that suggestion) and never abort the block write or the Bug-8 markdown flush;
   `GraphWriter.savePage`'s `Either` failure is surfaced (logged at minimum), not
   silently discarded.
8. `coordinatorFor` construction is race-safe under rapid concurrent text-change
   events (single-flight via `Mutex` or memoized `Deferred`) so two
   `CaptureEnrichmentCoordinator`/`PageNameIndex` instances are never built for one
   capture session.
9. Accepting a suggestion chip after Save (post-save "Done" state, sheet still open)
   creates the stub page and folds the link into the already-persisted block via one
   narrowly-scoped second write on the same `BlockUuid`/`writeActor` instance, without
   violating this project's own ADR-002 rejection of general retroactive post-save
   edits.

## Success Metrics

- A shared article/URL, sent through the Android share sheet, lands in today's
  journal with existing-page mentions already linked and at least the local-heuristic
  suggestion chips available — with zero additional taps beyond today's flow when the
  user just hits Save. **Known v1 limitation**: `CaptureActivity` only ever reads
  `EXTRA_TEXT`/`EXTRA_SUBJECT`/clipData (see `implementation/pre-mortem.md` failure #5)
  — no article-body fetch. When the sharing app's "Share" action sends only a bare URL
  (common for some share targets), there is no prose for the matcher/extractor to work
  against, so this scenario honestly produces zero auto-links and zero/minimal chips;
  `implementation/validation.md` documents and tests this expected behavior rather than
  treating it as undefined.
- No regression in `CaptureActivity`'s existing golden-path tests or perceived launch
  responsiveness.
- No regression in `ImportViewModel`/`TagSuggestionEngine` behavior — this is additive
  wiring, not a refactor of the shared domain code.

## Open Questions Resolved During Requirements

| Question | Resolution |
|----------|------------|
| Does capture need its own suggestion/matching algorithm? | No — reuse `ImportService`/`TopicExtractor`/`TopicEnricher` verbatim. |
| Is the LLM provider abstraction already available to consume? | Yes — `llm-service` (Epics 1–7) is merged; `LlmProviderRegistry`/`LlmProvider` exist today. |
| Can capture always assume a `PageNameIndex` already exists for the active graph? | No — `CaptureActivity` can cold-start without `StelekitViewModel` ever running; on-demand, race-safe construction is required (AC 8). |
| Does accepting a chip after Save reopen general post-save editing? | No — scoped to exactly one second write on the just-saved `BlockUuid`, to be documented as this project's ADR-002 with an explicit "why this doesn't generalize" rationale. |
