# Research: Architecture — capture-auto-enrich

Scope: how to wire `ImportService.scan()` + `TopicExtractor` + (optionally) an LLM
enhancement tier into `CaptureViewModel.performSave()` / `CaptureScreen`, following the
patterns already established by `ImportViewModel`/`ImportScreen` (ADR-004) and by the
`llm-service` provider abstraction. This is a straightforward integration of two already-
shipped pipelines into a third entry point — no new algorithm, no ECP table.

Builds directly on two prior research docs (read in full, cited inline below, not
re-derived):
- `project_plans/llm-service/research/architecture.md` — provider registry / credential /
  approval-workflow architecture.
- `project_plans/llm-provider/research/architecture.md` — original tag-suggestion two-tier
  engine design (superseded in part by `llm-service`, but the two-tier local-first /
  LLM-second pattern still applies).

## Current-state findings (code, not docs)

`project_plans/llm-service/implementation/plan.md` still reads "Status: Ready for
validation (Phase 4)" (2026-07-01), but the actual `kmp/src` tree shows Epics 1, 2, 3, 4, 5,
6 landed:

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/` — `LlmProvider`, `LlmProviderKind`,
  `LlmProviderAvailability`, `LlmProviderRegistry`, `LlmProviderRegistryFactory`
  (`buildLlmProviderRegistry(llmCredentialStore, llmSettings)`), `LlmSettings`,
  `LlmSuggestionInbox`, `RemoteLlmProvider`, `CustomOpenAiCompatibleLlmProvider`,
  `PlatformOnDeviceLlmProvider` (expect) with real actuals in `androidMain`/`iosMain`,
  no-op in `jvmMain`/`wasmJsMain`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt` —
  `CredentialStore` was relocated out of `git/` per that doc's §2 recommendation, with
  Android/iOS/JVM/WASM actuals present.
- `ui/App.kt` (~L488-499) wires the production composition root: `llmCredentialStore =
  LlmCredentialStore(CredentialStore())`, `llmSettings = LlmSettings(platformSettings)`,
  `llmProviderRegistry = buildLlmProviderRegistry(llmCredentialStore, llmSettings)`. The
  doc comment on `LlmProviderRegistryFactory.kt` confirms Epic 8 migrated tag-suggestion and
  voice-formatting onto this registry — "the single production-code composition root for
  both consumers now, not just the Settings UI."
- **Not migrated**: `domain/ClaudeTopicEnricher.kt` (the Import-screen enrichment tier) is
  still the standalone hand-rolled Claude client the `llm-service` doc flagged — it does
  **not** go through `LlmFormatterProvider`/`LlmProviderRegistry`. Per that plan's own
  sequencing note, `ClaudeTopicEnricher` migration is Epic 8's last step and evidently
  hasn't landed: `ui/ScreenRouter.kt:255` constructs the production `ImportViewModel`
  **without** a `topicEnricher` argument at all, so it defaults to `NoOpTopicEnricher()`.
  **The Import screen's LLM tier is off in production today** — only the local heuristic
  tier (`TopicExtractor` via `ImportService.scan()`) is wired end-to-end.

This matters directly for capture-auto-enrich: there is no existing "call the configured
LLM provider for topic enrichment" call site to copy. `ClaudeTopicEnricher` implements the
`fun interface TopicEnricher { suspend fun enhance(...) }` contract
(`domain/TopicEnricher.kt`), so the correct move — consistent with the requirement "reuse
whichever LLM provider is configured via the llm-service abstraction... no new provider
code" — is a **new**, small `TopicEnricher` adapter over the existing `LlmProviderRegistry`
(structurally identical to `tags/LlmTagProvider`, which already adapts
`LlmFormatterProvider` for a different prompt), not a dependency on `ClaudeTopicEnricher`.
This is glue code (an adapter implementing an existing interface over an existing
registry), not new provider/algorithm code, and it happens to also be the natural first
half of the Epic 8 `ClaudeTopicEnricher` migration the `llm-service` plan already calls for
— worth flagging as a shared win in the planning phase.

---

## 1. Where CaptureViewModel gets GraphManager/RepositorySet, and whether a matcher is cached

**No `PageNameIndex`/matcher is reachable from `CaptureViewModel` today, and none is cached
above the graph-loading layer.**

- `CaptureViewModel` (`androidApp/.../CaptureViewModel.kt:57-58`) reaches the graph purely
  through `getApplication<SteleKitApplication>()`: `steleApp.graphManager` (nullable,
  process-scoped `Application` field, `SteleKitApplication.kt:55`) and
  `steleApp.fileSystem`. `graphManager.getActiveRepositorySet()` returns the
  `RepositorySet` for whatever graph is currently active.
