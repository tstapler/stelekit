# Research: Pitfalls — Live Query Blocks

Agent 4 (Pitfalls), SDD Phase 2. All file references relative to repo root;
line numbers current as of this research pass.

## 1. Performance — reactive full-table scans and O(N²) risk

**SQLDelight `Flow`s invalidate at table granularity, not row granularity.**
`Query.asFlow()` re-runs the *entire* SELECT whenever any write touches a
table the query reads from — there is no row-level diffing. This is
confirmed precedent, not a hypothesis:

- `SqlDelightPageRepository.kt:98-104` (`getAllPages()`) wraps a full
  `selectAllPages().asFlow()` and explicitly adds `.conflate()` with the
  comment `// drop intermediate invalidations during bulk import to avoid
  O(N²) full-table scans`. This is the one place in the codebase that has
  already hit and mitigated exactly this class of bug.
- `SqlDelightBlockRepository.kt:937-954` (`getUnlinkedReferences`, no
  limit/offset) does `queries.selectAllBlocks().executeAsList()` inside a
  one-shot `flow { }` builder — a full block-table scan. Note this
  particular method is **not actually "live"**: it uses the one-shot `flow{}`
  builder with `executeAsList()`, not `.asFlow()`, so it computes once per
  collection and does not re-emit on later writes. It's a precedent for "we
  accept an O(N) scan for backlinks," not a precedent for "we accept
  reactively re-running an O(N) scan on every write."
- The genuinely reactive, full-scan-avoiding precedent is the *paginated*
  `getLinkedReferences(pageName, limit, offset)` at
  `SqlDelightBlockRepository.kt:892-935`: iterative SQL-side `LIKE`
  overfetch bounded by `MAX_LINKED_REF_BATCH = 2_000` and
  `MAX_LINKED_REF_ITERATIONS = 50` (lines 1186, 1188), explicitly commented
  as avoiding "scanning and loading the entire block table into memory."

**Implication for query blocks:** a naive `(todo TODO)` executor built as
`queries.selectAllBlocks().asFlow().mapToList(DB).map { filter... }` will
re-scan and re-filter every block in the graph on **every write anywhere**,
every time it's live — not just on the query page. Follow the
`getLinkedReferences`/paginated pattern (push the predicate into SQL where
possible — e.g. an indexed `marker = ?` column lookup for `(todo TODO)`,
`property_key/value` lookup for `(page-property ...)`) rather than the
`getAllPages()`/`getUnlinkedReferences` full-scan-in-Kotlin pattern. If a
predicate can't be pushed to SQL (e.g. compound tag matching against
free-text content), apply `.conflate()` the same way `getAllPages()` does,
and cap results (see §4).

**O(N²) risk from multiple simultaneous query blocks is real and unbounded
today.** Nothing in the existing repository or write-actor layer coalesces
identical or overlapping queries. If a page has *k* open `{{query}}` blocks,
each independently subscribes to its own `Flow`; a single keystroke-driven
block save (500ms debounce, per root `CLAUDE.md`) triggers a table-write
notification that SQLDelight broadcasts to *all k* subscribed flows
simultaneously, each re-running its own O(N) (or worse, unindexed O(N) LIKE)
scan — i.e., cost scales as *k × N* per edit, not amortized. This is worse
than today's ReferencesPanel case (typically one panel per page) because
Logseq graphs commonly place multiple query blocks on a single dashboard
page. Recommend: (a) push predicates to indexed SQL columns so each
individual scan is cheap even if k is not, and (b) revisit later whether a
per-page query-result cache/dedup layer is needed if k turns out large in
practice — flagged as a candidate follow-up, matching the requirements doc's
own "Query result caching ... address if perf research flags it as a v1
blocker" scope note.

## 2. Reactivity / lifecycle — repo-specific known bugs

Root `CLAUDE.md` documents two real, previously-fixed bugs in this exact
area; a query-result composable is squarely in the blast radius of both.

