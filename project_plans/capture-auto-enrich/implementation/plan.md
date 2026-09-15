# Implementation Plan: capture-auto-enrich

**Feature**: Wire the existing `ImportService.scan()`/`TopicExtractor`/`LlmProviderRegistry`
pipeline into `CaptureViewModel`/`CaptureScreen` so share-sheet/widget/tile captures get
auto-linked and suggestion chips, without ever blocking or delaying Save.
**Date**: 2026-08-10
**Status**: Ready for implementation
**ADRs**:
- [ADR-001: EnrichmentState as a Sibling StateFlow to SaveState](../decisions/ADR-001-enrichment-state-sibling-to-savestate.md)
- [ADR-002: Fold-Before-Save-Only Write Path](../decisions/ADR-002-fold-before-save-only.md)
- [ADR-003: Reuse ClaudeTopicEnricher Directly Instead of a New LLM Adapter Class](../decisions/ADR-003-reuse-claudetopicenricher-no-new-adapter.md)
- [ADR-004: Extract CaptureEnrichmentCoordinator as a Testable commonMain Collaborator](../decisions/ADR-004-capture-enrichment-coordinator-collaborator.md)

---

## Step 0.5 — Creative Pass: Alternatives Considered

Three high-level approaches for wiring the pipeline into `CaptureViewModel`/`CaptureScreen`:

