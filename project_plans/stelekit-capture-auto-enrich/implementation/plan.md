# Implementation Plan: stelekit-capture-auto-enrich

**Feature**: Wire `CaptureActivity`'s share-to-capture flow through the existing `ImportService`/`PageNameIndex`/`TopicEnricher` pipeline so shared text is auto-linked and topic-suggestion chips are offered, without regressing capture's responsiveness or its existing save-path correctness guarantees.
**Date**: 2026-08-27
**Status**: Ready for implementation
**ADRs**: [ADR-001-graphmanager-owned-capture-coordinator](../decisions/ADR-001-graphmanager-owned-capture-coordinator.md), [ADR-002-post-save-second-write-scope-boundary](../decisions/ADR-002-post-save-second-write-scope-boundary.md)

---

## Step 0.5 — Alternatives considered (overall feature shape)

| Approach | Strength | Weakness |
|---|---|---|
| **A. New small `CaptureEnrichmentCoordinator` + `GraphManager`-owned Mutex/Deferred cache + `CaptureViewModel.SavedCaptureContext` for the post-save write** (chosen) | Composes only what capture needs; respects the module-dependency direction (`androidApp` → `kmp`, never the reverse) so the coordinator *must* live where `GraphManager` can return it; matches AC #8/#9 literally with no translation layer. | One new class, one new `GraphManager` method, one new `CaptureViewModel` field — more moving pieces than a single-file patch. |
| B. Inline everything into `CaptureViewModel` (build `PageNameIndex` directly there, guarded by a ViewModel-local `Mutex`, no coordinator class, no `GraphManager` change) | Fewest new types. | Duplicates lifecycle logic `GraphManager` already owns (`activeGraphJobs[graphId]`, graph-switch invalidation) inside a ViewModel whose lifetime is the `CaptureActivity` instance, not the graph — a `PageNameIndex` built this way cannot survive `CaptureActivity.finish()` even though a second capture 30 seconds later would rebuild the identical trie for the identical graph; also has no natural single per-graph cache if `MainActivity` and `CaptureActivity` are alive concurrently (PF-7). |
| C. Fork/subclass `ImportViewModel` for capture | Reuses `ImportViewModel`'s already-implemented scan/enrich coroutine bodies verbatim. | `ImportViewModel` takes `matcherFlow: StateFlow<AhoCorasickMatcher?>` as an *injected* dependency — it never solves AC #8's construction problem, which is upstream of everything `ImportViewModel` does. It also drags in `ImportState`'s full-screen-review-only surface (`urlInput`, `undoBuffer`, `pageName`, `activeTab`, ...), none of which the capture sheet has any use for. |

