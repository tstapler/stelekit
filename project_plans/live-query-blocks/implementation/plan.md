# Implementation Plan: Live Query Blocks

**Feature**: Execute `{{query (...)}}` macro blocks as live, auto-updating result lists instead of dead literal text.
**Date**: 2026-09-15
**Status**: Ready for implementation
**ADRs**: [ADR-001: Hand-Rolled Simple Query Parser](../decisions/ADR-001-hand-rolled-simple-query-parser.md), [ADR-002: QueryExecutor Service Composition](../decisions/ADR-002-query-executor-service-composition.md)
**Execution note**: `/sdd:5-implement` should dispatch one subagent per **Story** (bundling that Story's Tasks into a single subagent's unit of work), not one subagent per Task — with ~65 Tasks sized 2-5 min each, literal one-subagent-per-Task dispatch would spend more on subagent spinup and dual-review overhead than on actual coding time per task.

---

## Step 0.5 — Alternatives Considered

**Where to execute the query (three approaches considered):**

1. **Inline, inside `MarkdownEngine.kt`'s `AnnotatedString` builder.** Strength: no new render tier, reuses the existing macro-rendering code path. Weakness: `AnnotatedString.Builder` has no way to host a live, independently-clickable multi-row list, and `MarkdownEngine.kt` isn't even `@Composable` — it's a pure synchronous text-building function with no access to `collectAsState()`. Rejected (confirmed dead-end by `research/architecture.md` §1 and `research/pitfalls.md` §2).
2. **A new page-level "queries panel"** that scans the whole page's blocks once and renders all query results together above/below the outline, decoupled from block position. Strength: avoids per-block Flow subscription overhead if a page has many query blocks. Weakness: breaks the mental model users bring from Logseq (a query result sits exactly where its `{{query}}` block is in the outline, e.g., interleaved with other blocks); would also require a new page-level scanning pass over block content on every render to find query blocks. Rejected — moves complexity to solve a perf concern (§1 of `research/pitfalls.md`) that's better solved by capping per-query result sets, not by abandoning the block-native positioning users expect.
3. **A new block-tier `@Composable QueryBlock`, dispatched from `BlockItem.kt`'s existing `when (block.blockType)` seam (ADR-002 from `render-all-markdown`), each instance independently subscribing to its own repository `Flow`.** Strength: reuses an already-accepted, already-tested dispatch mechanism (`TableBlock`/`CodeFenceBlock`/`HeadingBlock` are the existing siblings), keeps queries positioned exactly where authored, and each query's live-ness is independent (editing block on page A can't stall a query on page B). Weakness: per-`research/pitfalls.md` §1, k open query blocks on one page each independently re-scan on every write anywhere in the graph — a real but boundable cost (indexed/pushed-down SQL predicates + result caps, not a redesign).

**Chosen: Option 3.** It's the only one consistent with this repo's own accepted rendering-dispatch precedent (ADR-002) and with users' existing mental model of where a query result appears. Options 1 and 2 are recorded in the Pattern Decisions table below as rejected alternatives.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `SimpleQuery` | Sealed type representing a parsed `{{query ...}}` s-expression — either a single `QueryFilter` or an `And`/`Or` combinator. | `dev.stapler.stelekit.query.SimpleQuery` |
| `QueryFilter` | Sealed subset of `SimpleQuery` representing one of the four v1 base filter forms; also implements `QueryOperand` so a bare filter is directly usable as an `And`/`Or` operand without wrapping. | `Task`, `PageProperty`, `Between`, `PageRef` |
| `QueryFilter.Task` | Matches blocks whose leading task marker is in a requested, case-normalized marker set (bare `task` filter, with `todo` as a legacy alias). | Reuses the canonical uppercase vocabulary already enumerated at `InlineParser.kt:97` |
| `QueryFilter.PageProperty` | Matches blocks that belong to a page carrying a given property key/value pair. | Page-level, not block-level — see `research/features.md` §2 on the `property` vs `page-property` distinction |
| `QueryFilter.Between` | Matches blocks under journal pages whose journal date falls inclusively within a range. v1 scope: the range is given as two `[[journal page]]` name tokens, not relative-date symbols. | Confirmed journal-only semantics via upstream `rules.cljc` (`research/build-vs-buy.md`) |
| `QueryFilter.PageRef` | Matches blocks referencing a given page or tag (`[[Page]]` or `#tag`) as the entire query body. | Reuses `LinkPatterns`/`isLinkedReference` (below) |
| `QueryOperand` | Sealed type for exactly what an `And` operand may be: a bare `QueryFilter`, or `Not(QueryFilter)`. `QueryOperand` does not itself implement `SimpleQuery`, and `Not`'s constructor parameter is typed `QueryFilter` (never `QueryOperand`/`SimpleQuery`) — so both "`And`/`Or` nested inside `And`/`Or`" and "double negation" are unrepresentable at compile time, not just parser-rejected at runtime. `Or`'s operands are typed as plain `QueryFilter`, not `QueryOperand` — v1 restricts `Not` to `And`-operand position only, because `Or(x, Not(y))`'s correct semantics (`x ∪ (Universe − y)`) would require complementing against a full "universe of all blocks" this codebase has no practical reactive way to compute; `Or(_, Not(_))` is therefore a compile error, not a runtime rejection. | `dev.stapler.stelekit.query.QueryOperand` (declared in `SimpleQuery.kt`) — see Pattern Decisions ("`Or`+`Not` restriction") |
| `And` / `Or` | Exactly-one-level boolean combinators. `And.left`/`And.right` are typed `QueryOperand` (bare filter or `Not`); `Or.left`/`Or.right` are typed plain `QueryFilter` (bare filter only, no `Not`) — see `QueryOperand` above for why `Or`+`Not` is excluded by the type system rather than the parser. Either way, an operand can never itself be an `And`/`Or` — enforced by the type checker, not a convention. | `dev.stapler.stelekit.query.SimpleQuery.kt` |
| `Not` | Negation of a single base filter (`Not(filter: QueryFilter)`). A `QueryOperand`, not a `SimpleQuery` — a top-level `(not ...)` query (not nested inside `and`/`or`) is therefore not just rejected at parse time but literally cannot be returned from `QueryParser.parse(): Either<_, SimpleQuery>`. | Only reachable as an `And` operand in v1 — `Or`'s operand type (plain `QueryFilter`) makes `Or(_, Not(_))` a compile error; see `QueryOperand` |
| `QueryParser` | Hand-rolled recursive-descent parser turning `MacroNode.arguments`'s raw string into `Either<DomainError.ParseError, SimpleQuery>`, where `ParseError` is one of `InvalidSyntax` or `UnsupportedForm` (below). | `dev.stapler.stelekit.query.QueryParser`; see ADR-001 |
| `DomainError.ParseError.UnsupportedForm` | New `ParseError` subtype, sibling of `InvalidSyntax`, for input whose head symbol the parser recognizes (`task`/`page-property`/`between`/`and`/`or`/`not`) but whose argument shape exceeds v1's supported grammar — e.g. two-level `and`/`or` nesting, a top-level `(not ...)`, or `between`'s relative-date form. `InvalidSyntax` is reserved for input the parser cannot recognize at all (unknown head symbol, unbalanced parens, empty body, garbage). This distinction lets `QueryBlock` render a visibly different "recognized but unsupported" placeholder instead of reusing the plain literal-text fallback — see pre-mortem P1 #1, Story 1.2.1, Story 4.1.1. | `dev.stapler.stelekit.query.QueryParser` |
| `QueryExecutor` | Per-graph plain class composing `BlockSearchRepository`/`PropertyRepository`/`PageRepository`/`BlockReadRepository`; exposes `executeQuery(query: SimpleQuery): Flow<Either<DomainError, List<Block>>>`. | `dev.stapler.stelekit.query.QueryExecutor`; see ADR-002 |
| `BlockType.Query` | New `ParsedModels.BlockType` sealed case marking a block whose entire inline content is a single `{{query ...}}` macro; carries the raw query argument string at classification time. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt` |
| `BlockTypes.QUERY` | The `"query"` string discriminator persisted on `Block.blockType`, mirroring the existing `BlockTypes.TABLE`/`CODE_FENCE`/etc. constants. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/BlockTypes.kt` |
| `QueryBlock` | New block-tier `@Composable`, sibling of `TableBlock`/`CodeFenceBlock`, that re-derives the raw query text from `block.content`, parses + executes it, and renders the live result list. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/QueryBlock.kt` |
| `QueryResultRow` | A single rendered result: a `Block` paired with its resolved containing page name (needed for click-through navigation via the existing `onLinkClick(pageName)` contract). | Render-time-only value type, not persisted |
| `findBlocksWithTaskMarker` | New `BlockSearchRepository` method backing `QueryFilter.Task`; reactive, SQL-pushed-down prefix match — not a full-table Kotlin-side scan. | New interface method |
| `getPagesWithProperty` | New `PageRepository` method backing `QueryFilter.PageProperty`; reactive, SQL-pushed-down substring match against the pages table's delimited `properties` string — not a full-table Kotlin-side scan over `getAllPages()`. | New interface method — see Story 3.2.4 |
| `findReferencingBlocksReactive` | New `BlockSearchRepository` method backing `QueryFilter.PageRef`; the reactive counterpart to the existing one-shot `getLinkedReferences(limit, offset)`, sharing that method's iterative-overfetch correctness guarantee via `overfetchLinkedReferences`. | New interface method — see correction note in Pattern Decisions |
| `overfetchLinkedReferences` | Shared bounded-iteration overfetch helper, extracted from `getLinkedReferences(pageName, limit, offset)`'s existing loop, that widens the SQL `LIMIT`/`OFFSET` window until enough Kotlin-side-filtered matches are found — needed because SQL's `LIKE` predicate is looser than `isLinkedReference`'s regex filter, so a naive single-`LIMIT` query can under-return true matches. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/LinkMatching.kt` — see Story 3.1.1 |
| `LinkPatterns` / `compileLinkPatterns` / `isLinkedReference` | Shared page-ref/tag matching primitives, extracted from `SqlDelightBlockRepository` into a location both the SQL repository and `DatalogBlockRepository` call, closing a pre-existing SQL-vs-fake matching divergence. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/LinkMatching.kt` |
| `queryArgFromContent` | Render-time helper extracting the raw `{{query ...}}` argument string from `Block.content`, matching the style of `headingLevelFromContent`/`codeFenceLanguage` in `BlockItem.kt`. | Regex-based, whole-block-only match |
| `MAX_QUERY_RESULTS_DISPLAY` | UI-layer constant capping the number of result rows *rendered* before a "+K more" affix, per `research/ux.md`'s truncation recommendation. Distinct from `QueryExecutor.DEFAULT_LIMIT` (the repository-level *fetch* ceiling, 200) — the header's result count reflects the fetched count and signals with a `+` suffix when it hits `DEFAULT_LIMIT`, independent of how many of those rows are actually rendered. See `design/ux.md` Surface 5. | `= 50` (pinned; within `research/ux.md`'s suggested 20-50 range, matching Logseq's comparable default) |
| `JOURNAL_SCAN_LIMIT` | Repository-level cap on the number of journal pages fetched (most-recent-first) per `Between` query before the Kotlin-side date-range filter runs. Bounds the same k×N-per-edit fan-out concern `DEFAULT_LIMIT`/`MAX_QUERY_RESULTS_DISPLAY` already address for other forms, rather than scanning every journal page ever created. | `= 500` (pinned; ~1.4 years of daily journal pages, comfortably covering realistic `between` ranges while capping per-query Kotlin-side filtering cost) — `QueryExecutor.JOURNAL_SCAN_LIMIT`, see Task 3.2.2d |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Query argument parsing | Hand-rolled recursive-descent parser matching `InlineParser.kt`'s style (no new dependency) | `research/build-vs-buy.md`; ADR-001 | `h0tk3y/better-parse` parser-combinator library | No published artifacts for `androidTarget()`/`wasmJs` (2 of 5 enabled KMP targets); unmaintained since April 2022; grammar too small (4-6 forms) to justify a dependency |
| Query result computation | New `QueryExecutor` plain class (Service Layer, PoEAA) composing narrow repository interfaces | `research/architecture.md` §2; ADR-002 | Widen `BlockSearchRepository` to own all 4 forms, or introduce a new `QueryRepository` interface with prod/fake parity | Would invert the narrow-role-interface layering (`BlockRepository.kt:4-12`) for 3 forms that are cross-repository joins, not natural single-repository reads; a `QueryRepository` would have no direct DB access of its own to justify a fake-parity pair |
| Block render dispatch | Extend ADR-002's (`render-all-markdown`) `BlockType` → `blockType` string → `when` dispatch seam with one new `Query` case | `research/architecture.md` §5; `research/ux.md` §0 | Render results as `AnnotatedString` spans inside `MarkdownEngine.kt`'s existing `MacroNode` branch | `AnnotatedString.Builder` cannot host a live, per-row-clickable sub-list; `MarkdownEngine.kt` is not `@Composable` and has no `collectAsState()` access |
| Live refresh mechanism | Direct `collectAsState()` in `QueryBlock` on a `Flow` built from `asDbFlowList`/`.combine()`/`.catchDbError()`-guarded repository reads | `research/stack.md`; `research/pitfalls.md` §2 | A `rememberCoroutineScope()`-owned polling/refresh coroutine inside a remembered executor instance | Violates this repo's documented `rememberCoroutineScope()`-escaping-into-long-lived-object bug class (root `CLAUDE.md`); polling also violates the "no polling" constraint |
| Task-marker matching | New SQL-pushed-down query (`content LIKE` prefix match) behind `BlockSearchRepository.findBlocksWithTaskMarker` | `research/pitfalls.md` §1 | `selectAllBlocks().asFlow().mapToList(DB).map{filter}` full-table Kotlin-side scan | Re-scans and re-filters the entire block table on every write anywhere in the graph, for every open query block — cost scales as k×N per edit, not amortized |
| Page-property matching | New SQL-pushed-down query (`properties LIKE '%key:value%'` substring match against the delimited property string) behind `PageRepository.getPagesWithProperty` | Adversarial review (full-table-scan finding); `research/pitfalls.md` §1; `research/stack.md` §2 | `pageRepository.getAllPages().flatMapLatest { pages.filter { ... } }` full-table Kotlin-side scan (original v1 draft) | `research/pitfalls.md` §1 names `page-property` by name as a form that must be pushed to SQL, not scanned in Kotlin on every page write in the graph — same k×N-per-edit cost `findBlocksWithTaskMarker` already avoids for the `Task` form |
| Page-ref/tag matching | Extract `compileLinkPatterns`/`isLinkedReference` into a shared function (`LinkMatching.kt`) reused by both `SqlDelightBlockRepository` and `DatalogBlockRepository`, then build the new reactive method on top of it | `research/pitfalls.md` §3 | A third, independently-written page-name-matching regex directly in `QueryExecutor` or a new repository method | Three divergent implementations of "references this page" is a correctness bug waiting to happen — pitfalls.md already documents one existing SQL-vs-fake divergence this would compound |
| Linked-reference overfetch strategy | Extract the existing `getLinkedReferences(pageName, limit, offset)`'s bounded-iteration overfetch loop (`MAX_LINKED_REF_BATCH`/`MAX_LINKED_REF_ITERATIONS`) into a shared `LinkMatching.kt` helper (`overfetchLinkedReferences`), called by both that method and the new `findReferencingBlocksReactive` | Architecture review (reactive-query-correctness-regression finding) | A fresh, simpler single-`LIMIT` reactive query for `findReferencingBlocksReactive` (original v1 draft) | The SQL-side `LIKE` predicate is looser than the Kotlin-side `isLinkedReference` filter; a naive single-`LIMIT`-then-filter query can silently under-return true matches whenever false positives land inside the SQL window — exactly the bug the existing overfetch loop exists to prevent. A "reactive counterpart" that reproduces the weaker pattern is a correctness regression, not just a differently-scoped method |
| Query grammar nesting | Type-level cap: `And`'s `left`/`right` are typed `QueryOperand` (= bare `QueryFilter` or `Not(QueryFilter)`), which cannot itself be an `And`/`Or`, and `Not` is not itself a `SimpleQuery` — nesting beyond one level and top-level negation are unrepresentable, not merely parser-rejected; `QueryParser`'s operand-parsing function returns `Either<ParseError, QueryOperand>` so it structurally cannot construct an over-nested value in the first place | Architecture review (illegal-nesting-representable finding); `requirements.md` open question; `research/features.md` §2 | (a) Fully recursive Composite-pattern (GoF) `SimpleQuery` tree supporting arbitrary nesting; (b) flat `And`/`Or` typed directly over `SimpleQuery` with only a runtime parser check (original v1 draft) | (a) v1 scope is explicitly one level — arbitrary recursion is unneeded surface area and would let deep nesting compile; (b) let the exact same illegal 2+-level value compile, with only the parser's runtime check standing between a well-typed value and an invariant violation — `QueryOperand` closes that gap at zero extra runtime cost, and `QueryExecutor`'s `And`/`Or` dispatch (Task 3.2.2f) no longer has to trust an unenforced cross-module convention |
| `Or`+`Not` combination | Restrict `Not` to `And`-operand position only, enforced by typing `Or.left`/`Or.right` as plain `QueryFilter` (not `QueryOperand`), so `Or(_, Not(_))` is a compile error | Engineering review (`Or`+`Not` semantics finding) | Implement true `Or(x, Not(y))` semantics as `x ∪ (Universe − y)` | No practical reactive "universe of all blocks" exists to complement against in this codebase's `Flow`-based read model. The `And`+`Not` set-difference formula (`x − y`, Task 3.2.2f) is correct only because `And` already scopes the result to the other operand's set; naively reusing that same formula for `Or` would silently compute the wrong result (`x − y` instead of `x ∪ (Universe − y)`). Restricting the grammar — enforced at the type level, with the parser's `UnsupportedForm` classification as defense in depth — is simpler and safer than building or faking a universe-complement query for a form v1 users are unlikely to hit |
| Result rendering component | New block-tier `@Composable QueryBlock`, one implementation for this one consumer | `research/architecture.md` §5; `research/ux.md` §1 | A generic "rich inline result renderer" reusable by query results and a future `{{embed}}` resolver | Premature generalization — no second consumer exists yet (`{{embed}}` resolution doesn't exist in this codebase per `research/features.md` §1); Dataview's own inline-vs-block split shows conflating the two is a modeling mistake other tools already made |
| Where query execution happens (page-level scan vs. block-level composable) | Block-level composable, one per query block, independently subscribed | Step 0.5 above | Page-level "queries panel" scanning all blocks once | Breaks the Logseq mental model of a query result appearing exactly where authored; trades a boundable perf concern for a UX regression |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `MarkdownEngine.kt`'s `MacroNode` render branch (`:231-236`) | Not itself a violation, but the wrong layer to extend for rich/live content | Isolate via seam | Already correctly renders `embed`/`renderer`/other macros as literal text; extend the already-accepted `BlockType` dispatch seam (ADR-002) instead of bending this non-composable, `AnnotatedString`-only function — per `research/architecture.md` §5 |
| `SqlDelightBlockRepository.kt:999-1015` (`compileLinkPatterns`/`isLinkedReference`) vs. `DatalogBlockRepository`'s looser inline regex | Pre-existing divergence: SQL backend handles wikilink-alias and hashtag forms; in-memory fake only handles plain `[[name]]` | Refactor-first | This feature's new tag/page-ref query path needs page-name matching; adding a third independent implementation would compound a known correctness risk (`research/pitfalls.md` §3) — extract to a shared function first (Story 3.1.1), sequenced before Story 3.2.3 depends on it |
| `SqlDelightPropertyRepository.kt:98-122` (`getBlocksWithPropertyKey`/`getBlocksWithPropertyValue`) — one-shot `flow{executeAsList()}`, not reactive | Looks like the natural home for `page-property` query support but is NOT reactive and is block-level, not page-level | Extend as-is (do not build on it) | `research/stack.md` explicitly flags this as "looks like the right precedent by name but is the wrong one to copy"; `QueryExecutor`'s `PageProperty` branch is built fresh on the new SQL-pushed-down `PageRepository.getPagesWithProperty` (Story 3.2.4) instead — reactive and pushed to SQL, not an in-Kotlin scan over `getAllPages()` |
| `SearchRepository.searchWithFilters` / `SqlDelightSearchRepository.kt:176-186` — declared but unread `propertyFilters` field | Dead field on an otherwise-working, unrelated free-text-search feature | Extend as-is (do not touch) | Fixing the dead field would change behavior for the existing search UI, which is out of scope; `research/features.md` explicitly recommends a purpose-built method instead of reusing this API |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/search/DatalogQuery.kt` (`DatalogEngine`, `DatalogQuery`, `VisualQueryBuilder`) | Inert, unreferenced stub from an earlier, abandoned attempt at this exact problem (`DatalogEngine.execute()` always returns empty; nothing in the codebase references any of the three types) | Extend as-is (no action — not touched) | Confirmed dead by repo-wide reference search; not built upon by this plan per `research/architecture.md` §5's explicit warning. Cleanup/removal is a separate drive-by task, out of scope here |

---

## Migration Plan

Not applicable — no schema changes. This feature adds new `.sq` `SELECT` queries (`selectBlocksWithMarkerPrefix`, Task 3.2.1b; `selectPagesWithPropertyPair`, Task 3.2.4b) compiled by SQLDelight, not persisted schema objects, and a new valid string value (`"query"`) within the existing `blocks.block_type TEXT` column (`SteleDatabase.sq:34`). Per this repo's `CLAUDE.md` migration rule, `MigrationRunner.all` entries are required only for new `CREATE TABLE IF NOT EXISTS` statements — this feature adds neither a table nor a column, so that rule does not apply and `MigrationRunnerSchemaSyncTest` requires no changes.

## Observability Plan

- **Logs**: `QueryParser.parse()` logs at `warn` level (truncated raw argument string, max ~100 chars) on every `ParseError.InvalidSyntax` **or** `ParseError.UnsupportedForm` return, tagged with which subtype fired, so malformed-vs-unsupported-query frequency is separately visible in existing log output without a new logging backend. `QueryExecutor.executeQuery()` needs no entry/exit logging beyond what its composed repository calls already emit (avoids duplicate structured logs at both layers).
- **Metrics**: No new metric is required for v1. This repo already has an internal SQL-performance-tracing subsystem named `QueryStatsCollector`/`QueryStatsRepository`/`query_stats` (`SteleDatabase.sq:856`) — an unfortunate naming collision with this feature's unrelated "query blocks" concept; do not reuse or extend that subsystem for this feature, and do not name any new class `QueryStats*`. If `research/pitfalls.md` §1's k×N cost (multiple simultaneous query blocks on one page) turns out to matter in practice post-ship, add a `HistogramWriter` timing entry around `QueryExecutor.executeQuery()` at that time — deferred, not a v1 blocker.
- **Alerts**: No new alerts required — this is a local, offline-first, single-user read path with no server-side SLA to page against.

## Risk Control

- **Feature flag**: `getFlag("live_query_blocks", default = true)` via the existing `DebugFlagRepository` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugFlagRepository.kt`, backed by the `debug_flags` table already in the schema, and already exposed as `RepositorySet.debugFlagRepository` — no new sourcing mechanism needed). **Concretely wired**, not just named: Task 4.1.2a's `BlockTypes.QUERY` dispatch arm is gated on a `liveQueryBlocksEnabled: Boolean` parameter; Task 4.1.2f reads it once via `getFlag(...)` at the same `PageView` call site that sources `queryExecutor` (Task 4.1.2e) and threads it down alongside it. When `false`, the dispatch arm falls through to the same literal-text rendering the pre-existing `else` branch produces.
  - **Honest scope of "kill-switch"**: `DebugFlagRepository.getFlag()` is a synchronous one-shot `Boolean` read with no `Flow`/`State` backing (`DebugFlagRepository.kt:23-26`) — it is read once per `BlockItem` composition, not watched reactively. Toggling the flag therefore takes effect the next time the affected page (re)composes — typically on reopen or navigation — not instantly for an already-rendered `QueryBlock` still on screen. That is an acceptable rollback speed for a local, single-user dev/rollout flag; this plan does not claim instant in-place toggling.
- **Rollback procedure**: Toggle the `live_query_blocks` debug flag off (no deploy needed, since `DebugFlagRepository` reads/writes the local SQLite `debug_flags` table at runtime) and reopen the affected page(s) to pick up the change; if a code-level revert is still needed, standard revert via PR close + revert commit.
- **Staged rollout**: Full rollout on merge — this is a local single-user desktop/mobile app with no server-side deployment ring to stage across; the debug flag above is the staging mechanism (default-on, but flippable per-install without a new build).

## Unresolved Questions

- [ ] Whether `(between ...)`'s relative-date-symbol form (`-7d`, `today`) should be a fast-follow after v1 ships with only the `[[journal page]]` pair form — does not block any story in this plan (the unsupported form already has a defined fallback: `ParseError.UnsupportedForm` → the distinguishable "unsupported query" placeholder, Story 4.1.1/ux.md Surface 4b — not the plain literal-text rendering) — owner: Tyler Stapler, for a future backlog item.
- [ ] Whether block-level `property` (distinct from `page-property`) should be added given "same marginal cost" per `research/features.md` §2 — explicitly out of v1 scope per `requirements.md`, not blocking — owner: Tyler Stapler, future backlog item.
- [ ] Exact call site(s) that construct `PageView(...)` (needed for Task 4.1.2e's `queryExecutor` wiring) were not exhaustively enumerated during planning — resolved by a `grep -rn "PageView("` sweep at implementation time, not a design decision — owner: implementing subagent, resolved immediately before starting Task 4.1.2e.
- [ ] Pre-mortem P2 #3 — the `PageProperty`/`Between` branches' per-matched-page nested-Flow fan-out (k open query blocks × M matched pages) is a real but unmeasured cost beyond what small seeded test fixtures exercise — tracked, not a v1 blocker; see `implementation/pre-mortem.md` failure #3 — owner: Tyler Stapler, revisit with a large-graph benchmark if it proves material post-ship. **Threshold**: revisit if a graph with >50 matching pages for a single `page-property`/`between` query is reported, or if UI lag is noticeable with ≥3 simultaneous query blocks open.
- [ ] Pre-mortem P2 #4 — the end-to-end latency of the "live" marker-toggle-to-refresh path (debounce + write + Flow re-emission + recomposition) is asserted, not measured — tracked, not a v1 blocker; see `implementation/pre-mortem.md` failure #4 — owner: Tyler Stapler, validate during manual QA before wide rollout. **Threshold**: revisit if a marker-toggle-to-refresh round trip is subjectively noticeable (>1s) during manual QA.
- [ ] `research/pitfalls.md` §4 — v1 has no query-execution timeout mechanism; none exists anywhere in this codebase's data layer today (confirmed by research). Result-set size is already bounded by `QueryExecutor.DEFAULT_LIMIT` (200) and `JOURNAL_SCAN_LIMIT` (500), which bounds worst-case query cost indirectly even without an explicit timeout — tracked, not a v1 blocker — owner: Tyler Stapler, revisit if a query-block render is reported to hang or block the UI thread for longer than ~1s during manual QA (same latency-noticeability bar as Pre-mortem P2 #4's live-refresh-latency item, above).
- [ ] Pre-mortem P2 #5 — each v1 filter form touches 6+ locations (interface method, `.sq` query, SQL backend, in-memory fake, `UpgradeResilienceTest` entry, `QueryExecutor` dispatch branch) with no shared registry/extension seam — tracked, not a v1 blocker; see `implementation/pre-mortem.md` failure #5 — owner: Tyler Stapler, add an "Adding a new query filter form" checklist before the first v2 filter form ships. **Threshold**: revisit before implementing the first v2 filter form, not before — no threshold needed until then, just don't skip writing the checklist at that time.

## Dependency Visualization

```
Phase 1 — Query Grammar                Phase 2 — Block Classification
┌─────────────────────────┐            ┌──────────────────────────────┐
│ 1.1 SimpleQuery AST      │            │ 2.1 BlockType.Query plumbing │
└────────────┬─────────────┘            └───────────────┬──────────────┘
             │                                           │
             ▼                                           ▼
┌─────────────────────────┐            ┌──────────────────────────────┐
│ 1.2 QueryParser          │            │ 2.1.2 Parse-time classify     │
│    (+ 1.2.2 tests)       │            │    (+ 2.2 doc fixes, indep.) │
└────────────┬─────────────┘            └───────────────┬──────────────┘
             │                                           │
             │        Phase 3 — Repository / QueryExecutor
             │        ┌───────────────────────────────────────────────┐
             │        │ 3.1.1 LinkMatching refactor (refactor-first,   │
             │        │       incl. 3.1.1e overfetch-loop extraction)  │
             │        │        │                                      │
             │        │        ▼                                      │
             │        │ 3.2.3 findReferencingBlocksReactive            │
             │        │        (reuses 3.1.1e's overfetch helper)      │
             │        │                                                │
             │        │ 3.2.1 findBlocksWithTaskMarker (independent)   │
             │        │ 3.2.4 getPagesWithProperty (independent)       │
             │        │        │                                      │
             │        │        ▼                                      │
             │        │ 3.2.2 QueryExecutor                            │◄── depends on 1.2 SimpleQuery type
             │        │       (needs 3.2.1 + 3.2.3 + 3.2.4)            │
             │        └───────────────────────┬───────────────────────┘
             │                                 │
             └─────────────────┬───────────────┘
                                ▼
                  Phase 4 — Live UI Rendering
                  ┌─────────────────────────────────────┐
                  │ 4.1.1 QueryBlock composable          │◄── needs Phase 1 + 3.2.2
                  │        │                             │
                  │        ▼                             │
                  │ 4.1.2 BlockItem dispatch + threading │◄── needs Phase 2 (BlockTypes.QUERY)
                  │        │                             │
                  │        ▼                             │
                  │ 4.2 Accessibility & focus safety      │
                  └───────────────────┬───────────────────┘
                                      ▼
                       Phase 5 — Verification & Regression
                  ┌─────────────────────────────────────┐
                  │ 5.1 QueryExecutor + screenshot tests │
                  │ 5.1.2 Reference-extraction regression│
                  │ 5.1.3 Multi-graph-switch regression  │
                  │ 5.2 Tech-debt disposition (docs only)│
                  └───────────────────────────────────────┘
```

---

## Phase 1: Query Grammar & Domain Model

### Epic 1.1: SimpleQuery AST

**Goal**: Define the typed representation every later phase (parser, executor, UI) speaks in terms of.

#### Story 1.1.1: Define SimpleQuery sealed types
**As a** developer implementing the query parser and executor, **I want** a shared `SimpleQuery`/`QueryFilter` type hierarchy, **so that** the parser, executor, and tests all reference the same domain vocabulary instead of ad hoc strings or maps.

**Acceptance Criteria**:
- `SimpleQuery` and its four `QueryFilter` variants plus `And`/`Or`/`Not` combinators exist as data classes/objects usable across `commonMain`.
  - *Given* the type `QueryFilter.Task(markers = setOf("NOW", "LATER"))`, *When* it is compared for structural equality against another `QueryFilter.Task(markers = setOf("NOW", "LATER"))`, *Then* they are equal (data class semantics), confirming the type is a plain, testable value.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/query/SimpleQuery.kt` (new)

##### Task 1.1.1a: Create `SimpleQuery.kt` with the type hierarchy (~7 min)
- Create `dev.stapler.stelekit.query` package with `SimpleQuery.kt` containing:
  ```kotlin
  sealed interface SimpleQuery

  /** What an `And` operand may be: a bare filter, or its negation. Never an `And`/`Or`. */
  sealed interface QueryOperand

  sealed interface QueryFilter : SimpleQuery, QueryOperand {
      data class Task(val markers: Set<String>) : QueryFilter
      data class PageProperty(val key: String, val value: String) : QueryFilter
      data class Between(val startPage: String, val endPage: String) : QueryFilter
      data class PageRef(val target: String) : QueryFilter
  }

  /** Negation of a single base filter. Deliberately not a `SimpleQuery` (no top-level `(not ...)`)
   *  and deliberately wraps `QueryFilter`, not `QueryOperand` (no double negation). Only usable as
   *  an `And` operand — see `Or` below. */
  data class Not(val filter: QueryFilter) : QueryOperand

  data class And(val left: QueryOperand, val right: QueryOperand) : SimpleQuery

  /** `Or`'s operands are plain `QueryFilter`, not `QueryOperand` — `Or(x, Not(y))` is deliberately
   *  unrepresentable. True `OR NOT` semantics require complementing `y` against a "universe of all
   *  blocks" this app has no practical reactive way to compute; restricting `Not` to `And`-operand
   *  position sidesteps that instead of implementing incorrect semantics. */
  data class Or(val left: QueryFilter, val right: QueryFilter) : SimpleQuery
  ```
- Because `And.left`/`And.right` are typed `QueryOperand` (not `SimpleQuery`), a value like `And(And(...), PageRef(...))` does not compile — nesting beyond one level, and a top-level `Not`, are unrepresentable by construction, not merely rejected by a parser convention. Because `Or.left`/`Or.right` are typed plain `QueryFilter` (not `QueryOperand`), a value like `Or(PageRef(...), Not(PageRef(...)))` also does not compile — v1's `Or`+`Not` restriction (see Domain Glossary) is enforced the same way.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/query/SimpleQuery.kt`

##### Task 1.1.1b: Cross-reference canonical marker vocabulary (~3 min)
- Add a one-line KDoc on `QueryFilter.Task` pointing at `InlineParser.kt:97`'s `taskMarkers` set as the canonical vocabulary `markers` values are normalized against, so a future reader doesn't invent a second vocabulary.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/query/SimpleQuery.kt`

---

### Epic 1.2: Query Argument Tokenizer/Parser

**Goal**: Turn `MacroNode.arguments`'s raw string into a `SimpleQuery`, gracefully failing to `ParseError.InvalidSyntax` (genuinely unrecognized input) or `ParseError.UnsupportedForm` (a recognized shape outside v1's grammar) on anything unsupported — see the classification rule in this story's acceptance criteria.

#### Story 1.2.1: Parse the four base forms plus one-level and/or/not
**As a** SteleKit user with an existing `{{query (task now later)}}` block, **I want** the raw argument string parsed into a structured query, **so that** the executor can act on it instead of treating it as opaque text.

**Acceptance Criteria**:
- `(task now later)` parses with case-folded, canonicalized markers.
  - *Given* the raw string `"(task now later)"`, *When* `QueryParser.parse(raw)` is called, *Then* it returns `Either.Right(QueryFilter.Task(markers = setOf("NOW", "LATER")))`.
- Legacy `(todo TODO)` aliases to the same `Task` filter.
  - *Given* the raw string `"(todo TODO)"`, *When* `QueryParser.parse(raw)` is called, *Then* it returns `Either.Right(QueryFilter.Task(markers = setOf("TODO")))`.
- `(page-property key value)` with a quoted multi-word value parses correctly.
  - *Given* the raw string `"(page-property type \"book\")"`, *When* parsed, *Then* it returns `Either.Right(QueryFilter.PageProperty(key = "type", value = "book"))`.
- `(between [[Page1]] [[Page2]])` parses to the journal-page-pair form (v1 scope).
  - *Given* the raw string `"(between [[Dec 5th, 2020]] [[Dec 7th, 2020]])"`, *When* parsed, *Then* it returns `Either.Right(QueryFilter.Between(startPage = "Dec 5th, 2020", endPage = "Dec 7th, 2020"))`.
- A bare `[[Page]]` or `#tag` parses to `PageRef`.
  - *Given* the raw string `"[[ProjectX]]"`, *When* parsed, *Then* it returns `Either.Right(QueryFilter.PageRef(target = "ProjectX"))`.
- One level of `and`/`or`/`not` wraps exactly the base forms.
  - *Given* the raw string `"(and [[tag1]] [[tag2]])"`, *When* parsed, *Then* it returns `Either.Right(And(QueryFilter.PageRef("tag1"), QueryFilter.PageRef("tag2")))`.
- Recognized-but-out-of-scope forms fail gracefully as `UnsupportedForm`, distinct from genuinely unrecognized input.
  - *Given* the raw string `"(and (and [[a]] [[b]]) [[c]])"` (two levels of `and` nesting), *When* parsed, *Then* it returns `Either.Left(DomainError.ParseError.UnsupportedForm(...))`, not `InvalidSyntax` — the `and` head symbol is recognized and the operand shape is well-formed, it just exceeds v1's one-level nesting cap; rejected because the operand parser's return type is `Either<ParseError, QueryOperand>` and a nested `and`/`or` cannot be expressed as a `QueryOperand`, but that structural mismatch is a *known, named* v1 boundary, not unparseable garbage.
  - *Given* the raw string `"(between -7d +7d)"` (relative-date form, out of v1 scope), *When* parsed, *Then* it returns `Either.Left(DomainError.ParseError.UnsupportedForm(...))` — `between` is a recognized head symbol; only the relative-date argument shape is unsupported.
  - *Given* the raw string `"(not [[a]])"` at the top level (not nested inside `and`/`or`), *When* parsed, *Then* it returns `Either.Left(DomainError.ParseError.UnsupportedForm(...))` — `not` is a recognized head symbol, but a top-level `Not` value can't be returned as a `SimpleQuery`.
  - *Given* the raw string `"(or [[tag1]] (not [[tag2]]))"` (a `Not` operand under `or`, out of v1 scope), *When* parsed, *Then* it returns `Either.Left(DomainError.ParseError.UnsupportedForm(...))` — `or` and `not` are both recognized head symbols, but v1 only allows `Not` as an `And` operand (see Domain Glossary/`QueryOperand`); `Or`'s operand-parsing function returns `Either<ParseError, QueryFilter>`, so it cannot construct a `Not`-wrapped `Or` operand at all.
- Genuinely unrecognized input fails gracefully as `InvalidSyntax`.
  - *Given* an arbitrary garbage string matching no known head symbol or shape at all (unbalanced parens, empty body, an unknown head symbol, stray unicode, deeply malformed quoting, null bytes, etc.), *When* `QueryParser.parse(raw)` is called, *Then* it returns `Either.Left(DomainError.ParseError.InvalidSyntax(...))` and never throws — backstopped by Task 1.2.1g's blanket `catch (e: Exception)`, not just the named checks.
- Classification rule (applies to every branch above): if the parser recognizes the head symbol (`task`/`todo`/`page-property`/`between`/`and`/`or`/`not`) but the argument shape isn't in v1's supported set, return `UnsupportedForm`; if the head symbol itself isn't recognized, or the input doesn't parse into any known shape at all, return `InvalidSyntax`. This rule is what lets `QueryBlock` (Story 4.1.1) show a distinguishable "not yet supported" placeholder instead of collapsing every failure into the same literal-text fallback (pre-mortem P1 #1).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/query/QueryParser.kt` (new)

##### Task 1.2.1a: Write the tokenizer (~5 min)
- Add a private tokenizer to `QueryParser.kt` that splits the raw argument string on whitespace and parens while treating `[[...]]`, `#tag`, and `"quoted strings"` as single tokens — same recursive-descent/manual-`advance()` idiom as `InlineParser.kt`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/query/QueryParser.kt`

##### Task 1.2.1b: Implement `task`/`todo` parsing (~5 min)
- Dispatch on head symbol `task` or legacy alias `todo` (case-insensitive); case-fold each following bareword marker to uppercase; validate against `InlineParser.kt:97`'s canonical set (reject unknown markers as `InvalidSyntax`).
- Files: same

##### Task 1.2.1c: Implement `page-property` parsing (~4 min)
- Dispatch on head symbol `page-property`; accept both a bareword and a quoted-string value token.
- Files: same

##### Task 1.2.1d: Implement `between` parsing, v1 scope only (~5 min)
- Dispatch on head symbol `between`; require exactly two `[[...]]` tokens; any other argument shape (relative-date symbols like `-7d`, `today`) returns `UnsupportedForm` (the `between` head symbol is recognized; only the relative-date argument shape is out of v1 scope) with a message naming the unsupported form.
- Files: same

##### Task 1.2.1e: Implement bare `[[Page]]`/`#tag` top-level parsing (~3 min)
- When the entire trimmed raw string is a single `[[...]]` or `#tag` token (no leading head symbol), produce `QueryFilter.PageRef`.
- Files: same

##### Task 1.2.1f: Implement one-level `and`/`or`/`not` (~7 min)
- Dispatch on head symbol `and`; parse each operand with a private `parseOperand(...): Either<DomainError.ParseError, QueryOperand>` that accepts a bare `QueryFilter` or `Not(<filter>)` and returns `UnsupportedForm` for a nested `and`/`or` operand, `InvalidSyntax` for anything that isn't a recognized filter/`not` shape at all. Because `QueryOperand` cannot represent an `And`/`Or` (see Task 1.1.1a), a nested `and`/`or` operand is a genuine type mismatch inside `parseOperand` — classify it as `UnsupportedForm` (the operand's own head symbol is a recognized filter/combinator, it's just nested one level too deep), not `InvalidSyntax`.
- Dispatch on head symbol `or` separately, parsing each operand with a stricter private `parseFilterOperand(...): Either<DomainError.ParseError, QueryFilter>` that accepts only a bare `QueryFilter` — never `Not(<filter>)`. If an `or` operand's shape is `(not ...)`, return `UnsupportedForm`: `not` is a recognized head symbol, but v1 restricts `Not` to `And`-operand position only (see Domain Glossary/`QueryOperand` — `Or(x, Not(y))` has no correct bounded-complement formula without a graph-wide "universe of all blocks" to complement against), so `Or`'s operand type is plain `QueryFilter` and there is no value `parseFilterOperand` could return for a `Not`-wrapped operand.
- A **top-level** `(not ...)` (the entire query body, not nested inside `and`/`or`) is rejected as `UnsupportedForm` for the analogous reason: `not` is a recognized head symbol, but `parse()`'s return type is `SimpleQuery`, and `Not` does not implement `SimpleQuery`, so there is no value `parse()` could return for it even if the dispatch tried — this is a known v1 boundary, not unparseable input.
- Files: same

##### Task 1.2.1g: Wrap all parsing in a single fallible entry point with a blanket exception guard (~5 min)
- Public `fun parse(raw: String): Either<DomainError.ParseError, SimpleQuery>`. Wrap the entire body as `try { <the enumerated checks: unbalanced parens, empty body, unknown head symbols return InvalidSyntax; the parseOperand structural checks from Task 1.2.1f and the between/nesting checks from Tasks 1.2.1d/1.2.1f return UnsupportedForm> } catch (e: CancellationException) { throw e } catch (e: Exception) { DomainError.ParseError.InvalidSyntax(...).left() }` — this repo's established repository-layer convention (never a bare `catch (e: Throwable)`, which would also swallow real `Error`s). The blanket `catch (e: Exception)` always maps to `InvalidSyntax` (never `UnsupportedForm`) — it's the backstop for genuinely unanticipated malformed input the enumerated checks don't name (stray unicode, malformed quoting, etc.), not a recognized-but-out-of-scope shape, so `UnsupportedForm` is only ever returned by an enumerated check that positively identified a recognized head symbol. Required so `QueryBlock`'s `remember(content) { QueryParser.parse(...) }` (Task 4.1.1b) can never see an uncaught exception crash the page render, per `requirements.md`'s "degrade gracefully... rather than crashing" success criterion.
- Files: same

#### Story 1.2.2: Unit test coverage for QueryParser
**As a** maintainer, **I want** every parse branch covered by a fast unit test, **so that** grammar regressions are caught before they reach the UI's silent-fallback path.

**Acceptance Criteria**:
- Every base form, the `todo` alias, `and`/`or`/`not`, and malformed-input fallback have a passing test.
  - *Given* `kmp/src/commonTest/kotlin/dev/stapler/stelekit/query/QueryParserTest.kt`, *When* `./gradlew jvmTest --tests "dev.stapler.stelekit.query.QueryParserTest"` runs, *Then* all cases pass.
- No input, however malformed, escapes `parse()` as an uncaught exception.
  - *Given* a fuzz-style batch of garbage strings (stray unicode, deeply malformed quoting, null bytes, etc.), *When* each is passed to `QueryParser.parse()`, *Then* every call returns `Either.Left(DomainError.ParseError.InvalidSyntax(_))` and none throws.

**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/query/QueryParserTest.kt` (new)

##### Task 1.2.2a: Write happy-path test cases (~5 min)
- One test per: `task`, `todo` alias, `page-property` (quoted and unquoted value), `between`, bare `PageRef` (`[[...]]` and `#tag`), `and`, `or`, `not`.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/query/QueryParserTest.kt`

##### Task 1.2.2b: Write malformed-input and unsupported-form test cases (~6 min)
- `InvalidSyntax` cases: unbalanced parens, unknown head symbol, empty query body — asserting `Either.Left(DomainError.ParseError.InvalidSyntax(_))`.
- `UnsupportedForm` cases: two-level `and` nesting, top-level `(not ...)`, relative-date `between` — asserting `Either.Left(DomainError.ParseError.UnsupportedForm(_))`, not `InvalidSyntax`, confirming the classification rule from Story 1.2.1's acceptance criteria.
- Files: same

##### Task 1.2.2c: Add a fuzz-style no-crash test (~4 min)
- A test that feeds `QueryParser.parse()` a batch of unstructured/garbage strings (empty, only whitespace, stray unicode, unbalanced/mismatched brackets and quotes, deeply repeated parens, null bytes) and asserts every call returns `Either.Left` — never throws — confirming Task 1.2.1g's blanket `catch (e: Exception)` actually holds for inputs no enumerated case anticipated.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/query/QueryParserTest.kt`

---

## Phase 2: Parse-Time Block Classification

### Epic 2.1: `BlockType.Query` plumbing

**Goal**: Give a whole-block `{{query ...}}` macro a first-class structural classification, following the exact precedent `BlockType.Table`/`BlockType.CodeFence` already established.

#### Story 2.1.1: Add BlockType.Query discriminator end-to-end
**As a** developer extending the block-type dispatch system, **I want** `BlockType.Query` to round-trip through the existing discriminator-string machinery, **so that** it behaves identically to every other structural block type already supported.

**Acceptance Criteria**:
- `BlockType.Query(rawQuery)` maps to `"query"` and is a valid `Block.blockType` value.
  - *Given* `BlockType.Query(rawQuery = "(task now)")`, *When* `.toDiscriminatorString()` is called, *Then* it returns `"query"`, and `Block(..., blockType = "query")` constructs without throwing (i.e., `"query"` is now in `validBlockTypes`).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/BlockTypeMapper.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/BlockTypes.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/model/BlockTypeMapperTest.kt`

##### Task 2.1.1a: Add the sealed case (~2 min)
- Add `data class Query(val rawQuery: String) : BlockType()` to the sealed class at `ParsedModels.kt:17-27`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt`

##### Task 2.1.1b: Add the discriminator constant (~2 min)
- Add `const val QUERY = "query"` to `BlockTypes.kt`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/BlockTypes.kt`

##### Task 2.1.1c: Add the mapper arm (~2 min)
- Add `is BlockType.Query -> BlockTypes.QUERY` to the `when` in `BlockTypeMapper.kt:3-13`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/BlockTypeMapper.kt`

##### Task 2.1.1d: Add to `validBlockTypes` (~2 min)
- Add `"query"` to the `validBlockTypes` set at `Models.kt:93-97`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`

##### Task 2.1.1e: Extend `BlockTypeMapperTest.kt` (~4 min)
- Add a `queryMapsToCorrectDiscriminator` test, and add `BlockType.Query("(task now)")`/`BlockTypes.QUERY` to the two lists inside `allBlockTypesConstantsMatchDiscriminatorStrings`.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/model/BlockTypeMapperTest.kt`

#### Story 2.1.2: Classify whole-block `{{query ...}}` macros at parse time
**As a** SteleKit user, **I want** a block whose entire content is `{{query ...}}` to be recognized as a query block when the page is parsed, **so that** the renderer can dispatch it to a live-results composable instead of literal text.

**Acceptance Criteria**:
- A whole-block query macro is classified as `BlockType.Query`.
  - *Given* source markdown `"- {{query (task now)}}"` parsed via `MarkdownParser().parsePage(...)`, *When* `convertBlock()` classifies the resulting `BulletBlockNode`, *Then* the returned `ParsedBlock.blockType` is `BlockType.Query(rawQuery = "(task now)")`.
- A query macro mixed into other inline text is left unclassified (existing fallback still applies).
  - *Given* source markdown `"- some text {{query (task now)}} more text"`, *When* `convertBlock()` classifies it, *Then* `ParsedBlock.blockType` remains `BlockType.Bullet` — unchanged — so `MarkdownEngine.kt`'s existing `MacroNode` literal-text branch still renders it exactly as before.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parser/MarkdownParserTest.kt`

##### Task 2.1.2a: Add the single-query-macro detector (~5 min)
- Add `private fun singleQueryMacroArg(content: List<InlineNode>): String?` to `MarkdownParser.kt`: filter out blank `TextNode`s, then check the remaining single element is a `MacroNode` with `name.equals("query", ignoreCase = true)`; return `arguments.firstOrNull().orEmpty()` or `null`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt`

##### Task 2.1.2b: Override classification in `convertBlock()` (~5 min)
- After the existing `blockType` `when (block)` computation (`MarkdownParser.kt:52-62`), if `block is BulletBlockNode || block is ParagraphBlockNode` and `singleQueryMacroArg(block.content)` is non-null, override `blockType` to `BlockType.Query(arg)`.
- Files: same

##### Task 2.1.2c: Add classification test cases (~5 min)
- Add cases to `MarkdownParserTest.kt` for: whole-block query macro → `BlockType.Query`; query macro mixed with other text → unchanged `BlockType.Bullet`; query macro as the sole content of a `ParagraphBlockNode` → `BlockType.Query`.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parser/MarkdownParserTest.kt`

---

### Epic 2.2: Drive-by doc fixes

**Goal**: Correct two stale comments the research phase found, with zero design risk (documentation-only).

#### Story 2.2.1: Correct stale comments identified by research
**As a** maintainer reading this code later, **I want** comments that match the actual, current behavior, **so that** I don't waste time re-investigating something already resolved.

**Acceptance Criteria**:
- The stale "not yet in the AST" comment no longer describes passing tests as ignored/unimplemented.
  - *Given* `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/OutlinerExtensionsSpec.kt`'s section comment above the `query macro produces MacroNode with name query` test, *When* the file is read after this task, *Then* the comment no longer claims `MacroNode` is unimplemented or that these tests are `@Ignore`'d.

**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/OutlinerExtensionsSpec.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt`

##### Task 2.2.1a: Fix the stale section comment (~3 min)
- Locate and remove/rewrite the "MacroNode is not yet in the AST, all @Ignored" comment above the `query`/`renderer` macro tests (requirements.md confirms these tests already pass and are not `@Ignore`'d).
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/OutlinerExtensionsSpec.kt`

##### Task 2.2.1b: Reconcile `TaskMarkerNode` doc comment (~3 min)
- `InlineNodes.kt:77-80`'s doc comment lists `IN-PROGRESS` as a valid marker, but `InlineParser.kt:97`'s actual `taskMarkers` set doesn't include it. Fix the doc comment to match the actual set (do not expand the marker vocabulary — that's a separate, unscoped change).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt`

---

## Phase 3: Reactive Query Execution (Repository Layer)

### Epic 3.1: Shared page-ref/tag matching (refactor-first, sequenced before reuse)

**Goal**: Close the pre-existing SQL-vs-fake page-name-matching divergence before building a third consumer of "does this block reference this page."

#### Story 3.1.1: Extract compileLinkPatterns/isLinkedReference into a shared function
**As a** developer adding a new tag/page-ref query path, **I want** one canonical definition of "this block references this page," **so that** the SQL backend, the in-memory fake, and the new query path never disagree.

**Acceptance Criteria**:
- Both backends call the same function and agree on alias and hashtag forms.
  - *Given* the shared function `isLinkedReference(content: String, patterns: LinkPatterns)` in `LinkMatching.kt`, *When* called with `content = "see #ProjectX for details"` and `patterns = compileLinkPatterns("ProjectX")`, *Then* it returns `true`; and `DatalogBlockRepository`'s `getLinkedReferences("ProjectX")`, now delegating to the same function, also returns that block — closing the divergence `research/pitfalls.md` §3 documents (the fake previously had no hashtag-form support).
- The paginated overfetch loop backing linked-reference lookups is shared, not duplicated.
  - *Given* the extracted `overfetchLinkedReferences` helper in `LinkMatching.kt` (Task 3.1.1e), *When* both the existing `getLinkedReferences(pageName, limit, offset)` and the new `findReferencingBlocksReactive` (Story 3.2.3) call it, *Then* grepping the codebase for `MAX_LINKED_REF_ITERATIONS` finds exactly one definition site, not two independently-written batch-widening loops.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/LinkMatching.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogBlockRepository.kt`

##### Task 3.1.1a: Create `LinkMatching.kt` (~5 min)
- Move `data class LinkPatterns(val wikiLink: Regex, val simpleHashtag: Regex)`, `fun compileLinkPatterns(pageName: String): LinkPatterns`, and `fun isLinkedReference(content: String, patterns: LinkPatterns): Boolean` verbatim from `SqlDelightBlockRepository.kt:997-1015` into the new file (top-level functions, not private).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/LinkMatching.kt`

##### Task 3.1.1b: Repoint `SqlDelightBlockRepository` (~4 min)
- Delete the private `compileLinkPatterns`/`isLinkedReference`/`LinkPatterns` at `SqlDelightBlockRepository.kt:997-1015`; import and call the shared versions from all existing call sites (`getLinkedReferences` ×2, `countLinkedReferences`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

##### Task 3.1.1c: Repoint `DatalogBlockRepository` (~5 min)
- Replace `DatalogBlockRepository`'s existing looser `\[\[name\]\]`-only regex in `getLinkedReferences`/`getUnlinkedReferences` with calls to the shared `compileLinkPatterns`/`isLinkedReference`, gaining alias-pipe and hashtag support it previously lacked.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogBlockRepository.kt`

##### Task 3.1.1d: Extend existing cross-backend tests (~5 min)
- Locate the existing test(s) exercising `getLinkedReferences` against both backends (search for a shared test suite or duplicated SQL/fake test pair); add alias-form (`[[Page|alias]]`) and hashtag-form (`#Page`) cases and assert both backends now return identical results.
- Files: existing test file(s) covering `getLinkedReferences` for both `SqlDelightBlockRepository` and `DatalogBlockRepository` (locate exact path via `grep -rln "getLinkedReferences" kmp/src/*Test*`)

##### Task 3.1.1e: Extract the iterative overfetch loop into a shared helper (~6 min)
- Move the bounded-iteration overfetch loop from `getLinkedReferences(pageName, limit, offset)` (`SqlDelightBlockRepository.kt:892-935`; constants `MAX_LINKED_REF_BATCH = 2_000` / `MAX_LINKED_REF_ITERATIONS = 50` at `:1186`/`:1188`) into `LinkMatching.kt` as `suspend fun overfetchLinkedReferences(limit: Int, offset: Int, patterns: LinkPatterns, loadBatch: suspend (batchSize: Int, sqlOffset: Int) -> List<Block>): List<Block>` — parameterize the two constants and the per-batch SQL call (currently two inlined `executeAsList()` calls) behind the `loadBatch` lambda, keeping the existing widening/backoff logic (batch starts at `need * 4`, doubles on low yield, capped at `MAX_LINKED_REF_BATCH`/`MAX_LINKED_REF_ITERATIONS`) verbatim. Repoint `getLinkedReferences(pageName, limit, offset)` to call it, passing a `loadBatch` that runs the existing two `executeAsList()` calls. Pure extraction — no behavior change for the existing one-shot method; the existing test(s) covering it (Task 3.1.1d's file) must continue to pass unchanged.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/LinkMatching.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

---

### Epic 3.2: New reactive repository reads backing the four query forms

**Goal**: Give `QueryExecutor` genuinely reactive, SQL-pushed-down reads for the forms with no existing indexed-SQL surface (task marker, page-property), and a corrected reactive read for tag/page-ref (the existing "reactive" precedent turned out to be one-shot on inspection).

#### Story 3.2.1: Task-marker matching — new BlockSearchRepository method
**As a** SteleKit user with `{{query (task now)}}` on a dashboard page, **I want** matching blocks to update live as markers change anywhere in the graph, **so that** the query stays trustworthy without reopening the page.

**Acceptance Criteria**:
- `findBlocksWithTaskMarker` returns only blocks whose content starts with one of the canonical uppercase markers, and re-emits after a write.
  - *Given* a block with `content = "TODO write the report"` saved via the write actor, *When* `findBlocksWithTaskMarker(setOf("TODO"), limit = 50, offset = 0)` is collected, *Then* it emits `Either.Right(listOf(thatBlock))`; *When* the block's content is then edited to `"DONE write the report"` and the write commits, *Then* the same collected `Flow` re-emits `Either.Right(emptyList())` without the caller re-subscribing.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockSearchRepository.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogBlockRepository.kt`, `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/UpgradeResilienceTest.kt`

##### Task 3.2.1a: Add the interface method (~3 min)
- Add `fun findBlocksWithTaskMarker(markers: Set<String>, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>>` to `BlockSearchRepository`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockSearchRepository.kt`

##### Task 3.2.1b: Add the `.sq` query (~5 min)
- Add to `SteleDatabase.sq`:
  ```sql
  selectBlocksWithMarkerPrefix:
  SELECT * FROM blocks WHERE content = :marker OR content LIKE :marker || ' %'
  ORDER BY created_at DESC LIMIT :limit OFFSET :offset;
  ```
- Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

##### Task 3.2.1c: Implement the SQLDelight backend (~5 min)
- Implement `findBlocksWithTaskMarker`: for each marker in the set, build `queries.selectBlocksWithMarkerPrefix(marker, limit.toLong(), offset.toLong()).asFlow().mapToList(PlatformDispatcher.DB)`; `combine()` the per-marker flows, merge + `distinctBy { it.uuid }`, map to `Block` via the existing `toBlockModel()`, wrap the whole chain with `.catchDbError()`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

##### Task 3.2.1d: Implement the in-memory fake (~4 min)
- Implement `findBlocksWithTaskMarker` as `blocks.map { map -> map.values.filter { b -> markers.any { m -> b.content == m || b.content.startsWith("$m ") } }.drop(offset).take(limit).right() }`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogBlockRepository.kt`

##### Task 3.2.1e: Add to `UpgradeResilienceTest` TC-UPGRADE-001 (~4 min)
- Add a `findBlocksWithTaskMarker(setOf("TODO"), 50, 0)` call to the closed-DB sweep method list so a missing `.catchDbError()` is caught mechanically.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/UpgradeResilienceTest.kt`

##### Task 3.2.1f: Add a reactivity regression test (~5 min)
- New or existing `jvmTest`: save a `"TODO ..."` block, collect `findBlocksWithTaskMarker`, edit the block's marker to `"DONE"`, assert the collected `Flow` re-emits the updated (now-empty) result without re-subscribing.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/` (new or existing file covering `SqlDelightBlockRepository` reactivity)

#### Story 3.2.2: QueryExecutor service composing all four forms
**As a** developer wiring the parsed query into the UI, **I want** one entry point that dispatches any `SimpleQuery` to the right repository composition, **so that** `QueryBlock` doesn't need to know which repositories back which filter.

**Acceptance Criteria**:
- `executeQuery` dispatches correctly for `Task`.
  - *Given* `QueryFilter.Task(setOf("NOW", "LATER"))`, *When* `queryExecutor.executeQuery(query)` is collected, *Then* it delegates to `blockSearchRepository.findBlocksWithTaskMarker(setOf("NOW", "LATER"), limit = DEFAULT_LIMIT, offset = 0)` and returns its emissions unchanged.
- `executeQuery` dispatches correctly for `PageProperty`.
  - *Given* `QueryFilter.PageProperty(key = "type", value = "book")` and a `Page` whose delimited `properties` string contains `"type:book"`, holding 2 blocks, *When* `executeQuery` is collected, *Then* it emits `Either.Right(those 2 blocks)`, computed via `pageRepository.getPagesWithProperty("type", "book", DEFAULT_LIMIT, 0)` (Story 3.2.4 — SQL-pushed-down, not an in-Kotlin scan over `getAllPages()`), `combine()`-ed with `blockReadRepository.getBlocksForPage(page.uuid)` per matching page.
- `executeQuery` dispatches correctly for `Between`, including the nonexistent-page case.
  - *Given* `QueryFilter.Between(startPage = "2026_01_01", endPage = "2026_01_07")` where both names resolve via `pageRepository.getPageByName(...).first()` to journal pages with `journalDate` `2026-01-01` and `2026-01-07`, *When* `executeQuery` is collected, *Then* it emits blocks from every journal page (via `pageRepository.getJournalPages`) whose `journalDate` falls inclusively in that range.
  - *Given* `QueryFilter.Between(startPage = "NoSuchPage", endPage = "2026_01_07")` where `"NoSuchPage"` resolves to `null`, *When* `executeQuery` is collected, *Then* it emits `Either.Right(emptyList())` — zero results, not a `DomainError` — matching the existing dead-wikilink convention (`research/features.md` §1).
- `executeQuery` dispatches correctly for `PageRef` and for `And`/`Or`/`Not`.
  - *Given* `QueryFilter.PageRef(target = "ProjectX")`, *When* `executeQuery` is collected, *Then* it delegates to `findReferencingBlocksReactive("ProjectX", ...)` (Story 3.2.3).
  - *Given* `And(QueryFilter.PageRef("tag1"), QueryFilter.PageRef("tag2"))`, *When* `executeQuery` is collected, *Then* it emits the intersection (by `Block.uuid`) of both filters' independently-executed result sets.
  - *Given* `And(QueryFilter.PageRef("tag2"), Not(QueryFilter.PageRef("tag1")))`, *When* `executeQuery` is collected, *Then* it emits `tag2Results - tag1Results` (set difference by `Block.uuid`) — not the union or intersection.
  - *Given* `Or(QueryFilter.PageRef("tag1"), QueryFilter.PageRef("tag2"))`, *When* `executeQuery` is collected, *Then* it emits the union (by `Block.uuid`) of both filters' independently-executed result sets. `Or`'s operands are typed `QueryFilter` (Task 1.1.1a), so `Or(_, Not(_))` cannot be constructed and this branch never receives a `Not` operand.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/query/QueryExecutor.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`

##### Task 3.2.2a: Create the class skeleton (~5 min)
- `class QueryExecutor(private val blockSearchRepository: BlockSearchRepository, private val propertyRepository: PropertyRepository, private val pageRepository: PageRepository, private val blockReadRepository: BlockReadRepository)` with `fun executeQuery(query: SimpleQuery): Flow<Either<DomainError, List<Block>>>` dispatching on `query`'s runtime type; define `companion object { const val DEFAULT_LIMIT = 200; const val JOURNAL_SCAN_LIMIT = 500 }` (`DEFAULT_LIMIT` confirmed against the display cap — see Unresolved Questions; `JOURNAL_SCAN_LIMIT` is the `Between` branch's journal-page fetch ceiling — see Domain Glossary and Task 3.2.2d).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/query/QueryExecutor.kt`

##### Task 3.2.2b: Implement the `Task` branch (~3 min)
- Delegate to `blockSearchRepository.findBlocksWithTaskMarker(markers, DEFAULT_LIMIT, 0)`.
- Files: same

##### Task 3.2.2c: Implement the `PageProperty` branch (~5 min)
- `pageRepository.getPagesWithProperty(key, value, DEFAULT_LIMIT, 0).flatMapLatest { either -> either.fold({ flowOf(it.left()) }, { pages -> if (pages.isEmpty()) flowOf(emptyList<Block>().right()) else combine(pages.map { blockReadRepository.getBlocksForPage(it.uuid) }) { results -> /* merge Either list, dedupe by uuid */ } }) }` — driven by the SQL-pushed-down `getPagesWithProperty` (Story 3.2.4), not an in-Kotlin scan over `pageRepository.getAllPages()`.
- Files: same

##### Task 3.2.2d: Implement the `Between` branch (~5 min)
- Resolve `startPage`/`endPage` via `pageRepository.getPageByName(name).first()` to `journalDate` boundaries (zero results if either is `null` or not a journal page); then `pageRepository.getJournalPages(limit = JOURNAL_SCAN_LIMIT, offset = 0).flatMapLatest { filter journalDate in [start, end], combine() getBlocksForPage per match }`. `JOURNAL_SCAN_LIMIT = 500` (defined on `QueryExecutor`'s companion object alongside `DEFAULT_LIMIT`, Task 3.2.2a) bounds how many of the most-recent journal pages are fetched before the Kotlin-side date-range filter runs — a range whose `startPage`/`endPage` falls outside the 500 most recent journal pages will silently return fewer results than exist; this is an accepted v1 boundary consistent with `DEFAULT_LIMIT`/`MAX_QUERY_RESULTS_DISPLAY`'s existing pattern of bounding fetch cost, not a bug to fix here.
- Files: same

##### Task 3.2.2e: Implement the `PageRef` branch (~3 min)
- Delegate to `blockSearchRepository.findReferencingBlocksReactive(target, DEFAULT_LIMIT, 0)` (Story 3.2.3).
- Files: same

##### Task 3.2.2f: Implement `And`/`Or` (with `Not` restricted to `And` operands) (~5 min)
- `executeQuery` only ever receives `And`/`Or` at the top level, or a `QueryFilter` — never a bare `Not`, since `Not` doesn't implement `SimpleQuery` and so cannot be `executeQuery`'s `query` argument at all (see Domain Glossary/`QueryOperand`).
- **`And`**: each operand is a `QueryOperand` — either a bare `QueryFilter` or `Not(filter)`. For an operand that is `Not(filter)`, execute the inner `filter` and compute the set difference against the *other* operand's result set — e.g. `And(PageRef("tag2"), Not(PageRef("tag1")))` executes both `PageRef` filters independently, then returns `tag2Results - tag1Results` (set difference by `Block.uuid`), matching the "tag2 but NOT tag1" example from `research/features.md` §2. This set-difference formula is correct specifically for `And`+`Not` because `And` already scopes the result to the other operand's set — it is deliberately **not** reused for `Or` (see below). `And` with no `Not` operand: intersection-by-uuid.
- **`Or`**: both operands are typed plain `QueryFilter` (never `Not`, enforced by `Or`'s type signature — see Task 1.1.1a/Domain Glossary), so this branch never needs to handle a `Not` operand at all: execute both filters independently and return the union-by-uuid. This sidesteps the correctness bug true `Or(x, Not(y))` semantics would require (`x ∪ (Universe − y)`, i.e. complementing `y` against every block in the graph — not the `And`+`Not` set-difference formula, which would silently compute the wrong thing, `x − y`, if misapplied here).
- Combine via `kotlinx.coroutines.flow.combine`.
- Files: same

##### Task 3.2.2g: Add `queryExecutor` field to `RepositorySet` (~2 min)
- Add `val queryExecutor: QueryExecutor? = null` to `data class RepositorySet` near the other optional per-graph fields (`undoManager`, `writeActor`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`

##### Task 3.2.2h: Wire construction into `createRepositorySet` (~3 min)
- Instantiate `QueryExecutor(blockRepo, createPropertyRepository(backend), pageRepo, blockRepo)` and pass `queryExecutor = ...` into the `RepositorySet(...)` call at `RepositoryFactory.kt:297-326`.
- Files: same

#### Story 3.2.3: Tag/page-ref reactive repository method
**As a** developer implementing `QueryExecutor`'s `PageRef` branch, **I want** a genuinely reactive read (not the existing one-shot method that looked reactive on first read), **so that** tag/page-ref query blocks actually update live.

**Acceptance Criteria**:
- The new method re-emits on writes; it is built fresh on `.asFlow()`, not on the existing one-shot `getLinkedReferences(limit, offset)`.
  - *Given* no blocks reference `"ProjectX"` yet, *When* `findReferencingBlocksReactive("ProjectX", limit = 50, offset = 0)` is collected and then a new block `"see [[ProjectX]]"` is saved, *Then* the same collected `Flow` re-emits `Either.Right(listOf(that new block))` without the caller re-subscribing — a property the existing `getLinkedReferences(pageName, limit, offset)` does **not** have (verified: it's a one-shot `flow { executeAsList() }` builder despite reading as "the reactive paginated precedent" in `research/architecture.md`/`research/pitfalls.md` — a correction this plan records explicitly).
- The new method does not under-return true matches the way a naive single-`LIMIT` query would.
  - *Given* the shared `overfetchLinkedReferences` helper (Task 3.1.1e) backs both `getLinkedReferences(pageName, limit, offset)` and `findReferencingBlocksReactive`, *When* enough SQL-side `LIKE` false positives land inside the first `limit`-sized window that fewer than `limit` rows survive the Kotlin-side `isLinkedReference` filter, *Then* `findReferencingBlocksReactive` still returns `limit` results as long as that many true matches exist anywhere in the table — the same guarantee `getLinkedReferences(limit, offset)` already provides, not a regression from it.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockSearchRepository.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogBlockRepository.kt`

##### Task 3.2.3a: Add the interface method (~3 min)
- Add `fun findReferencingBlocksReactive(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>>` to `BlockSearchRepository`, with a KDoc explicitly noting it is the reactive counterpart to `getLinkedReferences(limit, offset)`, which is one-shot despite appearances, and that both share the `overfetchLinkedReferences` correctness guarantee (Task 3.1.1e).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockSearchRepository.kt`

##### Task 3.2.3b: Implement the SQLDelight backend (~8 min)
- Build the Flow in two parts, reusing the `overfetchLinkedReferences` helper (Task 3.1.1e) so this method inherits `getLinkedReferences`' bounded-iteration correctness instead of a naive single-`LIMIT` query that can under-return true matches:
  1. A lightweight trigger — `queries.selectBlocksWithContentLikePaginated("%[[${pageName}%", 1L, 0L).asFlow()` — whose only job is to re-emit on any `blocks`-table invalidation (SQLDelight's `asFlow()` signal, not its row payload).
  2. `.map { }` on every trigger tick, re-running `overfetchLinkedReferences(limit, offset, compileLinkPatterns(pageName)) { batchSize, sqlOffset -> queries.selectBlocksWithContentLikePaginated("%[[${pageName}%", batchSize.toLong(), sqlOffset.toLong()).executeAsList().map(::toBlockModel) + queries.selectBlocksWithContentLikePaginated("%#${pageName}%", batchSize.toLong(), sqlOffset.toLong()).executeAsList().map(::toBlockModel) }.right()`.
  Wrap the whole chain with `.flowOn(PlatformDispatcher.DB).catchDbError()`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

##### Task 3.2.3c: Implement the in-memory fake (~4 min)
- `blocks.map { map -> map.values.filter { isLinkedReference(it.content, compileLinkPatterns(pageName)) }.drop(offset).take(limit).right() }`, reusing the shared `LinkMatching.kt` functions from Story 3.1.1.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogBlockRepository.kt`

##### Task 3.2.3d: Add to `UpgradeResilienceTest` TC-UPGRADE-001 (~3 min)
- Add `findReferencingBlocksReactive("ProjectX", 50, 0)` to the closed-DB sweep.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/UpgradeResilienceTest.kt`

#### Story 3.2.4: Page-property matching — new PageRepository method
**As a** SteleKit user with `{{query (page-property type book)}}` on a dashboard page, **I want** matching pages found via an indexed SQL predicate instead of an in-Kotlin scan over every page in the graph, **so that** the query stays cheap as the graph grows and doesn't re-run on every unrelated page write.

**Acceptance Criteria**:
- `getPagesWithProperty` returns only pages whose delimited `properties` string contains the given `key:value` pair as a whole comma-delimited token, pushed to SQL, and re-emits after a write.
  - *Given* a page with `properties = "type:book,author:Jane"` saved via the write actor, *When* `getPagesWithProperty(key = "type", value = "book", limit = 50, offset = 0)` is collected, *Then* it emits `Either.Right(listOf(thatPage))`; *When* the page's `properties` is then edited to remove `type:book` and the write commits, *Then* the same collected `Flow` re-emits `Either.Right(emptyList())` without the caller re-subscribing.
- `getPagesWithProperty` does not false-positive on a substring match across delimiter boundaries (pre-mortem P1 #2 — an unanchored `LIKE '%key:value%'` would wrongly match this case).
  - *Given* a page with `properties = "sub-type:bookmark"` (key `sub-type`, value `bookmark` — its literal characters contain `"type:book"` as a substring) and no page anywhere has an actual `type:book` pair, *When* `getPagesWithProperty(key = "type", value = "book", limit = 50, offset = 0)` is collected, *Then* it emits `Either.Right(emptyList())` — the page with `sub-type:bookmark` must NOT appear in the result.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/PageRepository.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightPageRepository.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogPageRepository.kt`, `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/UpgradeResilienceTest.kt`

##### Task 3.2.4a: Add the interface method (~3 min)
- Add `fun getPagesWithProperty(key: String, value: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>` to `PageRepository`, with a KDoc noting `properties` is stored as a delimited `"key:value,key2:value2"` string, not JSON, per `research/stack.md` §2 — the match is a `LIKE '%key:value%'` substring match on that literal pair, the same convention the existing exact-match property writers already use.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/PageRepository.kt`

##### Task 3.2.4b: Add the `.sq` query, anchored to delimiter boundaries (~6 min)
- `SqlDelightPageRepository.kt`'s write path (`properties = page.properties.entries.joinToString(",") { "${it.key}:${it.value}" }`) confirms the stored string is comma-delimited `key:value` pairs with **no** leading/trailing comma. An unanchored `LIKE '%key:value%'` substring match (pre-mortem P1 #2) would false-positive whenever one pair's characters are a substring of a different pair — e.g. `type:book` inside `sub-type:bookmark`. Add to `SteleDatabase.sq` instead:
  ```sql
  selectPagesWithPropertyPair:
  SELECT * FROM pages WHERE properties = :keyValuePair
    OR properties LIKE :keyValuePair || ',%'
    OR properties LIKE '%,' || :keyValuePair
    OR properties LIKE '%,' || :keyValuePair || ',%'
  ORDER BY name LIMIT :limit OFFSET :offset;
  ```
  where the caller builds `keyValuePair = "$key:$value"` before binding. The four branches cover: the pair is the entire string, the pair is first (followed by a comma), the pair is last (preceded by a comma), and the pair is in the middle (surrounded by commas) — mirroring the boundary-anchoring style already used for `content = :marker OR content LIKE :marker || ' %'` in `findBlocksWithTaskMarker` (Task 3.2.1b).
- Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

##### Task 3.2.4c: Implement the SQLDelight backend (~4 min)
- `queries.selectPagesWithPropertyPair("$key:$value", limit.toLong(), offset.toLong()).asDbFlowList(PlatformDispatcher.DB) { it.toModel() }` — same `asDbFlowList` idiom as `getJournalPages` (`SqlDelightPageRepository.kt:106-108`), not the one-shot `flow { executeAsList() }` shape `research/stack.md` warns against copying.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightPageRepository.kt`

##### Task 3.2.4d: Implement the in-memory fake with the same delimiter-anchored match (~5 min)
- Implement `getPagesWithProperty` filtering on whatever representation `DatalogPageRepository` already uses for `properties` (confirm during implementation). If it's a raw delimited string, do **not** use a naive `it.properties.contains("$key:$value")` substring check (the same false-positive bug as the unanchored SQL, e.g. matching `sub-type:bookmark` when searching for `type:book`) — instead split on `,` and check for an exact token match: `pages.map { map -> map.values.filter { p -> p.properties.split(",").any { it == "$key:$value" } }.drop(offset).take(limit).right() }`. If `DatalogPageRepository` already stores `properties` as a parsed `Map<String, String>`, use an exact key/value lookup instead (`p.properties[key] == value`), which has no substring-boundary issue to begin with.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DatalogPageRepository.kt`

##### Task 3.2.4e: Add to `UpgradeResilienceTest` TC-UPGRADE-001 (~3 min)
- Add a `getPagesWithProperty("type", "book", 50, 0)` call to the closed-DB sweep method list.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/UpgradeResilienceTest.kt`

##### Task 3.2.4f: Add a cross-contamination regression test (~5 min)
- Seed two pages: one with `properties = "type:book,author:Jane"`, another with `properties = "sub-type:bookmark"`. Assert `getPagesWithProperty("type", "book", 50, 0)` returns only the first page — proving the delimiter-anchored match (Tasks 3.2.4b/3.2.4d) rejects the substring collision pre-mortem P1 #2 identified. Cover both the SQLDelight backend (`jvmTest`) and the in-memory fake (`commonTest`/`businessTest`) so both implementations are held to the same correctness bar.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/` (SQLDelight-backed test, new or existing `PageRepository` test file), `kmp/src/businessTest/kotlin/dev/stapler/stelekit/query/QueryExecutorTest.kt` (add as one of the `PageProperty` dispatch cases in Task 5.1.1a)

---

## Phase 4: Live UI Rendering

### Epic 4.1: QueryBlock composable

**Goal**: A block-tier composable that renders exactly one of five states (loading / results / empty / malformed-fallback / unsupported-form-fallback) and stays live via `collectAsState()`.

#### Story 4.1.1: Render live query results as a distinct block
**As a** SteleKit user, **I want** a `{{query ...}}` block to show its live matching blocks in a visually distinct container, **so that** I can trust what's on screen is current and tell it apart from authored content.

**Acceptance Criteria**:
- Before the first `Flow` emission arrives, the block shows a loading state, never a blank block.
  - *Given* `block.content = "{{query (task now)}}"` and `queryExecutor.executeQuery(...)`'s `Flow` not yet having emitted, *When* `QueryBlock` is composed, *Then* it renders the same bordered chrome as the results state with the body text "Loading query results..." — not a blank container, and not the 0-results "No matching blocks" empty-state text.
- Results render with a header showing count.
  - *Given* `block.content = "{{query (task now)}}"` and `queryExecutor.executeQuery(...)` emitting `Either.Right(listOf(block1, block2))`, *When* `QueryBlock` is composed, *Then* it renders a header showing "2 results" and two clickable result rows.
- Zero results render an explicit message, never a blank block.
  - *Given* the same query emitting `Either.Right(emptyList())`, *When* `QueryBlock` is composed, *Then* it renders the header "0 results" plus an explicit "No matching blocks" message.
- Malformed queries fall back to literal text, not a crash.
  - *Given* `block.content = "{{query (frobnicate xyz)}}"` where `QueryParser.parse` returns `Either.Left(DomainError.ParseError.InvalidSyntax)`, *When* `QueryBlock` is composed, *Then* it renders the same literal-text styling `MarkdownEngine.kt:231-236` already uses for unrecognized macros (monospace `{{query (frobnicate xyz)}}`), not a crash or blank block.
- Recognized-but-unsupported queries render a placeholder visibly distinct from the malformed-literal-text fallback (pre-mortem P1 #1 — without this, "not yet supported" is indistinguishable from "still broken," which defeats the feature's own reason for existing).
  - *Given* `block.content = "{{query (between -7d +7d)}}"` where `QueryParser.parse` returns `Either.Left(DomainError.ParseError.UnsupportedForm)`, *When* `QueryBlock` is composed, *Then* it renders, inside the same bordered chrome as the results/empty states, a muted-style line "Unsupported query — showing raw text:" followed by the literal query text `{{query (between -7d +7d)}}` — not the plain, chrome-less monospace text used for `InvalidSyntax`.
- Large result sets truncate, and the header signals when the fetched count may not be the true total (see `design/ux.md` Surface 5 — the repository never fetches more than `QueryExecutor.DEFAULT_LIMIT` rows, so a fetched count at that ceiling can't be asserted as exhaustive).
  - *Given* `queryExecutor.executeQuery(...)` emitting a fetched set of exactly `QueryExecutor.DEFAULT_LIMIT` (200) blocks with `MAX_QUERY_RESULTS_DISPLAY = 50`, *When* `QueryBlock` is composed, *Then* it renders the first 50 rows, a "+150 more" affix, and a header reading "200+ results" (not "200 results") to signal the fetch hit the repository ceiling.
  - *Given* a fetched set of 83 blocks (below `DEFAULT_LIMIT`), *When* `QueryBlock` is composed, *Then* the header reads the exact count "83 results" with no `+`, since a fetch below the ceiling is guaranteed to be the true total.
- Rendered rows sit inside a bounded-height scrollable region, so a 50-row result never dominates page height.
  - *Given* a fetched/displayed set of 50 rows (`MAX_QUERY_RESULTS_DISPLAY`), *When* `QueryBlock` is composed, *Then* the rows render inside a `Modifier.heightIn(max = ...)` scrollable container capped at roughly 8-10 visible row heights, with the header/collapse toggle remaining outside and always visible.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/QueryBlock.kt` (new)

##### Task 4.1.1a: Add `queryArgFromContent` helper (~4 min)
- `fun queryArgFromContent(content: String): String? = Regex("""^\{\{\s*query\s+(.*)\}\}$""", RegexOption.IGNORE_CASE).matchEntire(content.trim())?.groupValues?.get(1)?.trim()` — same style as `headingLevelFromContent`/`codeFenceLanguage` (`BlockItem.kt:538-555`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/QueryBlock.kt`

##### Task 4.1.1b: Create the composable skeleton (~5 min)
- `@Composable internal fun QueryBlock(content: String, queryExecutor: QueryExecutor?, pageRepository: PageRepository?, onStartEditing: () -> Unit, onLinkClick: (String) -> Unit, modifier: Modifier = Modifier)` with `val parsed = remember(content) { QueryParser.parse(queryArgFromContent(content) ?: "") }`.
- Files: same

##### Task 4.1.1c: Wire `collectAsState()` (~5 min)
- `val resultState by remember(content) { parsed.fold({ flowOf(it.left()) }, { queryExecutor?.executeQuery(it) ?: flowOf(DomainError.ParseError.InvalidSyntax("no active query executor").left()) }) }.collectAsState(initial = null)` — keyed on `content` so recomposition doesn't rebuild the `Flow` every frame. The `initial = null` value is not an unhandled edge case: it is the loading state (Task 4.1.1e's 5th body state), rendered for the real, if brief, window before the underlying `Flow`'s first emission arrives — distinct from the 0-results empty state, which is a resolved answer.
- Files: same

##### Task 4.1.1d: Implement the container chrome (~6 min)
- Bordered/tinted `Box`/`Column` using `StelekitTheme.colors.blockRefBackground`-family tokens (`Theme.kt`); header row with raw query text, result-count text, and a collapse `IconButton`; tapping the header (not a result row) calls `onStartEditing()`.
- The header/collapse-toggle row sits outside a `Modifier.heightIn(max = ...)` (~8-10 row heights, matching common list/dropdown UI conventions per `design/ux.md` Surface 1) scrollable body region wrapping the result rows — so up to `MAX_QUERY_RESULTS_DISPLAY` (50) rendered rows scroll within a bounded height instead of visually dominating the page. If fewer rows than the height cap exist, the region sizes to fit its content (no empty scroll space).
- Files: same

##### Task 4.1.1e: Implement the five body states (~8 min)
- When `resultState == null` (the pre-first-emission window, Task 4.1.1c): a muted-style "Loading query results..." `Text`, rendered inside the same bordered chrome as the results state (`design/ux.md` Surface 1 loading state) — distinct from the 0-results empty state.
- Once `resultState` is non-null: result rows (Task 4.1.1g), explicit "No matching blocks" empty-state `Text`, literal-text malformed-fallback (`DomainError.ParseError.InvalidSyntax`) reusing `MarkdownEngine.kt`'s monospace `SpanStyle` values for pixel parity with today's dead-macro rendering (no bordered chrome, unchanged from pre-feature behavior), and a new unsupported-form placeholder (`DomainError.ParseError.UnsupportedForm`) rendered *inside* the bordered chrome with muted-style (secondary-foreground-color) text reading "Unsupported query — showing raw text:" followed by the literal query text — visibly distinct from the malformed-fallback state so a user can tell "recognized but not yet supported" from "still broken" (pre-mortem P1 #1; `design/ux.md` Surface 4a/4b).
- Files: same

##### Task 4.1.1f: Implement truncation and ceiling-aware count text (~5 min)
- `private const val MAX_QUERY_RESULTS_DISPLAY = 50` (value per Unresolved Questions); render `results.take(MAX_QUERY_RESULTS_DISPLAY)` plus a `"+${results.size - MAX_QUERY_RESULTS_DISPLAY} more"` `Text` when `results.size > MAX_QUERY_RESULTS_DISPLAY`. Header count text: `"${results.size} results"` when `results.size < QueryExecutor.DEFAULT_LIMIT`; `"${results.size}+ results"` when `results.size == QueryExecutor.DEFAULT_LIMIT`, signaling the fetch hit the repository-level ceiling and the true total may be higher (see `design/ux.md` Surface 5 — do not imply an exhaustive total beyond what was actually fetched).
- Files: same

##### Task 4.1.1g: Resolve result rows' page names for click-through (~5 min)
- Batch-resolve each visible result `Block.pageUuid` to a page name via `pageRepository?.getPageByUuid(uuid)` for the currently-displayed page of results, producing `List<QueryResultRow>` (block + pageName) before rendering — resolved above the render call, handed down as a value, matching `BlockViewer.kt`'s `resolvedRefs` precedent rather than fetching inside the render loop.
- Files: same

#### Story 4.1.2: Wire BlockTypes.QUERY into the BlockItem dispatch and thread QueryExecutor down
**As a** developer, **I want** `block.blockType == "query"` to route to `QueryBlock` and every ancestor composable to have access to the active graph's `QueryExecutor`, **so that** the new render path is actually reachable from a real page.

**Acceptance Criteria**:
- A query-typed block dispatches to `QueryBlock` when the `live_query_blocks` flag is on.
  - *Given* a saved `Block` with `blockType = BlockTypes.QUERY` and `content = "{{query (task now)}}"`, and `liveQueryBlocksEnabled = true`, *When* `BlockItem` renders it in view mode, *Then* the `when (block.blockType)` at `BlockItem.kt:353` dispatches to a new `BlockTypes.QUERY ->` arm calling `QueryBlock`, not the `else` branch.
- A query-typed block falls back to literal-text rendering when the flag is off.
  - *Given* the same block but `liveQueryBlocksEnabled = false` (sourced from `debugFlagRepository.getFlag("live_query_blocks", default = true)`, Task 4.1.2f), *When* `BlockItem` renders it, *Then* the `BlockTypes.QUERY` arm renders the same literal `{{query ...}}` text the pre-existing `else` branch produces, not `QueryBlock`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockRenderer.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`, plus the `PageView(...)` call site(s)

The real call chain is `PageView` → `BlockList` (`BlockList.kt:197` calls `BlockRenderer(...)`) → `BlockRenderer` (`BlockRenderer.kt:87` calls `BlockItem(...)`, documented as "preserves the original public API so existing callers continue to work without changes") → `BlockItem`'s `when (block.blockType)` dispatch. `queryExecutor`/`pageRepository` must be threaded through all three wrapper layers, not just `BlockList`.

##### Task 4.1.2a: Add the dispatch arm, gated by the `live_query_blocks` debug flag (~5 min)
- Add a new arm in the `when (block.blockType)` (`BlockItem.kt:353`), placed after the `BlockTypes.TABLE` arm (`:393-397`) and before the `else` branch (`:398`):
  ```kotlin
  BlockTypes.QUERY -> if (liveQueryBlocksEnabled) {
      QueryBlock(content = block.content, queryExecutor = queryExecutor, pageRepository = pageRepository, onStartEditing = onStartEditing, onLinkClick = onLinkClick, modifier = Modifier.weight(1f))
  } else {
      // Same literal-text rendering the `else` branch below already produces for unrecognized block types.
      <call whatever the existing `else` branch (`:398`) calls, unchanged>
  }
  ```
- `liveQueryBlocksEnabled` is a plain `Boolean` parameter (Task 4.1.2b), not read from `DebugFlagRepository` inside `BlockItem` itself — `BlockItem` stays a pure function of its parameters; the actual `getFlag()` read happens once at the `PageView` call site (Task 4.1.2f).
- Import `BlockTypes.QUERY`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`

##### Task 4.1.2b: Add parameters to `BlockItem` (~2 min)
- Add `queryExecutor: QueryExecutor? = null`, `pageRepository: PageRepository? = null`, and `liveQueryBlocksEnabled: Boolean = true` to `@Composable internal fun BlockItem(...)` (`BlockItem.kt:45-93`).
- Files: same

##### Task 4.1.2c: Thread through `BlockRenderer.kt` (~3 min)
- Add `queryExecutor: QueryExecutor? = null`, `pageRepository: PageRepository? = null`, and `liveQueryBlocksEnabled: Boolean = true` to `@Composable fun BlockRenderer(...)` (`BlockRenderer.kt:41-86`) and pass all three through to its `BlockItem(...)` call at `BlockRenderer.kt:87`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockRenderer.kt`

##### Task 4.1.2d: Thread through `BlockList.kt` and `PageView.kt` (~5 min)
- Add `queryExecutor: QueryExecutor? = null` and `liveQueryBlocksEnabled: Boolean = true` to `BlockList`'s signature and pass both through to its `BlockRenderer(...)` call at `BlockList.kt:197` (`pageRepository` is very likely already available to `BlockList`/`BlockRenderer` call sites since `PageView` already receives one — confirm during implementation and only add a new parameter if it isn't already threaded). Add `queryExecutor: QueryExecutor? = null` and `liveQueryBlocksEnabled: Boolean = true` to `PageView`'s signature (`PageView.kt:59-76`, alongside the existing `pageRepository: PageRepository` parameter already there at line 62) and pass through to its `BlockList` call site.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

##### Task 4.1.2e: Source `queryExecutor` at the `PageView` call site via `collectAsState()`, not a one-shot getter (~5 min)
- Locate the caller(s) that construct `PageView(...)` (`grep -rn "PageView("` — not exhaustively enumerated during planning, see Unresolved Questions). At that call site, collect `GraphManager`'s reactive `activeRepositorySet: StateFlow<RepositorySet?>` (`GraphManager.kt:65`) via `val activeRepositorySet by graphManager.activeRepositorySet.collectAsState()`, then derive `val queryExecutor = activeRepositorySet?.queryExecutor` from that collected value.
- **Explicitly do not** use `graphManager.getActiveRepositorySet()` (`GraphManager.kt:470`) here — that's a synchronous one-shot getter that reads `_activeRepositorySet.value` once and does not recompose on `switchGraph()`. Capturing its result would go stale after a graph switch, reopening exactly the stale-subscription hazard ADR-002/`research/pitfalls.md` §5 already warn about for this codebase. `collectAsState()` on the `StateFlow` is required so `queryExecutor` correctly re-derives (and downstream composables recompose) on every graph switch.
- Follow the same already-established per-graph sourcing pattern used for `blockRepository`/`pageRepository` at that same call site if one already collects `activeRepositorySet` reactively; do not introduce a second, divergent sourcing mechanism.
- Files: caller of `PageView(...)` (resolve exact file via grep before editing)

##### Task 4.1.2f: Read the `live_query_blocks` debug flag once per graph, re-derived from the same reactive `activeRepositorySet` (~3 min)
- At the same call site and using the **same** `activeRepositorySet by graphManager.activeRepositorySet.collectAsState()` value collected in Task 4.1.2e (not a second, independent collection and not `getActiveRepositorySet()`), compute `val liveQueryBlocksEnabled = remember(activeRepositorySet) { activeRepositorySet?.debugFlagRepository?.getFlag("live_query_blocks", default = true) ?: true }` — `debugFlagRepository` is already a `RepositorySet` field (`RepositoryFactory.kt`), so no new sourcing mechanism is needed, only threading. `remember(activeRepositorySet)` (not `remember(Unit)`) re-reads the flag on graph switch rather than only once per process lifetime, and only works correctly because `activeRepositorySet` itself is the `collectAsState()`-derived value from Task 4.1.2e, not a stale one-shot capture. Pass `liveQueryBlocksEnabled` down through `PageView` → `BlockList` → `BlockRenderer` → `BlockItem` alongside `queryExecutor` (Tasks 4.1.2b-d). The flag read itself is still a one-shot `Boolean` read per composition, not a reactive `Flow` — see Risk Control's honest-scope note on why that's an acceptable kill-switch speed for this flag; only its *sourcing repository set* must be reactive, per Task 4.1.2e.
- Files: caller of `PageView(...)` (same file resolved in Task 4.1.2e)

---

### Epic 4.2: Accessibility & focus safety

**Goal**: Meet the platform-parity and focus-safety findings from `research/ux.md` §3.

#### Story 4.2.1: Live-region announcement, keyboard roving, focus-steal guard
**As a** screen-reader user, **I want** the result count to be announced when it changes, **and as any user**, **I want** a live-updating query block to never steal my focus while I'm editing elsewhere, **so that** the "live" behavior is trustworthy rather than disruptive.

**Acceptance Criteria**:
- The count text is a live region, scoped to just the count.
  - *Given* a `QueryBlock` showing "3 results" and the underlying data changing to 4 matching blocks, *When* the `Flow` re-emits, *Then* the header text recomposes to "4 results" with `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` attached to that `Text` node specifically, not the whole result list.
- Recomposition never steals focus.
  - *Given* a user mid-edit in an unrelated block elsewhere on the page, *When* a `QueryBlock` elsewhere on the same page recomposes due to a live update, *Then* no `FocusRequester.requestFocus()` call fires from `QueryBlock`'s recomposition.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/QueryBlock.kt`

##### Task 4.2.1a: Apply live-region semantics (~3 min)
- Apply `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` to the result-count `Text` only.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/QueryBlock.kt`

##### Task 4.2.1b: Implement roving-tabindex focus handling (~5 min)
- Result rows: arrow keys move focus within the list, `Tab` exits to the next block — same `FocusRequester` handoff idiom as `BlockItem.kt:94, 205-210`; the container itself is a single tab stop in the page's block-to-block tab order.
- Files: same

##### Task 4.2.1c: Audit against focus-steal on recomposition (~3 min)
- Confirm no `LaunchedEffect`/`remember` block in `QueryBlock` calls `requestFocus()` in response to data changes; add a one-line code comment stating the invariant (mirroring the guard already documented at `BlockItem.kt:205`).
- Files: same

---

## Phase 5: Verification & Regression Coverage

### Epic 5.1: End-to-end and regression coverage

**Goal**: Confirm the executor's branches and the composable's visual states before shipping, and confirm no pre-existing behavior regresses.

#### Story 5.1.1: Executor + rendering test coverage
**As a** maintainer, **I want** `QueryExecutor`'s branches and `QueryBlock`'s visual states covered by fast, repeatable tests, **so that** future changes can't silently break live query blocks.

**Acceptance Criteria**:
- All `QueryExecutor` branches are covered against in-memory fakes.
  - *Given* a `businessTest` seeding `DatalogBlockRepository`/`DatalogPageRepository` fakes with known pages/blocks, *When* `QueryExecutorTest` exercises the six `SimpleQuery` dispatch branches (`Task`/`PageProperty`/`Between`/`PageRef`/`And`/`Or`) plus `Not`-as-`And`-operand coverage (`Not` is a `QueryOperand`, not itself a `SimpleQuery`, so it is never a standalone dispatch branch, and is only valid under `And` in v1 — `Or`'s operand type excludes it at compile time, see Domain Glossary), *Then* every case returns the expected block set.
- The `Between` branch's `JOURNAL_SCAN_LIMIT` boundary is exercised, not just documented.
  - *Given* a `Between` range whose `startPage`/`endPage` journal dates resolve to dates older than the 500 most-recently-fetched journal pages (i.e., outside `QueryExecutor.JOURNAL_SCAN_LIMIT`'s scan window), *When* `executeQuery` is collected, *Then* it emits `Either.Right(emptyList())` (or an undercounted result short of the true match set) rather than throwing or hanging — confirming Task 3.2.2d's documented accepted-boundary behavior ("a range... will silently return fewer results than exist") is actually exercised by a test, not just asserted in prose.
- `QueryBlock`'s visual states are screenshot-covered.
  - *Given* a Roborazzi screenshot test rendering `QueryBlock` in each of its 5 states (loading/results/empty/malformed-fallback/unsupported-form-fallback), *When* `./gradlew jvmTest` runs, *Then* the recorded screenshots match (or are recorded fresh on first run, per `kmp/TESTING_README.md`'s Roborazzi convention).

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/query/QueryExecutorTest.kt` (new), a new or extended `jvmTest` Roborazzi screenshot test for `QueryBlock`

##### Task 5.1.1a: Write `QueryExecutorTest.kt` (~5 min per branch, 10 cases)
- One test per `SimpleQuery` dispatch branch (`Task`, `PageProperty` — including the Task 3.2.4f cross-contamination case, `Between` including the nonexistent-page case, `PageRef`, `And`, `Or`), plus a test for `Not` used as an `And` operand confirming the set-difference formula (`Not` is a `QueryOperand`, never a top-level `SimpleQuery`, and is only valid as an `And` operand in v1 — `Or`'s operand type is plain `QueryFilter`, so no `Not`-as-`Or`-operand test is possible or needed; that restriction is enforced at compile time, not by a runtime dispatch branch), plus a `JOURNAL_SCAN_LIMIT` boundary case (a `Between` range whose journal pages fall entirely outside the 500-most-recent scan window returns an empty/undercounted result, not a crash — Task 3.2.2d's documented boundary), using seeded `DatalogBlockRepository`/in-memory `PageRepository`/`PropertyRepository` fakes.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/query/QueryExecutorTest.kt`

##### Task 5.1.1b: Write the `QueryBlock` screenshot test (~5 min)
- Extend the existing Roborazzi screenshot-test setup (locate the pattern used for `TableBlock`/`CodeFenceBlock`, if one exists, via `grep -rln "Roborazzi" kmp/src/jvmTest`) to cover `QueryBlock`'s loading/results/empty/malformed-fallback/unsupported-form-fallback states.
- Files: locate/extend existing screenshot test infrastructure under `kmp/src/jvmTest`

#### Story 5.1.2: Reference-extraction non-regression
**As a** maintainer, **I want** confirmation that adding query-argument parsing doesn't change or duplicate the existing `{{query [[Page]]}}` reference-extraction behavior, **so that** backlinks/reference panels stay correct.

**Acceptance Criteria**:
- A `{{query [[ProjectX]]}}` block still contributes "ProjectX" to the block's reference set exactly once.
  - *Given* source `"- {{query [[ProjectX]]}}"`, *When* `MarkdownParser().parsePage(...)` runs (which now also classifies this block as `BlockType.Query` and separately feeds the same string to `QueryParser` for a different purpose), *Then* `ParsedBlock.references` contains `"ProjectX"` exactly once — confirming `extractReferences()` (`MarkdownParser.kt:244-249`) and the new query-argument parsing path don't double-contribute.

**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parser/MarkdownParserTest.kt`

##### Task 5.1.2a: Add the regression test (~4 min)
- Add the test case described above.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parser/MarkdownParserTest.kt`

#### Story 5.1.3: Multi-graph-switch regression coverage for query blocks
**As a** maintainer, **I want** `QueryExecutor`/`QueryBlock` exercised across a real `switchGraph()` call, **so that** Task 4.1.2e's fix (sourcing `queryExecutor` from `GraphManager`'s reactive `activeRepositorySet: StateFlow<RepositorySet?>`, not the one-shot `getActiveRepositorySet()` getter) has a regression guard, the same way `GraphManagerDatabaseLifecycleTest` already guards the closed-DB-after-switch bug class for other repositories.

**Acceptance Criteria**:
- A query block's live results correctly reflect the active graph after a switch, never a stale prior graph's data.
  - *Given* graph A seeded with a block matching `{{query (task now)}}` and graph B seeded with a different (or empty) set of matching blocks, and a `queryExecutor` obtained from `graphManager.activeRepositorySet.value?.queryExecutor` while graph A is active, feeding a live `QueryExecutor.executeQuery(...)` `Flow` collected in a launched coroutine (same `runBlocking`/`launch(Dispatchers.Default)`/`.collect { }` idiom as `GraphManagerDatabaseLifecycleTest.kt`, not Compose's `collectAsState()` — this is a plain JVM test with no composition), *When* `graphManager.switchGraph(graphB)` is called, *Then* re-reading `graphManager.activeRepositorySet.value?.queryExecutor` after the switch yields a `QueryExecutor` bound to graph B's repositories, and the collector attached to graph A's `queryExecutor` either completes/is torn down or never continues emitting graph A's stale result set — it must not silently keep returning graph A's data as if it were still current.
  - *Given* the same setup, *When* `switchGraph()` completes, *Then* no `IllegalStateException` (closed-DB) escapes the collector on the `Flow` returned by `executeQuery(...)`, mirroring `GraphManagerDatabaseLifecycleTest`'s existing `collectionCrashed` assertions for other repository `Flow`s.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryExecutorGraphSwitchTest.kt` (new, modeled on `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphManagerDatabaseLifecycleTest.kt`'s real-`GraphManager`-plus-real-repositories seam — no mocking of `GraphManager` or the repository layer)

##### Task 5.1.3a: Write `QueryExecutorGraphSwitchTest.kt` (~8 min)
- Follow `GraphManagerDatabaseLifecycleTest.kt`'s pattern (real `GraphManager`, real `SqlDelightBlockRepository`-backed `RepositorySet`s, `runBlocking`/`launch`/`.collect { }` — no mocks, no Compose runtime): add graph A and graph B, seed each with distinct `{{query (task now)}}`-matching content, launch a coroutine collecting `graphManager.activeRepositorySet.value?.queryExecutor?.executeQuery(...)` against graph A, call `graphManager.switchGraph(graphB)`, then assert (a) `graphManager.activeRepositorySet.value?.queryExecutor` now resolves against graph B's repositories and (b) the graph-A collector never emitted graph B-contradicting stale data and didn't crash — per this story's acceptance criteria.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/QueryExecutorGraphSwitchTest.kt`

### Epic 5.2: Tech-debt disposition follow-through

**Goal**: Confirm nothing in this plan silently touches the hotspots flagged as out-of-scope.

#### Story 5.2.1: Record disposition for out-of-scope hotspots
**As a** reviewer, **I want** confirmation that `DatalogQuery.kt` and `SqlDelightSearchRepository`'s dead `propertyFilters` field were deliberately left alone, **so that** their absence from this plan isn't mistaken for an oversight.

**Acceptance Criteria**:
- No task in this plan modifies `kmp/src/commonMain/kotlin/dev/stapler/stelekit/search/DatalogQuery.kt` or `SqlDelightSearchRepository.kt`'s `propertyFilters` handling.
  - *Given* the completed task list above, *When* it is grep'd for `DatalogQuery.kt` or `propertyFilters`, *Then* no match is found outside the Tech Debt Disposition table itself — confirming the "Extend as-is" disposition was honored, not silently abandoned.

**Files**: None — this story is a documentation/verification checkpoint, not a code change. (No tasks.)
