# Architecture Research: Live Query Blocks

## Prior art this builds on

`project_plans/render-all-markdown/research/architecture.md` and its
`decisions/ADR-002-block-renderer-dispatch.md` already solved "how does a block
whose rendering needs more than inline styled text get dispatched" — Option B
there (typed `blockType` set once at parse time, `when (block.blockType)`
dispatch in `BlockItem`, `contentType` in the surrounding `LazyColumn`) is
directly reusable. This research extends that pattern to query blocks rather
than re-deriving a dispatch mechanism; the new piece is what happens *inside*
the new dispatch arm (a reactive Flow subscription) and where the query
predicate gets executed.

## 1. Can a query render synchronously from `MarkdownEngine`, or does it need its own `collectAsState()`?

**It needs its own `collectAsState()`, and it cannot live inside `MarkdownEngine.kt` at all.**

`MarkdownEngine.kt` is not a `@Composable` file — every function in it
(`renderNodes` at
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt:61`,
`renderNode` at `:108`) is a `private fun AnnotatedString.Builder.*` extension.
The `MacroNode` branch at `:231-236` just appends monospace styled text plus a
string annotation (same pattern as `ImageNode` at `:208-214`, which appends an
`"image"` annotation resolved later by a tap handler, not by rendering an
actual image inline). There is no Composable slot anywhere in this file — it
produces a single `AnnotatedString`, consumed by `BasicText` one level up.

That consuming layer is `WikiLinkText`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockViewer.kt:78-225`).
Its `annotatedString` is computed with `remember(text, linkColor, textColor,
resolvedRefs, blockRefBg, codeBg, suggestionSpans) { parseMarkdownWithStyling(...) }`
(`BlockViewer.kt:107-118`) — a **synchronous, pure function of already-resolved
inputs**. Note `resolvedRefs: Map<String, String>` (`BlockViewer.kt:38, 84`) is
itself precomputed upstream and passed down as data, not fetched inside the
composable — this repo's existing pattern for "text needs external state" is
"resolve it above this layer, hand it down as a value," never "block on a Flow
inside the AnnotatedString builder."

A live query result is not more text to interpolate — the requirement is "a
live, read-only list of matching blocks embedded in the page" with
click-through per block, i.e. a list of block rows, each independently
clickable/navigable, not a span inside one block's text. That is
categorically a different render primitive than anything `MarkdownEngine.kt`
produces today, and forcing it through `AnnotatedString` would mean either (a)
flattening block rows into a single string (loses per-row click targets,
regresses UX) or (b) inventing a way to embed a Composable inside
`BasicText`'s `AnnotatedString`, which Compose does not support.

**Conclusion**: query rendering must happen at the block-dispatch level (the
`BlockItem` `when (block.blockType)` seam from ADR-002), as a new composable
(`QueryResultBlock` or similar) that sits alongside `HeadingBlock`,
`CodeFenceBlock`, etc. That composable owns a `collectAsState()` off a new
repository-level `Flow`. `MarkdownEngine.kt`'s macro branch keeps rendering
`query` macros as literal text only as the fallback for the (rare) case where
a `{{query ...}}` appears embedded inside otherwise-mixed inline content
rather than as a whole block — see §5.

## 2. Where a `Flow`-shaped query method should live

**Not a single new repository method on an existing interface — a new
service/executor class that composes existing repository methods.** Concretely:

- `(todo TODO)` / other task-marker states: no existing method. `Block` has no
  persisted marker column — `content: String` is the source of truth
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt:104`), and
  `TaskMarkerNode` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt:80`)
  is only produced by the *inline* parser
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt:99`).
  Matching "todo" blocks requires either a `content LIKE 'TODO %'`-style scan
  (SQLDelight) / prefix check (in-memory fake), or promoting marker to a
  queryable column — the latter is a schema change, likely out of scope for
  v1 per the "no new dependencies unless needed" constraint, but worth an
  open question in `plan.md` since a full-scan `LIKE` predicate is what the
  SQLDelight implementation would need to add as a **new query** in
  `SteleDatabase.sq` (this repo's schema-migration rule under "Adding a new
  table" doesn't apply here — it's a new query, not a new table — but the
  parity rule still applies: it must be reproduced in `DatalogBlockRepository`).
- `(page-property key value)`: **not** already covered by
  `PropertyRepository.getBlocksWithPropertyValue(key, value)`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/PropertyRepository.kt:21`,
  implemented at
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogPropertyRepository.kt:86-97`).
  That method filters by **per-block** property maps. Logseq's
  `page-property` simple query filters by properties declared on the *page*
  (`Page.properties: Map<String, String>`,
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt:74`), then
  returns that page's blocks. No existing method does this join —
  `PageRepository` has no `getPagesWithProperty(key, value)` and
  `BlockReadRepository.getBlocksForPage(pageUuid)`
  (`BlockReadRepository.kt:52`) only takes one page at a time. Needs
  composition of two repositories.
