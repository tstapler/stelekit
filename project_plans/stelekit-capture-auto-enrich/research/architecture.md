# Architecture Research: stelekit-capture-auto-enrich

**Agent**: 3 (Architecture)
**Date**: 2026-08-27

## Prior review — what carries over from `android-share-capture-whitespace`

[`architecture-review.md`](../../android-share-capture-whitespace/implementation/architecture-review.md)
reviewed a different change to the same two files (`CaptureActivity`/`CaptureViewModel`:
share-text whitespace normalization, not enrichment). Two findings are load-bearing for
this feature too:

1. **`SteleKitApplication` is the real manifest-declared `<application>`
   (`AndroidManifest.xml:24`), not a plain `Application`.** Under Robolectric,
   `ApplicationProvider.getApplicationContext<Application>()` in any new
   `CaptureViewModelTest` instantiates the real `SteleKitApplication.onCreate()` —
   real `DriverFactory`/SQLite, `GraphManager`, camera/ARCore/BLE/ONNX providers, all
   under one outer `catch (Throwable)`. Any new test that exercises `save()` (or the new
   enrichment path) against the real Application either needs
   `@Config(application = Application::class)` to get a lightweight stand-in, or must
   explicitly accept the heavier Robolectric path. This applies identically to testing
   the coordinator wiring below — a `CaptureViewModelTest` that drives
   `coordinatorFor()`/`performSave()` end-to-end will hit the same fork.
2. **`GraphWriter` has per-instance mutable state, not just constructor params** —
   `saveMutex`, `pendingByPage`, `activeConflicts` (see Q2 below). This project's AC #9
   ("same `writeActor`/`GraphWriter` instance") generalizes that same-instance
   discipline the prior review implicitly relied on when it audited `CaptureViewModel`'s
   existing single `GraphWriter` construction in `performSave()`.

The rest of that review (whitespace normalization placement, `normalizeShareWhitespace`)
is unrelated to enrichment/linking and is not revisited here.

---

## Q1: Where does the race-safe, per-graph coordinator live?

### Evidence

- `RepositorySet` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt:47-73`)
  is a plain `data class` with no `CoroutineScope` field and no identity beyond its
  constructor args. `RepositoryFactoryImpl.createRepositorySet()` builds a fresh one on
  every graph load (`RepositoryFactory.kt:237`) — it cannot own a cache; it *is* the
  cache-invalidation trigger (a new instance = new graph).
- `GraphManager` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`)
  owns the per-graph lifecycle directly:
  - `_activeRepositorySet: MutableStateFlow<RepositorySet?>` (`GraphManager.kt:79-80`),
    nulled out at the *start* of every `switchGraph()` (`GraphManager.kt:555`) before the
    new one is built, and again in `shutdown()` (`GraphManager.kt:858`).
  - `activeGraphJobs: MutableMap<GraphId, CoroutineScope>` (`GraphManager.kt:94`) — a
    real per-graph `CoroutineScope` (`graphScope`, `GraphManager.kt:562-564`) already
    exists and is cancelled on the next `switchGraph()`/`shutdown()`
    (`GraphManager.kt:540`, `848-850`). This is the correct scope to hand to a new
    `PageNameIndex` — its lifetime is exactly the active-graph lifetime, unlike a
    ViewModel-owned scope.
  - `switchGraph()`'s idempotency guard (`GraphManager.kt:540`) already demonstrates the
    single-flight problem this task must solve one layer up: rapid concurrent triggers
    for the *same* graph id must not race two initializations. The same shape (guard on
    graph id + in-flight marker) applies to the new coordinator.
- `StelekitViewModel` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt:518`)
  builds its own `PageNameIndex(pageRepository, scope)` eagerly at construction, where
  `scope` is the ViewModel's **own** `CoroutineScope` (`StelekitViewModel.kt:156`,
  derived from `deps.scope`), not `GraphManager`'s `graphScope`. `App.kt` wraps the
  whole per-graph subtree (including this ViewModel) in `key(activeGraphId) { ... }`
  (`App.kt:400`), so the ViewModel and its `PageNameIndex` are destroyed and rebuilt
  wholesale on every graph switch — a second, independent invalidation mechanism from
  `GraphManager`'s.
- `CaptureActivity` runs in the app's default process (no `android:process` override in
  `AndroidManifest.xml`), sharing the same `SteleKitApplication.graphManager` singleton
  (`androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt:55,92`) as
  `MainActivity`/`StelekitViewModel` whenever the app process is already alive. It has no
  access to `StelekitViewModel` or its `PageNameIndex` — only to `graphManager` and
  `fileSystem` via `getApplication<SteleKitApplication>()`
  (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:57-58`).