- `RepositorySet` (`repository/RepositoryFactory.kt:47-69`) has no `PageNameIndex` or
  `AhoCorasickMatcher` field — it carries `pageRepository`, `blockRepository`, `writeActor`,
  etc., but nothing precomputed for matching.
- `PageNameIndex` is constructed exactly once in the codebase, at
  `ui/StelekitViewModel.kt:516`: `val pageNameIndex = PageNameIndex(pageRepository, scope)`,
  where `scope` is `StelekitViewModel`'s own `CoroutineScope`. `suggestionMatcher: StateFlow
  <AhoCorasickMatcher?> = pageNameIndex.matcher` (`StelekitViewModel.kt:519`) is what
  `ImportViewModel` is constructed with in production
  (`ui/ScreenRouter.kt:260`: `matcherFlow = viewModel.suggestionMatcher`).
- `StelekitViewModel` lives inside the main app's Compose tree (`App.kt`/`ScreenRouter.kt`),
  tied to the main `Activity`'s lifecycle. `CaptureActivity` is a separate, standalone
  overlay `Activity` (share-sheet/widget/tile target) that can cold-start **without the main
  Activity or its Compose tree ever running in that process launch**. It has no path to an
  existing `StelekitViewModel` instance or its `pageNameIndex`.

**Conclusion**: `CaptureViewModel` must build its own `PageNameIndex` instance, scoped to
itself, the first time a capture happens — it cannot reuse a cache that doesn't exist at
the `Application`/`GraphManager` layer. This is not extra complexity: `PageNameIndex` is
already designed to be constructed cheaply and reactively
(`PageNameIndex(pageRepository, scope)`, `domain/PageNameIndex.kt:35`) — construction itself
does no work; it `launch`es a collector over `pageRepository.getPageNameEntries()` (a
names-only projection per `CLAUDE.md`'s bounded-reads rule, already used this way by
`StelekitViewModel`) debounced 500 ms, and the `AhoCorasickMatcher` rebuild happens on
`Dispatchers.Default` with `catch`/`Throwable`-guards already in place (OOM-safe — degrades
to `null` matcher, never crashes). `viewModelScope` is the correct scope to pass — it is a
real `ViewModel`-owned scope tied to `onCleared()`, not a `rememberCoroutineScope()`, so it
does not trip the `CLAUDE.md` "never let a Compose-remembered scope escape composition"
rule; `CaptureViewModel` is an `AndroidViewModel`, structurally the same category of owner
`StelekitViewModel` already is.

Practical construction site: inside `save()` (or lazily on `CaptureViewModel` init once
`graphManager.getActiveRepositorySet()` first resolves), do:

```kotlin
private var pageNameIndex: PageNameIndex? = null   // built lazily, once, per graph

private fun matcherFor(repoSet: RepositorySet): StateFlow<AhoCorasickMatcher?> =
    (pageNameIndex ?: PageNameIndex(repoSet.pageRepository, viewModelScope).also { pageNameIndex = it })
        .matcher
```

Cost/latency note (pitfall from requirements §"Pitfalls"): building the matcher itself is
async and non-blocking — the risk is not "building the index blocks `requestFocus()`" (it
never touches the UI thread synchronously), it is "the *first* build on a large graph
(8000+ pages) may not be ready by the time the user wants to save." That is exactly what
the requirement's timeout/fallback behavior is for (§4 below) — no gate is needed on
`focusRequester.requestFocus()` because index construction is fired off independently and
awaited only inside the save/enrichment path, never inside the composable's first frame.

## 2. `PlatformOnDeviceLlmProvider`/`LlmProviderRegistry` reachability

Unlike `PageNameIndex`, the LLM registry composition root has no graph dependency at all —
`buildLlmProviderRegistry(llmCredentialStore, llmSettings)` only needs a
`platform.security.CredentialStore()` (plain no-arg constructor, confirmed at
`ui/App.kt:489`) and a `LlmSettings(platformSettings)`, where `platformSettings` is
`PlatformSettings()` (`ui/App.kt:257`), also no-arg/context-free on Android (backed by
`SharedPreferences`). `CaptureViewModel` can therefore construct the exact same registry
App.kt does, independently, with zero new plumbing:

```kotlin
val llmCredentialStore = LlmCredentialStore(CredentialStore())
val llmSettings = LlmSettings(PlatformSettings())
val registry = buildLlmProviderRegistry(llmCredentialStore, llmSettings)
val selected = llmSettings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION)  // or a new CAPTURE_ENRICHMENT feature
    ?.let(registry::find) ?: registry.availableProviders().firstOrNull()
