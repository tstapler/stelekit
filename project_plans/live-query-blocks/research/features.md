# Research: Live Query Blocks — Similar Features & Edge Cases

Builds on `project_plans/render-all-markdown/research/features.md` (see its
"Queries" row, line 157: query blocks are "not markdown... must be evaluated
and replaced with result before rendering" — that research stopped at
recognizing the gap; this file designs into it and does not re-litigate the
markdown-renderer survey.

## 1. What the codebase already has (and what it doesn't)

**The macro parser is a single flat string, not a query AST.**
`InlineParser.parseMacro()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt:110-141`)
splits `{{name args}}` on the *first* whitespace only. Everything after that —
`(and (task now later) (sort-by created-at desc))`, nested parens, brackets,
quoted strings and all — lands as one opaque string in
`MacroNode.arguments: List<String>` (single-element list; see docstring at
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt:82-93`).
**A query executor needs a second-stage S-expression parser for this string**
— the existing macro parser does not tokenize it at all. This is new work,
not a wiring gap, and should be sized into the plan.

**Reference extraction already regex-scrapes `[[...]]` out of raw macro args.**
`MarkdownParser.extractReferences()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt:244-249`)
runs `wikiLinkRegex` over `MacroNode.arguments` today, so a block like
`{{query [[ProjectX]]}}` **already** contributes "ProjectX" to that block's
reference set and shows up in ProjectX's linked references — even though the
query itself renders as dead text. The query executor must not regress this:
adding real query execution must not remove or duplicate that reference
contribution (e.g. don't double-index if the new query AST parser also
extracts page refs from the same string).

**`MacroNode` is also how `embed` is parsed** — the generic literal-text
render branch in `MarkdownEngine.kt:231-236` currently handles `query`,
`embed`, and `renderer` identically (render as monospace `{{name args}}`
text). There is no `EmbedNode` and no embed-resolution code anywhere in
`commonMain` (confirmed: `grep -rn "resolveEmbed\|EmbedResolver"` returns
nothing) — `{{embed ...}}` is exactly as unimplemented as `{{query ...}}`
today. This matters for the nested-query edge case below: **`{{embed
[[page]]}}` cannot recursively render a `{{query}}` block that lives on the
embedded page, because embed resolution doesn't exist yet.** Treat
"query nested inside embed" as out-of-scope-by-construction for v1, not a
bug to fix — there's no embed renderer to nest inside.

**`SearchRepository.searchWithFilters` looks like a head start but isn't one.**
`SearchRequest` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SearchRepository.kt:44-51`)
already has `propertyFilters: Map<String, String>` and `dateRange: DateRange?`
fields that look tailor-made for `(page-property ...)` and `(between ...)`.
**Don't reuse this API as-is**: `SqlDelightSearchRepository.searchWithFilters`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt:176-186`)
short-circuits to an empty `SearchResult` whenever `searchRequest.query` is
null/blank — it's fundamentally a free-text-search API with filters layered
on top, not a filter-only query engine. Confirmed by `grep -n
"propertyFilters"` on the implementation file returning zero matches: that
field is declared but never read. A `(todo TODO)` or `(page-property type
book)` query has no free-text term at all, so it cannot go through this path
without either faking a query string or fixing the short-circuit (which
would also change behavior for the existing search UI). **Build a
purpose-built query repository method**, per the requirements doc's own
conclusion, but cite this concretely so the plan phase doesn't rediscover it
by trial and error.

**No "page doesn't exist" error type exists anywhere** (`grep -rn
"pageExists\|nonExistent\|missingPage\|redLink\|deadLink"` returns nothing in
`commonMain`). Wiki-links to not-yet-created pages already render normally
today (standard create-on-click wiki behavior) with no special error state in
`DomainError.kt`. **A query referencing a nonexistent page/tag should follow
this same convention: zero results, not an error** — there is no existing
precedent for treating a missing target as a `DomainError`, and inventing one
just for queries would be inconsistent with how every other page reference
in the app already behaves.