**A. Inline everything into `CaptureViewModel`** (matcher build, scan, timeout, LLM enrichment,
`EnrichmentState`, all as private members of the existing `androidApp` `AndroidViewModel`,
mirroring `research/architecture.md`'s own sketch).
- *Strength*: Fewest new files; everything lives next to the write path (`performSave()`) it
  must coordinate with.
- *Weakness*: `CaptureViewModel` has zero existing test coverage today (verified: only
  `CaptureShareTextTest.kt` exists, testing a static function, not the ViewModel) and is hard to
  unit-test as an `AndroidViewModel` — this would ship a whole new async pipeline untested.

**B. Extract a new commonMain collaborator** (`CaptureEnrichmentCoordinator`) that owns the
`PageNameIndex`, `EnrichmentState`, scan/timeout logic, and suggestion lifecycle;
`CaptureViewModel` becomes a thin Android wrapper.
- *Strength*: Fully unit-testable in `kmp/src/businessTest` with fake `PageRepository`/
  `TopicEnricher`, no Android/`Application` dependency required — closes the test-coverage gap
  directly.
- *Weakness*: One more file to design/name; a second, differently-shaped enrichment call site
  (`ImportViewModel`) already exists, and a shared coordinator could tempt out-of-scope
  refactoring of it.

**C. Hoist `PageNameIndex` to `GraphManager`/graph scope** so `StelekitViewModel` and
`CaptureViewModel` share one warm instance instead of each cold-building their own.
- *Strength*: Eliminates the cold-build-per-capture cost and the duplicate-stub-creation risk
  class at the root — the architecturally "correct" long-term fix per
  `research/pitfalls.md`'s P0 recommendation.
- *Weakness*: A bigger, cross-cutting change (new ownership model touching every existing
  `PageNameIndex` consumer, including `ScreenRouter.kt:260`) that both `architecture.md` and
  `pitfalls.md` explicitly flag as belonging to a separate item, not this one; blows past this
  plan's 2-5 minute task sizing.

**Chosen: B.** See [ADR-004](../decisions/ADR-004-capture-enrichment-coordinator-collaborator.md)
for the full rationale. A and C are recorded as rejected alternatives in the Pattern Decisions
table below.

---

## System Type

This is an **integration feature**: wiring an already-shipped, pure-function pipeline
(`ImportService.scan()`, `TopicExtractor`, `LlmProviderRegistry`) into a new entry point
(`CaptureViewModel`/`CaptureScreen`). No new matching, dedup, or LLM-call algorithm is written —
confirmed with zero gaps by `research/build-vs-buy.md`. The only genuinely new logic is
orchestration: timeout/fallback sequencing, suggestion-chip state, and duplicate-stub-write
gating, all of which have a directly-precedented shape in `ImportViewModel`.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `EnrichmentState` | Sealed interface (`Idle`/`Scanning`/`Ready`/`TimedOut`) tracking the capture-sheet auto-link/suggest pipeline, independent of `SaveState`. | New type, `capture/CaptureEnrichmentCoordinator.kt`. See ADR-001. |
| `CaptureEnrichmentCoordinator` | New commonMain class that owns a per-graph `PageNameIndex`, runs the debounced local scan and time-boxed LLM enrichment pass, and gates stub-page creation. The sole collaborator `CaptureViewModel` delegates all enrichment logic to. | New class. See ADR-004. |
| `resolveForSave(text)` | Coordinator suspend method `CaptureViewModel.performSave()` calls to obtain the final block content — linked text if a matcher resolved in budget, otherwise the raw text unchanged. | New method. |
| `createAcceptedStubPages(...)` | Coordinator suspend method that creates a `Page` for every still-accepted suggestion, gated by a live `getPageByName()` DB read per page — called exactly once, from inside `performSave()`. | New method. See ADR-002. |
| `sourceTextHash` | Field on `EnrichmentState.Ready` recording `text.hashCode()` for the text a completed scan applies to — lets `resolveForSave` detect whether a background scan result is still valid for the text being saved, or stale and requiring a fresh scan. | New field, prevents reusing a scan result for different text. |
| `LlmFeature.TOPIC_ENRICHMENT` | New enum case in `llm/LlmFeature.kt` enabling independent per-feature LLM provider selection (`Auto`/explicit/`DISABLED_SENTINEL`) for capture enrichment, parallel to the existing `TAG_SUGGESTION`/`VOICE_FORMATTING`/`GRAPH_EDIT_SYNTHESIS` cases. | New enum value — no schema change, `LlmSettings` already stores selections as strings keyed by feature name. |
| `CaptureSuggestionTray` | New `CaptureActivity`-local composable — the purpose-built, compact chip-tray container for the bottom sheet (no Accept-All dialog, no status badge, no pagination). | New composable, distinct from Import's `TopicSuggestionTray`. |
| `TopicSuggestionChip` (relocated) | Existing chip composable (confidence dot + term + accept/dismiss), promoted from `ImportScreen.kt`-private to `ui/components/TopicSuggestionChip.kt`, non-private, zero behavior change. | Moved, not modified. |
| fold-before-save | The architectural rule that every write this feature performs (stub pages, linked block content) happens exactly once, synchronously inside `performSave()`, never at chip-accept time. | Not a new type — a naming for ADR-002's rule, used throughout task descriptions below. |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Enrichment pipeline ownership | Extract Collaborator — new `CaptureEnrichmentCoordinator` in commonMain | Step 0.5 creative pass; ADR-004 | **A**: Inline everything into `CaptureViewModel` (architecture.md's own sketch) | `CaptureViewModel` has zero existing tests and is hard to unit-test as an `AndroidViewModel`; inlining ships an untested async pipeline |
| `PageNameIndex` sourcing | Per-instance construction inside the coordinator (status quo pattern — direct instantiation, no new abstraction) | research/architecture.md §1, research/stack.md §3 | **C**: Hoist `PageNameIndex` to `GraphManager` as a shared graph-scoped singleton | Bigger cross-cutting infra change touching every existing consumer; correctly sized by pitfalls.md as a *future* item, not this one |
| LLM provider selection + enrichment call | Reuse `buildLlmProviderRegistry(...)` composition root + reuse `ClaudeTopicEnricher` directly (no GoF Adapter) | research/architecture.md §2; direct code read of `domain/ClaudeTopicEnricher.kt:20-22`; ADR-003 | New `TopicEnricher` Adapter class wrapping `LlmFormatterProvider` (architecture.md's original recommendation) | `ClaudeTopicEnricher`'s constructor already takes the generic `LlmFormatterProvider` — a second Adapter over the identical interface/dependency would be a line-for-line duplicate |
| State shape for suggestion/enrichment UI | Sibling `StateFlow<EnrichmentState>`, not merged into `SaveState` | research/architecture.md §3; ADR-001 | Fold enrichment fields into `SaveState` as a richer `Saved(pendingSuggestions: Boolean)` variant | Breaks 4 existing `SaveState.Idle`/`Saving` `==` comparisons in `CaptureActivity.kt`; conflates write-success with optional-review-step |
| Write timing for stub pages + linked content | Fold-before-save-only, single write path inside `performSave()` | research/architecture.md §5; ADR-002 | Retroactive post-save content edit (re-read persisted block, re-apply `insertWikiLinks`, second write cycle) | Reintroduces Bug 1's `ClosedSendChannelException` race a second time for marginal UX benefit; requirements.md's phrasing treats fold-before-save as the expected case |
| Duplicate stub-page prevention | Live per-write `getPageByName()` DB read before each `pageSaver.save(...)` | research/pitfalls.md §5; `ImportViewModel.confirmImport()` (`ImportViewModel.kt:406-408`) | Trust the in-memory `PageNameIndex.vocabularyNames()`/`existingNames` snapshot as the create-vs-skip gate | Snapshot is provably staler across concurrent `CaptureActivity` instances (independent 500ms-debounced `PageNameIndex`s) — trusting it produces duplicate stub pages on rapid back-to-back captures |
| Chip UI reuse | Extract Method — promote `TopicSuggestionChip` to `ui/components/`; build a purpose-fit tray container, don't force-share `TopicSuggestionTray` | research/build-vs-buy.md §4 | Reuse `TopicSuggestionTray` wholesale (pass a synthetic `ImportState`) | `TopicSuggestionTray` is Import-review-specific (Accept-All dialog, Claude status badge, pagination) — forcing it in fights its own signature and violates ux.md's "diverge" list |
| Undo affordance | Not built — fold-before-save makes pre-Save accept reversible for free (nothing was written) | This plan; research/features.md §5 | Mirror `ImportViewModel.onUndoStubCreation`/`undoBuffer` | requirements.md's Must-Have list doesn't request it; ADR-004(Import)'s "undo is the safety net" rationale applied to an *immediate* write, which capture never does before Save — logged as an explicit Unresolved Question, not silently dropped |

---

## Migration Plan

N/A. No SQLDelight schema or data migration — `LlmFeature.TOPIC_ENRICHMENT` is a new enum case
consumed by `LlmSettings.getSelectedProviderId(feature)`, which already reads/writes a
namespaced string key (`llm.feature.topic_enrichment.provider_id`) that simply doesn't exist
yet for any installed user; its absence already defaults correctly to `null` ("Auto") with no
migration step required.

## Observability Plan

- **Logs**: `CaptureEnrichmentCoordinator` gets its own `Logger("CaptureEnrichmentCoordinator")`
  instance (mirrors `PageNameIndex`'s per-class `Logger` convention,
  `domain/PageNameIndex.kt:45`). Log at `debug` level when `resolveForSave` falls back to raw
  text (matcher-not-ready timeout) and when `createAcceptedStubPages` skips a term because
  `getPageByName` already found it — both are expected-and-silent-to-the-user paths that should
  still be diagnosable from a device log pull.
- **Metrics**: None added. This repo has no client-side telemetry/metrics pipeline for
  feature-level events outside the existing performance/SLO system
  (`performance/PerfExporter`), which is scoped to graph-load/query performance, not
  appropriate for a single-capture enrichment pass. Matches requirements' "no new
  runtimes/dependencies" constraint.
- **Alerts**: None — no server/alerting infrastructure exists for this mobile client.

## Risk Control

- **Feature flag**: None required. The feature is self-gating by construction: no matcher ready
  → raw-text save (today's exact behavior); no LLM provider configured/selected → local-only
  chips via `NoOpTopicEnricher`; both present → full enrichment. This graceful degradation *is*
  the flag. `LlmFeature.TOPIC_ENRICHMENT`'s `DISABLED_SENTINEL` path exists in the code for
  forward-compatibility with a future Settings UI entry, but no such UI entry ships in v1
  (out of scope per requirements.md).
- **Rollback procedure**: Revert the Phase 2 (`CaptureViewModel`) and Phase 3 (`CaptureActivity`)
  commits/PR. Phase 1's `CaptureEnrichmentCoordinator` and Phase 3's extracted
  `TopicSuggestionChip` are additive and self-contained — safe to leave in place even if Phase
  2/3 wiring is reverted (unused code, zero runtime effect on `ImportScreen` or anything else).
  In practice this ships as one PR per requirements' scope, so rollback is a single revert.
- **Staged rollout**: N/A — solo-user personal app (per requirements.md Stakeholders section);
  no canary/staged-rollout mechanism exists or is warranted.

## Unresolved Questions

- [ ] Should capture-tier LLM enrichment get its own on/off Settings toggle (like
      `TagSettings.isLlmTierEnabled()`), or always attempt "Auto" resolution whenever
      `LlmFeature.TOPIC_ENRICHMENT` has an available provider? — blocks Story 1.2.2 — owner:
      Tyler (product call; this plan defaults to "always Auto, no dedicated toggle in v1").
- [ ] Should `PageNameIndex` be hoisted to `GraphManager`/graph scope so `StelekitViewModel` and
      `CaptureViewModel` share one warm instance instead of each cold-building their own? —
      explicitly out of scope for this plan (see Alternative **C** above) — owner: Tyler, as a
      candidate follow-up item, not a blocker for this ship.
- [ ] Undo-after-accept is **not** built in v1 (see Pattern Decisions table's last row and
      ADR-002's consequences) — confirm this reading of requirements.md (Must-Have list omits
      it) is correct before implementation starts — owner: Tyler.
- [ ] Cold-start "graph not ready yet" (`SteleKitApplication.graphManager` still
      async-initializing) vs. "matcher timed out" currently degrade identically (raw-text save,
      no chips) per `research/ux.md`'s error-UX table — confirm no distinct UX treatment is
      desired for the cold-start case — owner: Tyler.
- [ ] Should `ClaudeTopicEnricher` be renamed to something provider-neutral (e.g.
      `LlmTopicEnricher`) now that ADR-003 establishes it's already provider-agnostic
      internally? — out of scope here (touches `ImportViewModel`/`ScreenRouter.kt`) — owner:
      Tyler, candidate follow-up.

## Dependency Visualization

```
Phase 1: Domain/Coordinator (kmp commonMain — testable in businessTest)
├─ Epic 1.1 CaptureEnrichmentCoordinator scaffold + matcher acquisition
│   Story 1.1.1 (state/skeleton) ──► Story 1.1.2 (debounced scan + resolveForSave)
├─ Epic 1.2 LLM enhancement tier               [depends on 1.1.2]
│   Story 1.2.1 (fire-and-forget enrichment) ──► Story 1.2.2 (provider resolution, ADR-003)
└─ Epic 1.3 Suggestion lifecycle + dedup guard [depends on 1.1.2]
    Story 1.3.1 (accept/dismiss) ──► Story 1.3.2 (live-DB dedup gate) ──► Story 1.3.3 (cancellation)
        │
        ▼
Phase 2: Android Wiring (androidApp)            [depends on all of Phase 1]
├─ Epic 2.1 CaptureViewModel integration
│   Story 2.1.1 (construct coordinator) ──► Story 2.1.2 (performSave uses it) ──► Story 2.1.3 (expose enrichmentState)
├─ Epic 2.2 Race-condition guards                [depends on 2.1.2]
│   Story 2.2.1 (Save reads post-accept state) ──► Story 2.2.2 (no async write to race) ──► Story 2.2.3 (teardown)
└─ Epic 2.3 Post-save accept handling            [depends on 2.1.2; UI trigger arrives via Phase 3's Story 3.2.2 post-save gate]
    Story 2.3.1 (post-save accept creates page + folds link via second write)
        │
        ▼
Phase 3: UI — Suggestion Chip Tray              [depends on Phase 2]
├─ Epic 3.1 Shared chip extraction (independent — can start any time)
│   Story 3.1.1
└─ Epic 3.2 CaptureScreen tray                   [depends on 3.1.1 and 2.1.3]
    Story 3.2.1 (tray container) ──► Story 3.2.2 (gate onSaved + accessibility)
        │
        ▼
Phase 4: Testing & Verification                 [interleaved with, and closing out, Phases 1-3]
├─ Epic 4.1 Coordinator regression suite (businessTest) — pairs with Phase 1
└─ Epic 4.2 CaptureViewModel/CaptureActivity verification — pairs with Phase 2/3
```

Epic 3.1 has no dependency on Phase 1/2 and may be implemented in parallel with any of them.

---

## Phase 1: Domain & Coordinator Layer

### Epic 1.1: CaptureEnrichmentCoordinator scaffold + matcher acquisition
**Goal**: Stand up the new commonMain collaborator with a per-instance `PageNameIndex` and the
`EnrichmentState` shape, unit-testable with zero Android dependency.

#### Story 1.1.1: Coordinator owns a per-instance PageNameIndex and EnrichmentState
**As a** capture-sheet user, **I want** the app to start building a page-name matcher for my
graph as soon as the coordinator exists, **so that** by the time I finish typing a note, the
matcher has the best chance of being ready.
**Acceptance Criteria**:
- A newly-constructed `CaptureEnrichmentCoordinator` starts in `EnrichmentState.Idle` with a
  `null` matcher, and does not block its constructor call on anything.
  - *Given* a `CaptureEnrichmentCoordinator(pageRepository = fakeRepo, coroutineScope =
    testScope)` is constructed, *When* `state.value` is read immediately (no `advanceTimeBy`),
    *Then* it equals `EnrichmentState.Idle` and `matcher.value` is `null`.
- `close()` cancels the coordinator's owned scope when no external scope was supplied.
  - *Given* a coordinator constructed with `coroutineScope = null` (owned-scope path), *When*
    `close()` is called, *Then* a subsequent `onTextChanged("x")` call does not throw but also
    schedules no further work (owned scope is cancelled).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`,
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinatorTest.kt`

##### Task 1.1.1a: Define EnrichmentState (~3 min)
- Add `sealed interface EnrichmentState { data object Idle; data object Scanning; data class
  Ready(linkedText: String, matchedPageNames: List<String>, topicSuggestions:
  List<TopicSuggestion> = emptyList(), isEnhancing: Boolean = false, sourceTextHash: Int); data
  object TimedOut }` at the top of the new file, per the Domain Glossary shape.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`

##### Task 1.1.1b: Coordinator skeleton + owned-scope pattern (~5 min)
- `class CaptureEnrichmentCoordinator(private val pageRepository: PageRepository, private val
  topicEnricher: TopicEnricher = NoOpTopicEnricher(), private val scanDispatcher:
  CoroutineDispatcher = Dispatchers.Default, private val localScanBudgetMs: Long = 500L,
  coroutineScope: CoroutineScope? = null)`.
- `private val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`; `private val
  scope = coroutineScope ?: ownedScope`; `fun close() = ownedScope.cancel()` — mirror
  `ImportViewModel.kt:125-133` exactly (only cancel the owned scope, never a caller-supplied
  one).
- `private val pageNameIndex = PageNameIndex(pageRepository, scope)`; `val matcher:
  StateFlow<AhoCorasickMatcher?> get() = pageNameIndex.matcher`.
- `private val _state = MutableStateFlow<EnrichmentState>(EnrichmentState.Idle)`; `val state:
  StateFlow<EnrichmentState> = _state.asStateFlow()`.
- Files: same file as 1.1.1a.

##### Task 1.1.1c: Coordinator skeleton unit tests (~4 min)
- Test initial `Idle` state and `null` matcher (no `advanceTimeBy`).
- Test `close()` on the owned-scope path doesn't throw on a subsequent call.
- Use a minimal fake `PageRepository` (check `kmp/src/businessTest` or `commonTest` for an
  existing in-memory/fake `PageRepository` test double before writing a new one — reuse if
  present).
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinatorTest.kt`

#### Story 1.1.2: Debounced local-heuristic scan with time-boxed save-time fallback
**As a** capture-sheet user, **I want** existing-page mentions in my note auto-linked without
ever waiting for it, **so that** Save always feels instant even on a cold-started app.
**Acceptance Criteria**:
- A completed background scan produces `Ready` state with auto-linked text and local
  suggestions.
  - *Given* `onTextChanged("Check out Kubernetes")` is called against a coordinator whose
    matcher already contains a page named `"Kubernetes"`, *When* the test advances virtual time
    past the 300ms debounce, *Then* `state.value` is `EnrichmentState.Ready` with `linkedText ==
    "Check out [[Kubernetes]]"` and `matchedPageNames == listOf("Kubernetes")`.
- `resolveForSave` falls back to raw text when the matcher hasn't resolved within budget.
  - *Given* a coordinator whose `matcher.value` is still `null` (no `getPageNameEntries()`
    emission has landed yet), *When* `resolveForSave("Some new note")` is called, *Then* it
    returns `"Some new note"` unchanged within `localScanBudgetMs` (500ms) and does not throw.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`

##### Task 1.1.2a: onTextChanged debounce + scanJob cancellation (~5 min)
- `private var scanJob: Job? = null`; `fun onTextChanged(text: String)`: `scanJob?.cancel()`; if
  `text.isBlank()`, reset to `EnrichmentState.Idle` and return; else set `EnrichmentState
  .Scanning` and `scanJob = scope.launch { delay(300); val matcher = matcher.value ?: return
  @launch; try { runScan(text, matcher) } catch (e: CancellationException) { throw e } catch (e:
  Throwable) { logger.debug("scan failed, falling back to unlinked text: ${e.message}");
  _state.value = EnrichmentState.Idle } }` — mirror `ImportViewModel.onRawTextChanged`
  (`ImportViewModel.kt:174-195`)'s debounce/cancellation shape, but — per
  adversarial-review.md's Blocker #2 — catch `Throwable`, not the narrower `Exception` set
  `ImportViewModel`'s own coroutines catch, always re-throwing `CancellationException` first. A
  matcher-rebuild `OutOfMemoryError` on an 8k+ page graph (the same OOM class
  `PageNameIndex.kt:52-56` already documents) must degrade to the unlinked-text fallback, not
  escape uncaught and kill the Android process (mandatory, not a stretch goal — see Task 2.2.3a).
- Files: same file.

##### Task 1.1.2b: runScan populates Ready state (~4 min)
- `private suspend fun runScan(text: String, matcher: AhoCorasickMatcher)`: fetch
  `existingNames` via `pageRepository.getPageNameEntries().first().getOrNull()?.map{it.name}
  ?.toSet() ?: emptySet()` (names-only projection, per `CLAUDE.md`'s bounded-reads rule); run
  `withContext(scanDispatcher) { ImportService.scan(text, matcher, existingNames) }`; update
  `_state.value = EnrichmentState.Ready(linkedText = result.linkedText, matchedPageNames =
  result.matchedPageNames, topicSuggestions = result.topicSuggestions, sourceTextHash =
  text.hashCode())`; then call `launchLlmEnrichment(text, result.topicSuggestions)` (Story
  1.2.1).
- Files: same file.

##### Task 1.1.2c: resolveForSave (~5 min)
- `suspend fun resolveForSave(text: String): String`: if `(_state.value as?
  EnrichmentState.Ready)?.sourceTextHash == text.hashCode()`, return that `Ready.linkedText`
  (background scan already valid for this exact text — no rescan). Else `val matcher =
  withTimeoutOrNull(localScanBudgetMs) { matcher.filterNotNull().first() } ?: return text`; run
  the same scan as `runScan` synchronously, update state, return `result.linkedText`. Wrap the
  whole body so any unexpected exception (matcher build `Throwable`, scan exception) also falls
  back to `text` — never let `resolveForSave` throw.
- Files: same file.

##### Task 1.1.2d: Story 1.1.2 unit tests (~5 min)
- Test the two ACs above verbatim, plus: a `Ready` state whose `sourceTextHash` does **not**
  match the text passed to `resolveForSave` (user kept typing after the background scan
  finished) triggers a fresh scan rather than returning stale `linkedText`.
- Files: `CaptureEnrichmentCoordinatorTest.kt`

---

### Epic 1.2: LLM enhancement tier
**Goal**: Best-effort LLM suggestion enrichment that never gates `resolveForSave`.

#### Story 1.2.1: Fire-and-forget LLM enrichment pass
**As a** user with an LLM provider configured, **I want** AI-enhanced suggestions to appear if
they finish in time, **so that** I get better suggestions without ever waiting for them.
**Acceptance Criteria**:
- A configured, non-`NoOp` `topicEnricher` runs after the local scan completes and merges into
  state if the text hasn't changed.
  - *Given* a coordinator built with a `topicEnricher` that returns
    `listOf(TopicSuggestion("Rust", 0.9f, AI_ENHANCED))` after a short delay, *When*
    `onTextChanged("Learning Rust")` completes its local scan and the enrichment coroutine
    finishes before the text changes again, *Then* `state.value` (`Ready`) eventually contains a
    `TopicSuggestion` with `source == AI_ENHANCED` and `isEnhancing == false`.
- A stale enrichment result (text changed while the LLM call was in flight) is discarded.
  - *Given* the enrichment coroutine is still running for text `"A"`, *When* `onTextChanged("B")`
    is called before it resolves, *Then* the eventual (discarded) `"A"`-based result does not
    overwrite `"B"`'s suggestions — verified by asserting `state.value`'s `Ready.linkedText`
    (or `sourceTextHash`) reflects `"B"`, not `"A"`, after both coroutines settle.
- `resolveForSave`'s return value is never gated on this coroutine finishing.
  - *Given* `topicEnricher.enhance(...)` is a suspend function that never returns (simulated with
    `awaitCancellation()`), *When* `resolveForSave(text)` is called after the local scan has
    already produced a `Ready` state, *Then* `resolveForSave` still returns promptly (the local
    `linkedText`), unaffected by the hung LLM call.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`

##### Task 1.2.1a: launchLlmEnrichment (~5 min)
- `private fun launchLlmEnrichment(text: String, localSuggestions: List<TopicSuggestion>)`: if
  `topicEnricher is NoOpTopicEnricher`, return immediately (no coroutine launched, no
  `isEnhancing` flip — matches `ImportViewModel.runScan`'s guard, `ImportViewModel.kt:243`).
  Else set `isEnhancing = true` on the current `Ready` state, `val textHash = text.hashCode()`,
  `scope.launch { try { withTimeout(8_000) { val enriched = topicEnricher.enhance(text,
  localSuggestions); if ((_state.value as? Ready)?.sourceTextHash != textHash) return@withTimeout;
  _state.update { (it as Ready).copy(topicSuggestions = merge(...), isEnhancing = false) } } }
  catch (e: TimeoutCancellationException) { ...isEnhancing = false... } catch (e:
  CancellationException) { throw e } catch (e: Throwable) { ...isEnhancing = false, log... } }` —
  mirror `ImportViewModel.runScan`'s Coroutine 2 (`ImportViewModel.kt:242-267`)'s shape and
  stale-result discard-by-hash check, but — per adversarial-review.md's Blocker #2 — widen the
  final catch clause from `Exception` to `Throwable` (re-throwing any non-timeout
  `CancellationException` first, so normal structured-concurrency cancellation still propagates),
  so an OOM-class `Throwable` from the enrichment call resets `isEnhancing` to `false` instead of
  escaping uncaught and killing the Android process. Mandatory, not a stretch goal — see Task
  2.2.3a.
- Files: same file.

##### Task 1.2.1b: merge helper (~3 min)
- Reuse `ImportViewModel`'s `mergeEnrichedSuggestions` shape (append net-new AI items, update
  confidence for overlapping terms, never re-show dismissed terms) as a private function in the
  coordinator — do not import it from `ImportViewModel` (different module boundary/visibility);
  a small private duplicate of this ~15-line pure function is acceptable since it has no other
  dependencies.
- Files: same file.

##### Task 1.2.1c: Story 1.2.1 unit tests (~5 min)
- Test all three ACs above using a fake `TopicEnricher` (`fun interface`, trivially fakeable) and
  `TestScope`/`StandardTestDispatcher` for virtual-time control (mirror
  `ImportViewModelTest`'s existing time-control pattern if one exists — check before writing a
  new harness).
- Files: `CaptureEnrichmentCoordinatorTest.kt`

#### Story 1.2.2: CaptureViewModel resolves the configured LLM provider without a new adapter class
**As a** user who has configured an Anthropic/OpenAI/on-device provider, **I want** capture
enrichment to use that same provider, **so that** I don't have to configure anything twice.
**Acceptance Criteria**:
- "Auto" resolves to the first available provider for the new feature.
  - *Given* `llmSettings.getSelectedProviderId(LlmFeature.TOPIC_ENRICHMENT) == null` and
    `llmProviderRegistry.availableForFeature(LlmFeature.TOPIC_ENRICHMENT)` returns
    `[claudeProvider]`, *When* `CaptureViewModel` resolves its enricher, *Then* it constructs
    `ClaudeTopicEnricher(claudeProvider.formatter)` (per ADR-003 — **not** a new adapter class)
    and passes it into the `CaptureEnrichmentCoordinator` it builds.
- No provider configured means zero network/on-device calls.
  - *Given* `availableForFeature(LlmFeature.TOPIC_ENRICHMENT)` returns `emptyList()`, *When*
    `CaptureViewModel` resolves its enricher, *Then* it uses `NoOpTopicEnricher()` and the
    coordinator's `launchLlmEnrichment` never launches a coroutine for any capture in that
    session (per Task 1.2.1a's `is NoOpTopicEnricher` early-return).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmFeature.kt`,
`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 1.2.2a: Add TOPIC_ENRICHMENT enum case (~2 min)
- `enum class LlmFeature { VOICE_FORMATTING, TAG_SUGGESTION, GRAPH_EDIT_SYNTHESIS,
  TOPIC_ENRICHMENT }`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmFeature.kt`

##### Task 1.2.2b: Pure selection function (~4 min)
- `internal fun resolveEnricherProvider(selectedId: String?, available: List<LlmProvider>,
  find: (String) -> LlmProvider?): LlmProvider?` — extracted, side-effect-free branch logic:
  `DISABLED_SENTINEL -> null; null -> available.firstOrNull(); else -> find(selectedId)` —
  mirrors `App.kt:1107-1116`'s inline `when` but as a standalone testable function (this is what
  Task 1.2.2c unit-tests, without needing a real registry).
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt` (private
  top-level function in the same file, or a small file-local object).

##### Task 1.2.2c: Wire registry construction + resolveTopicEnricher (~5 min)
- In `CaptureViewModel`: lazily build `llmCredentialStore`/`llmSettings`/`llmProviderRegistry`
  once (via `buildLlmProviderRegistry(LlmCredentialStore(CredentialStore()),
  LlmSettings(PlatformSettings()))`, matching `App.kt:488-499`'s construction exactly — no
  shared instance needed, this is a composition root like every other consumer).
- `private suspend fun resolveTopicEnricher(): TopicEnricher`: call
  `resolveEnricherProvider(llmSettings.getSelectedProviderId(LlmFeature.TOPIC_ENRICHMENT),
  llmProviderRegistry.availableForFeature(LlmFeature.TOPIC_ENRICHMENT), llmProviderRegistry
  ::find)`, then `?.let { ClaudeTopicEnricher(it.formatter) } ?: NoOpTopicEnricher()`.
- Files: `CaptureViewModel.kt`

##### Task 1.2.2d: Unit test for resolveEnricherProvider (~3 min)
- Test all three branches (`DISABLED_SENTINEL`, `null`/Auto, explicit id) with fake `LlmProvider`
  instances — no real registry/network needed.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` (new; see also
  Story 4.2.1)

---

### Epic 1.3: Suggestion lifecycle & duplicate-stub guard
**Goal**: Accept/dismiss mutate memory only (ADR-002); stub creation is gated by a live DB read
(never a stale `PageNameIndex` snapshot).

#### Story 1.3.1: Accept/dismiss mutate in-memory state only
**As a** user, **I want** tapping Accept on a chip to instantly show the link in my note, **so
that** I can see what I'm about to save without any write happening yet.
**Acceptance Criteria**:
- Accepting folds the wiki link into `linkedText` immediately, with zero DB writes.
  - *Given* `state.value` is `Ready` with `topicSuggestions = [TopicSuggestion("Kubernetes",
    0.8f, LOCAL, accepted = false)]` and `linkedText = "Learning Kubernetes"`, *When*
    `onSuggestionAccepted("Kubernetes")` is called, *Then* `state.value`'s matching suggestion
    has `accepted == true`, `linkedText == "Learning [[Kubernetes]]"`, and a fake `PageSaver`
    injected into the coordinator records zero `save()` calls.
- Dismissing removes a term from consideration permanently for that capture.
  - *Given* the same starting state, *When* `onSuggestionDismissed("Kubernetes")` is called,
    *Then* the matching suggestion has `dismissed == true`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`

##### Task 1.3.1a: onSuggestionAccepted / onSuggestionDismissed (~5 min)
- Mirror `ImportViewModel.onSuggestionAccepted`/`onSuggestionDismissed`
  (`ImportViewModel.kt:309-331`) exactly: `_state.update { current -> (current as?
  Ready)?.let { it.copy(topicSuggestions = it.topicSuggestions.map { s -> if (s.term == term)
  s.copy(accepted = true) else s }, linkedText = ImportService.insertWikiLinks(it.linkedText,
  listOf(term))) } ?: current }` for accept; analogous `dismissed = true` (no `linkedText`
  change) for dismiss. No-op if `state.value` isn't `Ready`.
- Files: same file.

##### Task 1.3.1b: Unit tests (~4 min)
- Both ACs above, plus: calling `onSuggestionAccepted` then never calling
  `createAcceptedStubPages`/`resolveForSave`'s save path results in a fake `PageSaver` recording
  zero calls (proves ADR-002's "no write at accept time" property directly).
- Files: `CaptureEnrichmentCoordinatorTest.kt`

#### Story 1.3.2: Stub-page creation is gated by a live DB read, not a snapshot
**As a** user who fires two rapid captures that both suggest the same new page, **I want** only
one stub page created, **so that** my graph doesn't accumulate duplicate pages.

*Terminology note*: `research/pitfalls.md` describes this as a check "at accept-time." Under
ADR-002 (fold-before-save-only), accepting a chip never writes — so "the moment the write
actually happens" is inside `createAcceptedStubPages`, called once from `performSave()`, exactly
where `ImportViewModel.confirmImport()` (not `onSuggestionAccepted`) does its own live check.
This story implements the live-read gate at that write moment, which is the correct reading of
the pitfall (the danger it names is a *stale-snapshot* create-vs-skip decision, not a specific
UI gesture) and is consistent with the reference implementation it cites.
**Acceptance Criteria**:
- A single coordinator creates a stub page for an accepted term that doesn't yet exist.
  - *Given* `state.value` is `Ready` with an accepted `TopicSuggestion("Kubernetes", ...)`, and a
    fake `PageRepository.getPageByName("Kubernetes")` currently returns `null`, *When*
    `createAcceptedStubPages(pageSaver = fakePageSaver, graphPath = "/graph")` is called, *Then*
    `fakePageSaver` records exactly one `save()` call for a `Page` named `"Kubernetes"`.
- Two independent coordinators sharing the same underlying repository do not double-create.
  - *Given* two separate `CaptureEnrichmentCoordinator` instances (A and B, simulating two
    separate `CaptureActivity` launches) both have an accepted `"Kubernetes"` suggestion, and
    both are backed by the **same** fake `PageRepository`/`PageSaver` pair, *When*
    `A.createAcceptedStubPages(...)` is called and completes (its `PageSaver` fake updates the
    shared fake repository's backing map), *Then* a subsequent
    `B.createAcceptedStubPages(...)` call's live `getPageByName("Kubernetes")` read finds A's
    page and does **not** call `pageSaver.save` a second time.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`

##### Task 1.3.2a: createAcceptedStubPages (~5 min)
- `suspend fun createAcceptedStubPages(pageSaver: PageSaver, graphPath: String): List<Page>`:
  read `(state.value as? Ready)?.topicSuggestions?.filter { it.accepted } ?: emptyList()`; for
  each, `val existing = pageRepository.getPageByName(suggestion.term).first().getOrNull()`; if
  `null`, build a `Page(uuid = PageUuid(UuidGenerator.generateV7()), name = suggestion.term,
  createdAt = now, updatedAt = now)`, `pageSaver.save(stubPage, emptyList(), graphPath)`, collect
  into the returned list — mirror `ImportViewModel.confirmImport()`'s stub-creation loop
  (`ImportViewModel.kt:403-418`) line-for-line, reusing the existing `PageSaver` fun interface
  from `ui/screens/ImportViewModel.kt` (no new saver abstraction).
- Files: same file. Import `dev.stapler.stelekit.ui.screens.PageSaver` (existing type).

##### Task 1.3.2b: Unit tests for both ACs (~5 min)
- Exactly the two ACs above. The second (two-coordinator) test is the direct regression test for
  `research/pitfalls.md` finding #5.
- Files: `CaptureEnrichmentCoordinatorTest.kt`

#### Story 1.3.3: Cancellation discipline for rapid re-typing
**As a** user who keeps typing while a scan is in progress, **I want** only my latest text to
ever produce suggestions, **so that** I never see chips for text I've already changed.
**Acceptance Criteria**:
- A newer `onTextChanged` call cancels the prior scan before starting its own.
  - *Given* `onTextChanged("first draft")` has started its debounce, *When*
    `onTextChanged("second draft")` is called before 300ms elapses, *Then*, after virtual time
    fully advances, `state.value` (`Ready`) has `sourceTextHash == "second draft".hashCode()` —
    the first scan's result is never observed.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`
(no production code change beyond Task 1.1.2a's `scanJob?.cancel()`, which already covers this —
this story is a dedicated regression test for that behavior under concurrent/rapid input)

##### Task 1.3.3a: Regression test using virtual time (~4 min)
- Using `StandardTestDispatcher`/`TestScope`, call `onTextChanged` twice in quick succession
  (before advancing time), then `advanceUntilIdle()`; assert only the second text's scan result
  is ever reflected in `state.value`.
- Files: `CaptureEnrichmentCoordinatorTest.kt`

---

## Phase 2: Android Wiring

### Epic 2.1: CaptureViewModel integration
**Goal**: `CaptureViewModel` becomes a thin wrapper: build the coordinator once per graph,
forward text/save events, expose `enrichmentState`.

#### Story 2.1.1: CaptureViewModel constructs its coordinator lazily, once per active graph
**As a** developer, **I want** exactly one `CaptureEnrichmentCoordinator` per capture session,
**so that** the matcher doesn't rebuild on every keystroke or every save attempt.
**Acceptance Criteria**:
- The coordinator is built once and reused across subsequent calls for the same graph.
  - *Given* `CaptureViewModel.updateText("a")` is called (first call after
    `graphManager.getActiveRepositorySet()` resolves), *When* `updateText("ab")` is called again
    shortly after, *Then* both calls operate against the same `CaptureEnrichmentCoordinator`
    instance (verified via a test seam — see Task 2.1.1c — not by re-invoking `PageNameIndex`
    construction a second time).
- Before the graph resolves (cold start), text updates are still accepted locally but no
  coordinator work happens.
  - *Given* `graphManager.getActiveRepositorySet()` returns `null` (graph not yet loaded), *When*
    `updateText("hello")` is called, *Then* `captureText.value == "hello"` (unaffected) and no
    exception is thrown — enrichment is silently skipped for that keystroke.
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.1a: coordinatorFor lazy-build-once helper (~4 min)
- `private var coordinator: CaptureEnrichmentCoordinator? = null`; `private suspend fun
  coordinatorFor(repoSet: RepositorySet): CaptureEnrichmentCoordinator = coordinator ?:
  CaptureEnrichmentCoordinator(pageRepository = repoSet.pageRepository, topicEnricher =
  resolveTopicEnricher(), coroutineScope = viewModelScope).also { coordinator = it }` — the
  `coroutineScope = viewModelScope` argument is load-bearing (per ADR-004 / CLAUDE.md's
  coroutine-scope-ownership rule — never a `rememberCoroutineScope()`, and never left as the
  coordinator's own owned-scope in production, since `viewModelScope` must drive cancellation on
  `onCleared()`).
- Files: `CaptureViewModel.kt`

##### Task 2.1.1b: Wire updateText to forward into the coordinator, best-effort (~5 min)
- `fun updateText(text: String) { _captureText.value = text; val steleApp =
  getApplication<SteleKitApplication>(); val repoSet =
  steleApp.graphManager?.getActiveRepositorySet() ?: return; viewModelScope.launch {
  coordinatorFor(repoSet).onTextChanged(text) } }` — the early `return` on a `null` repoSet is
  the "graph not ready yet" fallback path from AC2 above; no exception, no state change beyond
  `_captureText`.
- Files: `CaptureViewModel.kt`

##### Task 2.1.1c: Expose enrichmentState + Robolectric test (~5 min)
- `private val _enrichmentState = MutableStateFlow<EnrichmentState>(EnrichmentState.Idle)`; `val
  enrichmentState: StateFlow<EnrichmentState> = _enrichmentState.asStateFlow()`; inside
  `coordinatorFor`'s `.also { }` block, additionally `viewModelScope.launch {
  it.state.collect { s -> _enrichmentState.value = s } }` so the ViewModel-level flow always
  mirrors whichever coordinator instance is live.
- Add a Robolectric test asserting a second `updateText` call after the first coordinator is
  built does not reset `enrichmentState` back to `Idle` (proves single-instance reuse indirectly,
  since a fresh `PageNameIndex` would reset the matcher/debounce timers).
- Files: `CaptureViewModel.kt`, `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

##### Task 2.1.1d: initializeText also gives the scan an early start (~4 min)
- **Self-review catch**: `initializeText(text)` (`CaptureViewModel.kt:47-51`, called from
  `CaptureActivity.onCreate`/`onNewIntent` with the share-sheet text) sets `_captureText.value`
  directly — it does **not** go through `updateText()`, which is the only method wired to
  `coordinator.onTextChanged` (Task 2.1.1b). Left as-is, a user who shares text and taps Save
  immediately (without typing anything first) never gets a background scan head start — the
  matcher has strictly less time to become ready than it would if scanning started at
  `onCreate`, working against the exact "start the scan as early as possible without blocking
  focus/save" principle `research/pitfalls.md` finding #1 argues for. Fix: extract the shared
  body of `updateText` (the `_captureText.value = text; ...forward to coordinator...` part) into
  a private `private fun applyText(text: String)`, and have both `updateText` and
  `initializeText` call it (`initializeText` only when it actually sets a non-empty value, per
  its existing idempotency guard).
- Files: `CaptureViewModel.kt`

#### Story 2.1.2: performSave() writes coordinator-resolved content and creates accepted stub pages
**As a** capture-sheet user, **I want** my saved note to already contain auto-links and any
accepted new-page links, **so that** I don't have to do a second pass in the main app.
**Acceptance Criteria**:
- Existing-page mentions are auto-linked in the persisted block.
  - *Given* captured text `"Meeting notes about Kubernetes"` and a graph containing a page named
    `"Kubernetes"` whose matcher has resolved within budget, *When* `save()` completes, *Then*
    the persisted `Block.content` is `"Meeting notes about [[Kubernetes]]"`.
- Timeout/no-matcher falls back to exactly today's raw save.
  - *Given* the coordinator's matcher is still `null` when `save()` is tapped (cold start,
    budget not yet elapsed), *When* `save()` completes, *Then* the persisted `Block.content`
    equals the raw typed text unchanged, and `saveState` still transitions to `Saved` (no error
    surfaced) — matches `research/ux.md`'s "silent fallback" table row.