**(a) `rememberCoroutineScope()` escaping into a long-lived object.** A
per-query-block Compose node is exactly the kind of "long-lived object
created inside `remember {}`" the rule warns about. If a query executor
class is instantiated via `remember { QueryExecutor(rememberCoroutineScope()) }`
to launch its own collection/debounce coroutine, that scope is cancelled on
recomposition and the executor throws `ForgottenCoroutineScopeException` on
its next `launch`. **Safe pattern:** don't give the query executor a
caller-supplied scope at all — express it as a `Flow<Either<DomainError,
List<Block>>>` built from repository-layer `Flow`s and collect it directly
with `collectAsState()` in the composable (transient UI collection is
exactly what `rememberCoroutineScope()` is documented as safe for — event
handlers and one-shot work, not object ownership). If per-graph debounce
logic is needed, put it in the repository/domain layer behind a class that
owns `CoroutineScope(SupervisorJob() + Dispatchers.Default)` internally, per
the documented correct pattern.

**(b) Raw `asFlow()` crashing on DB close.** `GraphManager.shutdown()`
(`db/GraphManager.kt:537-548`) and `switchGraph()`
(`db/GraphManager.kt:327-...`, sets `_activeRepositorySet.value = null` at
line 351 before creating the new set) both tear down the active
`RepositorySet`. Any composable holding a `Flow` built with a raw
`.asFlow()` chain and no `catchDbError()` will hit `IllegalStateException`
on the closed driver and crash the main thread — this is precisely what
`UpgradeResilienceTest`
(`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/UpgradeResilienceTest.kt`)
and `RepositoryFlowResilienceTest`
(`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/RepositoryFlowResilienceTest.kt`)
exist to catch — TC-UPGRADE-001 exercises every `Flow`-backed read across all
repositories against a closed DB. **The new query-executor repository method
must use `asDbFlowList`/`asDbFlowOrNull`
(`repository/DbFlowExtensions.kt:43-68`) or, for hand-rolled chains, end with
`.catchDbError()` explicitly** — and should be added to the
`UpgradeResilienceTest` coverage set so a regression here is caught
mechanically, not by manual review.