### Recommendation: own the coordinator on `GraphManager`, keyed by graph id

Add a small holder to `GraphManager`:

```kotlin
private val coordinatorMutex = Mutex()
private var coordinatorFor: Pair<GraphId, Deferred<CaptureEnrichmentCoordinator>>? = null

/** Race-safe, memoized per-graph coordinator. Single-flight: concurrent callers for the
 *  same graph id share one in-flight Deferred instead of racing two constructions. */
suspend fun getOrCreateEnrichmentCoordinator(): CaptureEnrichmentCoordinator? = coordinatorMutex.withLock {
    val graphId = graphRegistry.value.activeGraphId ?: return@withLock null
    val repoSet = _activeRepositorySet.value ?: return@withLock null
    val scope = activeGraphJobs[graphId] ?: return@withLock null
    val existing = coordinatorFor
    if (existing != null && existing.first == graphId) return@withLock existing.second.await()
    val deferred = scope.async(start = CoroutineStart.LAZY) {
        CaptureEnrichmentCoordinator(
            pageNameIndex = PageNameIndex(repoSet.pageRepository, scope),
            topicEnricher = /* from LlmProviderRegistry, see below */,
        )
    }
    coordinatorFor = graphId to deferred
    deferred.await()
}
```

Key properties this satisfies:

- **Single-flight (AC #8)**: the `Mutex` serializes construction attempts; a second
  concurrent caller for the same graph id awaits the same `Deferred` rather than building
  a second `PageNameIndex`/trie. (A memoized `Deferred` alone — without the `Mutex` around
  the check-and-set — reintroduces the double-checked-locking race the AC explicitly
  rules out; the `Mutex` is what makes the "check existing, else create" step atomic.)
- **Natural invalidation on graph switch**: build the coordinator from `activeGraphJobs[graphId]`,
  the *same* scope `GraphManager` already cancels on the next `switchGraph()`/`shutdown()`.
  When that scope is cancelled, the `PageNameIndex`'s internal `stateIn`/`launch` (which
  also run on that scope, per `PageNameIndex.kt:73,76`) die with it. Compare `coordinatorFor`'s
  `graphId` against `graphRegistry.value.activeGraphId` on every call — a stale entry (graph
  id no longer active) is simply not returned; overwrite it. No separate teardown hook is
  needed because the underlying scope cancellation already renders the stale coordinator's
  flows inert.
- **Reachable from both consumers**: `CaptureActivity` reaches it via
  `steleApp.graphManager.getOrCreateEnrichmentCoordinator()` — no `StelekitViewModel`
  dependency. This is new code, so it doesn't inherit `StelekitViewModel`'s eager,
  ViewModel-scoped construction pattern.

### The StelekitViewModel duplication question — recommend leaving it as-is for v1

The requirements text asks for a design that avoids duplicating the trie build "by BOTH"
consumers, but the constraints section is explicit that this project is **additive wiring,
not a refactor of shared domain code**, and success metrics require **no behavior change**
in `StelekitViewModel`. Rewiring `StelekitViewModel.pageNameIndex` (`StelekitViewModel.kt:518`)
to source from `GraphManager.getOrCreateEnrichmentCoordinator()` instead of constructing
its own is a real de-duplication win, but it:

- changes `StelekitViewModel`'s scope-ownership for `pageNameIndex` from a ViewModel-owned
  scope to a `GraphManager`-owned scope — a lifecycle change, not just a call-site swap,
  because `key(activeGraphId)` (`App.kt:400`) already tears the ViewModel down and rebuilds
  it per graph switch today; moving to `GraphManager`'s scope changes *when* rebuilds happen
  relative to Compose recomposition,
  and
- touches `App.kt`'s dependency wiring (`StelekitViewModelDependencies`) and the two
  additional consumers already reading `pageNameIndex` off the ViewModel
  (`localPageNames` at `StelekitViewModel.kt:524`, `tagEngine` at `App.kt:1129`) —
  outside this feature's stated blast radius.