- Accepting before Save creates the stub page and folds the link into the saved content.
  - *Given* the user tapped Accept on a `"Kubernetes"` suggestion chip before tapping Save, *When*
    `save()` completes, *Then* a `Page` named `"Kubernetes"` exists in the graph (created via
    `GraphWriter.savePage`, the same path `ImportViewModel.confirmImport()` uses) **and** the
    persisted `Block.content` contains `"[[Kubernetes]]"`.
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.2a: Insert resolveForSave into performSave (~4 min)
- Between the existing `existingBlocks` computation and `newBlock` construction
  (`CaptureViewModel.kt:83-98`), add: `val finalContent =
  coordinatorFor(repoSet).resolveForSave(text)`; change `content = text` to `content =
  finalContent` in the `Block(...)` constructor call. Bug 1 (`writeActor.saveBlock`) and Bug 8
  (`GraphWriter`/`startAutoSave`/`savePage`) mitigation blocks are otherwise untouched, per
  `research/architecture.md` §4's explicit ordering recommendation.
- Files: `CaptureViewModel.kt`

##### Task 2.1.2b: Create accepted stub pages before the journal flush (~5 min)
- Immediately before the existing `writer.savePage(page, existingBlocks + newBlock, graphPath)`
  call (`CaptureViewModel.kt:117`), add: `coordinatorFor(repoSet)
  .createAcceptedStubPages(pageSaver = PageSaver.from(writer), graphPath = graphPath)` — reusing
  the **same** `writer: GraphWriter` instance already constructed for the Bug 8 flush (do not
  construct a second `GraphWriter`). Order matters: stub pages (separate files) are created
  first, then the journal page's own block content (which now already contains the folded-in
  link) is flushed — mirrors `ImportViewModel.confirmImport()`'s stub-then-content order.
