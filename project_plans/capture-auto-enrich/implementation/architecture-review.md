# Architecture Review: capture-auto-enrich
**Date**: 2026-08-10
**Verdict**: BLOCKED

## Constitution Check

`/home/tstapler/Programming/stelekit/docs/adr/ADR-000-architecture-constitution.md` does not
exist (checked; `docs/adr/` runs `ADR-001` through `ADR-017`, no `ADR-000`). No constitution
document to apply as a hard-constraint gate — skipping this section.

## Blockers

- [ ] **Task 2.1.1a (`coordinatorFor` lazy-build-once)** — Non-atomic "check field, then build and
  assign" across a real suspension point. `resolveTopicEnricher()` (Task 1.2.2c) calls
  `llmProviderRegistry.availableForFeature(...)`, which is declared `suspend fun` in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistry.kt:59` — so
  `coordinator ?: CaptureEnrichmentCoordinator(..., topicEnricher = resolveTopicEnricher(), ...).also { coordinator = it }`
  suspends *before* the field is ever written. `updateText()` (Task 2.1.1b) fires a fresh
  `viewModelScope.launch { coordinatorFor(repoSet).onTextChanged(text) }` on every keystroke —
  ordinary rapid typing during the exact cold-start window this feature is built around means a
  second `updateText` call can observe `coordinator == null` while the first call's
  `resolveTopicEnricher()` is still suspended, causing **two independent
  `CaptureEnrichmentCoordinator`/`PageNameIndex` instances** to be constructed. Only one survives
  in the field (last write wins); the other's `PageNameIndex` keeps its `stateIn` subscription
  alive against the shared `viewModelScope` with nothing referencing it — an orphaned matcher
  rebuild loop that leaks until `onCleared()`. This directly falsifies Story 2.1.1's own
  Acceptance Criterion ("both calls operate against the same `CaptureEnrichmentCoordinator`
  instance") and reintroduces, one layer up, the exact "duplicate-index-construction" risk class
  the plan's Alternative C write-up (plan.md lines 39-47) says is *out of scope* for this item —
  except here it's not out of scope, it's an unguarded gap in the chosen design. Epic 2.2 ("Race-
  condition guards") does not cover this path — it only covers Save/Dismiss races, not coordinator
  construction itself.
  **Remediation**: make `coordinatorFor` genuinely single-flight — either wrap the check-and-set
  in a `kotlinx.coroutines.sync.Mutex`, or cache a single `Deferred<CaptureEnrichmentCoordinator>`
  built once via `viewModelScope.async { ... }` with all callers `.await()`-ing it, or resolve the
  `TopicEnricher` synchronously up front (at first graph-load, not inside the per-keystroke hot
  path) so `coordinatorFor` itself never suspends and the plain `?:` check becomes safe.

- [ ] **Task 1.3.2a (`createAcceptedStubPages`) + Task 1.3.1a (`onSuggestionAccepted`/
  `onSuggestionDismissed`)** — `TopicSuggestion` (`domain/TopicSuggestion.kt`) models
  `accepted: Boolean` and `dismissed: Boolean` as two independent flags, and neither handler
  clears the other (`onSuggestionAccepted` only ever sets `accepted = true`;
  `onSuggestionDismissed` only ever sets `dismissed = true` — mirrored verbatim from
  `ImportViewModel.kt:309-331`). An accept-then-dismiss sequence on the same term (a completely
  ordinary user action: tap Accept, change your mind, tap Dismiss) produces
  `accepted=true, dismissed=true`. `CaptureSuggestionTray` (Task 3.2.1a) filters visible chips by
  `!it.dismissed` — the chip disappears, telling the user the suggestion was withdrawn — but
  `createAcceptedStubPages` (Task 1.3.2a) filters by `it.accepted` alone, with no `!it.dismissed`
  exclusion, so it **still creates the stub page and still folds the `[[wiki link]]` in**. Because
  ADR-002 makes `createAcceptedStubPages` the *sole* write gate (no second review/confirm stage
  like Import has), there is no later checkpoint to catch this — it ships as a silent write for a
  term the UI explicitly showed as withdrawn, directly contradicting requirements.md's Must-Have
  "no silent page creation, ever."
  **Remediation**: minimally, filter `it.accepted && !it.dismissed` in Task 1.3.2a. Better: replace
  the two independent booleans with a single sealed `SuggestionDecision { Pending, Accepted, Dismissed }`
  (or have `onSuggestionDismissed` clear `accepted` and strip the term back out of `linkedText`)
  so the illegal `accepted && dismissed` combination is unrepresentable rather than merely
  filtered-around. This is a pre-existing shape in `ImportViewModel`/`TopicSuggestion` too
  (`confirmImport()`'s `filter { it.accepted }` at `ImportViewModel.kt:403` has the identical gap),
  but it's surfaced here as a blocker because this plan is the one introducing a code path
  (`createAcceptedStubPages`) with no downstream chance to catch it before the write lands.

## Concerns

- [ ] **Task 1.1.2c / Task 2.1.2a (`resolveForSave` staleness check via `sourceTextHash`)** —
  `EnrichmentState.Ready.sourceTextHash` is `text.hashCode()` (Int), and `resolveForSave` trusts a
  hash match to reuse a background scan's `linkedText` as the content it writes to the permanent
  journal block. A 32-bit hashCode collision between two different captured texts (rare, but not
  impossible over an app's lifetime) would make `resolveForSave` silently persist the *wrong*
  linked text as a permanent write. The pattern is inherited from `ImportViewModel`'s own
  `textHash` staleness gate (`ImportViewModel.kt`, Coroutine 2), but there the consequence of a
  false-positive match is only a skipped UI suggestion-merge (self-healing on the next scan) — not
  a persisted write. Elevating the same hash-comparison technique to gate `performSave()`'s actual
  written content (Task 2.1.2a) raises the stakes of a rare collision from "cosmetic" to
  "wrong permanent data."
  **Recommendation**: compare the literal source text (store `sourceText: String` on `Ready`
  alongside/instead of `sourceTextHash`) rather than its hash — captured-note lengths are small
  enough that this costs nothing, and removes the collision class entirely.

- [ ] **Task 1.3.2a — `PageSaver` imported from `ui.screens.ImportViewModel.kt`** — `PageSaver`
  (and `PageDeleter`) are defined directly inside
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt:87-95`, not in a
  neutral domain/db location. The plan has `CaptureEnrichmentCoordinator` — a `capture/`-package
  collaborator ADR-004 explicitly designed to be independently testable and decoupled from Import
  — import `dev.stapler.stelekit.ui.screens.PageSaver` directly (task text: "Import
  `dev.stapler.stelekit.ui.screens.PageSaver` (existing type)"). Two independent features (Import,
  Capture) now both depend on an abstraction whose owning file is neither's — a future
  rename/refactor of `ImportViewModel.kt` breaks `CaptureEnrichmentCoordinator.kt` with no
  ownership signal pointing there, and it blurs the ViewModel/domain layering CLAUDE.md's
  Architecture section otherwise keeps clean.
  **Recommendation**: extract `PageSaver`/`PageDeleter` into a shared, feature-neutral file (e.g.
  `domain/PageSaver.kt`) before Task 1.3.2a lands — the exact same "promote a private/local type to
  a shared location" move Epic 3.1 already applies to `TopicSuggestionChip`; apply it here too for
  consistency rather than reaching across features.