**Recommendation**: build `GraphManager.getOrCreateEnrichmentCoordinator()` as described
above for `CaptureActivity`'s use only in v1. Leave `StelekitViewModel.pageNameIndex` as
its own independent construction, unchanged. The duplicate-trie-build cost is bounded and
rare in practice — it only doubles memory/CPU while `CaptureActivity` is on-screen
*concurrently* with the main app already resident in the same process on the same graph
(the common share-sheet case is app backgrounded or not yet running, where `StelekitViewModel`
doesn't exist yet at all). Flag consolidating the two as a follow-up, not a v1 requirement —
this doc's Q1 answer already gives `GraphManager` the right shape to absorb that follow-up
later without another rewrite.

---

## Q2: The second-write flow (AC #9)

### Evidence: `GraphWriter` is not a disposable, stateless collaborator

`GraphWriter` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`) holds
per-instance mutable state beyond its constructor params:

- `saveMutex: Mutex` (`GraphWriter.kt:110`)
- `activeConflicts: HashMap<String, Int>` (`GraphWriter.kt:115`) — per-file-path conflict
  tracking
- `pendingByPage: MutableMap<PageUuid, Pair<Job, SaveRequest>>` + `pendingMutex`
  (`GraphWriter.kt:124-125`) — per-page debounce dedup, used by `queueSave()`
- `ownedScope` (`GraphWriter.kt:127`) plus the mutable `scope`/`debounceMs` fields set by
  `startAutoSave()` (`GraphWriter.kt:138-149`)

`savePage()` itself (`GraphWriter.kt:223`) delegates straight to `savePageInternal` — it
does **not** go through the debounced `pendingByPage` path, so a second `savePage()` call
on a *fresh* `GraphWriter` instance would not obviously break in the common case. The risk
AC #9 is guarding against is **two independently-mutexed instances writing the same
`graphPath`/`PageUuid` uncoordinated**: if the original save's `GraphWriter` still has an
in-flight or recently-completed write for this page tracked in its `activeConflicts`/
`saveMutex`, a second freshly-constructed `GraphWriter` has no visibility into that state
and could race the same markdown file. Reusing the exact instance keeps both writes
serialized through the same `saveMutex` and the same conflict-tracking map — this is the
concrete, evidence-based root cause for AC #9's "same instance, not a fresh one" wording,
not just an optimization.

### Retained state `CaptureViewModel` needs after `performSave()`

Today, `performSave()` (`CaptureViewModel.kt:71-118`) is a pure local function: `page`,
`graphPath`, `existingBlocks`, `newBlock`, and `writer` are all local vals, discarded on
return — `save()` only keeps the boolean success/failure (`CaptureViewModel.kt:63-68`).
For AC #9, `CaptureViewModel` must retain a small post-save context on success:

```kotlin
private data class SavedCaptureContext(
    val block: Block,          // current on-disk content, updated after each chip accept
    val page: Page,
    val blocks: List<Block>,   // full page block list, for GraphWriter.savePage's blocks param
    val graphPath: String,
    val writer: GraphWriter,   // the SAME instance performSave() constructed
    val writeActor: DatabaseWriteActor?,
)

private var savedContext: SavedCaptureContext? = null
```

Set once, at the end of a successful `performSave()`, from the values already computed
there (no new lookups needed — `page`, `graphPath`, `existingBlocks + newBlock`, and
`writer` are all already in scope at `CaptureViewModel.kt:117`).

### Exact call sequence for post-save chip acceptance

```
acceptSuggestion(suggestion) [post-save branch]
  ctx = savedContext ?: return  // no-op — chip UI should be gone if this is null
  1. stubPage = Page(name = suggestion.term, ...)
     ctx.writer.savePage(stubPage, emptyList(), ctx.graphPath)
        .onLeft { logger.error(...) }   // AC #7 isolation — do not throw, do not abort
        // (skip stub creation if a page with this name already exists — same
        //  pre-existence check ImportViewModel.confirmImport() does)
  2. updatedBlock = ctx.block.copy(
         content = ImportService.insertWikiLinks(ctx.block.content, listOf(suggestion.term)),
         updatedAt = Clock.System.now(),
     )
  3. (ctx.writeActor?.saveBlock(updatedBlock) ?: direct-write fallback)
        .getOrElse { logger.error(...); return }   // second writeActor.saveBlock() call, same BlockUuid
  4. ctx.writer.savePage(
         ctx.page,
         ctx.blocks.map { if (it.uuid == updatedBlock.uuid) updatedBlock else it },
         ctx.graphPath,
     )   // second GraphWriter.savePage() flush, SAME writer instance
        .onLeft { logger.error(...) }
  5. savedContext = ctx.copy(block = updatedBlock, blocks = ...)  // allow a second chip accept
```

This is exactly "one more `writeActor.saveBlock()` + one more `GraphWriter.savePage()`"
per accepted chip, on the same `BlockUuid`/writer/actor — matching AC #9's literal scope
and giving ADR-002 (required by AC #9, no existing ADR covers this) a concrete boundary to
write against: the *only* thing this second write is allowed to touch is
`savedContext.block`'s content and the one stub page for the accepted term — never a
different block, never re-reading from disk, never a general "edit after save" affordance.
Each stub-page failure is caught locally per AC #7 (mirrors the isolation
`ImportViewModel.confirmImport()`'s stub-creation loop at
`ImportViewModel.kt:401-412` does **not** have today, per the requirements doc's callout —
do not copy that gap into Capture).

---

## Q3: Data flow — `updateText()` → scan → chip tray → `Save`

```
CaptureActivity (share sheet / widget / QS tile, cold or warm start)
  │
  ▼
CaptureViewModel.updateText(text)               ── every keystroke/paste
  │  _captureText.value = text
  ▼
scan coroutine (viewModelScope, .debounce(...) on captureText)
  │  graphManager.getOrCreateEnrichmentCoordinator()   ── Q1's race-safe, memoized coordinator
  │  matcher = coordinator?.pageNameIndex?.matcher?.value
  │  if matcher == null or budget exceeded → leave _scanState = NotReady (Save falls back to raw text)
  │  result = withContext(Default) { ImportService.scan(text, matcher, existingNames) }
  │           ── mirrors ImportViewModel.runScan()'s scanDispatcher hop (ImportViewModel.kt:216-218)
  ▼
_scanState.value = ScanState.Ready(text = <text this scan was for>, result: ScanResult)
  │   (linkedText, matchedPageNames, topicSuggestions)
  ▼
fire-and-forget LLM enrichment (only if an LlmProvider is configured, mirrors
ImportViewModel.kt:243-267's Coroutine 2 exactly: withTimeout, discard-if-stale-by-hash,
non-destructive merge into topicSuggestions, TopicSuggestion.Source.AI_ENHANCED tag)
  ▼
UI: chip tray renders _scanState.topicSuggestions (dismiss ×, single-item accept —
    no "Accept All", per AC #2/#6); linked-text preview shows auto-applied [[links]]
    distinct from suggestion chips (AC #6)
  ▼
Save tapped
  │  current = _scanState.value
  │  textToSave = if (current is Ready && current.text == _captureText.value) current.result.linkedText
  │               else _captureText.value.trim()     ── AC #4: never blocked by an incomplete/stale scan
  ▼
performSave(graphManager, fileSystem, textToSave)   ── unchanged Bug-1/Bug-8 mitigations
  │  on success → savedContext = SavedCaptureContext(...)   ── Q2
  ▼
SaveState.Saved  (sheet stays open; chip tray still interactive — post-save accept path, Q2)
```

Two correctness notes baked into the diagram:

- The `current.text == _captureText.value` staleness check is what makes AC #4 concrete:
  a scan is only usable if it was computed for the *exact* text currently in the field.
  Without it, a slow/stale scan could silently apply stale links to newly-typed text.
- The scan/enrichment coroutines must never touch `save()`'s critical path directly —
  `save()` only *reads* `_scanState.value` (a `StateFlow` snapshot), it never awaits the
  scan or enrichment coroutine. This is what "never blocked" means structurally, not just
  behaviorally: there's no `await`/`join` on the scan job anywhere in `save()`.

---

## Summary of new types (bounded, per the "no new abstractions" constraint)

| Type | Role |
|---|---|
| `CaptureEnrichmentCoordinator` | Binds one graph's `PageNameIndex` + `TopicEnricher` (resolved from `LlmProviderRegistry`, `NoOpTopicEnricher` default) together for capture's use. No new algorithm — pure composition of existing pieces per the requirements doc's "existing machinery to reuse" list. |
| `GraphManager.getOrCreateEnrichmentCoordinator()` | Mutex + memoized-`Deferred`, keyed to `activeGraphId`, scoped to `activeGraphJobs[graphId]`. New method, not a new class. |
| `CaptureViewModel.SavedCaptureContext` (private) | Retains `block`/`page`/`blocks`/`graphPath`/`writer`/`writeActor` after a successful save, for AC #9's post-save chip acceptance. Private, capture-local — not a general edit-after-save mechanism (that boundary is ADR-002's job to state explicitly). |