- `(between date1 date2)`: requirements.md correctly scopes this to **journal
  date range**, and the model supports it —
  `Page.journalDate: LocalDate?` and `Page.isJournal: Boolean`
  (`Models.kt:76-77`), with `PageRepository.getJournalPages(limit, offset)`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/PageRepository.kt:16`)
  as the closest existing precedent (no date-range variant exists yet). This
  again spans `PageRepository` (filter journal pages by date range) +
  `BlockReadRepository` (fetch each matching page's blocks) — another
  cross-repository join.
- tag/page-ref queries: closest existing precedent is
  `BlockSearchRepository.getLinkedReferences(pageName)`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockSearchRepository.kt:17`),
  which is single-`Block`-table already and the best-fitting existing shape
  of the four.

Only the last form is a natural fit for a single-repository extension
(`BlockSearchRepository`). The other three need either a same-table scan
(todo marker) or a **join across `PageRepository` and
`BlockReadRepository`** (page-property, journal between). Bolting all four
onto `BlockSearchRepository` would force it to depend on `PageRepository`,
inverting the existing layering where search/read repositories are
independent, narrow role interfaces (per the composite-interface doc comment
on `BlockRepository.kt:4-12`, which explicitly says "prefer the narrower role
interfaces for consumer dependencies").

**Recommendation**: introduce a new `QueryExecutor` (plain class, not a
repository interface) in a new package, e.g.
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/query/QueryExecutor.kt`, with
a signature matching the shape requested in the research prompt:

```kotlin
fun executeQuery(query: SimpleQuery): Flow<Either<DomainError, List<Block>>>
```

It takes `BlockSearchRepository`, `PropertyRepository`, `PageRepository`, and
`BlockReadRepository` as constructor dependencies (all already
`RepositoryFactory`-provided interfaces) and combines their existing
`Flow`s with `kotlinx.coroutines.flow.combine` — no new SQLDelight query is
strictly required for the `page-property`/`between` forms (they can be
expressed as `PageRepository` result → `flatMapLatest` per-page block fetch →
merge), which keeps the "no new dependencies" constraint satisfiable for 3 of
4 forms. Only the `(todo ...)` marker form needs a genuinely new read path
(content-prefix scan), and that one new method should go on
`BlockSearchRepository` (its natural home — same shape as
`searchBlocksByContent`) with the standard SQLDelight + `DatalogBlockRepository`
parity pair.

This avoids inventing a `QueryRepository` interface whose "read" contract
would just be a thin wrapper around calls the app already makes to three
other repositories, and avoids widening any existing repository's dependency
graph. It does **not** get its own test-fake/prod-backend parity pair the way
a repository would, because it has no direct DB access — it is pure
composition, testable by composing in-memory fakes (`DatalogBlockRepository`,
etc.) exactly as `businessTest` already does for ViewModel-level tests.

## 3. Data flow: where to trigger execution — parse-time or render-time

**Two distinct things, two distinct times — this is the key design insight:**

1. **Classification is parse-time, static, and already has an established
   seam.** `MarkdownParser.convertBlock()`
   (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt:39-110`)
   already computes a `BlockType` sealed value once per block
   (`ParsedModels.kt:17-27`: `Bullet`, `Paragraph`, `Heading(level)`,
   `CodeFence(language)`, etc.), serialized to the persisted `Block.blockType`
   string (`Models.kt:93-97, 113, 127`). Detecting "this block's entire
   content is a single `{{query ...}}` macro" is the same kind of one-time
   structural classification as detecting a code fence or heading — it
   should be added as a new `BlockType.Query(rawQuery: String)` case,
   classified once when the block is parsed/saved, not re-detected on every
   recomposition.
2. **Execution is render-time and reactive — this is the actual "auto-update"
   mechanism, and it must not be conflated with parsing.** The query's raw
   text is static; the *result set* is not. The `BlockItem` dispatch arm for
   `BlockType.Query` calls the new composable, which does
   `queryExecutor.executeQuery(parsedQuery).collectAsState(...)` — every
   underlying repository `Flow` (SQLDelight-backed) already invalidates and
   re-emits on any write to the tables it reads, per this repo's existing
   `PlatformDispatcher.DB` / `asDbFlowList` reactive-read convention (root
   `CLAUDE.md`). No polling, no manual refresh call, satisfying the "no new
   dependencies" and "Flow-based, no polling" constraints directly.