- Files: `CaptureViewModel.kt`

##### Task 2.1.2c: performSave regression coverage (~5 min)
- Cover all three ACs above — this can be satisfied either by a coordinator-level test (Story
  1.3.2 already covers the stub-creation half) plus a thin `performSave`-level Robolectric test
  (Story 4.2.1), or inline here if the harness from Story 4.2.1 is built first. Cross-reference,
  do not duplicate test bodies — see Epic 4.2.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 2.1.3: EnrichmentState exposed alongside, never inside, SaveState
**As a** developer, **I want** `SaveState`'s existing shape and every comparison site
untouched, **so that** this feature can't silently break `CaptureActivity`'s existing save-flow
logic.
**Acceptance Criteria**:
- `SaveState`'s declaration and all 4 existing comparison sites in `CaptureActivity.kt` (lines
  218-219, 234, 305, 310) compile and behave unchanged after this feature ships.
  - *Given* the diff for this feature, *When* `git diff` is inspected for
    `CaptureViewModel.kt`'s `sealed class SaveState { ... }` block, *Then* it shows zero changes
    to that block.
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.3a: Verification-only task (~2 min)
- After Stories 2.1.1-2.1.2 are implemented, `grep -n "SaveState" CaptureViewModel.kt
  CaptureActivity.kt` and confirm the `sealed class SaveState` block and its 4 existing
  comparison sites are byte-for-byte unchanged from `git diff` against the pre-feature baseline.