- [ ] **Task 1.2.1b (`mergeEnrichedSuggestions` duplication)** — The task deliberately hand-copies
  `ImportViewModel`'s `private fun mergeEnrichedSuggestions` (`ImportViewModel.kt:513`) into
  `CaptureEnrichmentCoordinator` rather than sharing it, citing "different module boundary/
  visibility." On inspection this is inaccurate: `ImportViewModel.kt` and the new
  `CaptureEnrichmentCoordinator.kt` are both under `kmp/src/commonMain` — the same Gradle
  module/source set. There is no module boundary, only a `private` visibility choice in one file.
  Duplicating confidence-merge/dedup domain logic contradicts `research/build-vs-buy.md` §3's own
  explicit guard ("name `ImportService.scan()`, `TopicExtractor.extract()`... as the *only* allowed
  entry points for matching/suggestion/provider-selection logic... no new matching/dedup code" —
  a hand-duplicated merge function is exactly the kind of re-approximation that guard exists to
  prevent) and is inconsistent with this same plan's Epic 3.1 precedent (promote, don't copy).
  **Recommendation**: promote `mergeEnrichedSuggestions` to a shared, non-private function (e.g.
  next to `TopicSuggestion` in `domain/`), consumed by both `ImportViewModel` and
  `CaptureEnrichmentCoordinator` — mirrors Task 3.1.1a/b's chip-extraction pattern exactly.

## Nitpicks

- `CaptureEnrichmentCoordinator.matcher: StateFlow<AhoCorasickMatcher?>` (Task 1.1.1b) is exposed
  as public API, but no Phase 2/3 consumer is listed anywhere in the plan besides the coordinator's
  own tests (Task 1.1.1c). Narrow the surface (`internal`, or drop it and assert via `state`
  instead) unless there's a concrete external need — keeps the coordinator's public contract
  minimal per the stability goal in Lens 3.
- Task 2.1.1a's lazy-build-once `coordinator` field has no invalidation path if the active graph
  changes while a `CaptureActivity` instance stays alive (e.g. `singleTop` re-launch across a graph
  switch) — the coordinator would keep pointing at the old graph's `PageRepository`/`PageNameIndex`.
  Likely fine given `CaptureActivity`'s short-lived, single-launch nature, but worth a one-line
  acknowledgment alongside the plan's existing Unresolved Questions rather than being implicit.
- `EnrichmentState.Ready` (Task 1.1.1a) bundles five fields including a loose `isEnhancing: Boolean`
  alongside a `topicSuggestions` list whose entries already carry a `Source.AI_ENHANCED` marker.
  A nested `LlmEnhancement { NotAttempted | InProgress | Done }` would read slightly cleaner, but
  this exactly matches `ImportState`'s existing flat-boolean shape, so it's consistency-preserving
  rather than a regression — not worth diverging from precedent over.