```

This is what "reuse whichever LLM provider is configured via the llm-service
abstraction... no new provider code" (requirements, Must Have) resolves to concretely. The
only genuinely new code is the small `TopicEnricher` adapter from §"Current-state findings"
wrapping `selected.formatter` (an `LlmFormatterProvider`) — same shape as
`tags/LlmTagProvider`, reusing `TopicExtractor`'s existing suggestion list as the prompt
context per `ImportViewModel.runScan`'s pattern (local suggestions computed first, LLM tier
augments/replaces asynchronously).

Whether to add a new `LlmFeature.CAPTURE_ENRICHMENT` enum entry (`llm/LlmSettings.kt`, next
to the existing `LlmFeature` values referenced in the `llm-service` architecture doc) vs.
reuse `TAG_SUGGESTION`'s selection is a Phase-3 planning call, not an architecture question
— both are supported by the existing `LlmSettings` shape.

## 3. `SaveState` and the suggestion-chip review step

**Recommendation: do not change the shape of `SaveState` (`CaptureViewModel.kt:35-40`) —
add a second, independent `StateFlow` for enrichment, and gate the UI's post-save dismiss
on both.**

Rationale: `SaveState.Idle/Saving/Saved/Error` is consumed today via both `==` comparisons
(`saveState == SaveState.Idle`, `CaptureActivity.kt:234,310`) and `is` matches
(`CaptureActivity.kt:218-219`). Coupling enrichment progress into this enum (e.g., a
`Saved(pendingSuggestions: Boolean)` data class) would work mechanically but conflates two
orthogonal concerns — "did the write succeed" vs. "is there an optional follow-up review
step" — and risks breaking the `==` comparisons that assume `Idle`/`Saving` stay singleton
`data object`s. `ImportState` (`ui/screens/ImportViewModel.kt:59-81`) already demonstrates
the codebase's answer to this exact shape question: it keeps `isSaving`/`savedPageName` as
plain fields alongside — not nested inside — the suggestion-tray fields
(`topicSuggestions`, `isEnhancing`, `claudeStatus`). Mirror that: add a second state class
next to `SaveState`, e.g.:

```kotlin
sealed interface EnrichmentState {
    data object Idle : EnrichmentState
    data object Scanning : EnrichmentState
    data class Ready(
        val linkedText: String,
        val matchedPageNames: List<String>,
        val topicSuggestions: List<TopicSuggestion> = emptyList(),
        val isEnhancing: Boolean = false,          // LLM tier still in flight
    ) : EnrichmentState
    data object TimedOut : EnrichmentState          // scan/local tier didn't finish in budget
}
```

exposed as `val enrichmentState: StateFlow<EnrichmentState>`, populated by a debounced
scan triggered from `updateText()` (same 300 ms debounce pattern as
`ImportViewModel.onRawTextChanged`, `ui/screens/ImportViewModel.kt:174-195`), independent
of `save()`/`SaveState`.

`CaptureScreen`'s existing `LaunchedEffect(saveState) { is SaveState.Saved -> onSaved() }`
(`CaptureActivity.kt:216-226`) becomes `LaunchedEffect(saveState, enrichmentState)`: only
call `onSaved()` immediately if `saveState is Saved` **and** there are no unresolved
suggestions (`enrichmentState` is not `Ready` with any suggestion that is
`!accepted && !dismissed`); otherwise the sheet stays open showing the chip tray with a
"Done" button that calls `onSaved()` explicitly. No `SaveState` shape change; `Save` button
`enabled =` logic (`CaptureActivity.kt:310`) is untouched.

## 4. Where `scan()` belongs relative to the two bug-mitigations, and timeout mechanism

Current `performSave()` order (`CaptureViewModel.kt:71-118`):
1. Resolve `repoSet`/`page`/`graphPath`/`existingBlocks`.
2. Build `newBlock` from raw `text`.
3. **Bug 1 mitigation** (L100-111): write via `writeActor.saveBlock()`, catching
   `ClosedSendChannelException` from a graph-switch race.
4. **Bug 8 mitigation** (L113-117): construct `GraphWriter`, `startAutoSave()`, then
   `savePage()` to flush Markdown to disk.

**Recommendation: the scan must run and resolve (or time out) *before* step 2 — it produces
the content step 2 writes — and must not touch the write path itself.** This keeps both
existing bug-mitigation blocks completely untouched (they still operate on whatever
`content` string they're handed; they don't care whether it's raw or linked text), which
minimizes risk to two fixes that were clearly hard-won.

```kotlin
private suspend fun performSave(...): Result<Unit> = runCatching {
    val repoSet = graphManager.getActiveRepositorySet() ?: error(...)
    val page = repoSet.journalService.ensureTodayJournal()
    val graphPath = graphManager.getActiveGraphInfo()?.path ?: error(...)
    val existingBlocks = repoSet.blockRepository.getBlocksForPage(page.uuid).first().getOrElse { error(...) }

    // NEW: time-boxed scan — resolves to raw text unchanged on timeout/no-matcher/failure.
    val finalContent = resolveEnrichedContent(repoSet, text)   // <= existing `text` on any non-happy path

    val now = Clock.System.now()
    val newBlock = Block(..., content = finalContent, ...)     // was: content = text

    // Bug 1 mitigation — unchanged, operates on newBlock as before.
    // Bug 8 mitigation — unchanged.

    // NEW, only if accepted-before-save suggestions exist: create stub pages, same as
    // ImportViewModel.confirmImport() (pageSaver.save(stubPage, emptyList(), graphPath)),
    // using the *same* GraphWriter instance already constructed for Bug 8's flush.
}
```

`resolveEnrichedContent` should reuse the pending `enrichmentState` from §3 if it's already
`Ready` (the debounced background scan may have finished while the user was still typing —
common case, since `ImportService.scan()` is a pure, fast function once the matcher
exists), and only fall back to running a fresh bounded scan if `enrichmentState` is still
`Scanning`/`Idle` at the moment `save()` is tapped.

Timeout mechanism (`CLAUDE.md`-consistent, and directly precedented by
`ImportViewModel.runScan`'s `withTimeout(8_000) { topicEnricher.enhance(...) }`,
`ui/screens/ImportViewModel.kt:247`): use `withTimeoutOrNull`, not `withTimeout`, because a
timeout here must degrade to the raw-text save, not throw and fail the whole save:

```kotlin
private suspend fun resolveEnrichedContent(repoSet: RepositorySet, text: String): String {
    val matcher = withTimeoutOrNull(RESPONSIVENESS_BUDGET_MS) {   // 500ms per requirements' budget
        matcherFor(repoSet).filterNotNull().let { flow -> flow.firstOrNull() ?: flow.first() }
    } ?: return text   // matcher not ready in budget → save raw text, exactly today's behavior

    val result = withContext(Dispatchers.Default) { ImportService.scan(text, matcher, existingNames) }
    // topicSuggestions surfaced via enrichmentState for the chip tray; LLM tier (if any)
    // continues independently in the background per §5 and never gates this return.
    return result.linkedText
}
```

The **local heuristic tier is bounded by the 500 ms/10 KB budget** (requirements'
Constraints section, matching `import-topic-suggestions`'s own target); the **optional LLM
tier is explicitly best-effort and must never gate save at all** — it should be `launch`ed
independently (fire-and-forget into `enrichmentState`, same two-coroutine split
`ImportViewModel.runScan` already uses: local tier updates state synchronously, LLM tier
`scope.launch { withTimeout(8_000) { ... } }` updates it again later) so a slow/unavailable
LLM provider never affects `performSave()`'s critical path at all — not even via a timeout,
since it isn't awaited there in the first place.

Cancellation: because `save()` already runs in `viewModelScope.launch { ... }`
(`CaptureViewModel.kt:63-68`), cancelling the enrichment `Job` when a second `save()` call
or `onCleared()` fires is automatic/structural — no separate cancellation code needed beyond
keeping a reference to the background-scan `Job` (mirrors `ImportViewModel.scanJob`,
`ui/screens/ImportViewModel.kt:161,177`) so a new keystroke can `scanJob?.cancel()` a
stale in-flight scan.

## 5. Data flow: fold-before-save vs. retroactive post-save edit

**Recommendation: fold-before-save is the default and only write path in v1; do not build
a retroactive post-save content-edit path.**

Evidence for this call:

- **Existing-page auto-linking** (no confirmation, per requirements) has no "after save"
  case to reason about at all — it's computed synchronously as part of
  `resolveEnrichedContent` before the block is ever constructed, identical in spirit to how
  `ImportViewModel` always auto-links matched pages into `linkedText` with no user gesture
  required (`ui/screens/ImportViewModel.kt:216-229`).
- **New-page topic-suggestion chips** (require explicit accept) follow
  `ImportViewModel.confirmImport()`'s precedent exactly: stub pages are **not** created at
  chip-accept time — `onSuggestionAccepted` (`ui/screens/ImportViewModel.kt:309-321`) only
  mutates in-memory `linkedText`/`topicSuggestions` state; stub-page creation
  (`pageSaver.save(stubPage, emptyList(), graphPath)`) happens once, at `confirmImport()`,
  for every `accepted` suggestion still outstanding. The natural analogue in
  `CaptureViewModel` is: "accept" before `save()` is tapped only updates
  `enrichmentState` (checkmarks the chip, updates the linked-text preview) exactly like
  `ImportViewModel.onSuggestionAccepted`; `performSave()` is the single place that (a)
  creates stub pages for every still-accepted suggestion and (b) writes the final linked
  block content — both using the `GraphWriter`/`writeActor` instance already constructed
  for the Bug 8 flush, no second write path.
- **Requirements' own text is the tie-breaker**: "accepting a suggestion chip creates a
  stub page... and (**if the accept happens before save**) folds the new wiki link into the
  saved block content" — phrased as the qualifying/expected case, not one of two symmetric
  branches. There is no corresponding sentence describing what "after save" acceptance does
  to already-written content, which reads as the spec deliberately leaving that case
  unaddressed rather than requiring it.
- **A true retroactive edit is materially riskier for a v1 integration.** It would require:
  re-reading the already-persisted block from `blockRepository` (a second DB round-trip
  after the file has already been flushed to disk by Bug 8's `writer.savePage()`), applying
  `ImportService.insertWikiLinks` to its *current* content (which could itself have drifted
  if the user re-opened and edited the journal in the interim — a real possibility given
  `CaptureActivity` is intentionally a fire-and-forget overlay the user may reopen the main
  app right after), then issuing a second `writeActor.saveBlock()` + `GraphWriter.savePage()`
  pair. That's a second full write cycle carrying its own version of Bug 1's
  `ClosedSendChannelException` race, for a UX benefit (accepting a chip that finished
  computing a beat after Save was tapped) that's better solved by simply **not
  auto-dismissing the sheet** when suggestions are still pending — i.e., §3's
  `EnrichmentState`-gated `onSaved()` call. If the local heuristic scan is within its
  500 ms/10 KB budget (which it should be for the vast majority of captures — this is the
  same budget `import-topic-suggestions` already validated as achievable), suggestions are
  simply ready by the time the user taps Save in the overwhelming common case, and the
  "still Scanning when Save was tapped" case is rare enough that "the sheet stays open one
  extra beat, chips arrive, user reviews before dismiss" is sufficient — no separate
  post-save write path is needed to serve it.

If a future iteration finds users routinely tapping Save faster than the scan resolves
(telemetry question, not an architecture one), the fallback is to extend the *existing*
open-sheet-until-reviewed flow's time budget, not to add a second write mechanism.

---

## Summary table — files touched

| File | Change |
|---|---|
| `androidApp/.../CaptureViewModel.kt` | Add `PageNameIndex` (lazy, per-graph), `EnrichmentState` + `StateFlow`, debounced scan triggered from `updateText()`, `resolveEnrichedContent()` inserted into `performSave()` before block construction, stub-page creation loop before the existing write calls, new `TopicEnricher` adapter wired via `buildLlmProviderRegistry`. |
| `androidApp/.../CaptureActivity.kt` | `CaptureScreen` observes `enrichmentState` alongside `saveState`; renders a chip tray (mirrors `ImportScreen`'s `TopicSuggestionTray` / ADR-004); gates the `onSaved()` auto-dismiss on no unresolved suggestions; adds a "Done" button for the post-save-but-pre-dismiss review step. |
| `kmp/src/commonMain/.../domain/` (new, small) | `LlmBackedTopicEnricher` (or similarly named) adapter: `TopicEnricher` implemented over an `LlmProvider.formatter` from `LlmProviderRegistry` — same shape as `tags/LlmTagProvider`. Not a new provider, not new LLM call logic — an adapter over existing pieces. |
| `kmp/src/commonMain/.../llm/LlmSettings.kt` | Optional: add `LlmFeature.CAPTURE_ENRICHMENT` if capture enrichment should have independent provider selection from tag suggestion (Phase-3 call). |

## Open questions for planning phase

1. Does capture enrichment get its own `LlmFeature` selection, or default to whatever
   `TAG_SUGGESTION` (or a shared "content enrichment" grouping) resolves to? Both are
   supported by `LlmSettings` as-is.
2. Exact prompt/adapter class name and package for the new `TopicEnricher` LLM adapter —
   naming/location only, not a design question (pattern is `tags/LlmTagProvider`).
3. Whether the Android share-widget cold-start path (no `Application.graphManager` yet
   resolved, per `SteleKitApplication`'s async init) needs its own explicit "graph not
   ready yet" `EnrichmentState`/UX treatment distinct from "matcher timed out" — worth a
   quick look at `SteleKitApplication.kt:83-92`'s init-deferred pattern in planning.