- Files: n/a (verification, no file changes expected)

---

### Epic 2.2: Race-condition guards
**Goal**: Close the specific races `research/pitfalls.md` flagged, using the fold-before-save
architecture (ADR-002) wherever it structurally already closes them, and explicit tests where it
doesn't.

#### Story 2.2.1: Save reads the coordinator's post-accept linked text, never a stale snapshot
**As a** user who accepts a chip and immediately taps Save, **I want** the accepted link to be
in my saved note, **so that** my explicit accept action is never silently lost.
**Acceptance Criteria**:
- Accept-then-immediate-Save (no delay) includes the accepted link.
  - *Given* text has been auto-linked and a new-page chip accepted via
    `onSuggestionAccepted("Terraform")` (synchronously updating `coordinator.state`'s
    `Ready.linkedText`), *When* `save()` is called in the same test body with no `delay()`
    between accept and save, *Then* the persisted `Block.content` contains `"[[Terraform]]"` —
    because `resolveForSave` (Task 1.1.2c) checks the *current* `Ready.linkedText`
    (`sourceTextHash`-matched) rather than re-deriving from the raw `_captureText.value`
    snapshot.
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.2.1a: Confirm performSave sources content from the coordinator, not a raw re-scan (~2 min)
- Verification task: re-read Task 2.1.2a's diff and confirm `resolveForSave(text)` — not a
  fresh, from-scratch `ImportService.scan(text, ...)` call bypassing coordinator state — is what
  `performSave` calls. (This is already true by construction from Task 1.1.2c/2.1.2a; this task
  is the explicit cross-check called for by this story.)