**Chosen: Approach A**, matching `research/architecture.md` Q1/Q2 and `research/build-vs-buy.md` §4's independent convergence. Rejected alternatives are also recorded in the Pattern Decisions table below.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `CaptureEnrichmentCoordinator` | New `commonMain` class binding one graph's `PageNameIndex` and a resolved `TopicEnricher` together for one capture session; exposes `scan()` (budget-bounded `ImportService.scan()` wrapper) and `enhance()` (timeout-bounded `TopicEnricher.enhance()` wrapper). Owns no write path. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt` |
| `GraphManager.getOrCreateEnrichmentCoordinator()` | Suspend method on `GraphManager` that returns the memoized `CaptureEnrichmentCoordinator` for the currently active graph, race-safely constructing one on first call. Returns `null` if there is no active graph/repository set/graph scope yet. | Mutex-guarded, memoized `Deferred`, keyed by `GraphId`, scoped to `activeGraphJobs[graphId]` |
| `CaptureViewModel.ScanState` | Sealed type replacing a nullable-`ScanResult` + boolean-flag design: `NotReady` (no usable scan yet — cold start, still debouncing, or budget exceeded) or `Ready(text, result)` (a `ScanResult` computed for the exact `text` it was scanned against). | Nested in `CaptureViewModel`, mirrors the existing `SaveState` nesting pattern in the same file |
| `CaptureViewModel.SavedCaptureContext` | Private data class retained on `CaptureViewModel` after a successful `performSave()`: `block`, `page`, `blocks`, `graphPath`, `graphId` (the `GraphId` `performSave()` wrote to), `writer` (the exact `GraphWriter` instance `performSave()` constructed), `writeActor`, `pageRepository`, `blockRepository` (the originating graph's repositories, captured at save time — never re-fetched from "the active graph" later). Enables AC #9's post-save second write without a new `GraphWriter`, and lets `acceptSuggestionPostSave()` detect a graph switch during the post-save window instead of silently reading/writing against the wrong graph. | Private, capture-local — not a general edit-after-save mechanism (ADR-002's job to bound); `graphId`/`pageRepository`/`blockRepository` close the graph-identity gap architecture-review.md and adversarial-review.md both flagged as a BLOCKER |
| `CaptureEnrichmentCoordinator.ScanOutcome` | Sealed type nested in `CaptureEnrichmentCoordinator`, replacing `scan()`'s nullable `ScanResult?` return: `MatcherNotReady` (matcher not built yet) / `TimedOut` (budget exceeded) / `Success(result: ScanResult)`. Distinguishes the two "no result" cases the old signature collapsed, so the "scan budget exceeded (debug)" log line in the Observability Plan is actually implementable. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt`; `CaptureViewModel.ScanState` still collapses both non-success cases to `NotReady` for save-time behavior (AC #4 unaffected) |
| `LlmFeature.CAPTURE_ENRICHMENT` | New enum entry on the existing `LlmFeature` sealed set (`VOICE_FORMATTING`, `TAG_SUGGESTION`, `GRAPH_EDIT_SYNTHESIS`), used to resolve an available `LlmProvider` for capture's opt-in tier via `LlmProviderRegistry.availableForFeature(...)`. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmFeature.kt` |
| `CaptureSuggestionChip` | Private `@Composable` in `CaptureActivity.kt` — a scaled-down `[confidence dot][term][×]` chip reusing `ImportScreen.kt`'s `TopicSuggestionChip` anatomy at reduced size, no "linked" persistence state, no Accept-All. | Rendered inside a `LazyRow`, capped at 4 visible |
| `acceptSuggestion(term, isPostSave)` | `CaptureViewModel` method handling both AC #2's pre-save accept (throwaway `GraphWriter.savePage` + fold into pending text) and AC #9's post-save accept (reuses `SavedCaptureContext`'s `writer`/`writeActor` for a second write). | Single entry point, branches internally on whether `savedContext` is set |
| Post-save "Done" window | UI-only concept (not a `ViewModel` type): the period after `SaveState.Saved` during which the sheet stays open because ≥1 suggestion chip is still pending, bounded by a resettable ~2.5–3s auto-finish timer owned by `CaptureScreen`. | Implemented as a transient `LaunchedEffect`/`delay()` in the composable, not a stored class — compliant with the `rememberCoroutineScope()` ownership rule since nothing long-lived is constructed there |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Overall feature shape | New small coordinator class, composition over inheritance | `research/build-vs-buy.md` §4, `research/architecture.md` | B: inline everything into `CaptureViewModel` with a ViewModel-local `Mutex` | Duplicates per-graph lifecycle logic (`activeGraphJobs`) `GraphManager` already owns; couples `PageNameIndex`'s lifetime to `CaptureActivity` instead of the graph |
| `CaptureEnrichmentCoordinator` | Composition (GoF: favor composition over inheritance) | `research/build-vs-buy.md` §4 | C: fork/subclass `ImportViewModel` | `ImportViewModel` injects `matcherFlow` — never solves AC #8's construction problem — and carries `ImportState`'s unrelated full-screen-review surface |
| `GraphManager.getOrCreateEnrichmentCoordinator()` construction | `Mutex`-guarded, permanently memoized `Deferred<CaptureEnrichmentCoordinator>`, keyed by `GraphId` | `research/stack.md` §2, `research/architecture.md` Q1, `research/build-vs-buy.md` §1 | `RequestCoalescer<K, V>` reused as-is | `RequestCoalescer.execute()` evicts its key once the loader completes (correct for repeated DB reads) — capture needs *permanent* per-graph memoization, not re-triggered construction on the next call |
| `GraphManager.getOrCreateEnrichmentCoordinator()` construction | (same as above) | AC #8 (requirements.md) | Nullable-field double-checked outside the lock | The exact anti-pattern AC #8 forbids; `AndroidPhotoPickerLauncher`'s unguarded `pendingResult` field is safe only because of a single-callback guarantee that does not hold for `CaptureActivity`'s concurrent text-change events |
| `CaptureViewModel.ScanState` | Sealed type / type-driven design (illegal states unrepresentable) | `type-driven-design` principles; contrast with `ImportState`'s flag-heavy design | Nullable `ScanResult?` + separate `isScanning: Boolean` flags (mirroring `ImportState`) | Flags permit illegal combinations (`isScanning=true` alongside a stale `linkedText`); `Ready(text, result)` ties a scan result to the exact text it was computed for, which *is* the AC #4 staleness check, not a bolt-on assertion |
| AC #9 second write | Reuse the exact `GraphWriter`/`DatabaseWriteActor` instance (PoEAA: respect a stateful collaborator's identity, don't re-instantiate it) | `research/architecture.md` Q2 | Construct a fresh `GraphWriter(fileSystem, writeActor)` per post-save accept | A second, independently-mutexed `GraphWriter` has no visibility into the first instance's `saveMutex`/`activeConflicts`/`pendingByPage` state and could race the same markdown file |
| Coordinator ownership/lifetime | Owned by `CaptureViewModel` (a ViewModel-held reference to a `GraphManager`-owned singleton), never constructed in a composable | `pitfalls.md` PF-3; root `CLAUDE.md` coroutine-scope-ownership rule | `remember { CaptureEnrichmentCoordinator(rememberCoroutineScope()) }` inside `CaptureScreen` | Forbidden by this repo's `CLAUDE.md` rule (scope torn down on recomposition); also defeats AC #8's single-flight guarantee since `remember{}` rebuilds per recomposition |
| Auto-link visualization | Read-only, non-editable preview line below the text field | UX research §3b, adapted; Import's own `ReviewStage` precedent (raw editable input vs. separate linked preview) | Rewrite `linkedText` directly into the editable `OutlinedTextField.value` while the user types | No existing code in this repo rewrites a live edit buffer mid-composition; doing so risks cursor-position loss / interrupted typing with no precedent to copy. A read-only caption gives the same "recognized" signal without that risk, and keeps AC #5's responsiveness budget measured against real text-field interaction only |
| `StelekitViewModel.pageNameIndex` de-duplication | Left unchanged — no cross-ViewModel cache in v1 | `research/architecture.md` Q1 | Rewire `StelekitViewModel` to source `pageNameIndex` from `GraphManager.getOrCreateEnrichmentCoordinator()` | Changes `StelekitViewModel`'s scope ownership (a lifecycle change interacting with `App.kt`'s `key(activeGraphId)` teardown/rebuild), outside this feature's "additive wiring, not a refactor" constraint. Flagged as a follow-up (PF-7), not a v1 blocker |
| `LlmProviderRegistry` resolution for capture | `GraphManager` builds its own registry lazily via `buildLlmProviderRegistry(LlmCredentialStore(CredentialStore()), LlmSettings(platformSettings))` — the same recipe `App.kt:490-500` uses, self-contained (no new constructor param) | `GraphManager`'s existing `platformSettings: Settings` field already matches `LlmSettings`'s constructor type | Pass an `LlmProviderRegistry` in from `CaptureActivity`/`CaptureViewModel` | `CaptureActivity` has no access to the Compose-tree-scoped registry `App.kt` builds (it never runs `App.kt`'s composition) — `GraphManager` already holds every dependency needed to build an equivalent registry itself, avoiding a second, divergent construction site |
| `CaptureViewModel.save()` / `acceptSuggestion()` ordering | Shared `Mutex` (`saveOpMutex`) serializes the two entry points end-to-end — whichever starts first runs to completion before the other proceeds | adversarial-review.md Blocker #2 | Re-check `savedContext` immediately before the final write inside `acceptSuggestion()`'s pre-save branch, then re-route to the post-save path if a save landed mid-flight | Both entry points are independent `scope.launch { }` bodies with no shared-state guard today; wrapping each entire body in one `Mutex.withLock { }` is a smaller, more obviously-correct diff than restructuring the pre-save branch to detect a save that completed mid-accept and switch code paths — same idiom this file already uses elsewhere in the codebase (`coordinatorMutex`, Task 1.2.1a; `GraphWriter.saveMutex`) |
| `CaptureEnrichmentCoordinator.scan()` return type | Sealed `ScanOutcome` (type-driven design: keep "not ready" and "timed out" distinguishable) | architecture-review.md Concern (Epic 1.1) | Nullable `ScanResult?` (original plan) | A nullable return collapsed two distinguishable failure modes into one signal, making the Observability Plan's promised "budget exceeded (debug)" log line unimplementable and hiding a systematic slow-scan regression behind ordinary cold-start `NotReady` |
| `acceptSuggestionPostSave()` graph-identity check | Compare `ctx.graphId` to `GraphManager.getActiveGraphId()` at entry; short-circuit to an isolated, logged failure on mismatch, and read/write only through `ctx`'s captured repositories/writer — never a freshly-fetched "active" `RepositorySet` | architecture-review.md Blocker, adversarial-review.md Blocker #1, ADR-002 (constraint added) | Rely on the existing `ClosedSendChannelException` guard alone to detect a graph switch | That exception only fires if the actor's channel happens to be closed — it does not fire for every way a graph switch can leave `ctx`'s captured repositories stale (e.g. the newly-active graph happens to have its own live, open `writeActor` too) |

---

## Migration Plan

N/A — no schema or data changes. No new SQLDelight tables, no `MigrationRunner` entries.

## Observability Plan

- **Logs**: `Logger("CaptureEnrichmentCoordinator")` logs matcher-build/`Throwable` degradation (mirrors `PageNameIndex.matcher`'s own logging) and enrichment timeout/failure at `error`/`warn`. `Logger("CaptureViewModel")` (new) logs: per-`collectLatest`-iteration scan failure (`warn`, Task 2.1.2b, Blocker #3), scan budget exceeded distinct from matcher-not-ready (`debug`, `ScanOutcome.TimedOut` vs `MatcherNotReady`, Task 2.1.2b), stub-page save failures per accepted suggestion with the term and `DomainError` (`error`, AC #7), a graph-identity mismatch at the top of `acceptSuggestionPostSave()` ("suggestion not applied — active graph changed since save", `error`, Task 4.2.1a, Blocker #1), post-save `writeActor.saveBlock`/`writer.savePage` failures with a distinct "suggestion not applied" message (`error`, PF-5), and `ClosedSendChannelException` catches on both the pre-save and post-save write (`warn`).
- **Metrics**: none new. No existing capture-specific telemetry event API surfaced in research; adding one is out of scope for this feature (would be new instrumentation not requested by requirements.md).
- **Alerts**: N/A — mobile client feature, no server-side alerting surface.

## Risk Control

- **Feature flag**: none. The local heuristic tier is always-on per requirements ("this is the default state, not a degraded state"); the LLM tier is already self-gating via `LlmProviderRegistry.availableForFeature(...)` returning an empty list when nothing is configured. Adding a separate flag would violate the "no new abstractions beyond what the task requires" constraint.
- **Rollback procedure**: revert the wiring commit(s). `CaptureViewModel.performSave()`'s core write (one `saveBlock` + one `savePage`) is structurally unchanged by this feature — enrichment wraps around it, per the requirements' explicit constraint — so a revert restores today's exact save behavior with no data-shape cleanup required.
- **Staged rollout**: none specified by requirements/research. Standard PR review + `bazel test //kmp:jvm_tests` / `./gradlew ciCheck` gate per repo convention.

## Unresolved Questions

- [ ] Should `getOrCreateEnrichmentCoordinator()` attempt to discover and reuse an already-running `StelekitViewModel.pageNameIndex` when the main app process is alive concurrently with `CaptureActivity` (PF-7, avoids a duplicate trie build on large graphs)? — blocks nothing in v1 (Epic 1.2 ships without it) — owner: flagged as a follow-up in `research/architecture.md` Q1, not a v1 requirement.
- [ ] Optional `visualTransformation` tint for `[[bracket]]` runs in the *live* text field, mentioned as a stretch enhancement in `research/ux.md` §3b — superseded in this plan by the read-only preview-line decision (Pattern Decisions table), so this question is now moot for v1; recorded only in case a future story revisits live-field styling.

## Dependency Visualization

```
Phase 1: Coordinator & Concurrency Foundation
  Epic 1.1 CaptureEnrichmentCoordinator ─────┐
                                              ▼
  Epic 1.2 GraphManager.getOrCreateEnrichmentCoordinator (AC#8)
                                              │
                                              ▼
Phase 2: CaptureViewModel Scan Pipeline
  Epic 2.1 ScanState + scan trigger (AC#1,4,5; PF-1,6) ──┐
  Epic 2.2 LLM enrichment pass (AC#3) ───────────────────┤
  Epic 2.3 Save-time resolution + SavedCaptureContext ───┤
                                              │           │
                                              ▼           ▼
Phase 3: Chip Tray UI                Phase 4: Accept/Dismiss
  Epic 3.1 Chip + tray (AC#2,6) ──┐    Epic 4.1 Pre-save accept (AC#2,7)
  Epic 3.2 Auto-link preview      │    Epic 4.2 Post-save write-back (AC#9) ← needs 2.3
  (AC#1,6)                        │    Epic 4.3 Post-save "Done" window (AC#9)
                                   └──────────┘
                                              │
                                              ▼
Phase 5: Tests
  Epic 5.1 Coordinator/GraphManager race tests (+ScanOutcome, enhance() sanitization)
  Epic 5.2 CaptureViewModel regression tests (PF-6, image-only, AC#9, PF-5,
            + graph-identity guard, save/accept race, scan-survives-Throwable,
            AC#5 no-await, AC#6 negative cases)
```

---

## Phase 1: Coordinator & Concurrency Foundation

### Epic 1.1: `CaptureEnrichmentCoordinator`

**Goal**: A small, `commonMain`, testable class that owns one graph's `PageNameIndex` and a resolved `TopicEnricher`, and exposes budget/timeout-bounded wrappers around `ImportService.scan()` and `TopicEnricher.enhance()`. Placed in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/` (not `androidApp`) because `GraphManager` (also `commonMain`) must be able to construct and return it — `androidApp` depends on `kmp`, never the reverse, so an `androidApp`-only type could not be `GraphManager`'s return type at all. This is a structural requirement, not just a style preference.

#### Story 1.1.1: Coordinator skeleton, `PageNameIndex` ownership, budget-bounded `scan()`

**As a** capture session, **I want** a single object that owns my graph's page-name matcher, **so that** `CaptureViewModel` never has to construct or reason about `PageNameIndex` directly.

**Acceptance Criteria**:
- `CaptureEnrichmentCoordinator(pageRepository, scope, topicEnricher)` constructs one `PageNameIndex(pageRepository, scope)` and stores it as `val pageNameIndex: PageNameIndex`.
- `suspend fun scan(text: String, budgetMs: Long = 500): ScanOutcome` returns `ScanOutcome.MatcherNotReady` if `pageNameIndex.matcher.value` is `null` (matcher not built yet), `ScanOutcome.TimedOut` if the scan does not complete within `budgetMs`, and `ScanOutcome.Success(result)` otherwise. A sealed `ScanOutcome` (not a nullable `ScanResult?`) keeps these two "no usable result" cases distinguishable at the call site, so a systematically-too-slow scan (large graph, slow device) is logged distinctly from ordinary cold start instead of looking identical to it forever (architecture-review.md Concern, Epic 1.1).
  - *Given* a `CaptureEnrichmentCoordinator` whose `pageNameIndex.matcher.value` is still `null` (cold-start, trie not yet built), *When* `scan("Meeting notes")` is called, *Then* it returns `ScanOutcome.MatcherNotReady` immediately (no `withTimeoutOrNull` wait needed — the matcher-null check short-circuits before any work).
  - *Given* a matcher is ready, *When* `scan("I'm reading about Kotlin Multiplatform")` is called and page "Kotlin Multiplatform" exists, *Then* it returns `ScanOutcome.Success(ScanResult(linkedText = "I'm reading about [[Kotlin Multiplatform]]", matchedPageNames = ["Kotlin Multiplatform"], topicSuggestions = [...]))` within the 500ms budget.
  - *Given* a matcher is ready but the scan takes longer than `budgetMs`, *When* `scan(...)` is called, *Then* it returns `ScanOutcome.TimedOut` — distinguishable from `MatcherNotReady` at the call site, so `Logger("CaptureEnrichmentCoordinator")` can log budget-exceeded at `debug` (Observability Plan) without conflating it with cold start.
- Uses `pageNameIndex.vocabularyNames().toSet()` as `existingNames` for `ImportService.scan(text, matcher, existingNames)` — **not** a second `pageRepository.getPageNameEntries().first()` call — since the coordinator already owns the built index (`PageNameIndex.vocabularyNames()` returns exactly this projection).
- `enhance()` sanitizes whatever the resolved `TopicEnricher` returns before it reaches the chip tray: blank/whitespace-only terms are dropped, confidence is clamped to `0f..1f`, and duplicate terms (compared trimmed + lowercased, first-seen wins) are deduped — matching PF-8's requirement that the LLM tier degrade identically to `NoOpTopicEnricher` on *any* malformed-output failure mode, not just a thrown `Throwable` (adversarial-review.md Concern, AC #3).
  - *Given* `topicEnricher.enhance(...)` returns normally with `[TopicSuggestion(term = "  ", confidence = 47f, ...), TopicSuggestion(term = "Kotlin", confidence = 0.9f, ...), TopicSuggestion(term = "kotlin", confidence = 0.3f, ...)]`, *When* `coordinator.enhance(...)` processes it, *Then* the blank-term entry is dropped, the surviving `"Kotlin"` entry's confidence is clamped to `1f`, and only one `"Kotlin"`/`"kotlin"` entry survives — no garbage ever reaches `mergeBySource()` or a stub-page write.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt` (new)

##### Task 1.1.1a: Create `CaptureEnrichmentCoordinator.kt` skeleton + `ScanOutcome` sealed type (~4 min)
- New file with the standard license header (copy from `PageNameIndex.kt:1-3`).
- `class CaptureEnrichmentCoordinator(pageRepository: PageRepository, scope: CoroutineScope, val topicEnricher: TopicEnricher)`.
- `val pageNameIndex: PageNameIndex = PageNameIndex(pageRepository, scope)`.
- Nested sealed type (mirrors `CaptureViewModel.ScanState`'s nesting pattern, Task 2.1.1a):
  ```kotlin
  sealed interface ScanOutcome {
      data object MatcherNotReady : ScanOutcome
      data object TimedOut : ScanOutcome
      data class Success(val result: ScanResult) : ScanOutcome
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt`

##### Task 1.1.1b: Add `scan()` with matcher-null short-circuit and budget timeout (~4 min)
- `suspend fun scan(text: String, budgetMs: Long = 500): ScanOutcome`:
  ```kotlin
  suspend fun scan(text: String, budgetMs: Long = 500): ScanOutcome {
      val matcher = pageNameIndex.matcher.value ?: return ScanOutcome.MatcherNotReady
      val result = withTimeoutOrNull(budgetMs) {
          withContext(Dispatchers.Default) {
              ImportService.scan(text, matcher, pageNameIndex.vocabularyNames().toSet())
          }
      }
      return result?.let { ScanOutcome.Success(it) } ?: ScanOutcome.TimedOut
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt`

##### Task 1.1.1c: Add `enhance()` with 8s timeout + `Throwable` guard (PF-1) + output sanitization (AC #3) (~6 min)
- `suspend fun enhance(text: String, local: List<TopicSuggestion>): List<TopicSuggestion>`:
  ```kotlin
  suspend fun enhance(text: String, local: List<TopicSuggestion>): List<TopicSuggestion> =
      if (topicEnricher is NoOpTopicEnricher) local
      else try {
          sanitize(withTimeout(8_000) { topicEnricher.enhance(text, local) })
      } catch (e: CancellationException) {
          throw e
      } catch (e: Throwable) {
          logger.warn("Enrichment failed/timed out — using local suggestions only: ${e::class.simpleName}")
          local
      }

  // A TopicEnricher that returns normally with malformed output (blank term, out-of-range
  // confidence, duplicate terms) must not pass garbage through to the chip tray or a
  // stub-page write — this is the same degrade-to-safe bar as the try/catch above, just for
  // the "no exception, bad data" failure mode.
  private fun sanitize(enriched: List<TopicSuggestion>): List<TopicSuggestion> {
      val seen = mutableSetOf<String>()
      return enriched.mapNotNull { s ->
          val term = s.term.trim()
          if (term.isEmpty() || !seen.add(term.lowercase())) return@mapNotNull null
          s.copy(term = term, confidence = s.confidence.coerceIn(0f, 1f))
      }
  }
  ```
- Add `private val logger = Logger("CaptureEnrichmentCoordinator")`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt`

#### Story 1.1.2: `TopicEnricher` resolution from `LlmProviderRegistry`

**As a** capture session with an on-device or cloud LLM provider configured, **I want** the coordinator's `TopicEnricher` to reflect that provider, **so that** AC #3's opt-in tier actually activates.

**Acceptance Criteria**:
- `LlmFeature` gains a `CAPTURE_ENRICHMENT` entry.
- A factory function resolves a `TopicEnricher` from an `LlmProviderRegistry`: first available provider for `CAPTURE_ENRICHMENT` → `ClaudeTopicEnricher(provider.formatter)` (the class name is historical; its field is a generic `LlmFormatterProvider`, so this works for Anthropic, OpenAI, and the on-device ML Kit tier identically — same pattern as `LlmTagProvider(it.formatter)` at `App.kt:1133`); no available provider → `NoOpTopicEnricher()`.
  - *Given* `LlmProviderRegistry.availableForFeature(LlmFeature.CAPTURE_ENRICHMENT)` returns a provider with `id = "android-ondevice"`, *When* the coordinator is constructed, *Then* `coordinator.topicEnricher` is a `ClaudeTopicEnricher` wrapping that provider's `formatter`, not `NoOpTopicEnricher`.
  - *Given* the registry returns an empty list (no provider configured), *When* the coordinator is constructed, *Then* `coordinator.topicEnricher is NoOpTopicEnricher` — and this is not logged as an error/degraded state, per requirements.md's explicit framing.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmFeature.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt`

##### Task 1.1.2a: Add `LlmFeature.CAPTURE_ENRICHMENT` (~2 min)
- `enum class LlmFeature { VOICE_FORMATTING, TAG_SUGGESTION, GRAPH_EDIT_SYNTHESIS, CAPTURE_ENRICHMENT }`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmFeature.kt`

##### Task 1.1.2b: Add `CaptureEnrichmentCoordinator.Companion.resolveTopicEnricher()` (~4 min)
- ```kotlin
  companion object {
      suspend fun resolveTopicEnricher(registry: LlmProviderRegistry): TopicEnricher =
          registry.availableForFeature(LlmFeature.CAPTURE_ENRICHMENT).firstOrNull()
              ?.let { ClaudeTopicEnricher(it.formatter) }
              ?: NoOpTopicEnricher()
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt`

---

### Epic 1.2: `GraphManager.getOrCreateEnrichmentCoordinator()` (AC #8)

**Goal**: Race-safe, memoized, per-graph coordinator construction reachable from `CaptureActivity` without any `StelekitViewModel` dependency.

#### Story 1.2.1: Mutex-guarded memoized `Deferred` cache, self-contained `LlmProviderRegistry`

**As** `CaptureViewModel`, **I want** to call one suspend method and get back the current graph's coordinator, **so that** I never construct `PageNameIndex` myself and never race a second caller doing the same.

**Acceptance Criteria**:
- `GraphManager.getOrCreateEnrichmentCoordinator(): CaptureEnrichmentCoordinator?` returns `null` if there is no active graph, no active `RepositorySet`, or no `activeGraphJobs` scope yet (matches `performSave()`'s existing "no active graph" guard shape).
- Two concurrent calls for the same `GraphId` share one `Deferred` and produce exactly one `PageNameIndex`.
  - *Given* `GraphManager._activeRepositorySet.value` is non-null for `GraphId("g1")` and no coordinator has been built yet, *When* two coroutines call `getOrCreateEnrichmentCoordinator()` within the same dispatcher tick, *Then* both receive references to the same `CaptureEnrichmentCoordinator` instance (`===`), and only one `PageNameIndex(repoSet.pageRepository, scope)` was constructed (verified in Task 1.2.2a's test).
- A stale entry (graph id no longer active) is not returned; a fresh `Deferred` is built for the new graph id under the same lock.
- `coordinatorMutex` is held only for the read/insert of the memoized `Deferred` — never across the `await()` that actually constructs the coordinator — so a slow construction for one graph never blocks a concurrent `getOrCreateEnrichmentCoordinator()` call for a *different* graph (architecture-review.md Concern, Epic 1.2).
  - *Given* graph `g1`'s coordinator construction is in flight (its `Deferred` not yet resolved) inside `coordinatorMutex`'s critical section, *When* a concurrent call requests graph `g2`'s coordinator, *Then* the `g2` call is not blocked waiting for `g1`'s construction to finish — it only blocks for the brief duration of the mutex-guarded cache read/insert.
- If constructing the coordinator fails (the `Deferred` completes exceptionally — any `Throwable` during `PageNameIndex`/`CaptureEnrichmentCoordinator` construction), the memoized entry for that `GraphId` is evicted so the *next* call attempts a fresh construction instead of re-`await()`-ing and rethrowing the same failure forever (adversarial-review.md Concern).
  - *Given* `coordinatorFor["g1"]`'s `Deferred` fails with an `IllegalStateException` during construction, *When* a second `getOrCreateEnrichmentCoordinator()` call for `g1` happens afterward, *Then* `coordinatorFor` no longer holds the failed entry and a new `Deferred` is constructed and attempted, rather than immediately rethrowing the first failure.
- `LlmProviderRegistry` is built lazily, once, self-contained, reusing `GraphManager`'s own `platformSettings` field — no new `GraphManager` constructor parameter.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

##### Task 1.2.1a: Add coordinator cache fields + lazy `LlmProviderRegistry` (~4 min)
- After the existing `activeGraphJobs` field (`GraphManager.kt:94`), add:
  ```kotlin
  private val coordinatorMutex = Mutex()
  private var coordinatorFor: Pair<GraphId, Deferred<CaptureEnrichmentCoordinator>>? = null

  // Same construction recipe App.kt:490-500 uses for the Compose tree's registry — CaptureActivity
  // never runs that composition, so GraphManager builds its own equivalent, self-contained.
  private val llmProviderRegistry: dev.stapler.stelekit.llm.LlmProviderRegistry by lazy {
      dev.stapler.stelekit.llm.buildLlmProviderRegistry(
          dev.stapler.stelekit.llm.LlmCredentialStore(dev.stapler.stelekit.platform.security.CredentialStore()),
          dev.stapler.stelekit.llm.LlmSettings(platformSettings),
      )
  }
  ```
- Add `import kotlinx.coroutines.sync.Mutex` and `import kotlinx.coroutines.sync.withLock` (check not already imported — `GraphManager.kt` does not currently import `Mutex`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

##### Task 1.2.1b: Implement `getOrCreateEnrichmentCoordinator()` — lock released before `await()`, failure-evicting (~7 min)
- The `Deferred` is looked up/inserted under `coordinatorMutex`, then **awaited after the lock is released** — awaiting inside the lock (an earlier draft of this task) would serialize every graph's coordinator construction behind whichever one is currently in flight, defeating the point of keying the cache by `GraphId` (architecture-review.md Concern, Epic 1.2). A failed `Deferred` is evicted from the cache on `await()` failure so it isn't replayed forever (adversarial-review.md Concern):
  ```kotlin
  suspend fun getOrCreateEnrichmentCoordinator(): dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator? {
      val (graphId, deferred) = coordinatorMutex.withLock {
          val graphId = _graphRegistry.value.activeGraphId ?: return@withLock null
          val repoSet = _activeRepositorySet.value ?: return@withLock null
          val scope = activeGraphJobs[graphId] ?: return@withLock null
          val existing = coordinatorFor?.takeIf { it.first == graphId }?.second
          val deferred = existing ?: scope.async(start = CoroutineStart.LAZY) {
              val topicEnricher = dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator
                  .resolveTopicEnricher(llmProviderRegistry)
              dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator(repoSet.pageRepository, scope, topicEnricher)
          }.also { coordinatorFor = graphId to it }
          graphId to deferred
      } ?: return null
      return try {
          deferred.await()
      } catch (e: CancellationException) {
          throw e
      } catch (e: Throwable) {
          coordinatorMutex.withLock {
              if (coordinatorFor?.first == graphId) coordinatorFor = null
          }
          throw e
      }
  }
  ```
- Add `import kotlinx.coroutines.CoroutineStart`, `import kotlinx.coroutines.async`, and `import kotlinx.coroutines.CancellationException` if not already present (`GraphManager.kt` already imports `Deferred`, `CompletableDeferred`, `launch`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

##### Task 1.2.1c: Verify natural invalidation on `switchGraph()`/`shutdown()` (~3 min)
- No new teardown code needed: `activeGraphJobs[graphId]` is cancelled by the existing `currentGraphId?.let { activeGraphJobs.remove(it)?.cancel() }` at `GraphManager.kt:541` and by `shutdown()` — since the coordinator's `PageNameIndex` was built on that same scope, its internal `stateIn`/`launch` (`PageNameIndex.kt:73,76`) die with it. Confirm by reading `switchGraph()` end-to-end (lines 527-565+) that `coordinatorFor` is never read again for the old `graphId` (the `existing.first == graphId` check in Task 1.2.1b already handles this — no code change, verification only).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (read-only verification)

#### Story 1.2.2: Race-safety regression test (AC #8)

**As a** maintainer, **I want** an automated test proving two concurrent callers never build two `PageNameIndex` instances, **so that** AC #8 stays enforced.

**Acceptance Criteria**:
- A `businessTest` launches two coroutines calling `getOrCreateEnrichmentCoordinator()` concurrently against a `GraphManager` with an active graph, and asserts both results are reference-equal (`===`) and that a construction counter (injected via a wrapped `PageRepository` or a spy) was incremented exactly once.
  - *Given* a `GraphManager` with one active graph and zero coordinators built, *When* `launch { graphManager.getOrCreateEnrichmentCoordinator() }` is called twice back-to-back without an intervening suspension point, *Then* `GraphManagerEnrichmentCoordinatorTest` asserts both `Deferred.await()` results are `===` and any `pageRepository.getPageNameEntries()` collector was started exactly once.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerEnrichmentCoordinatorTest.kt` (new)

##### Task 1.2.2a: Write `GraphManagerEnrichmentCoordinatorTest` (~5 min)
- Model after `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerAddGraphTest.kt`'s setup (real `GraphManager` + in-memory backend).
- Two `async { graphManager.getOrCreateEnrichmentCoordinator() }` launched from the test's own scope before either is awaited; assert `first.await() === second.await()`.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerEnrichmentCoordinatorTest.kt`

---

## Phase 2: CaptureViewModel Scan Pipeline

### Epic 2.1: `ScanState` + scan trigger (AC #1, #4, #5; PF-1, PF-6)

**Goal**: Wire `captureText` changes to a debounced, cancellation-safe, budget-bounded scan that never blocks Save and never crashes the process.

#### Story 2.1.1: `ScanState` type

**As** `CaptureViewModel`, **I want** a type that ties a scan result to the exact text it was computed for, **so that** Save-time staleness checks (AC #4) are structural, not a separate boolean.

**Acceptance Criteria**:
- `sealed interface ScanState { data object NotReady : ScanState; data class Ready(val text: String, val result: ScanResult) : ScanState }` nested in `CaptureViewModel`, mirroring the existing `SaveState` sealed class already in this file.
  - *Given* `_scanState.value = ScanState.Ready(text = "abc", result = ...)` and the user then types one more character (`captureText.value` becomes `"abcd"`), *When* `save()` reads `_scanState.value`, *Then* `(current as? ScanState.Ready)?.text == _captureText.value` is `false`, correctly identifying the scan as stale without a separate `isStale` flag.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.1a: Add `ScanState` sealed interface + `_scanState` field (~3 min)
- Add next to the existing `SaveState` sealed class (`CaptureViewModel.kt:35-40`):
  ```kotlin
  sealed interface ScanState {
      data object NotReady : ScanState
      data class Ready(val text: String, val result: dev.stapler.stelekit.domain.ScanResult) : ScanState
  }
  private val _scanState = MutableStateFlow<ScanState>(ScanState.NotReady)
  val scanState: StateFlow<ScanState> = _scanState.asStateFlow()
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

#### Story 2.1.2: Debounced `collectLatest` scan coroutine with exception guard (AC #1, #4, #5; PF-1, PF-6)

**As a** user typing/pasting into the capture sheet, **I want** scanning to happen in the background without ever blocking my typing or Save tap, and without ever crashing the app on an OOM, **so that** capture stays fast and safe.

**Acceptance Criteria**:
- A dedicated scope with a `CoroutineExceptionHandler` (mirroring `StelekitViewModel.scope`, `StelekitViewModel.kt:156-168`) hosts the scan coroutine — not raw `viewModelScope`. This protects the *process* (no crash) but, on its own, does **not** keep the `collectLatest` collector alive after a `Throwable` — see the next bullet, which is the actual PF-1/AC #1,#4,#5 mitigation (adversarial-review.md Blocker #3).
- Every `collectLatest` iteration wraps its body (at minimum the `getOrCreateEnrichmentCoordinator()`/`coordinator.scan()` call chain) in its own `try { } catch (e: CancellationException) { throw e } catch (e: Throwable) { ... }`, matching `PageNameIndex.matcher`'s own established degrade-on-`Throwable` pattern (`PageNameIndex.kt:65-70`, `:96-101`) — so one failed iteration (e.g. an `OutOfMemoryError` from trie construction) degrades only that scan attempt, and the collector keeps running for every subsequent text change. A `CoroutineExceptionHandler` alone is insufficient here: it only fires once the root coroutine hosting `collectLatest` has already died, and does not resume or relaunch the collector — every text change for the rest of the `CaptureViewModel` instance's life would otherwise silently stop scanning, with no user-visible signal, contradicting requirements.md's "floor, not a degraded fallback" framing.
  - *Given* `coordinator.scan()` throws an `OutOfMemoryError` on one `collectLatest` iteration, *When* the per-iteration `catch (e: Throwable)` handles it, *Then* `_scanState.value` becomes `ScanState.NotReady` for that attempt, the `collectLatest` coroutine is still alive afterward, and the *next* text change still triggers a normal scan attempt — verified by Task 5.2's new scan-survives-`Throwable` test asserting a second, successful scan after an injected first-attempt failure.
- `captureText.debounce(300).collectLatest { ... }` is the trigger shape (not a bare `launch` per keystroke) — `collectLatest` cancels the previous scan when a new one supersedes it, satisfying PF-6's `onNewIntent`/rapid-retype cancellation requirement.
  - *Given* a scan for text `"first share"` is in flight (awaiting `coordinator.scan(...)`), *When* `updateText("first share text")` fires before that scan completes, *Then* `collectLatest` cancels the in-flight scan and only the second scan's result ever reaches `_scanState`.
- On `NotReady` (matcher not built, coordinator unavailable, or budget exceeded), `_scanState.value` stays/becomes `ScanState.NotReady` and `save()` proceeds on raw text (AC #4) — see Story 2.3.1.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.2a: Add `CoroutineExceptionHandler`-wrapped `scope` field (~4 min)
- ```kotlin
  private val logger = Logger("CaptureViewModel")
  private val scope = CoroutineScope(
      viewModelScope.coroutineContext + CoroutineExceptionHandler { _, e ->
          if (e !is CancellationException) {
              logger.error("Uncaught Throwable in capture-enrichment coroutine — ${e::class.simpleName}: ${e.message}")
          }
      }
  )
  ```
- Add imports: `dev.stapler.stelekit.logging.Logger`, `kotlinx.coroutines.CoroutineExceptionHandler`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.CancellationException`.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.2b: Wire the `collectLatest` scan trigger in `init { }`, per-iteration `Throwable` guard (~7 min)
- Each iteration is wrapped in its own `try/catch` (Blocker #3 fix) so a single failure never kills the collector, matching `PageNameIndex.matcher`'s pattern (`PageNameIndex.kt:65-70`); `coordinator.scan()`'s `ScanOutcome` (Task 1.1.1b) is mapped explicitly, logging `TimedOut` distinctly from `MatcherNotReady` per the Observability Plan:
  ```kotlin
  init {
      scope.launch {
          captureText
              .debounce(300)
              .collectLatest { text ->
                  try {
                      if (text.isBlank()) { _scanState.value = ScanState.NotReady; return@collectLatest }
                      val coordinator = getApplication<SteleKitApplication>().graphManager
                          ?.getOrCreateEnrichmentCoordinator() ?: run {
                          _scanState.value = ScanState.NotReady
                          return@collectLatest
                      }
                      val (_, freeText) = splitImagePrefix(text)  // Story 2.1.3
                      when (val outcome = coordinator.scan(freeText)) {
                          is dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator.ScanOutcome.Success ->
                              _scanState.value = ScanState.Ready(text, outcome.result)
                          dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator.ScanOutcome.MatcherNotReady ->
                              _scanState.value = ScanState.NotReady
                          dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator.ScanOutcome.TimedOut -> {
                              logger.debug("Scan budget exceeded for ${text.length} chars")
                              _scanState.value = ScanState.NotReady
                          }
                      }
                  } catch (e: CancellationException) {
                      throw e
                  } catch (e: Throwable) {
                      // Degrade this scan attempt only — the collector must stay alive for the
                      // next text change (PF-1, adversarial-review.md Blocker #3). A
                      // CoroutineExceptionHandler on `scope` (Task 2.1.2a) alone would not do
                      // this: it fires only after collectLatest has already died.
                      logger.warn("Scan attempt failed — degrading to NotReady: ${e::class.simpleName}: ${e.message}")
                      _scanState.value = ScanState.NotReady
                  }
              }
      }
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.2c: Confirm budget/`NotReady` fallback needs no extra code beyond the `when` mapping (~2 min)
- Verify `CaptureEnrichmentCoordinator.scan()` (Task 1.1.1b) already returns `ScanOutcome.MatcherNotReady`/`TimedOut` for both "no usable result" cases, which Task 2.1.2b's `when` already maps to `ScanState.NotReady` (while still logging `TimedOut` distinctly) — no additional timeout wrapper needed at the `CaptureViewModel` layer (avoids duplicating the 500ms budget in two places).
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt` (verification only)

#### Story 2.1.3: Image-only-share free-text scanning (resolves `research/features.md` §2.6)

**As a** user sharing a bare image with no caption, **I want** the scan to ignore the synthetic `[image: path]` prefix, **so that** the matcher/extractor never waste budget on a file-path string or produce a false-positive match on a path fragment.

**Acceptance Criteria**:
- `CaptureViewModel` splits the composite `captureText` (`"[image: <path>]\n<freeText>"`, exactly the format `CaptureActivity.kt:82,120` produces) into `(imagePrefix: String?, freeText: String)` before scanning, and scans only `freeText`.
  - *Given* `captureText.value == "[image: /data/user/0/dev.stapler.stelekit/cache/share_1700000000000.jpg]\n"` (bare image share, no caption), *When* the scan coroutine runs, *Then* `splitImagePrefix(...)` returns `("[image: /data/user/0/dev.stapler.stelekit/cache/share_1700000000000.jpg]\n", "")`, and `coordinator.scan("")` is never called (blank free text short-circuits to `NotReady`, same as any blank capture) — no `AhoCorasickMatcher.findAll` call ever sees the file path.
  - *Given* `captureText.value == "[image: /data/.../share_123.jpg]\nGreat article about Kotlin Multiplatform"`, *When* the scan runs, *Then* `freeText == "Great article about Kotlin Multiplatform"` is what's scanned, and the auto-linked result is recombined as `"[image: /data/.../share_123.jpg]\nGreat article about [[Kotlin Multiplatform]]"` before it's used as `textToSave` (Task 2.3.1).

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.3a: Add `splitImagePrefix()` helper (~3 min)
- ```kotlin
  private fun splitImagePrefix(text: String): Pair<String?, String> {
      val match = IMAGE_PREFIX_REGEX.find(text) ?: return null to text
      return match.value to text.removePrefix(match.value)
  }
  companion object {
      private val IMAGE_PREFIX_REGEX = Regex("""^\[image: .*?]\n""")
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.1.3b: Use `freeText` (not the composite) as the scan input (~2 min)
- Already wired in Task 2.1.2b's `val (_, freeText) = splitImagePrefix(text)` line — confirm `ScanState.Ready(text, result)` still stores the *original* composite `text` (needed for the staleness check against `_captureText.value`), while `result` was computed from `freeText` only.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt` (verification of Task 2.1.2b)

##### Task 2.1.3c: Recombine `imagePrefix` + linked free text at Save time (~3 min)
- In the `textToSave` computation (Story 2.3.1), when `current is ScanState.Ready && current.text == _captureText.value`, reconstruct: `val (imagePrefix, _) = splitImagePrefix(current.text); textToSave = (imagePrefix ?: "") + current.result.linkedText`.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

---

### Epic 2.2: LLM enrichment pass (AC #3)

**Goal**: Fire-and-forget, timeout-bounded enhancement that merges non-destructively into the chip tray, mirroring `ImportViewModel.runScan()`'s Coroutine 2.

#### Story 2.2.1: Fire-and-forget `enhance()` merge

**As a** user with an LLM provider configured, **I want** the chip tray to gain AI-sourced suggestions shortly after the local scan lands, **so that** I get better suggestions without waiting for them.

**Acceptance Criteria**:
- After `_scanState.value = ScanState.Ready(text, result)` in Task 2.1.2b, if `coordinator.topicEnricher !is NoOpTopicEnricher`, launch a second `scope.launch { }` calling `coordinator.enhance(freeText, result.topicSuggestions)`.
  - *Given* `coordinator.topicEnricher` is a `ClaudeTopicEnricher`, *When* `enhance()` resolves with `[TopicSuggestion(term="Zettelkasten", confidence=0.8f, source=AI_ENHANCED)]` after the local scan already produced `[TopicSuggestion(term="Note-taking", confidence=0.5f, source=LOCAL)]`, *Then* `_scanState.value`'s `Ready.result.topicSuggestions` contains both terms (local suggestion untouched, AI suggestion appended) — never a clear-and-replace.
- Discard-if-stale-by-hash: capture `text.hashCode()` before launching; if `_captureText.value` has changed by the time `enhance()` resolves, discard the result (mirrors `ImportViewModel.kt:244,250`).
  - *Given* the enrichment coroutine was launched for text hash `H1`, *When* it resolves after the user has typed further text (`_captureText.value.hashCode() == H2 != H1`), *Then* the enriched result is discarded and `_scanState` is not updated with it.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.2.1a: Launch the enhancement coroutine after local scan lands (~4 min)
- Inside the `collectLatest` block, after setting `_scanState.value = ScanState.Ready(...)`:
  ```kotlin
  if (coordinator.topicEnricher !is dev.stapler.stelekit.domain.NoOpTopicEnricher) {
      val textHash = text.hashCode()
      scope.launch {
          val enriched = coordinator.enhance(freeText, result.topicSuggestions)
          if (_captureText.value.hashCode() != textHash) return@launch
          val current = _scanState.value
          if (current is ScanState.Ready && current.text == text) {
              _scanState.value = current.copy(result = current.result.copy(
                  topicSuggestions = mergeBySource(current.result.topicSuggestions, enriched),
              ))
          }
      }
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.2.1b: Add `mergeBySource()` non-destructive merge helper (~3 min)
- Compares terms normalized (trimmed, lowercased) — not raw string equality — so e.g. local `"Kotlin"` correctly suppresses an AI-sourced `"kotlin "` duplicate (Task 1.1.1c's `sanitize()` already cleans `enriched`'s *internal* duplicates/blanks; this is the local-vs-enriched cross-check, per adversarial-review.md Concern on AC #3):
  ```kotlin
  private fun mergeBySource(
      local: List<dev.stapler.stelekit.domain.TopicSuggestion>,
      enriched: List<dev.stapler.stelekit.domain.TopicSuggestion>,
  ): List<dev.stapler.stelekit.domain.TopicSuggestion> {
      val localTermsNormalized = local.map { it.term.trim().lowercase() }.toSet()
      return local + enriched.filter { it.term.trim().lowercase() !in localTermsNormalized }
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

---

### Epic 2.3: Save-time resolution + `SavedCaptureContext` capture

**Goal**: `save()` reads `_scanState` synchronously (never awaits it) and retains what AC #9 needs after a successful save.

#### Story 2.3.1: `textToSave` staleness check (AC #4)

**As a** user, **I want** Save to always work instantly regardless of scan progress, **so that** capture never feels blocked.

**Acceptance Criteria**:
- `save()` computes `textToSave` from `_scanState.value` only if it is `Ready` **and** its `text` matches `_captureText.value` exactly; otherwise falls back to `_captureText.value.trim()`.
  - *Given* `_scanState.value == ScanState.NotReady` (scan never completed, e.g. cold-start budget exceeded), *When* `save()` is called, *Then* `textToSave == _captureText.value.trim()` and `performSave()` is invoked immediately — no `await`/`join` on any scan or coordinator job anywhere in the call path.
  - *Given* `_scanState.value == ScanState.Ready(text = "old text", result = ...)` but `_captureText.value == "old text plus more"`, *When* `save()` is called, *Then* the stale `Ready` state is ignored and raw current text is saved (matches AC #4's Given-When-Then above under Story 2.1.1).

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.3.1a: Compute `textToSave` in `save()` (~4 min)
- Replace `val text = _captureText.value.trim()` in `save()` (`CaptureViewModel.kt:54`) with:
  ```kotlin
  val current = _scanState.value
  val text = if (current is ScanState.Ready && current.text == _captureText.value) {
      val (imagePrefix, _) = splitImagePrefix(current.text)
      ((imagePrefix ?: "") + current.result.linkedText).trim()
  } else {
      _captureText.value.trim()
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.3.1b: Add `saveOpMutex` and serialize `save()` against `acceptSuggestion()` (Blocker #2) (~4 min)
- Add `private val saveOpMutex = Mutex()` next to `savedContext` (Task 2.3.2a). Wrap `save()`'s entire `scope.launch { }` body (the `performSave()` call, the `_saveState` update, and Task 2.3.2b's `savedContext = it` assignment) in `saveOpMutex.withLock { ... }` — matched by an identical wrap around `acceptSuggestion()`'s body (Task 4.1.1a). This closes the pre-save-accept/Save race (adversarial-review.md Blocker #2): whichever of `save()`/`acceptSuggestion()` starts first now runs to completion — including every mutation it makes to `_scanState`/`savedContext` — before the other is allowed to begin. `save()` can therefore never persist a block that's missing an accept already in flight, and `acceptSuggestion()` can never commit to its pre-save branch based on a `savedContext` snapshot a concurrent `save()` is about to invalidate. A `Mutex` around each whole method body is the chosen fix over re-checking `savedContext` mid-`acceptSuggestion()` (see Pattern Decisions table) — smaller diff, same idiom this file already uses for `coordinatorMutex`/`GraphWriter.saveMutex`.
  - *Given* the user taps a suggestion chip's Accept region and then immediately taps Save, *When* `acceptSuggestion()` acquires `saveOpMutex` first, *Then* `save()`'s `scope.launch { }` blocks on `saveOpMutex.withLock { }` until the accept's stub-page write and `markAccepted()` link-fold have both completed and updated `_scanState`, so the subsequent `save()` snapshots `_scanState` *after* the link fold and persists a block that includes the accepted `[[link]]` — never a stale, unlinked snapshot.
- Add `import kotlinx.coroutines.sync.Mutex` and `import kotlinx.coroutines.sync.withLock` if not already present.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

#### Story 2.3.2: Retain `SavedCaptureContext` after a successful save (AC #9 prerequisite)

**As** `CaptureViewModel`, **I want** to remember the exact `Block`/`Page`/`GraphWriter`/`writeActor` a save just used, **so that** a post-save chip accept can write through the same instances (ADR-002's scope boundary).

**Acceptance Criteria**:
- `performSave()` returns (or otherwise exposes) `page`, `graphPath`, `existingBlocks + newBlock`, `writer`, `repoSet.writeActor`, the graph's `GraphId`, and the originating `PageRepository`/`BlockRepository` on success; `save()` stores them in `private var savedContext: SavedCaptureContext?`.
  - *Given* `performSave()` completes successfully for `Block(uuid = BlockUuid("b1"), content = "...")` while `GraphId("g1")` is active, *When* `save()` observes success, *Then* `savedContext == SavedCaptureContext(block = <that Block>, page = <Page>, blocks = existingBlocks + newBlock, graphPath = <path>, graphId = GraphId("g1"), writer = <the GraphWriter performSave() constructed>, writeActor = repoSet.writeActor, pageRepository = repoSet.pageRepository, blockRepository = repoSet.blockRepository)`.
- `graphId`/`pageRepository`/`blockRepository` exist specifically so a later `acceptSuggestionPostSave()` call (Epic 4.2) never has to re-fetch "the active graph" — which could by then be a *different* graph than the one this save wrote to (architecture-review.md Blocker, adversarial-review.md Blocker #1). `SavedCaptureContext`'s captured fields are the *only* source of truth `acceptSuggestionPostSave()` reads from for graph identity and repository access.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.3.2a: Add `SavedCaptureContext` data class + field, with graph identity/repositories (Blocker #1) (~3 min)
- ```kotlin
  private data class SavedCaptureContext(
      val block: dev.stapler.stelekit.model.Block,
      val page: dev.stapler.stelekit.model.Page,
      val blocks: List<dev.stapler.stelekit.model.Block>,
      val graphPath: String,
      val graphId: dev.stapler.stelekit.model.GraphId,
      val writer: GraphWriter,
      val writeActor: dev.stapler.stelekit.db.DatabaseWriteActor?,
      val pageRepository: dev.stapler.stelekit.repository.PageRepository,
      val blockRepository: dev.stapler.stelekit.repository.BlockRepository,
  )
  private var savedContext: SavedCaptureContext? = null
  ```
- `graphId` is the graph *identity* guard: `acceptSuggestionPostSave()` (Task 4.2.1a) compares it against `GraphManager.getActiveGraphId()` before doing anything else. `pageRepository`/`blockRepository` are the *originating* graph's repositories, captured once at save time — `acceptSuggestionPostSave()` must never call `GraphManager.getActiveRepositorySet()` for these, since "active" can silently mean a different graph by the time a post-save accept happens (the post-save "Done" window is unbounded — PF-4).
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 2.3.2b: Change `performSave()`'s return type and populate `savedContext` (~5 min)
- Change `performSave(...): Result<Unit>` to `performSave(...): Result<SavedCaptureContext>`, with the `runCatching { ... }` block's final expression becoming `SavedCaptureContext(newBlock, page, existingBlocks + newBlock, graphPath, graphId = graphManager.getActiveGraphId() ?: error("no active graph"), writer, writeActor = repoSet.writeActor, pageRepository = repoSet.pageRepository, blockRepository = repoSet.blockRepository)` instead of the current `writer.savePage(...)` call's bare `Either` (keep the `savePage(...).getOrElse { error(...) }` call for its side effect, just change what the lambda returns). `graphManager.getActiveGraphId()` is read at the same point `repoSet`/`graphPath` already are (`performSave()` already resolves the active `RepositorySet`/graph to construct `writer` — this is one more read off the same already-resolved `graphManager`, not a new dependency).
- In `save()`: `val result = performSave(...); result.getOrNull()?.let { savedContext = it }; _saveState.value = if (result.isSuccess) SaveState.Saved else SaveState.Error(result.exceptionOrNull())`.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

---

## Phase 3: Chip Tray UI

### Epic 3.1: Compact suggestion chip + tray (AC #2, #6)

**Goal**: A capped, low-noise `LazyRow` of tappable suggestion chips, per `research/ux.md`'s cap/haptics/accessibility guidance.

#### Story 3.1.1: `CaptureSuggestionChip` composable

**As a** user, **I want** each suggestion rendered as a small, clearly-labeled, easily-dismissible chip, **so that** I can act on it in one tap without it dominating the compact sheet.

**Acceptance Criteria**:
- Reuses `ImportScreen.kt:551-606`'s `[confidence dot][term][×]` anatomy at a smaller `padding`/`size`, with **both** independently-tappable regions — the accept surface (the chip body / term-and-dot region, copied from `ImportScreen.kt:607-610`'s accept `IconButton`) and the dismiss `×` — padded to Material3's 48×48dp minimum via `Modifier.minimumInteractiveComponentSize()` even though each one's visible glyph (8–20dp) stays small (`design/ux.md` Surface 7 / AC #20 — the plan's earlier wording named only the dismiss target; both need the fix independently, since they are separate `IconButton`s in the copied anatomy).
  - *Given* `TopicSuggestion(term = "Zettelkasten", confidence = 0.6f, source = LOCAL)`, *When* `CaptureSuggestionChip` renders it, *Then* the confidence dot uses `MaterialTheme.colorScheme.secondary` (0.4–0.69 tier, matching `ImportScreen.kt:132-136`'s thresholds) and the chip's `contentDescription` reads `"Suggested page, Zettelkasten, confidence medium. Double-tap to accept."` (per `research/ux.md` §3d — spelled-out confidence word, not a numeric score or color-only signal).
  - *Given* the same chip, *When* measured at implementation time, *Then* both the accept `IconButton`/tappable region and the dismiss `IconButton` report a minimum touch target of 48×48dp, independently of each other's visible glyph size.
- A screen-reader-reachable dismiss action exists via `customActions` semantics, alongside the existing `contentDescription` (which only states the double-tap-to-accept default action) — closes `design/ux.md` Surface 7 / Cross-Check Finding #3: a TalkBack user has no other way to invoke dismiss, since the visual `×` target has no non-visual equivalent today.
  - *Given* a `CaptureSuggestionChip` rendered under TalkBack, *When* the user opens the chip's actions menu (rotor / explore-by-touch custom-actions list), *Then* a "Dismiss suggestion" action is listed and invoking it calls `onDismiss()` — equivalent to the sighted `×` tap — while the chip's default double-tap action remains accept (unchanged).

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 3.1.1a: Add `CaptureSuggestionChip` private composable (~5 min)
- New private `@Composable fun CaptureSuggestionChip(suggestion: TopicSuggestion, onAccept: () -> Unit, onDismiss: () -> Unit)` below `CaptureScreen` in the same file, structurally copying `ImportScreen.kt:551-606`'s `Row`/`Box`/`Icon` shape at reduced `padding`/`8.dp` dot size, with:
  ```kotlin
  Modifier.semantics {
      contentDescription = "Suggested page, ${suggestion.term}, " +
          "confidence ${confidenceWord(suggestion.confidence)}. Double-tap to accept."
      customActions = listOf(
          CustomAccessibilityAction("Dismiss suggestion") { onDismiss(); true },
      )
  }
  ```
  on the chip's root, giving TalkBack an equivalent affordance to the sighted `×` tap via the actions menu without changing the default double-tap action (still accept). Apply `Modifier.minimumInteractiveComponentSize()` to **both** `IconButton`s copied from the `ImportScreen.kt:551-620` anatomy — the accept region (`ImportScreen.kt:607-610`'s checkmark `IconButton`, `Modifier.size(20.dp)`) and the dismiss `×` `IconButton` — not only the dismiss one.
- Add a private `confidenceWord(confidence: Float): String` helper (`"high"`/`"medium"`/`"low"` at the same 0.7/0.4 thresholds as `ImportScreen.kt:132-136`).
- Add imports: `androidx.compose.ui.semantics.customActions`, `androidx.compose.ui.semantics.CustomAccessibilityAction`.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

#### Story 3.1.2: Chip tray `LazyRow` wiring — cap, silent truncation, live region

**As a** user, **I want** at most 3–4 suggestions shown with no "Show more" control, **so that** the tray never competes with Save for my attention.

**Acceptance Criteria**:
- The tray renders only when `pendingSuggestions.isNotEmpty()` (no empty-state UI); `pendingSuggestions` is derived from `scanState` **gated on the same staleness check `save()`'s `textToSave` computation already uses (Story 2.3.1)**: only a `ScanState.Ready` whose `text` equals the current `captureText` contributes suggestions — a `Ready` computed against superseded text is treated as `NotReady` for tray-rendering purposes, exactly like it already is for save. Non-stale suggestions are filtered (`!dismissed && !accepted`), sorted by descending confidence, and capped at 4 — extras beyond 4 are silently truncated, no disclosure affordance (per requirements' explicit rejection of Accept-All-scale UI).
  - *Given* a scan produces 7 local suggestions with varying confidence, *When* the tray renders, *Then* exactly the top 4 by confidence appear in the `LazyRow`, and there is no "Show 3 more" button anywhere in the composable tree.
  - *Given* `scanState == ScanState.Ready(text = "old text", result = ...)` but the user has since typed further so `captureText.value == "old text plus more"` (the scan for the new text hasn't landed yet), *When* the tray computes `pendingSuggestions`, *Then* it evaluates to empty — the stale `Ready`'s suggestions never render, even for the up-to-one-debounce-cycle window before the new scan resolves (closes `design/ux.md` Surface 3 / Cross-Check Finding #1: the tray must not show suggestions computed against text the user has already edited away from).
- `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the tray container (not `Assertive`), so TalkBack announces new suggestions only at a natural pause, never interrupting active typing.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 3.1.2a: Add the tray `LazyRow` to `CaptureScreen`, below the text field (~5 min)
- Insert between the existing `OutlinedTextField` block and the `Row` of Dismiss/Save buttons (`CaptureActivity.kt:321-322`). `captureText` is already collected earlier in `CaptureScreen` (`CaptureActivity.kt:236`) — reuse it, don't re-declare it. The `takeIf { it.text == captureText }` gate is the staleness check (same idiom `design/ux.md` Surface 3 recommends, mirroring `textToSave`'s gate in Task 2.3.1a):
  ```kotlin
  val scanState by viewModel.scanState.collectAsState()
  val pendingSuggestions = (scanState as? CaptureViewModel.ScanState.Ready)
      ?.takeIf { it.text == captureText }
      ?.result?.topicSuggestions
      ?.filterNot { it.dismissed || it.accepted }
      ?.sortedByDescending { it.confidence }
      ?.take(4)
      .orEmpty()
  if (pendingSuggestions.isNotEmpty()) {
      LazyRow(
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
          items(pendingSuggestions, key = { it.term }) { suggestion ->
              CaptureSuggestionChip(
                  suggestion = suggestion,
                  onAccept = { viewModel.acceptSuggestion(suggestion.term) },
                  onDismiss = { viewModel.dismissSuggestion(suggestion.term) },
              )
          }
      }
      Spacer(Modifier.height(8.dp))
  }
  ```
- Add imports: `androidx.compose.foundation.lazy.LazyRow`, `androidx.compose.foundation.lazy.items`, `androidx.compose.ui.semantics.semantics`, `androidx.compose.ui.semantics.liveRegion`, `androidx.compose.ui.semantics.LiveRegionMode`.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 3.1.2b: Confirm 150–200ms scanning-state suppression needs no code (~2 min)
- Verify no "scanning" spinner/indicator is added anywhere in `CaptureScreen` — per `research/ux.md` §3a, the correct v1 behavior is to render *nothing* while `scanState is ScanState.NotReady`, which Task 3.1.2a's `pendingSuggestions.isEmpty()` guard already produces with zero extra code.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt` (verification only)

#### Story 3.1.3: Haptics per interaction type

**As a** user, **I want** a satisfying confirm-click when I accept a suggestion, and no jarring haptic when the app silently auto-links text, **so that** haptic feedback matches the weight of each interaction.

**Acceptance Criteria**:
- `HapticFeedbackType.Confirm` fires at the moment of the accept tap (not after the async write resolves); no haptic on auto-applied links; light/no haptic on chip dismiss.
  - *Given* the user taps the "Accept" region of a `CaptureSuggestionChip`, *When* `onAccept` fires, *Then* `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.Confirm)` is called synchronously in the click handler, before `viewModel.acceptSuggestion(...)` (whose write is async) is invoked.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 3.1.3a: Wire `HapticFeedbackType.Confirm` on chip accept (~3 min)
- Inside `CaptureSuggestionChip`, capture `val haptics = LocalHapticFeedback.current`; on the accept click: `haptics.performHapticFeedback(HapticFeedbackType.Confirm); onAccept()` — verify `HapticFeedbackType.Confirm` is available on the project's compose-material3 version at implementation time; omit the haptic call (accept still proceeds) rather than block on it if unavailable, same hedge already applied to `HapticFeedbackType.SegmentTick` below (adversarial-review.md Minor). Dismiss `IconButton`'s `onClick` calls `onDismiss()` with no haptic call (or `HapticFeedbackType.SegmentTick` if available — same verify-or-omit hedge).
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

---

### Epic 3.2: Auto-link inline signal (AC #1, #6)

**Goal**: Communicate auto-applied `[[links]]` without touching the live editable buffer (Pattern Decisions table).

#### Story 3.2.1: Read-only linked-text preview line

**As a** user, **I want** to see which existing pages my capture will link to, **so that** I can catch an obviously-wrong auto-link before saving, without the app rewriting what I'm actively typing.

**Acceptance Criteria**:
- When `scanState is ScanState.Ready` and `result.linkedText != freeText` (at least one auto-link applied), render a small read-only caption below the text field showing the linked form; otherwise render nothing (no empty-state).
  - *Given* `scanState == ScanState.Ready(text = "Reading about Kotlin Multiplatform", result = ScanResult(linkedText = "Reading about [[Kotlin Multiplatform]]", ...))`, *When* `CaptureScreen` renders, *Then* a `Text` composable shows `"Reading about [[Kotlin Multiplatform]]"` in a muted/secondary style, and the `OutlinedTextField`'s own `value` remains the unmodified `captureText` the user is editing — the field is never overwritten by `linkedText`.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 3.2.1a: Add the read-only preview caption (~4 min)
- Below the `OutlinedTextField`, before the suggestion tray:
  ```kotlin
  val readyState = scanState as? CaptureViewModel.ScanState.Ready
  if (readyState != null && readyState.result.linkedText != readyState.text) {
      Text(
          text = readyState.result.linkedText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
      )
      Spacer(Modifier.height(8.dp))
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

---

## Phase 4: Suggestion Accept/Dismiss

### Epic 4.1: Pre-save accept path (AC #2, #7)

**Goal**: Tapping a chip before Save creates a stub page (isolated failure) and folds the link into the pending text.

#### Story 4.1.1: `acceptSuggestion()` pre-save branch

**As a** user, **I want** tapping a suggestion chip before I've saved to create the page and link it in, **so that** my capture lands already-connected to the graph.

**Acceptance Criteria**:
- `CaptureViewModel.acceptSuggestion(term: String)`, when `savedContext == null` (pre-save), constructs a throwaway `GraphWriter(fileSystem, writeActor = repoSet.writeActor)` and calls `writer.savePage(Page(name = term, ...), emptyList(), graphPath)`; on `Either.Left`, logs and returns without throwing (AC #7) — matches Given-When-Then under AC #2/#7 above.
- On success, `_scanState` is updated so the accepted term's `[[link]]` is folded into the text that will be saved (via `ImportService.insertWikiLinks`), and the suggestion is marked `accepted = true` so it stops rendering as a pending chip (Task 3.1.2a's filter already excludes `accepted`).
- The entire method body runs inside `saveOpMutex.withLock { }` (Task 2.3.1b), so an in-flight `save()` and an in-flight `acceptSuggestion()` can never interleave (adversarial-review.md Blocker #2) — see Task 2.3.1b's Given-When-Then for the race this closes.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 4.1.1a: Add `acceptSuggestion()` pre-save branch, serialized via `saveOpMutex` (Blocker #2), isolated `Either.Left` handling (~6 min)
- ```kotlin
  fun acceptSuggestion(term: String) {
      scope.launch {
          saveOpMutex.withLock {
              val ctx = savedContext
              if (ctx == null) {
                  val steleApp = getApplication<SteleKitApplication>()
                  val graphManager = steleApp.graphManager ?: return@withLock
                  val repoSet = graphManager.getActiveRepositorySet() ?: return@withLock
                  val graphPath = graphManager.getActiveGraphInfo()?.path ?: return@withLock
                  val existing = repoSet.pageRepository.getPageByName(term).first().getOrNull()
                  if (existing == null) {
                      val stubPage = dev.stapler.stelekit.model.Page(
                          uuid = dev.stapler.stelekit.model.PageUuid(UuidGenerator.generateV7()),
                          name = term, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
                      )
                      val writer = GraphWriter(steleApp.fileSystem, writeActor = repoSet.writeActor)
                      writer.savePage(stubPage, emptyList(), graphPath).onLeft {
                          logger.error("Stub page save failed for '$term': $it")
                          return@withLock
                      }
                  }
                  markAccepted(term)
              } else {
                  acceptSuggestionPostSave(ctx, term)  // Epic 4.2
              }
          }
      }
  }
  ```
- Note: `return@launch` from the original draft is replaced with `return@withLock` throughout, since every early-return now needs to release the mutex (which `withLock` does automatically) rather than just exiting the coroutine.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 4.1.1b: Add `markAccepted()` to fold the link into pending `_scanState` (~4 min)
- ```kotlin
  private fun markAccepted(term: String) {
      _scanState.update { state ->
          if (state !is ScanState.Ready) return@update state
          val updatedSuggestions = state.result.topicSuggestions.map {
              if (it.term == term) it.copy(accepted = true) else it
          }
          state.copy(result = state.result.copy(
              linkedText = ImportService.insertWikiLinks(state.result.linkedText, listOf(term)),
              topicSuggestions = updatedSuggestions,
          ))
      }
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

#### Story 4.1.2: `dismissSuggestion()`

**As a** user, **I want** to dismiss a suggestion I don't want, **so that** it stops appearing without any write happening.

**Acceptance Criteria**:
- `dismissSuggestion(term: String)` sets `dismissed = true` on the matching `TopicSuggestion` in `_scanState`, synchronously, no coroutine/write involved.

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 4.1.2a: Add `dismissSuggestion()` (~2 min)
- ```kotlin
  fun dismissSuggestion(term: String) {
      _scanState.update { state ->
          if (state !is ScanState.Ready) return@update state
          state.copy(result = state.result.copy(
              topicSuggestions = state.result.topicSuggestions.map {
                  if (it.term == term) it.copy(dismissed = true) else it
              },
          ))
      }
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

#### Story 4.1.3: Transient failure signal for a failed chip accept (AC #7)

**As a** user whose chip-accept write fails (pre-save or post-save), **I want** some visible signal naming the term that failed, **so that** a silently-reverted chip doesn't read as "did my tap even register?" (`research/ux.md` §4's explicit warning, `design/ux.md` Surface 6 / Cross-Check Finding #2). AC #7's literal bar ("surfaced — at minimum logged") is already met by the existing `logger.error(...)` calls in Tasks 4.1.1a/4.2.1a/4.2.1b; this story closes the remaining UX gap by routing the same failures to the `SnackbarHostState` `CaptureScreen` already owns and already uses for save failures (`CaptureActivity.kt:238-248`) — no new notification system.

**Acceptance Criteria**:
- `CaptureViewModel` exposes a one-shot event stream (`SharedFlow`, not `StateFlow` — a `StateFlow` would re-fire the same message on recomposition/config change) that a failed chip accept — pre-save (Task 4.1.1a) or post-save (Tasks 4.2.1a/4.2.1b) — emits into, carrying a message naming the specific term that failed.
  - *Given* `writer.savePage(stubPage, ...)` returns `Either.Left` inside `acceptSuggestion()`'s pre-save branch for term `"Zettelkasten"`, *When* the failure is handled, *Then* `logger.error(...)` fires (unchanged, AC #7's literal bar) **and** `chipFailure` emits `"Couldn't create page for \"Zettelkasten\""`.
  - *Given* `acceptSuggestionPostSave()` detects a graph-identity mismatch for term `"Multiplatform"`, *When* it short-circuits, *Then* `chipFailure` emits `"Couldn't link \"Multiplatform\" — the graph changed"` (same distinct-message requirement PF-5 already established for the log line — only the user-facing surfacing is new, not the message content).
- `CaptureScreen` collects this stream via `LaunchedEffect` and calls `snackbarHostState.showSnackbar(message)` — the same `SnackbarHostState` instance already wired for `SaveState.Error` (`CaptureActivity.kt:238`), not a second host.
- The chip itself is unaffected by this addition: it is left exactly as it already was pre-tap by Tasks 4.1.1a/4.2.1a/4.2.1b (not `accepted`, not removed) — this story only adds the visible signal, it does not change accept/dismiss state handling (AC #7's isolation guarantee, unaffected).

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`, `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 4.1.3a: Add `_chipFailure` one-shot event flow to `CaptureViewModel` (~2 min)
- ```kotlin
  private val _chipFailure = MutableSharedFlow<String>(extraBufferCapacity = 1)
  val chipFailure: SharedFlow<String> = _chipFailure.asSharedFlow()
  ```
- Add imports: `kotlinx.coroutines.flow.MutableSharedFlow`, `kotlinx.coroutines.flow.SharedFlow`, `kotlinx.coroutines.flow.asSharedFlow`.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 4.1.3b: Emit from the pre-save failure branch (Task 4.1.1a) (~2 min)
- In `acceptSuggestion()`'s pre-save branch, alongside the existing `logger.error(...)` call:
  ```kotlin
  writer.savePage(stubPage, emptyList(), graphPath).onLeft {
      logger.error("Stub page save failed for '$term': $it")
      _chipFailure.tryEmit("Couldn't create page for \"$term\"")
      return@withLock
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 4.1.3c: Collect `chipFailure` in `CaptureScreen` via the existing `snackbarHostState` (~3 min)
- Alongside the existing `LaunchedEffect(saveState) { ... }` block (`CaptureActivity.kt:241-251`), add:
  ```kotlin
  LaunchedEffect(Unit) {
      viewModel.chipFailure.collect { message ->
          snackbarHostState.showSnackbar(message)
      }
  }
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

---

### Epic 4.2: Post-save write-back (AC #9)

**Goal**: Exactly the 5-step sequence from `research/architecture.md` Q2, with PF-5's `ClosedSendChannelException` guard.

#### Story 4.2.1: `acceptSuggestionPostSave()` — second write on the same `writer`/`writeActor`

**As a** user who already tapped Save, **I want** to still be able to accept a pending chip, **so that** I don't lose the enrichment opportunity just because I saved quickly.

**Acceptance Criteria**:
- `acceptSuggestionPostSave(ctx: SavedCaptureContext, term: String)` first compares `ctx.graphId` against `GraphManager.getActiveGraphId()`; on a mismatch it short-circuits to a logged, isolated "suggestion not applied — graph changed" failure and does **not** touch any repository or writer (Blocker #1 — the exact ADR-002 constraint 1 violation architecture-review.md and adversarial-review.md both flagged). Only if the graph is still current does it re-check `ctx.pageRepository.getPageByName(term)` immediately before creating the stub (FM-5 — the post-save window is unbounded, per PF-4, so this re-check matters more here than in Import's bounded review screen) — **never** a freshly-fetched "active" `RepositorySet`. It then does exactly: (1) `ctx.writer.savePage(stubPage, ...)` isolated per AC #7, (2) build `updatedBlock = ctx.block.copy(content = ImportService.insertWikiLinks(ctx.block.content, listOf(term)))`, (3) `ctx.writeActor?.saveBlock(updatedBlock)` (falling back to `ctx.blockRepository` if `writeActor` is null) wrapped in the same `try { } catch (e: ClosedSendChannelException) { ... }` guard `performSave()`'s first write already has, with a **distinct**, non-"please retry" message (PF-5), (4) `ctx.writer.savePage(ctx.page, updatedBlocks, ctx.graphPath)`, (5) `savedContext = ctx.copy(block = updatedBlock, blocks = updatedBlocks)` so a second chip accept in the same window still works.
  - *Given* `savedContext = SavedCaptureContext(block = Block(uuid = BlockUuid("b1"), content = "Reading about Kotlin"), graphId = GraphId("g1"), ..., writer = W, writeActor = A, pageRepository = P)` and the active graph is still `GraphId("g1")` when the user taps a "Multiplatform" chip, *When* `acceptSuggestionPostSave` runs, *Then* the existence check goes through `P.getPageByName("Multiplatform")` (never a freshly-fetched repo set), `A.saveBlock(Block(uuid = BlockUuid("b1"), content = "Reading about [[Multiplatform]] Kotlin"))` is called exactly once (same `BlockUuid`, same actor instance `A`), and `W.savePage(...)` is called exactly once more on the same `GraphWriter` instance `W`.
  - *Given* the same `savedContext` (`graphId = GraphId("g1")`) but the user has since switched the active graph to `GraphId("g2")` before tapping the chip, *When* `acceptSuggestionPostSave` runs, *Then* it detects `GraphManager.getActiveGraphId() == GraphId("g2") != ctx.graphId`, logs a "suggestion not applied — graph changed" failure, and returns immediately — `g2`'s `pageRepository` is never queried and `g1`'s block/page are never touched from this call.
  - *Given* a `ClosedSendChannelException` is thrown from `ctx.writeActor.saveBlock(...)` (the actor's channel was closed by a graph switch that happened to race past the identity check above), *When* it is caught, *Then* the surfaced error message is distinct from `performSave()`'s `"Graph switched during save — please retry"` (e.g. `"Suggestion could not be applied — the graph changed"`) and the already-saved block content is left untouched (no retry of the *original* save is implied).

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 4.2.1a: Add `acceptSuggestionPostSave()` — graph-identity guard, then pre-accept existence re-check via `ctx.pageRepository` (~6 min)
- ```kotlin
  private suspend fun acceptSuggestionPostSave(ctx: SavedCaptureContext, term: String) {
      val graphManager = getApplication<SteleKitApplication>().graphManager
      if (graphManager?.getActiveGraphId() != ctx.graphId) {
          logger.error("Suggestion '$term' not applied — active graph changed since save")
          _chipFailure.tryEmit("Couldn't link \"$term\" — the graph changed")
          return
      }
      val existing = ctx.pageRepository.getPageByName(term).first().getOrNull()
      if (existing == null) {
          val stubPage = dev.stapler.stelekit.model.Page(
              uuid = dev.stapler.stelekit.model.PageUuid(UuidGenerator.generateV7()),
              name = term, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
          )
          ctx.writer.savePage(stubPage, emptyList(), ctx.graphPath).onLeft {
              logger.error("Post-save stub page save failed for '$term': $it")
              _chipFailure.tryEmit("Couldn't create page for \"$term\"")
              return
          }
      }
      // Task 4.2.1b continues here (second saveBlock + savePage)
  }
  ```
- The graph-identity guard is the Blocker #1 fix: it replaces the old draft's `steleApp.graphManager?.getActiveRepositorySet()` fetch (which queried whatever graph happened to be active *now*, not the one `ctx` was captured for) with a comparison against `ctx.graphId`, plus reads that go through `ctx.pageRepository` exclusively from this point on.
- The two `_chipFailure.tryEmit(...)` calls are Task 4.1.3a's channel (defined earlier in this file, ahead of Phase 4 in implementation order) — see Story 4.1.3.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 4.2.1b: Second `saveBlock()` with `ClosedSendChannelException` guard, distinct message (~5 min)
- Append to `acceptSuggestionPostSave()`:
  ```kotlin
  val updatedBlock = ctx.block.copy(
      content = ImportService.insertWikiLinks(ctx.block.content, listOf(term)),
      updatedAt = Clock.System.now(),
  )
  val writeResult = try {
      if (ctx.writeActor != null) ctx.writeActor.saveBlock(updatedBlock)
      else { @OptIn(DirectRepositoryWrite::class) ctx.blockRepository.saveBlock(updatedBlock) }
  } catch (e: ClosedSendChannelException) {
      logger.error("Suggestion '$term' not applied — graph changed during post-save write")
      _chipFailure.tryEmit("Couldn't link \"$term\" — the graph changed")
      return
  }
  writeResult.onLeft {
      logger.error("Post-save block write failed for '$term': $it")
      _chipFailure.tryEmit("Couldn't link \"$term\"")
      return
  }
  ```
- Note: the `writeActor == null` fallback now goes through `ctx.blockRepository` (captured in `SavedCaptureContext`, Task 2.3.2a) instead of a freshly-fetched "active" repo set — the same graph-identity fix as the existence check in Task 4.2.1a applies symmetrically here, since a fresh fetch would have the identical wrong-graph failure mode.
- The two `_chipFailure.tryEmit(...)` calls reuse Task 4.1.3a's channel (Story 4.1.3) — same pattern as Task 4.2.1a, message text matches PF-5's distinct-from-save-retry requirement.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

##### Task 4.2.1c: Flush via `ctx.writer.savePage()` and update `savedContext` (~4 min)
- Append to `acceptSuggestionPostSave()`:
  ```kotlin
  val updatedBlocks = ctx.blocks.map { if (it.uuid == updatedBlock.uuid) updatedBlock else it }
  ctx.writer.savePage(ctx.page, updatedBlocks, ctx.graphPath).onLeft {
      logger.error("Post-save markdown flush failed for '$term': $it")
      _chipFailure.tryEmit("Couldn't link \"$term\"")
  }
  savedContext = ctx.copy(block = updatedBlock, blocks = updatedBlocks)
  markAccepted(term)
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`

---

### Epic 4.3: Post-save "Done" UI window (AC #9)

**Goal**: The sheet stays open only while chips are pending, with a resettable, accessibility-aware auto-finish timer.

#### Story 4.3.1: Pending-chip-aware auto-finish

**As a** user who saved with no pending suggestions, **I want** the sheet to close immediately exactly like today, **so that** the common case has zero added latency.

**As a** user who saved with pending suggestions, **I want** a brief window to still tap one, **so that** I don't lose the enrichment opportunity, without the sheet lingering indefinitely.

**Acceptance Criteria**:
- *Given* `saveState == Saved` and `pendingSuggestions.isEmpty()` at that instant, *When* `CaptureScreen` observes the transition, *Then* `onSaved()` fires immediately — identical to today's behavior, zero added taps (Success Metrics).
- *Given* `saveState == Saved` and `pendingSuggestions.isNotEmpty()`, *When* the transition happens, *Then* the sheet enters a "Saved ✓" state and does **not** call `onSaved()` immediately; a resettable ~2.5–3s timer is started.
- *Given* the user taps a chip while in the "Saved ✓" state, *When* the tap is registered, *Then* the timer resets to its full duration (does not simply extend); a tap on the scrim or system back-press finishes immediately regardless of pending chips.
- *Given* TalkBack/accessibility focus is anywhere inside the sheet during the "Saved ✓" state, *When* that focus is active, *Then* the auto-finish timer is paused (not merely extended) until focus leaves.
- *Given* `isDone == true` (the "Saved ✓" state, ≥1 pending chip), *When* the sheet renders, *Then* the `OutlinedTextField` is `enabled = false` for the duration of the window — the block was already saved by `performSave()` and no code path re-reads `captureText` afterward (Epic 4.2's second write only ever touches `ctx.block.content`), so a still-editable field would silently discard keystrokes with no save path and no error, a soft "input" dead end distinct from (and in addition to) the sheet-exit dead end AC #9 already covers (`design/ux.md` Surface 5's Flag / Cross-Check Finding #5, UX AC #15).
  - *Given* `isDone` transitions from `false` to `true`, *When* the `OutlinedTextField` recomposes, *Then* its `enabled` parameter becomes `false` — consistent with the sheet being in a "done, only chips are actionable" state, matching the already-established pattern of disabling the Dismiss button during `Saving` (`CaptureActivity.kt:330`).

**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 4.3.1a: Zero-pending-chips immediate finish (verify no regression) (~2 min)
- Confirm the existing `LaunchedEffect(saveState) { when (state) { is Saved -> onSaved() ... } }` (`CaptureActivity.kt:241-243`) is replaced by a version that branches on `pendingSuggestions.isEmpty()` — write the `isEmpty()` branch first and confirm it calls `onSaved()` with no added `delay()`, matching today.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 4.3.1b: Resettable timer for the pending-chips branch (~5 min)
- ```kotlin
  var resetKey by remember { mutableIntStateOf(0) }
  var isDone by remember { mutableStateOf(false) }
  LaunchedEffect(saveState, pendingSuggestions.isEmpty()) {
      if (saveState == CaptureViewModel.SaveState.Saved) {
          if (pendingSuggestions.isEmpty()) onSaved() else isDone = true
      }
  }
  LaunchedEffect(isDone, resetKey, hasAccessibilityFocus) {
      if (isDone && !hasAccessibilityFocus) {
          delay(2_750)
          onSaved()
      }
  }
  ```
- Each chip's `onAccept`/`onDismiss` callback also does `resetKey++` when `isDone` is true, so the second `LaunchedEffect` restarts.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 4.3.1c: Pause on accessibility focus; scrim tap always finishes during the "Done" window (real code change, not verify-only) (~5 min)
- Track `hasAccessibilityFocus` via `Modifier.onFocusEvent { ... }` (or the accessibility-focus callback available on this Compose/Material3 version — verify exact API at implementation time) on the sheet's root `Surface`; wire into Task 4.3.1b's second `LaunchedEffect` key.
- **This is a real code change, not verification-only** (architecture-review.md Concern, Epic 4.3): the existing scrim `clickable { if (captureText.isBlank()) onDismiss() else viewModel.save() }` (`CaptureActivity.kt:274`) has no branch for the "Done" window at all. During that window `captureText` is still non-blank, so an unmodified scrim tap would call `viewModel.save()` again instead of finishing immediately — directly contradicting Story 4.3.1's own AC ("a tap on the scrim... finishes immediately regardless of pending chips"). Update the scrim's `clickable` to add the `isDone` branch first:
  ```kotlin
  clickable {
      if (isDone) onSaved() else if (captureText.isBlank()) onDismiss() else viewModel.save()
  }
  ```
  - *Given* `isDone == true` (the sheet is in the post-save "Done" window with pending chips), *When* the user taps the scrim, *Then* `onSaved()` fires immediately — `viewModel.save()` is never called a second time for an already-saved capture.
- `BackHandler` needs no change: `BackHandler(enabled = saveState == Idle)` already means back is unhandled once `saveState == Saved`, so it already falls through to the default dispatcher and finishes the Activity with no gap (confirmed clean in architecture-review.md).
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

##### Task 4.3.1d: Disable the text field during the "Done" window (~2 min)
- Hoist Task 4.3.1b's `var isDone by remember { mutableStateOf(false) }` declaration to above the `OutlinedTextField` call site in `CaptureScreen` (it currently reads naturally alongside `resetKey` right before the two `LaunchedEffect`s, which sit *after* the text field in file order today — only the `remember` declaration needs to move up; the `LaunchedEffect` bodies stay where Task 4.3.1b puts them). Add `enabled = !isDone` to the existing `OutlinedTextField(...)` call (`CaptureActivity.kt:312-321`):
  ```kotlin
  OutlinedTextField(
      value = captureText,
      onValueChange = viewModel::updateText,
      enabled = !isDone,
      modifier = Modifier
          .fillMaxWidth()
          .focusRequester(focusRequester),
      placeholder = { Text("Capture a note…") },
      minLines = 3,
      maxLines = 8,
  )
  ```
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`

---

## Phase 5: Tests

### Epic 5.1: Coordinator/GraphManager tests

Covered by **Task 1.2.2a** (`GraphManagerEnrichmentCoordinatorTest`). One additional domain-level test:

#### Story 5.1.1: `CaptureEnrichmentCoordinatorTest` (budget timeout, `Throwable` degradation)

**Acceptance Criteria**:
- *Given* a `PageNameIndex` whose matcher is still `null`, *When* `coordinator.scan(text)` is called, *Then* it returns `ScanOutcome.MatcherNotReady` without invoking `withTimeoutOrNull` work (fast path).
- *Given* a matcher that is ready but a scan body artificially delayed past `budgetMs`, *When* `coordinator.scan(text, budgetMs = 10)` is called, *Then* it returns `ScanOutcome.TimedOut` — distinguishable from `MatcherNotReady` by type, not just by both being `null`.
- *Given* `topicEnricher.enhance()` throws (a fake `TopicEnricher` that throws `RuntimeException`), *When* `coordinator.enhance(text, local)` is called, *Then* it returns `local` unchanged and does not propagate the exception.
- *Given* `topicEnricher.enhance()` returns normally with `[TopicSuggestion(term = "", confidence = 5f, ...), TopicSuggestion(term = "Dup", confidence = 0.5f, ...), TopicSuggestion(term = "dup", confidence = 0.9f, ...)]`, *When* `coordinator.enhance(text, local)` is called, *Then* the returned list has no blank-term entry, all confidences are within `0f..1f`, and exactly one `"Dup"`/`"dup"` entry survives (Task 1.1.1c's `sanitize()`, AC #3).

**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt` (new)

##### Task 5.1.1a: Write `CaptureEnrichmentCoordinatorTest` (~6 min)
- Model after `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/PageNameIndexTest.kt`'s fake `PageRepository` setup; add a fake `TopicEnricher` that throws, and a fake `TopicEnricher` that returns malformed output, for the `sanitize()` case.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt`

### Epic 5.2: `CaptureViewModel` regression tests

**Note (carried-over finding, `research/architecture.md`)**: any test exercising `save()`/`acceptSuggestion()` against the real `graphManager` must **not** apply `@Config(application = Application::class)` — that override (used by the existing whitespace test) swaps out `SteleKitApplication`, which is exactly what these new paths need. Expect the heavier real-`SteleKitApplication` Robolectric path.

#### Story 5.2.1: `onNewIntent` stale-scan test (PF-6)

**Acceptance Criteria**: *Given* a scan is in flight for share-intent A's text, *When* share-intent B's text arrives (via a second `initializeText` call after the field was cleared) before A's scan completes, *Then* `collectLatest` guarantees only B's scan result ever reaches `_scanState` — A's result never applies to text the user has moved past.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

##### Task 5.2.1a: Add the `onNewIntent`/stale-scan test case (~5 min)
- Use a `CoroutineScope`/`TestDispatcher`-driven fake `CaptureEnrichmentCoordinator` (or a fake `GraphManager.getOrCreateEnrichmentCoordinator()` return) with a controllable `scan()` delay to construct the race deterministically.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 5.2.2: Image-only-share scan test

**Acceptance Criteria**: *Given* `captureText.value` is the exact composite `"[image: <path>]\n"` (no caption), *When* the scan trigger fires, *Then* `ImportService.scan` (or the coordinator's `scan()`) is never invoked with the raw path string as input — verified via a spy/fake coordinator asserting the text it received equals `""`, not the composite.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` (or a new `CaptureImagePrefixTest.kt` if the file grows unwieldy)

##### Task 5.2.2a: Add `splitImagePrefix()` unit tests (~3 min)
- Table-driven: composite-with-caption, composite-without-caption (bare image), no-image-prefix-at-all.
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 5.2.3: AC #9 post-save write path test

**Acceptance Criteria**: *Given* a successful `performSave()` populated `savedContext`, *When* `acceptSuggestion(term)` is called, *Then* the same `writer`/`writeActor` instances are reused (asserted via reference equality against what `performSave()` constructed) and exactly one additional `saveBlock`/`savePage` pair is invoked.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

##### Task 5.2.3a: Add the AC #9 post-save write-path test (~5 min)
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 5.2.4: Graph-switch race on the second write (PF-5)

**Acceptance Criteria**: *Given* `savedContext.writeActor`'s channel is closed (simulating a graph switch during the post-save window), *When* `acceptSuggestion(term)` is called, *Then* `ClosedSendChannelException` is caught, a distinct (non-"please retry") message is logged, and no exception escapes to crash the sheet.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

##### Task 5.2.4a: Add the graph-switch-race test with a closed-channel fake `DatabaseWriteActor` (~5 min)
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 5.2.5: Graph-identity guard test (Blocker #1)

**Acceptance Criteria**: *Given* `savedContext.graphId == GraphId("g1")` but a fake `GraphManager.getActiveGraphId()` now returns `GraphId("g2")`, *When* `acceptSuggestion(term)` is called (routing to `acceptSuggestionPostSave()`), *Then* neither `ctx.pageRepository.getPageByName(...)` nor `ctx.writer.savePage(...)` nor `ctx.writeActor.saveBlock(...)` is ever invoked — the method returns after logging "suggestion not applied — graph changed" and no state under `ctx` is mutated. This is distinct from Story 5.2.4's `ClosedSendChannelException` test: it exercises the graph-identity guard added in Task 4.2.1a directly, for the case where the newly-active graph's `writeActor` channel is still open (so the exception-based guard alone would not have caught it).

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

##### Task 5.2.5a: Add the graph-identity-mismatch test with fake `pageRepository`/`writer`/`writeActor` spies asserting zero invocations (~5 min)
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 5.2.6: `save()` / `acceptSuggestion()` serialization test (Blocker #2)

**Acceptance Criteria**: *Given* a controllable-delay fake stub-page write inside `acceptSuggestion()`'s pre-save branch, *When* `acceptSuggestion(term)` is launched and `save()` is launched immediately after (before the accept's write completes), *Then* `save()`'s persisted `textToSave` includes the accepted term's `[[link]]` — proving `saveOpMutex` (Task 2.3.1b) served the accept to completion (including its `markAccepted()` link-fold) before `save()`'s `_scanState` snapshot was taken, not the stale, unlinked text a naive race would produce.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

##### Task 5.2.6a: Add the chip-tap-then-Save race test with a `TestDispatcher`-controlled delay on the stub-page write (~6 min)
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 5.2.7: Scan collector survives a `Throwable` (Blocker #3)

**Acceptance Criteria**: *Given* a fake `CaptureEnrichmentCoordinator.scan()` that throws an `OutOfMemoryError` (or any `Throwable`) on its first invocation and succeeds on its second, *When* the user types text twice (two debounced text changes), *Then* the first attempt degrades `_scanState` to `NotReady` without crashing or cancelling the collector, and the *second* text change still produces a normal `ScanState.Ready` result — proving the per-iteration `try/catch` in Task 2.1.2b (not just the `CoroutineExceptionHandler` in Task 2.1.2a) is what keeps the collector alive.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

##### Task 5.2.7a: Add the scan-survives-`Throwable` test with a fake coordinator that throws once then succeeds (~5 min)
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 5.2.8: AC #5 — `save()` never suspends on coordinator/scan work (adversarial-review.md Concern)

**As a** maintainer, **I want** an automated check that `save()`'s critical path can't regress into blocking on enrichment, **so that** AC #5 (no responsiveness regression) has a falsifiable test, not just an architectural argument.

**Acceptance Criteria**: *Given* a fake `CaptureEnrichmentCoordinator` whose `scan()` suspends indefinitely (never completes) via a `CompletableDeferred` the test never resolves, *When* `save()` is called while a scan is in flight against that stuck coordinator, *Then* `save()` completes (`_saveState.value` reaches `Saved`/`Error`) without ever awaiting the stuck `scan()` call — a synchronous proof that `save()`'s `textToSave` computation (Task 2.3.1a) only ever reads `_scanState.value` and never calls into the coordinator itself.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` (or `CaptureAndImportTest.kt` if colocating with existing golden-path tests reads better)

##### Task 5.2.8a: Add the "save doesn't await a stuck scan" test with a never-resolving fake `scan()` (~5 min)
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt`

#### Story 5.2.9: AC #6 negative cases — no haptic on auto-link, dismiss doesn't confirm (adversarial-review.md Concern)

**As a** maintainer, **I want** one consolidated test for AC #6's stated negative cases, **so that** "no haptic on auto-applied links" and "dismiss doesn't fire an accept-shaped signal" are enforced, not just prose in Story 3.1.3's AC.

**Acceptance Criteria**:
- *Given* `scanState` transitions to `Ready` with a non-empty auto-linked preview (Story 3.2.1), *When* the read-only preview `Text` composable renders, *Then* no `performHapticFeedback` call is ever recorded on a spy `HapticFeedback` provided via `CompositionLocalProvider(LocalHapticFeedback provides spy)` — auto-applied links never fire a haptic.
- *Given* a `CaptureSuggestionChip`'s dismiss `×` is tapped, *When* `onDismiss` fires, *Then* the same spy records no `HapticFeedbackType.Confirm` call (only, at most, the lighter dismiss haptic from Task 3.1.3a's hedge) — dismiss is never confirm-shaped.

**Files**: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureActivityTest.kt` (new, or an existing Compose UI test file for `CaptureActivity` if one exists — verify at implementation time)

##### Task 5.2.9a: Add the consolidated AC #6 negative-case test with a spy `HapticFeedback` (~6 min)
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureActivityTest.kt`
