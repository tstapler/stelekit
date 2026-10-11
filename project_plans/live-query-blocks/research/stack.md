# Research: Stack & Libraries for Live Query Blocks

Agent 1 (Stack) — SDD Phase 2 research, `live-query-blocks`.

## Summary

No new dependencies are needed. SQLDelight 2.3.2 + Kotlin Flow (already on the
classpath) are expressive enough for all four v1 query forms. The schema has
**no dedicated columns** for task marker or structured properties — both are
embedded as delimited text inside `blocks.content` / `blocks.properties` —
so query execution will need `LIKE`-based SQL filtering (already precedented)
or in-Kotlin filtering over a reactively-streamed row set, not new indexed
columns. Critically, **the existing property-query methods that most closely
resemble what a query executor needs are NOT actually reactive** — this is a
trap to avoid copying. There is no s-expression/grammar library or existing
parser for `MacroNode.arguments` strings; a small hand-rolled tokenizer is the
right call and matches the repo's established style.

## 1. SQLDelight reactivity: sufficient, but pick the right existing pattern

Version pin: `app.cash.sqldelight:runtime:2.3.2` /
`coroutines-extensions:2.3.2` / `async-extensions:2.3.2`
(`kmp/build.gradle.kts:66-68`), driver deps per-platform at lines 117, 175, 283.

Two reactive patterns coexist in the repo, with materially different "live"
semantics — **only one of them is actually live**:

- **Truly reactive** — `Query.asFlow().mapToList/mapToOneOrNull(dispatcher)`,
  wrapped by the shared helpers in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/DbFlowExtensions.kt:36-63`
  (`asDbFlowList` / `asDbFlowOrNull`, both baking in `catchDbError()`). SQLDelight's
  `asFlow()` re-runs the query and re-emits whenever a transaction touches any
  table the query reads from — this is genuinely "no polling" reactivity.
  Example call sites:
  `SqlDelightBlockRepository.kt:86` (`asDbFlowList`),
  `SqlDelightBlockRepository.kt:80` (`asDbFlowOrNull`),
  `SqlDelightBlockRepository.kt:1022`.
  `SqlDelightBlockRepository.kt:222` additionally uses `.conflate()` on a manual
  `asFlow()` chain to collapse rapid-fire invalidations during bulk import —
  worth reusing if a live query watches a page with many blocks changing at once.

- **One-shot, NOT reactive** — plain `flow { try { ...executeAsList()... emit(...) } }.flowOn(DB)`.
  This is used for exactly the kind of predicate the query executor needs:
  `SqlDelightPropertyRepository.kt:98-109` (`getBlocksWithPropertyKey`) and
  `:111-122` (`getBlocksWithPropertyValue`) both call
  `queries.selectAllBlocks().executeAsList()` once, filter in Kotlin, and `emit`
  a single value. **This flow completes after one emission and will never
  auto-update when blocks change** — it looks like the right precedent by name
  but is the wrong one to copy for a "live" feature. Same one-shot shape at
  `SqlDelightSearchRepository.kt:74,123,163,176` and
  `SqlDelightBlockRepository.kt:865,892,937,956,979,1024`.

**Implication for the query executor**: new repository methods backing
`(todo ...)`, `(page-property ...)`, `(between ...)`, and tag/page-ref queries
must be built on `.sq` queries wrapped with `asDbFlowList`/`asDbFlowOrNull` (or
a manual `asFlow()...catchDbError()` chain per the CLAUDE.md dispatcher
matrix), not on the `flow { executeAsList() }` one-shot shape that already
exists for property lookups — even though that's the most topically similar
existing code.

## 2. Schema reality: text-blob storage, not structured columns

`kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`:

- `blocks` table (`:20-38`): no `marker`/`task_status` column. Task marker
  state (`TODO`, `DOING`, `DONE`, etc.) lives only as a leading token in the
  free-text `content` column (`:26`). The parser recognizes it post-hoc — see
  §3 below — it does not exist as a queryable value anywhere in the DB.
- `properties` columns on both `blocks` (`:31`) and `pages` (`:11`) are
  commented `-- JSON string for ... properties` but are **not actually JSON**
  at runtime: `SqlDelightPropertyRepository.kt:124-131` (`parseProperties`)
  and `SqlDelightPageRepository.kt:257,278` both parse them as a hand-rolled
  `"key1:value1,key2:value2"` comma/colon delimited string via
  `.split(",")` / `.split(":", limit = 2)`, and writes serialize the same way
  (`SqlDelightPageRepository.kt:162,173`; `SqlDelightPropertyRepository.kt:70,87`
  via `updateBlockProperties`). This is a pre-existing schema-comment/reality
  mismatch, not something to fix as part of this feature, but the query
  executor must match the delimited-string format, not treat the column as JSON.
- `LIKE` filtering on `blocks.content` and `pages.name`/journal fields is
  already an established, reactive-safe pattern:
  `SteleDatabase.sq:206,209` (`content LIKE ?`), `:428,431` (`pages.name LIKE ?`),
  `:654,658,662,677,685` (`content LIKE '%[[' || :pageName || ']]%'` — this is
  literally the existing page-ref/tag reference query, reusable almost as-is
  for tag/page-ref query forms).
- `(between date1 date2)` (journal date range, per requirements.md's scoped
  interpretation): `pages.journal_date` is `TEXT` (`SteleDatabase.sq:15`),
  already queried journal-style at `:369` (`ORDER BY journal_date DESC`) and
  `:372` (`WHERE is_journal = 1 AND journal_date = ?`). A reactive
  `WHERE journal_date BETWEEN ? AND ?` join against `blocks.page_uuid` follows
  the same shape and needs no new dependency or column — ISO-8601-sortable
  text `BETWEEN` works fine in SQLite.
- `block_references` table (`:61-69`, from/to `block_uuid`, unique pair) backs
  `SqlDelightReferenceRepository.kt` (e.g. `:34` outgoing, `:45` incoming) but
  is **populated from block-to-block refs** (`[[...]]`/`((...))` inline
  parsing), not page-level tag aggregation directly — the existing linked/
  unlinked-references queries at `SteleDatabase.sq:654-685` instead do a
  direct `content LIKE '%[[pageName]]%'` scan rather than joining through
  `block_references`. A tag/page-ref query form should follow that same
  `content LIKE` join pattern (or `#tag` equivalent), not assume
  `block_references` is the right join target — confirm against
  `SqlDelightBlockRepository.kt:865-978` (`getLinkedReferences` /
  `getUnlinkedReferences`) which is the closest existing precedent for
  "blocks that reference a given page."