**Collapse state already exists and is orthogonal.** `BlockList.kt:52,125-126`
tracks `collapsedBlocks: Set<String>` and computes `hiddenBlocks` by
descendant UUID. A query block that is itself a child of a collapsed block is
just not rendered at all (same as any other block) — no special handling
needed. The open edge case is the *reverse*: should the query's **own**
result list default to collapsed (matching Logseq, see §3) — that's a new
per-query-block UI state, not reuse of `collapsedBlocks`, since query results
aren't real child blocks in the outline tree.

## 2. Logseq's actual simple-query grammar (verified against upstream docs)

Source: [logseq/docs Queries.md](https://raw.githubusercontent.com/logseq/docs/master/pages/Queries.md)
(fetched directly, not summarized from a secondary source).

- **Task/todo filter**: current canonical name is `task`, **not** `todo` —
  the docs state outright "**task** (used to be `todo`)". Syntax is
  `(task now)` or `(task now later)`: **bare, unquoted, space-separated,
  lowercase state symbols** (`now`, `later`, `done`, presumably also `todo`,
  `doing`, `waiting`, `canceled`/`cancelled` by the same pattern), not a
  quoted string and not comma-separated. The repo's task-marker vocabulary
  (`TaskMarkerNode`, `InlineNodes.kt:78`: `TODO, DONE, NOW, LATER, WAITING,
  CANCELLED, DOING, WAIT, STARTED, IN-PROGRESS`) is uppercase; the query
  filter's state symbols are lowercase in every documented example. **The
  executor must case-fold when matching filter args against stored marker
  text**, and should accept the legacy `todo` filter name as an alias for
  `task` — SteleKit's stated audience is users *migrating existing Logseq
  graphs* (requirements.md "Stakeholders"), and older graphs are exactly
  where the legacy `(todo TODO)` spelling from the requirements doc's own
  example would appear. **UNVERIFIED**: whether upstream Logseq's parser
  still accepts `todo` as a literal alias at all (the docs page doesn't say),
  vs. it being fully removed — web search did not turn up a definitive
  answer either way; treat "accept `todo` as alias for `task`" as a design
  choice justified by the migration-compatibility goal, not a confirmed
  upstream-compatibility fact.

- **`page-property`** is page-level only: `(page-property key value)`, e.g.
  `(page-property related "Block embed")` — value is a quoted string when it
  contains a space. Docs explicitly warn: **page-only filters (`page`,
  `page-property`, `page-tags`, `all-page-tags`) cannot be mixed with
  block-level filters** (`task`, `property`, `priority`, tag/page-ref) inside
  the same query. This is a real constraint the executor should validate (or
  at least document as unsupported-combination) rather than silently
  producing wrong results if a user writes `(and (task now) (page-property
  type book))`.

- **`property` vs `page-property` are two different filters** — easy to
  conflate. `property` (block-level) matches page/tag references *inside* a
  property value at the block level (e.g. `description:: I liked this
  #book`); `page-property` matches a page-level `key:: value` property. The
  requirements doc's v1 scope names `page-property` specifically — correct
  per docs — but a real-world graph will also contain plain `property`
  queries; flag as a candidate "same marginal cost" addition per the
  requirements doc's open question, since it reuses the same key/value
  match logic minus the page-vs-block distinction.

- **`(between start end)`**: docs state plainly **"will only support blocks
  on the journal pages"** — confirming the requirements doc's own scoping
  note. Two argument forms appear in the wild, **both valid, not either/or**:
  1. Relative symbolic dates: `today|yesterday|tomorrow|now` combined with
     `±<number><y|m|w|d|h|min>`, e.g. `(between -7d +7d)`, `(between -2w
     today)`.
  2. Explicit journal-page wiki-links: `(between [[Dec 5th, 2020]] [[Dec 7th,
     2020]])` — the *page name itself* is the journal date, parsed via
     whatever journal-date-to-page-title format the graph uses.
  A v1 executor should support at least the explicit `[[journal page]]` pair
  form (straightforward: resolve two page titles to date boundaries) and can
  treat the relative-symbol grammar as a stretch goal or explicitly
  unsupported-with-graceful-fallback, since relative-date parsing
  (`-7d`, `+2w`) is a small DSL of its own.

- **Tag/page-ref queries are not a separate filter — they're just a bare
  `[[page]]` or `#tag` as the entire query body**: `{{query [[tag1]]}}` finds
  every block referencing that page. This is the simplest form and composes
  with `and`/`or`/`not`.

- **`and`/`or`/`not` are not "advanced" — they show up in the *simplest*
  documented examples.** All five of the docs' own "More query examples"
  (find blocks with tag1; tag1 AND tag2; tag1 OR tag2; tag2 but NOT tag1;
  journal blocks between two dates) use `and`/`or`/`not` or `between` as the
  *top-level* form — none of them is a single bare filter. **This is a
  material finding for the requirements doc's open question** ("should
  compound predicates be in v1?"): if the v1 executor only accepts a single
  filter with no boolean combinator, it will fail on what upstream's own
  documentation presents as the *baseline* usage pattern (e.g. "tag1 AND
  tag2" is example #2, not an advanced example). Recommend: support a single
  top-level filter **or** an `(and ...)`/`(or ...)`/`(not ...)` wrapping
  exactly one level of the four v1 filter types — full arbitrary nesting can
  still be deferred, but zero nesting will feel broken on real graphs.

- **`sort-by`** (`(sort-by property-name [asc|desc])`) commonly appears
  combined via `and` with a task filter, e.g. `(and (task now later)
  (sort-by created-at desc))`. Not required for v1 per requirements scope,
  but note it as the most likely "next filter users ask for."

## 3. Comparable features in other PKM tools

| Tool | Mechanism | UX pattern relevant here |
|---|---|---|
| **Logseq itself** | `{{query ...}}` simple queries (this feature) / advanced Datalog queries | Result block renders **collapsed by default** with a summary line ("N results"); expanding shows the block list; each result is a live link to the source block (click navigates to it in context); editing the query means clicking into the block to reveal `{{query ...}}` raw text, same as any other block. |
| **Obsidian Dataview** | Two distinct mechanisms: **inline queries** (`` `= this.field` ``) that "always yield exactly one value" rendered in place of the query text, and **block queries** (` ```dataview ` fenced code blocks with `TABLE`/`LIST`/`TASK`/`CALENDAR` query types) that render a full embedded view. [Dataview query-types docs](https://blacksmithgu.github.io/obsidian-dataview/queries/query-types/) | The inline-vs-block split matters as precedent: SteleKit's `{{query}}` is closer to Dataview's **block** form (a list of matching blocks), not the inline single-value form — don't conflate the two when designing the render node, since a single `QueryResultNode` covering both would over-generalize. |
| **Notion linked/filtered database views** | A view is a saved filter+sort over a database, live-updating as source rows change. | Precedent for "the query is metadata attached to a view, not baked into a snapshot" — reinforces the requirement that results come from a live `Flow`, not a one-time execute-and-cache. Notion always shows a result count and an explicit empty state ("No results" with an icon), never a bare blank area — worth matching. |
| **Roam Research query blocks** | `{{query: {and [[tag1]] [[tag2]]}}}` — very similar bracket-based simple-query grammar to Logseq's (both trace to the same Datalog-over-Datascript lineage). | Roam renders results nested directly under the query block as pseudo-children, and — notably — **lets you drag a result block back into its query's context** and supports simple boolean composition as the default expected usage, reinforcing the `and`/`or`/`not`-as-baseline finding above. |
| **Trilium Notes saved search** | A "Saved Search" note stores a search string (fulltext or attribute-based, optionally backed by a script); expanding the note in the tree shows results as **live child notes**. [TriliumNext wiki: Search](https://github.com/zadam/trilium/wiki/Search/3f4b64f4823c86dbcd057cc96e65029405533746) | Confirms the "collapsed-by-default, expand to see live results" pattern independently of Logseq — two unrelated tools converged on the same default. Also: Trilium explicitly discusses whether clicking a result should open the note vs. preview it ([TriliumNext#722](https://github.com/TriliumNext/Trilium/issues/722)) — an open UX question there too, not something with one obvious right answer industry-wide. For SteleKit's read-only-results v1 scope, "click navigates to the source page/block" (matching how `BlockRefNode` click-through already works, `MarkdownEngine.kt:177-188`) is the simplest and most consistent choice — no separate preview mode needed for v1. |

**Cross-tool convergence worth designing for explicitly:**
1. **Result count is always shown**, even implicitly via a visible list length — never a silent empty render.
2. **An explicit "no results" state** is universal (Notion's empty state, Logseq's "0 results" collapsed summary) — required per this feature's own success criteria ("graceful... rather than crashing"), but the *positive* requirement (show a legible zero-results message, don't just render nothing) isn't explicitly stated in requirements.md and should be added as an acceptance criterion.
3. **Results are collapsed/summarized by default**, not fully expanded — matches both Logseq and Trilium independently; prevents a query matching hundreds of blocks from making the host page unreadable, which is directly relevant to this feature's own "Out of Scope: pagination for very large graphs" note — a collapsed-by-default result view reduces the urgency of pagination for v1 (most users won't expand a 500-result query, and the ones who do can tolerate a one-time render cost).

## 4. Unstated need: raw query source must stay visible/editable

The repo's block-editing architecture already implements the exact mechanism
this feature needs, with no new UI concept required:

- `BlockItem.kt` toggles between a **rendered** view (via `MarkdownEngine`,
  producing an `AnnotatedString`) and an **editing** view (`BlockEditor.kt`,
  a raw-text `BasicTextField` over the literal markdown source) keyed on
  `isEditing` (`BlockItem.kt:315-321`). Clicking any block already reveals
  its raw markdown source for editing and re-renders on blur/debounce — this
  is precisely the "click to reveal `{{query (task now)}}` and edit it"
  behavior Logseq users expect, and **it requires zero new mechanism**: the
  query executor only needs to plug into the existing rendered-view branch
  (`MarkdownEngine.kt`'s `MacroNode` handling) with a new `query`-specific
  case. When the block is in edit mode, the user already sees and can edit
  the literal `{{query ...}}` text via the pre-existing `BlockEditor`/
  `BlockStateManager` path (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`)
  — this should be called out explicitly as a **non-goal to build**, not an
  open question, since building a separate "edit query" affordance would
  duplicate infrastructure that already exists and already matches user
  expectations from Logseq.
- The one new state this feature *does* need that doesn't exist yet: a
  per-query-block **collapsed/expanded toggle for the result list** (§3
  finding: collapsed-by-default is the cross-tool norm). This is not the
  same as `BlockList.kt`'s `collapsedBlocks` (that hides real outline
  children); it's local UI state scoped to one `QueryResultNode` instance,
  most naturally held wherever `MarkdownEngine`'s render context
  (`ctx` parameter, referenced throughout `MarkdownEngine.kt`) already
  threads per-block UI state today — worth checking what `ctx` carries
  before inventing a new state-holder.

## Summary of design implications

1. Query args need a **new small S-expression parser** — the existing macro
   parser only gives a flat string; this is real, unscoped work.
2. **Don't reuse `SearchRepository.searchWithFilters`** — it's a free-text
   search API with a dead `propertyFilters` field, not a filter-only engine.
3. Support **`task` as the primary filter name with `todo` as a back-compat
   alias**, case-fold state symbols, and support **at least one level of
   `and`/`or`/`not`** — real Logseq graphs use compound queries as the
   baseline case, not an edge case, per upstream's own example set.
4. **`(between ...)` is journal-only**; support the `[[journal page]]` pair
   form as the v1 minimum; treat relative-date symbols (`-7d`, `+2w`) as
   optional/stretch.
5. **Nonexistent page/tag → zero results, not an error** (matches existing
   dead-wikilink convention; no `DomainError` variant needed for this case).
6. **Query nested inside `{{embed}}` is moot for v1** — embed resolution
   doesn't exist in this codebase yet, so there's nothing to nest inside.
7. **Reference extraction from query args must be preserved**, not
   duplicated, when the new query-AST parser is added alongside the existing
   regex-based `extractReferences()`.
8. **No new "reveal source to edit" mechanism is needed** — `BlockItem`'s
   existing edit/render toggle already provides it. The one genuinely new UI
   state is a per-query collapsed/expanded toggle for the result list,
   matching the Logseq/Trilium convergence in §3.
9. Add an explicit **"N results" / "no results" rendering requirement** to
   acceptance criteria — implied by every comparable tool surveyed, but not
   stated as a positive requirement in requirements.md today (only the
   negative "don't crash" is stated).