**Compounding risk specific to this feature:** the render path itself
(`ui/components/MarkdownEngine.kt`) is currently **not composable at all**.
`parseMarkdownWithStyling()` (`MarkdownEngine.kt:375-406`) is a plain
synchronous function returning `AnnotatedString`, called from
`BlockViewer.kt:107-117` inside `remember(text, linkColor, textColor,
resolvedRefs, blockRefBg, codeBg, suggestionSpans) { ... }` — i.e. the
rendered output is memoized purely on the block's *own* text/style inputs,
with **no dependency on external DB state** and no `Flow` collection
anywhere in that pipeline. A `{{query}}` block cannot be executed inside
`parseMarkdownWithStyling` (it's not suspend, not composable, and isn't
re-invoked when the queried data changes elsewhere in the graph — only when
the block's own `text` changes). This means the "live" part of live query
blocks needs a structurally different render path than the rest of
`MarkdownEngine`: something like a dedicated `@Composable QueryResultBlock`
that `BlockViewer` renders as a sibling/replacement to the `Text(annotatedString)`
call when a block's macro is `query`, collecting its own `Flow` via
`collectAsState()`, rather than trying to inline live results as
`AnnotatedString` spans. This is an architecture decision the plan phase
needs to make explicitly — it's not a detail that falls out of "add a
`QueryResultNode` case to `renderNode()`" as the requirements doc's Must Have
list phrases it, because `renderNode` is a non-composable, non-suspend
function with no access to `collectAsState()`.

## 3. Correctness — query grammar and matching semantics

**Raw argument string, not a structured AST.** `InlineParser.parseMacro()`
(`parsing/InlineParser.kt:110-141`) captures everything after the macro name
as a single joined string in `MacroNode.arguments` (e.g. `{{query (todo
TODO)}}` → `arguments = ["(todo TODO)"]`). The parser does **no**
s-expression tokenization — parens, nested forms, quoted strings with
spaces, and escaped brackets are all unparsed text. The query executor must
write its own small s-expression parser from scratch and must not assume
well-formed input; malformed forms (unbalanced parens, empty query, unknown
head symbol) are the common case for hand-edited/legacy Logseq graphs, not
the exception.

**Existing `DomainError.ParseError` variant fits this.**
`error/DomainError.kt:24-29` already has `ParseError.InvalidSyntax` and
`ParseError.MalformedMarkdown`. Prefer reusing `ParseError.InvalidSyntax` for
unparseable query bodies rather than inventing a new `DomainError` branch —
keeps the executor consistent with the rest of the domain-error taxonomy
research/plan should call out explicitly.

**Task-marker case sensitivity.** Logseq's real syntax is uppercase markers
(`TODO`, `DOING`, `DONE`, `LATER`, `NOW`, `CANCELED`) as a structural token,
not a free-text match. The query form `(todo TODO)` — note lowercase head
`todo`, uppercase argument `TODO` — combines both: the S-expression head
symbol should almost certainly be treated case-insensitively (`(TODO todo)`,
`(Todo TODO)` are plausible hand-typed variants) while the marker argument
should be normalized to the canonical uppercase set before comparison rather
than compared as opaque case-sensitive text, otherwise `(todo todo)` (all
lowercase, as literally shown in the requirements doc's own example) would
silently match nothing. Confirm against how block markers are actually
stored — search hit no existing marker-normalization helper outside the
parser's `TaskMarkerNode`; the executor will need to either read the
stored/parsed marker field (if persisted structurally) or re-derive it from
content, and should normalize case at that boundary once, not per-query.

**Page-name / tag matching must not diverge from `getLinkedReferences`
semantics.** `SqlDelightBlockRepository.kt:997-1015` (`compileLinkPatterns`,
`isLinkedReference`) is the established, tested definition of "this block
references this page": case-insensitive `\[\[name(\|alias)?\]\]` wikilink
regex **or** `#name` hashtag regex with a lookahead boundary
(`\s,\.!?;"\[\]` or end-of-string). The in-memory test fake
(`DatalogBlockRepository.kt:131-161`) uses a looser but consistent
case-insensitive `\[\[name\]\]` pattern (no alias-pipe handling, no hashtag
form) — this is a **known, pre-existing divergence between the SQL and fake
backends** the new query executor should not compound. For tag/page-ref
query forms, reuse `compileLinkPatterns`/`isLinkedReference` (or extract them
to a shared location both backends call) rather than writing a third,
independently-tested definition of "references this page" — three divergent
implementations of page-name matching (SQL repo, in-memory fake, new query
executor) is a correctness bug waiting to happen the day one of them is
tweaked and the others aren't.

**`(between ...)` date semantics and timezone/locale risk.** Per the
requirements doc, Logseq's simple-query `(between d1 d2)` operates on
**journal date**, not an arbitrary date property. Journal dates in this repo
are `kotlinx.datetime.LocalDate` keyed off the page name pattern
`^(\d{4})[-_](\d{2})[-_](\d{2})$` (`outliner/JournalUtils.kt:14,20-32`) —
this is timezone-free once parsed (a calendar date, not an instant), which
avoids the worst timezone bugs *for stored journal pages*. The risk is at
the query-input boundary: relative date keywords Logseq supports
(`today`, `yesterday`, `-7d`, etc., if in scope) require resolving "now" to a
`LocalDate` via `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())`
— pick the systemDefault timezone consistently with wherever else "today's
journal page" is currently computed (search did not find a single shared
"todayLocalDate()" helper; several files independently derive journal-date
context — `JournalService.kt`, `StelekitViewModel.kt`, `JournalsView.kt` —
so confirm during planning which one is canonical and reuse it, rather than
adding a fourth independent "what is today" computation with its own
timezone default).

## 4. Failure-mode UX

- **Unparseable/unsupported query body:** requirements doc already commits
  to graceful degradation (literal text or "unsupported query" placeholder)
  — consistent with how the generic `MacroNode` branch
  (`MarkdownEngine.kt:231-236`) already renders any *other* unrecognized
  macro as literal `{{name args}}` text today. Reuse that exact fallback
  render for `query` bodies the executor can't parse, so failure mode is
  visually consistent with existing "unknown macro" behavior rather than a
  new error UI users have to learn.
- **Unbounded result sets.** None of the four v1 query forms have a
  requirements-mandated cap. An unindexed `(page-property key value)` or
  broad tag query against a large graph could return thousands of blocks;
  rendering all of them as Compose list items with no windowing risks
  janking recomposition the same way an unpaginated `getUnlinkedReferences()`
  call would (`SqlDelightBlockRepository.kt:937-954` has no limit variant
  called from any live UI path today — its paginated sibling exists
  specifically because the unbounded one wasn't safe for UI use). Recommend
  a hard result cap (e.g. reuse `MAX_LINKED_REF_BATCH`-style constant or a
  smaller UI-appropriate number, plus a "N of M results, refine your query"
  affordance) rather than shipping v1 with no ceiling — this is explicitly
  flagged as address-if-blocking in the requirements doc's Out of Scope
  section ("Query result caching/pagination... address if perf research
  flags it as a v1 blocker") and this research does flag it: unbounded
  result lists are a Compose performance risk, not just a data-volume nice-
  to-have.
- **Slow/expensive queries:** no query timeout mechanism exists anywhere in
  the repository layer today (checked `SqlDelightBlockRepository.kt`,
  `DbFlowExtensions.kt` — no `withTimeout`/timeout DomainError variant).
  If a query predicate is expensive (e.g. an unindexed multi-condition scan),
  there's no existing pattern to bound it other than the SQL query itself
  being fast — plan should decide whether v1 needs an explicit timeout or
  can rely on keeping every v1 predicate SQL-indexable.

## 5. Multi-graph scoping

`GraphManager` (`db/GraphManager.kt`) holds `_activeRepositorySet:
MutableStateFlow<RepositorySet?>` (line 64-65) and explicitly nulls it out
*before* installing a new one on `switchGraph()` (line 351) and on
`shutdown()` (line 548). Any object that captures a `RepositorySet` /
`BlockRepository` reference directly (rather than deriving it from
`activeRepositorySet` on each use) will keep querying the **previous**
graph's (possibly-closed) database after a switch — a stale-subscription
leak. Two concrete implications for query blocks:

1. **A query executor/composable must re-derive its repository from
   `GraphManager.activeRepositorySet` (or equivalent DI scoping used by
   other repository-consuming composables in this codebase) on each
   collection, not cache it at construction time**, or it will either throw
   on the closed old DB (mitigated by `catchDbError()`, see §2b) or — worse —
   silently keep showing results from the wrong graph if the old DB object
   happens to still be technically queryable for a window before GC/close
   completes.
2. Because each open page can have its own query blocks and `GraphManager`
   supports multiple *simultaneously open* graphs (not just switching), a
   query result Flow must be scoped through the `RepositorySet` that
   corresponds to the page's own graph, not a single implicit "current
   graph" — confirm during planning whether query blocks are only ever shown
   for the currently *active* graph (simpler, matches how `BlockViewer` etc.
   are scoped today) or must also render correctly for a background graph if
   the UI ever surfaces multiple graphs' pages concurrently (no evidence in
   current UI code that it does — `activeRepositorySet` is singular/current-
   graph-only across the composables checked).

## Summary of concrete guardrails to carry into planning

1. Push `(todo TODO)` / `(page-property k v)` predicates to indexed SQL
   columns; don't build them as `selectAllBlocks().asFlow().map{filter}`.
2. Reuse `asDbFlowList`/`asDbFlowOrNull`/`catchDbError()`
   (`repository/DbFlowExtensions.kt`) for every new reactive read; add the
   new method(s) to `UpgradeResilienceTest` coverage.
3. Do not give a query executor its own `rememberCoroutineScope()`-derived
   scope; collect its `Flow` directly via `collectAsState()`.
4. Design the query-result render path as a distinct `@Composable`, not as
   an addition to the non-composable `renderNode`/`AnnotatedString` pipeline
   in `MarkdownEngine.kt`.
5. Reuse `compileLinkPatterns`/`isLinkedReference`
   (`SqlDelightBlockRepository.kt:997-1015`) for tag/page-ref matching
   instead of writing a third independent page-name-matching regex.
6. Normalize task-marker case (both s-expression head and marker argument)
   before comparison.
7. Cap result-set size and reuse the existing generic-`MacroNode` literal-
   text fallback for unparseable query bodies.
8. Re-derive the repository/`RepositorySet` from `GraphManager` per
   collection rather than capturing it once, to avoid stale-graph
   subscriptions across `switchGraph()`.