## 3. No existing s-expression parser — hand-roll a small tokenizer

- `MacroNode` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt:90`)
  is produced by `InlineParser.parseMacro()`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt:110-141`).
  Critically, **`arguments` is a single-element `List<String>` holding the
  entire raw remainder of the macro as one opaque string** — the parser does
  not tokenize or split on whitespace/parens at all (`argSb.append(...)` per
  char at `:128`, wrapped once into `listOf(args)` at `:137`). For
  `{{query (todo TODO)}}`, `node.arguments == listOf("(todo TODO)")` verbatim.
  This confirms requirements.md's framing: this is a rendering/execution gap,
  not a parsing gap for the outer macro — but the *inner* s-expression string
  is completely unparsed and needs its own small grammar.
- No s-expression, Lisp-reader, or generic tokenizer library exists in
  `kmp/build.gradle.kts`, and none is needed: `parsing/lexer/Lexer.kt` +
  `parsing/lexer/Token.kt` + `parsing/InlineParser.kt`/`BlockParser.kt` show
  the repo's established pattern of hand-rolled recursive-descent
  lexer/parser pairs for its own Markdown dialect — no PEG/ANTLR/parser-combinator
  dependency anywhere in the tree. A "simple query" grammar for v1
  (`(todo MARKER)`, `(page-property key value)`, `(between date1 date2)`,
  bare `[[Page]]`/`#tag` forms) is a handful of tokens (`(`, `)`, bareword,
  quoted-or-unquoted string) — trivially smaller than the existing Markdown
  lexer, so a ~50-100 line hand-rolled tokenizer/parser producing a small
  sealed `SimpleQuery` AST is consistent with repo style and requires no new
  dependency, per the "no new dependencies unless proven insufficient"
  constraint in requirements.md.