- Files: n/a (verification)

##### Task 2.2.1b: Regression test — accept then save, zero delay (~4 min)
- The AC above, exercised at the `CaptureViewModel`/Robolectric level (Story 4.2.1's harness) or
  the coordinator level with a `PageSaver`+`resolveForSave` pair called back-to-back in the same
  test body.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` or
  `CaptureEnrichmentCoordinatorTest.kt`

#### Story 2.2.2: No independent async write exists to race Dismiss/finish()
**As a** user who accepts a chip and then taps Dismiss without saving, **I want** nothing to be
written to my graph, **so that** "no silent writes, ever" holds even for this sequence.
**Acceptance Criteria**:
- Accept-without-save leaves the graph untouched.
  - *Given* `onSuggestionAccepted("Terraform")` is called and `save()` is **never** called, *When*
    the coordinator/ViewModel is torn down (simulating `finish()`/`onCleared()`), *Then* a fake
    `PageSaver`/`PageRepository` records zero writes for `"Terraform"` — because
    `createAcceptedStubPages` (Task 1.3.2a) is only ever invoked from inside `performSave()`
    (Task 2.1.2b), never from `onSuggestionAccepted`.
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt` (no new runtime
guard/lock needed — this is a structural property of ADR-002, verified by test, not enforced by
a new lock)

##### Task 2.2.2a: Regression test proving zero writes on accept-without-save (~4 min)
- The AC above. This is functionally the same assertion as Task 1.3.1b's third bullet, exercised
  one layer up (through `CaptureViewModel`, not just the coordinator) to catch any future
  regression where a developer wires a write into `updateText`/accept handling in
  `CaptureViewModel` itself.
- Files: `CaptureViewModelTest.kt`

##### Task 2.2.2b: Load-bearing comment (~2 min)
- Add a short code comment on `CaptureViewModel`'s suggestion-accept forwarding method (Story
  3.2.1 wires this) and on `CaptureEnrichmentCoordinator.onSuggestionAccepted`: "Do not add any
  write here — stub-page creation must stay inside performSave()/createAcceptedStubPages() only.
  See ADR-002."
- Files: `CaptureViewModel.kt`, `capture/CaptureEnrichmentCoordinator.kt`

#### Story 2.2.3: Coordinator teardown cancels background work cleanly
**As a** developer, **I want** in-flight scan/enrichment coroutines to be cancelled when the
capture sheet is destroyed, **so that** no coroutine leaks past the Activity's lifecycle.
**Acceptance Criteria**:
- The coordinator is always constructed with `viewModelScope`, not its own owned scope, in
  production.
  - *Given* `CaptureViewModel.coordinatorFor(repoSet)` (Task 2.1.1a), *When* the constructor call
    is inspected, *Then* `coroutineScope = viewModelScope` is passed explicitly (never omitted,
    which would default to the coordinator's internal owned scope and outlive `onCleared()`).
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.2.3a: Mandatory Throwable guard on the forwarding collector (~5 min)
- Confirm Task 2.1.1a's `coroutineScope = viewModelScope` argument is present (verification, not
  new code). Separately: `viewModelScope` (via `androidx.lifecycle`) attaches no
  `CoroutineExceptionHandler` by default. Per `CLAUDE.md`'s "Uncaught coroutine Throwables kill
  the process on Android" rule and adversarial-review.md's Blocker #2, this is **required**, not
  optional: wrap the `state.collect { }` forwarding launch (Task 2.1.1c) in a
  `try { ... } catch (e: CancellationException) { throw e } catch (e: Throwable) { logger.error
  ("enrichmentState forwarding failed", e) }` so a non-cancellation `Throwable` is logged and
  swallowed rather than propagating uncaught. This guard is in addition to — not a substitute
  for — the `catch (e: Throwable)` guards Tasks 1.1.2a and 1.2.1a add directly around `scanJob`'s
  scan body and `launchLlmEnrichment`'s coroutine; those two are the more likely OOM sites
  (matcher rebuild / `ImportService.scan()` on an 8k+ page graph) and must not rely on this
  collector-level guard alone to keep the process alive.
- Files: `CaptureViewModel.kt`

---