This matters because of a data-flow fact specific to this app: page content
in the UI is **not** continuously streamed from the DB. `StelekitViewModel`
fetches a page's blocks with a one-shot `.first()`
(e.g. `blockRepository.getBlocksForPage(page.uuid).first()` at
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt:678`,
repeated at `:723, 747, 778, 796`), and `PageView`'s Compose tree collects
`blockStateManager.blocks` — a locally-managed `StateFlow` refreshed on
specific triggers (block save debounce, `GraphLoader.externalFileChanges`) —
via `collectAsState()`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt:87`).
That state flow only refreshes for changes to blocks *on the currently open
page*. A query like `{{query (todo TODO)}}` must reflect a TODO edited on a
**different** page, which the current page's own block state will never
signal. This rules out "reuse the already-loaded page state" as a design —
the query result composable genuinely needs its own independent subscription
straight to the repository layer, not a derived view of `PageView`'s existing
state.

## 4. Consistency: does the existing `DiskConflict`/external-change flow cover query result invalidation?

**Partially, and not sufficiently on its own — but no separate invalidation
path needs to be built if query execution is Flow-based as described above.**

`GraphLoader.externalFileChanges`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt:326`) is a
`SharedFlow<ExternalFileChange>` that exists to let the *currently displayed
page* react to on-disk edits made outside the app (another editor, sync).
It's orthogonal to in-app edits — in-app edits go through
`GraphWriter`/`DatabaseWriteActor` writes directly, which SQLDelight's
reactive queries already observe. Since the recommended design (§3) has the
query composable subscribe directly to repository `Flow`s rather than to
`PageView`'s per-page state, **both** in-app edits (write → SQLDelight
invalidation → `Flow` re-emits → `collectAsState` recomposes) and
external-file changes that eventually land as DB writes are covered by the
same mechanism, with no bespoke invalidation path required. A block that gets
deleted while displayed in a query result simply drops out of the next
emission, the same way any other reactive list in this app handles deletion
(e.g. `getBlockChildren`).

The one gap: if `GraphLoader` ever short-circuits and updates only in-memory
caches without going through a path that triggers the SQLDelight query
invalidation (this is exactly what `cacheEvictAll()` /
`cacheEvictPage(pageUuid)` on `BlockReadRepository` exist to paper over,
`BlockReadRepository.kt:74-82`), a query result could go briefly stale. This
is a pre-existing risk shared by every other reactive read in the app, not
something new to this feature — no additional invalidation path is needed
specifically for query blocks.

## 5. Disposition

**Isolate via seam.** `MarkdownEngine.kt`'s macro-rendering branch
(`:231-236`) is not a SOLID/Clean-Architecture violation — it correctly does
one job (render inline macros as styled text) and does it consistently for
`embed`, `renderer`, and other macro names, which must keep working
unchanged per the requirements. The mistake would be extending *that* branch
to somehow special-case `query` into rich content; `AnnotatedString.Builder`
has no way to host a live sub-list of blocks, so any attempt would be a
symptom fix bending the wrong layer. Instead, extend the **already-accepted**
seam from ADR-002 (`BlockType` sealed class → persisted `blockType` string →
`when` dispatch in `BlockItem`) with one new case, and leave
`MarkdownEngine.kt` untouched except as the graceful-fallback path for
`{{query ...}}` appearing outside a whole-block context (mixed inline text),
which still renders as literal text exactly as today — satisfying the "existing
macro rendering for other types is unaffected" and "graceful fallback for
unsupported forms" requirements simultaneously.

The repository layer needs no refactor either: `BlockReadRepository`,
`BlockSearchRepository`, `PropertyRepository`, and `PageRepository` are
already narrow, composable role interfaces (explicitly documented as the
preferred consumer-facing shape on `BlockRepository.kt:4-12`). A new
`QueryExecutor` that composes them is additive, not a repair.

One pre-existing dead-code note worth flagging in `plan.md`'s tech-debt
section: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/search/DatalogQuery.kt`
contains a `DatalogEngine`, `DatalogQuery`, and `VisualQueryBuilder` that look
like an earlier, abandoned attempt at exactly this problem — `DatalogEngine.execute()`
unconditionally returns an empty list (`:74-76`), `VisualQueryBuilder.buildFromSchema`
is a stub with a `TODO` (`:91-94`), and nothing in the codebase references any
of these three types (confirmed via repo-wide search). This file is inert and
should **not** be built on or extended for live query blocks — the "Advanced/raw
Datalog queries" the requirements explicitly place out of scope for v1 map to
this exact stub, and it can be left alone or removed in a separate drive-by
cleanup, not treated as a foundation.

## Scoping note (not a multi-actor domain)

This is a single-actor read-model feature (one user, one graph, executing a
query against that graph's own repositories) — no Event-Command-Policy table
applies. The only actor-adjacent nuance is multi-graph scoping: `GraphManager`
maintains per-graph `RepositorySet` instances, so `QueryExecutor` must be
instantiated per-graph (constructed with that graph's repository instances,
the same lifetime pattern already used for `DatabaseWriteActor` and other
per-graph services) rather than as a singleton — otherwise a query block on
Graph A could silently query Graph B's repositories after a graph switch.