- Task marker vocabulary is already enumerated at
  `InlineParser.kt:97`: `TODO, DONE, NOW, LATER, WAITING, CANCELLED, DOING,
  WAIT, STARTED` (plus `TaskMarkerNode` doc comment at
  `parsing/ast/InlineNodes.kt:77-80` additionally lists `IN-PROGRESS`, which
  is absent from the actual `taskMarkers` set at `InlineParser.kt:97` — a
  second stale-doc-vs-code mismatch worth a drive-by note, not a blocker).
  A `(todo TODO)` query executor should reuse this exact vocabulary rather
  than inventing a new one, to stay consistent with what the parser already
  recognizes as a valid marker.

## 4. Flow composition patterns already in the codebase to reuse

- `asDbFlowList`/`asDbFlowOrNull` (`DbFlowExtensions.kt:36-63`) — use for any
  new single-query reactive read.
- `.conflate()` on a manual `asFlow()` chain (`SqlDelightBlockRepository.kt:222`)
  — reuse if a live query subscribes to a high-churn table (e.g. `blocks`)
  during bulk import/paste, to avoid backpressure stalls on the UI.
  `getAllPages()` in the CLAUDE.md dispatcher-matrix example doc shows the same
  idiom for exactly this reason (O(N²) scan avoidance on bulk import).
- In-memory test fake `DatalogBlockRepository` (`repository/DatalogBlockRepository.kt`)
  backs everything with `MutableStateFlow<Map<String, Block>>` (`:34`,
  `byPageUuid` at `:40`, `byParentUuid` at `:41`) and derives reactive reads via
  plain `.map { }` over that StateFlow (e.g. `:43-47`, `:49-53`). This is
  genuinely reactive (StateFlow re-emits on `.update{}`), so the in-memory-fake
  counterpart required by requirements.md ("any new query capability needs
  both a SQLDelight-backed implementation and an in-memory-fake equivalent")
  is a direct, low-risk `blocks.map { map -> map.values.filter { ... } }`
  addition — no new pattern needed there either.
- No existing `combine()`-based multi-source reactive composition was found
  in the repository layer (`grep` across `repository/*.kt` for `combine(`
  returned nothing) — if a v1 query form ever needs to join two independently
  reactive sources (e.g. blocks + pages both changing), that would be new
  ground for this codebase, though `Flow.combine` is stdlib and needs no new
  dependency. None of the four required v1 forms appear to need it: `(todo)`
  and `(page-property)` are single-table (`blocks`) predicates, `(between)`
  needs one join (`blocks` ⋈ `pages` on `page_uuid`/`journal_date`), and
  tag/page-ref reuses the existing `content LIKE '%[[...]]%'` single-table
  pattern.

## Recommendations

1. **Do not copy `getBlocksWithPropertyKey`/`getBlocksWithPropertyValue`'s
   one-shot `flow { executeAsList() }` shape.** Write new `.sq` queries with
   proper `WHERE` clauses and wrap them in `asDbFlowList`, matching the
   dispatcher-matrix convention in the project's root `CLAUDE.md`.
2. Model the property-value format as the existing delimited
   `"key:value,key2:value2"` string (via `LIKE '%key:value%'` at the SQL layer,
   confirmed by exact-match writers), not JSON, despite the misleading schema
   comment.
3. For tag/page-ref queries, follow `getLinkedReferences`'s
   `content LIKE '%[[' || :pageName || ']]%'` precedent
   (`SqlDelightBlockRepository.kt:865-978`, `SteleDatabase.sq:654-685`) rather
   than joining through `block_references`.
4. Build a small hand-rolled recursive-descent tokenizer/parser for the
   `(form arg1 arg2)` s-expression subset feeding a `SimpleQuery` sealed
   class — no new dependency, consistent with `parsing/lexer/Lexer.kt` and
   `InlineParser.kt` style.
5. Add the SQLDelight-backed query method(s) to a repository (likely
   `BlockSearchRepository` or a new small interface) plus a matching
   `DatalogBlockRepository`/in-memory-fake implementation using
   `blocks.map { }`, per the requirements.md dual-backend constraint.