### Epic 2.3: Post-save accept handling
**Goal**: Close design/ux.md §8's flagged gap — Must-Have #3 in requirements.md implies an
"accept after save" case ("folds the new `[[wiki link]]` into the saved block content... if the
accept happens before save"), and this plan had no story for the "after save" branch. Implements
design/ux.md §8's recommended option (a): a post-save accept still creates the page and, because
the sheet is still open in the post-save "Done" gate (Story 3.2.2) with the same `writeActor`/
`GraphWriter` instance still live, folds the link into the already-persisted block via one small,
narrowly-scoped second write — rather than leaving the block permanently unlinked as ux.md's
baseline option (a) accepted as a v1 limitation.

#### Story 2.3.1: Accepting a chip after Save still creates the page and updates the saved block
**As a** user who is still looking at unresolved chips in the post-save "Done" state, **I want**
tapping Accept to actually create the page and link it in my already-saved note, **so that** my
explicit accept action after Save isn't silently weaker than the same action before Save.
**Acceptance Criteria**:
- Accepting a chip after `saveState` is `Saved` creates the stub page immediately, without a
  second Save tap.
  - *Given* `saveState.value` is `SaveState.Saved` and `enrichmentState` is `Ready` with an
    unaccepted `TopicSuggestion("Terraform", ...)`, *When* `onSuggestionAccepted("Terraform")` is
    called, *Then* `coordinator.createAcceptedStubPages(pageSaver, graphPath)` is invoked directly
    for that term (not deferred to a future `performSave()` call, since none is coming) and a
    `Page` named `"Terraform"` exists afterward.
- The already-persisted block is updated to contain the new link, via one narrowly-scoped second
  write — not the generic "retroactive post-save content edit" this plan's Pattern Decisions
  table rejected elsewhere. That rejection was about re-opening and re-editing a block at an
  arbitrary later time (reintroducing Bug 1's `ClosedSendChannelException` race); this case is
  different in kind, not just narrower: it fires only while the sheet is still open in the
  post-save "Done" gate, against the same `BlockUuid` and the same `writeActor`/`GraphWriter`
  instance `performSave()` already constructed and is still holding — no re-fetch of a stale
  block, no second `CaptureActivity` instance, no arbitrary time delay.
  - *Given* the block persisted by the original `performSave()` call still has its known
    `BlockUuid` and the sheet has not closed, *When* the post-save accept in the AC above
    completes, *Then* `writeActor.saveBlock` is called a second time with `content =
    ImportService.insertWikiLinks(currentContent, listOf("Terraform"))`, and the block read back
    afterward contains `"[[Terraform]]"`.
- A post-save stub-page-creation failure does not roll back or re-corrupt the already-saved block.
  - *Given* `createAcceptedStubPages` throws or returns without creating the page (disk
    full/permission error), *When* the post-save accept handler runs, *Then* the second
    `writeActor.saveBlock` call for the link fold is skipped (never fold a link to a page that
    wasn't actually created) and the block's prior content is left exactly as `performSave()`
    persisted it — mirrors the "no partial/stalled saves" guarantee from requirements.md.
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.3.1a: Branch onSuggestionAccepted on saveState (~5 min)
- In the forwarding method Task 3.2.1c adds, branch on `saveState.value`: if `Idle`/`Saving`,
  keep today's pre-save behavior (forward to `coordinator.onSuggestionAccepted(term)`, no write —
  ADR-002 unchanged); if `Saved`, launch `viewModelScope.launch { acceptAfterSave(term) }`
  instead.
- Files: `CaptureViewModel.kt`

##### Task 2.3.1b: acceptAfterSave — create page, then fold link into the saved block (~5 min)
- `private suspend fun acceptAfterSave(term: String)`: call
  `coordinatorFor(repoSet).createAcceptedStubPages(pageSaver = PageSaver.from(writer), graphPath =
  graphPath)` reusing the same `writer`/`repoSet` `performSave()` (Task 2.1.2b) already resolved
  and is still holding (do not re-fetch `getActiveRepositorySet()` or construct a new
  `GraphWriter`); only if that call reports the term as created (or already-existing via the
  live-DB dedup gate, Story 1.3.2), read the persisted block's current content, compute
  `ImportService.insertWikiLinks(currentContent, listOf(term))`, and call
  `writeActor.saveBlock(...)` a second time with the updated content. Wrap the whole body so any
  `Throwable` from either write degrades to "chip stays visible, nothing folded" rather than
  crashing the post-save "Done" state — same `catch (e: CancellationException) { throw e } catch
  (e: Throwable)` shape as Tasks 1.1.2a/1.2.1a/2.2.3a.
- Files: `CaptureViewModel.kt`

##### Task 2.3.1c: Unit/regression tests for all three ACs (~5 min)
- Robolectric-backed, mirroring Story 4.2.1's harness (`IN_MEMORY` `RepositorySet`): (1) post-save
  accept creates the page without a second Save tap; (2) the persisted block, read back, contains
  the folded link; (3) a forced `createAcceptedStubPages` failure leaves the block's prior content
  untouched.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

---

## Phase 3: UI — Suggestion Chip Tray

### Epic 3.1: Shared chip component extraction

#### Story 3.1.1: Promote TopicSuggestionChip to a shared, non-private composable
**As a** developer, **I want** `TopicSuggestionChip` reusable outside `ImportScreen.kt`, **so
that** `CaptureScreen` doesn't have to duplicate ADR-004's chip anatomy.
**Acceptance Criteria**:
- The confidence indicator is redesigned from a color-only dot to a shape-based glyph (solid =
  high ≥0.7, half-fill = medium 0.4–0.69, hollow ring = low 0.2–0.39), matching design/ux.md
  §4/§10 — this promotion is **not** a zero-visual-diff move; ADR-004's Import-screen chip and the
  new Capture-screen chip both pick up the accessible redesign simultaneously, since they share
  one promoted component (see Task 3.1.1c).
  - *Given* the promoted `TopicSuggestionChip` with `confidence = 0.5f` (medium tier), *When* it
    renders, *Then* the confidence glyph is a half-filled dot (ring + inner fill), not a
    solid-color-only dot, and the shape distinction remains legible in a desaturated/grayscale
    screenshot (UX Acceptance Criterion #13).
- Accept and dismiss touch targets each meet the ≥44×44dp minimum design/ux.md §10 and UX
  Acceptance Criterion #12 require (see Task 3.1.1d).
  - *Given* the promoted `TopicSuggestionChip`, *When* its accept region and dismiss control are
    measured, *Then* each is ≥44×44dp with ≥8dp gap between them, achieved via padding around the
    existing ~16–20dp visual glyphs so the chip's visual footprint does not grow.
- `ImportScreen`'s existing Roborazzi/compose tests are updated for the new glyph and touch
  targets (not left asserting the old color-only/undersized rendering), and `./gradlew ciCheck`
  passes after the move.
  - *Given* `ImportScreen.kt`'s existing Roborazzi/compose tests (if any target this composable
    directly or indirectly via `ImportScreen`), *When* `./gradlew ciCheck` is run after the
    move, *Then* they are updated to match the new rendering and pass.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportScreen.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopicSuggestionChip.kt` (new)

##### Task 3.1.1a: Create the shared file (~3 min)
- New file `ui/components/TopicSuggestionChip.kt`: move the `@Composable private fun
  TopicSuggestionChip(suggestion: TopicSuggestion, onAccepted: () -> Unit, onDismissed: () ->
  Unit)` body verbatim from `ImportScreen.kt:545-620`, dropping `private` (package-visible or
  public — match the file's other exported components' visibility convention). Adjust imports
  (`TopicSuggestion`, Material3 `Icon`/`IconButton`/`CircleShape`, `Icons.Default.Check/Close`).
- Files: `ui/components/TopicSuggestionChip.kt`

##### Task 3.1.1b: Remove the private copy and update the call site (~3 min)
- Delete lines 545-620 from `ImportScreen.kt`; add `import
  dev.stapler.stelekit.ui.components.TopicSuggestionChip`; no change needed at the call site
  (`TopicSuggestionTray`, `ImportScreen.kt:511`) beyond the import, since the call signature is
  unchanged.
- Files: `ImportScreen.kt`

##### Task 3.1.1c: Redesign confidence indicator — shape, not color alone (~5 min)
- Replace the existing color-only confidence dot with a shape-based glyph per design/ux.md §4's
  table: solid filled dot (≥0.7, high), half-filled dot — ring + inner fill (0.4–0.69, medium),
  hollow ring (0.2–0.39, low). Color may still be layered on top as reinforcement but must not be
  the sole differentiator (WCAG 1.4.1). Update the glyph's semantics/`contentDescription` to state
  the tier in words ("high confidence" / "medium confidence" / "low confidence") so TalkBack
  doesn't rely on the visual glyph alone (design/ux.md §10, UX Acceptance Criterion #13).
- Files: `ui/components/TopicSuggestionChip.kt`

##### Task 3.1.1d: Resize accept/dismiss touch targets to ≥44×44dp (~4 min)
- Wrap the existing ~20dp `IconButton` dismiss control and the tappable accept (dot+term) region
  in padding so each reaches a ≥44×44dp minimum tap target, per design/ux.md §4/§10 and UX
  Acceptance Criterion #12 — padding, not glyph size, provides the target, so the chip's visual
  footprint is unchanged. Keep ≥8dp gap between the two regions so a mis-tap near the boundary
  doesn't trigger the wrong action (design/ux.md §6). This is the shared component, so fixing it
  once here fixes the touch-target gap for both `ImportScreen` and `CaptureScreen` — see Task
  3.2.2c, which previously deferred this and now points here instead of excluding it.
- Files: `ui/components/TopicSuggestionChip.kt`

##### Task 3.1.1e: Verify no regression (~3 min)
- Run `./gradlew ciCheck` (or a narrower `bazel test //kmp:jvm_tests` if faster) after Tasks
  3.1.1a–d land, and confirm green — including any updated Roborazzi goldens for the new
  shape-based confidence glyph and resized touch targets. Document the command's output as the
  completion evidence for this task, per `CLAUDE.md`'s "no completion claim without proof" rule.
- Files: n/a (verification)

---

### Epic 3.2: CaptureScreen suggestion tray

#### Story 3.2.1: Compact tray container purpose-built for the bottom sheet
**As a** capture-sheet user, **I want** to see and act on suggestion chips without the sheet
turning into a full review screen, **so that** the sheet stays fast and uncluttered.
**Acceptance Criteria**:
- Undismissed suggestions render as a horizontal chip row; no Accept-All, no status badge.
  - *Given* `enrichmentState` is `EnrichmentState.Ready` with 2 undismissed `topicSuggestions`,
    *When* `CaptureScreen` composes, *Then* a `LazyRow` (or equivalent horizontal-scroll
    container) of `TopicSuggestionChip`s renders between the `OutlinedTextField` and the
    Dismiss/Save `Row`, with no "Accept All" button and no AI-status badge anywhere in the tray
    (per `research/ux.md`'s "diverge" list).
- Zero suggestions renders nothing (no placeholder text, no empty-state UI).
  - *Given* `enrichmentState` is `EnrichmentState.Idle`, `Scanning`, or a `Ready` with an empty
    (or fully dismissed) `topicSuggestions` list, *When* `CaptureScreen` composes, *Then* no
    tray-related composable draws any pixels (early `return` from the tray composable).
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 3.2.1a: CaptureSuggestionTray composable (~5 min)
- `@Composable private fun CaptureSuggestionTray(state: EnrichmentState, onAccepted: (String) ->
  Unit, onDismissed: (String) -> Unit) { val ready = state as? EnrichmentState.Ready ?: return;
  val visible = ready.topicSuggestions.filter { !it.dismissed }; if (visible.isEmpty()) return;
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(visible) { s ->
  TopicSuggestionChip(suggestion = s, onAccepted = { onAccepted(s.term) }, onDismissed = {
  onDismissed(s.term) }) } } }` — no header row, no "Suggested new pages (N)" label, no status
  badge, no 8-item cap/"Show more" (the sheet's bottom-sheet layout has no vertical scroll budget
  for pagination — a single horizontally-scrollable row is the whole tray).
- Files: `CaptureActivity.kt`

##### Task 3.2.1b: Wire into CaptureScreen's Column (~3 min)
- In `CaptureScreen`'s `Column` (`CaptureActivity.kt:267-324`), after the `OutlinedTextField`'s
  `Spacer(Modifier.height(12.dp))` (line 297) and before the Dismiss/Save `Row`, insert:
  `CaptureSuggestionTray(state = enrichmentState, onAccepted = viewModel::onSuggestionAccepted,
  onDismissed = viewModel::onSuggestionDismissed)` followed by a conditional `Spacer` (only if
  the tray actually rendered — acceptable to always emit a small spacer since an empty tray
  draws nothing and a doubled 8dp gap is visually negligible; simplest correct option, not worth
  a second task to conditionalize).
- Files: `CaptureActivity.kt`

##### Task 3.2.1c: CaptureViewModel forwarding methods (~3 min)
- Add `fun onSuggestionAccepted(term: String)` / `fun onSuggestionDismissed(term: String)` on
  `CaptureViewModel`, each forwarding to `coordinator?.onSuggestionAccepted(term)` /
  `?.onSuggestionDismissed(term)` (no-op if `coordinator` hasn't been built yet — cannot accept a
  chip that was never shown, so this null-safety is a formality, not a real runtime path).
- Files: `CaptureViewModel.kt`

#### Story 3.2.2: Gate onSaved()'s auto-dismiss on unresolved suggestions; accessibility
**As a** user who accepted or is still deciding on a chip when Save completes, **I want** the
sheet to stay open until I've had a chance to see the result, **so that** I don't miss what got
linked/created.
**Acceptance Criteria**:
- Unresolved suggestions keep the sheet open after Save.
  - *Given* `saveState` becomes `SaveState.Saved` while `enrichmentState` is `Ready` with at
    least one suggestion that is neither `accepted` nor `dismissed`, *When* `CaptureScreen`'s
    `LaunchedEffect(saveState, enrichmentState)` re-evaluates, *Then* `onSaved()` is **not**
    called automatically, and a "Done" button is visible.
- No unresolved suggestions behaves exactly as today.
  - *Given* `saveState` becomes `Saved` while `enrichmentState` has zero unresolved suggestions
    (empty, or all accepted/dismissed), *When* the effect re-evaluates, *Then* `onSaved()` is
    called immediately, matching pre-feature behavior byte-for-byte.
- Async-arriving chips don't steal focus from the text field.
  - *Given* the text field has active focus, *When* `enrichmentState` transitions from
    `Scanning` to `Ready` with suggestions (asynchronously, mid-keystroke), *Then* focus remains
    on the `OutlinedTextField` (no `LaunchedEffect` re-requests focus onto the tray).
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 3.2.2a: Update the save-state LaunchedEffect (~4 min)
- Change `LaunchedEffect(saveState) { ... }` (`CaptureActivity.kt:216-226`) to
  `LaunchedEffect(saveState, enrichmentState) { val hasUnresolved = (enrichmentState as?
  EnrichmentState.Ready)?.topicSuggestions?.any { !it.accepted && !it.dismissed } == true; when
  (val state = saveState) { is SaveState.Saved -> if (!hasUnresolved) onSaved(); is
  SaveState.Error -> { ...unchanged... }; else -> {} } }`.
- Files: `CaptureActivity.kt`

##### Task 3.2.2b: "Done" button for the post-save-with-pending-suggestions case (~4 min)
- In the Dismiss/Save `Row`, when `saveState == SaveState.Saved && hasUnresolved`, swap the
  Save `Button`'s content/action to a `Button(onClick = onSaved) { Text("Done") }` (Save has
  already succeeded at this point — the button's job changes from "commit" to "acknowledge and
  close").
- Files: `CaptureActivity.kt`

##### Task 3.2.2c: Accessibility — live region on the tray (~3 min)
- Add `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` to
  `CaptureSuggestionTray`'s `LazyRow` (or a wrapping `Box`), per `research/ux.md` §3's
  recommendation, so TalkBack announces new suggestions without interrupting in-progress typing
  or moving focus. Touch-target sizing for the chip's accept/dismiss regions is handled once, at
  the shared-component level, by Story 3.1.1's Task 3.1.1d — not duplicated here, since
  `CaptureSuggestionTray` composes the same promoted `TopicSuggestionChip` `ImportScreen` uses.
- Files: `CaptureActivity.kt`

##### Task 3.2.2d: Story 3.2.2 UI test (~5 min)
- A `jvmTest`/Roborazzi-style compose test (matching this repo's existing UX-test pattern, e.g.
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/transfer/QrTransferEntryPointsTest.kt`'s
  structure) asserting: (1) `saveState = Saved` + unresolved suggestion → `onSaved` not invoked,
  "Done" button present; (2) `saveState = Saved` + no unresolved suggestions → `onSaved` invoked.
- Files: a new test file under `androidApp/src/test/kotlin/dev/stapler/stelekit/` (Robolectric,
  since `CaptureScreen` lives in `androidApp`, not `kmp/commonMain`) — e.g.
  `CaptureScreenSuggestionGateTest.kt`.

##### Task 3.2.2e: One-shot haptic tick on silent auto-link (~3 min)
- Add a `LaunchedEffect(enrichmentState)` in `CaptureScreen` (alongside Task 3.2.2a's effect)
  that fires `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType
  .TextHandleMove)` exactly once per capture the instant `enrichmentState` becomes
  `EnrichmentState.Ready` with a non-empty `matchedPageNames` — the requirements.md Should-Have
  "visual/haptic distinction between matched-existing-page auto-links and new-page suggestion
  chips," implemented per design/ux.md §2's recommended one-shot-tick design. Guard with a
  `remember { mutableStateOf(false) }` "already ticked this capture" flag (reset when
  `enrichmentState` returns to `Idle`) so re-observing the same `Ready` emission doesn't re-fire.
  Reuses this codebase's existing `LocalHapticFeedback`/`HapticFeedbackType` precedent — no new
  haptic API introduced (see `ui/transfer/QrDecodeScreen.kt:74-75` and
  `ui/components/BlockItem.kt:256-269`). Per requirements.md's Should-Have framing and design/
  ux.md §2, this is optional polish: omitting it (e.g. on a haptics-disabled device) must not
  create a dead end or regress any other AC.
- Files: `CaptureActivity.kt`

---

## Phase 4: Testing & Verification

### Epic 4.1: Coordinator regression suite (businessTest)
**Goal**: Close out every timeout/fallback/no-provider/dedup scenario from the research docs
with a fast, Android-free test suite.

#### Story 4.1.1: Full timeout/fallback/no-provider matrix
**As a** developer, **I want** every degrade-to-today's-behavior path covered by a fast test,
**so that** a future change can't silently reintroduce a blocking save.
**Acceptance Criteria**:
- Each of the 5 branches below is independently asserted (already partially covered by Stories
  1.1.2/1.2.1/1.2.2 — this story is the consolidation/completion pass):
  1. Matcher never resolves → `resolveForSave` returns raw text.
  2. Matcher resolves within budget → `resolveForSave` returns linked text.
  3. No LLM provider (`NoOpTopicEnricher`) → suggestions are local-only, `isEnhancing` never
     becomes `true`.
  4. LLM provider configured and completes in time → suggestions include an `AI_ENHANCED`
     entry.
  5. LLM call throws or exceeds the 8s timeout → local suggestions are retained unchanged, no
     exception escapes the coordinator.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinatorTest.kt`

##### Task 4.1.1a–e: One test method per branch (~3 min each, ~15 min total)
- Fill any gap not already covered by Tasks 1.1.2d/1.2.1c. Reuse the fake `PageRepository`/
  `TopicEnricher` test doubles already built for those stories — do not create parallel fakes.
- Files: `CaptureEnrichmentCoordinatorTest.kt`

#### Story 4.1.2: End-to-end duplicate-stub-prevention test
**As a** developer, **I want** one test that exercises the full "two rapid captures, same
suggestion" scenario end-to-end, **so that** `research/pitfalls.md` finding #5 has a named,
permanent regression test.
**Acceptance Criteria**:
- Already specified as Story 1.3.2's second AC; this story adds the test if not already written
  there.
**Files**: `CaptureEnrichmentCoordinatorTest.kt`

##### Task 4.1.2a: Two-coordinator shared-repo test (~4 min)
- If not already satisfied by Task 1.3.2b, add it now. Name the test
  `` `two coordinators accepting the same suggestion create only one stub page`() ``, so it's
  discoverable as the pitfalls.md #5 regression test by name.
- Files: `CaptureEnrichmentCoordinatorTest.kt`

---

### Epic 4.2: CaptureViewModel/CaptureActivity verification

#### Story 4.2.1: CaptureViewModel save-path regression coverage
**As a** developer, **I want** `CaptureViewModel`'s first-ever test coverage to exist, **so
that** future changes to `performSave()` can't silently reintroduce a blocking or unlinked save.
**Acceptance Criteria**:
- A Robolectric-backed harness exercises `save()` end-to-end against a real, in-memory-backed
  `GraphManager`/`RepositorySet`.
  - *Given* a `CaptureViewModel` built against a test `GraphManager` using the `IN_MEMORY`
    repository backend (per `repository/RepositoryFactory.kt`'s existing backend enum) seeded
    with a page named `"Kubernetes"`, *When* `updateText("Meeting about Kubernetes")` then
    `save()` are called and the test advances past the debounce/scan, *Then* the persisted block
    (read back via the same in-memory `blockRepository`) contains `"[[Kubernetes]]"`.
  - *Given* the same harness but `save()` called immediately with no debounce wait, *When* the
    matcher hasn't resolved yet, *Then* the persisted block content is the raw text.
**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` (new)

##### Task 4.2.1a: Stand up the Robolectric + IN_MEMORY harness (~5 min)
- `@RunWith(RobolectricTestRunner::class)`; construct a `GraphManager` (or its `RepositorySet`
  directly, if `GraphManager`'s constructor requires more Android plumbing than is worth
  standing up here — prefer constructing a `RepositorySet` via `RepositoryFactory` with the
  `IN_MEMORY` backend directly and injecting it, over a full `GraphManager`, if
  `CaptureViewModel` can be adapted to accept one; otherwise use `ApplicationProvider
  .getApplicationContext()` plus a real `GraphManager` per this repo's other Robolectric
  precedents). Check `kmp/src/businessTest`/`androidUnitTest` for an existing
  `RepositorySet`-in-memory test-setup helper before writing a new one.
- Files: `CaptureViewModelTest.kt`

##### Task 4.2.1b: Test AC1 — auto-link existing page (~3 min)
- Files: `CaptureViewModelTest.kt`

##### Task 4.2.1c: Test AC2 — raw fallback, matcher not ready (~3 min)
- Files: `CaptureViewModelTest.kt`

##### Task 4.2.1d: Test accept-before-save creates stub page (~4 min)
- Exercises Story 2.1.2's third AC through the full `CaptureViewModel`, not just the coordinator.
- Files: `CaptureViewModelTest.kt`

#### Story 4.2.2: Manual verification pass
**As a** developer, **I want** a documented manual check for the paths Robolectric/JVM tests
cannot reach, **so that** the real share-sheet/widget/tile launch experience is verified at
least once before shipping.
**Acceptance Criteria**:
- N/A in Given-When-Then form — this is a manual checklist, not an automated test, because
  real Android share-sheet IPC, translucent-overlay first-frame timing, and physical
  focus/keyboard behavior cannot be exercised by Robolectric.
**Files**: none (checklist, not code)

##### Task 4.2.2a: Manual smoke check (~5 min, manual)
- Using the `run` skill or a physical/emulator device: share a URL/article snippet into
  SteleKit from a browser; confirm the sheet opens with the keyboard focused with no visible
  delay; confirm auto-link and/or chip tray appear if the local scan completes in time; confirm
  Save closes the sheet (or shows "Done" if suggestions are still unresolved); confirm a second
  rapid share behaves per today's `initializeText`-idempotent behavior (unaffected by this
  feature).
- Files: n/a
