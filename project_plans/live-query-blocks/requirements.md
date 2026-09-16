# Requirements: Live Query Blocks

**Status**: Draft | **Phase**: 1 — Requirements (auto-generated from backlog item, no interview)
**Created**: 2026-09-15
**Complexity**: 3 (feature / system design — new execution engine + reactive UI layer, touches parser, repository, and rendering layers)
**Backlog item**: `2a877a49-445a-413b-8eb4-f818930ad8a4` — "feat: execute {{query}} blocks as live, auto-updating results"

## Problem Statement

Logseq-style `{{query (todo todo)}}` macro blocks are parsed into a `MacroNode`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt:110-141`)
but never executed. `MarkdownEngine.kt` renders every macro, including `query`,
as literal monospace text (`{{query ...}}`) — see the generic `MacroNode` render
branch at `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt:231-236`.

This is Logseq's signature "live query" feature: a block whose content is a
standing query over the graph that stays current as pages change, comparable
to Trilium Notes' saved-search notes / Table / Kanban collections.

**Primary user**: SteleKit user who already has (or wants to author) `{{query}}`
blocks in their Logseq-origin graph and expects them to render live results,
not dead text.

**Why now (prioritization rationale)**: this doc was auto-generated from a
backlog item with no discovery interview; absent a formal RICE score, the
backlog item's own argument for doing this now holds up on inspection, led by
the parts that don't depend on a real broken instance existing today: (a) the
parser work is already done, making this an execution-only, comparatively
contained lift; (b) it's named as the substrate for follow-on Table/Kanban/
Calendar collection views; and (c) it closes a correctness gap that would
silently break any graph containing `{{query}}` syntax — a defensive,
general-case argument, not a claim that a specific broken instance was found
and will be fixed (the Context/Existing Work section below found none in the
one graph actually inspected). Stated plainly as the rationale rather than
backed by fabricated numeric RICE inputs.

This prioritization is a low-cost/low-risk judgment call — already-scoped
parser work, small blast radius, reversible via the `live_query_blocks`
feature flag (see `implementation/plan.md` Risk Control) — not a rigorous
RICE comparison against the rest of the backlog. No such comparison was run;
that's an accepted limitation of a solo-dev backlog without formal
comparative scoring, not an oversight.

## Success Criteria

- A `{{query (todo TODO)}}` block renders as a live, read-only list of matching
  blocks embedded in the page, instead of literal `{{query ...}}` text.
- Supported query forms (v1): `(todo TODO)` / other task-marker states,
  `(page-property key value)`, `(between date1 date2)`, and tag/page-ref based
  queries (e.g. blocks referencing `[[Page]]` or `#tag`).
  - Note: `(between ...)` in Logseq's simple-query grammar operates on
    **journal date range**, not a generic date; scope precisely for whichever
    interpretation research confirms is most common in real graphs.
- Query results auto-refresh when underlying block/page data changes (edit,
  add, delete) without requiring the user to reopen the page.
- Unrecognized/unsupported query forms degrade gracefully (e.g. render as
  literal text or a clear "unsupported query" placeholder) rather than
  crashing the page render.
- Existing macro parsing/rendering behavior for other macro types (`embed`,
  `renderer`, etc.) is unaffected.

**Outcome metric**: given this app's local-first, no-telemetry architecture,
success is measured by dogfooding, not a metrics dashboard. A check of the
owner's own migrated Logseq graph found no real, live `{{query (...)}}`
blocks to verify against (see Context/Existing Work below), so "existing
blocks render live results" is not a checkable outcome. Instead, success is
verified post-ship by manually authoring one test query per v1 form on a
scratch page (real or test graph) — `{{query (task now later)}}`,
`{{query (page-property type "book")}}`,
`{{query (between [[<journal page>]] [[<journal page>]])}}`, and both a bare
`{{query [[Page]]}}` and `{{query #tag}}` page-ref form — and confirming each
renders a live, auto-updating result list instead of literal text, with no
regressions in existing macro rendering (`embed`, `renderer`). Adoption/usage
metrics beyond this manual check are structurally unmeasurable (no telemetry
pipeline) — an accepted characteristic of a local-first,
single-user-distributed app, not a gap to fix.

## Scope

### Must Have (MoSCoW)
- Query executor that maps a parsed `(todo ...)`, `(page-property ...)`,
  `(between ...)`, or tag/page-ref simple-query form to a result set of blocks.
- A live, auto-updating render path for `{{query ...}}` macro blocks that
  replaces literal-text rendering for whole-block query macros (other macro
  names, and `{{query}}` usage mixed inline with other text on the same
  block, keep current behavior — see scope note below).
- Live/reactive updates: result list reflects underlying repository state via
  existing `Flow`-based repository reads (see `BlockReadRepository`,
  `BlockSearchRepository`) — no polling.
- Read-only result rendering (click-through to block/page is fine; inline
  editing of results, or bulk actions on the result set, is out of scope).
- Graceful fallback rendering for any simple-query form not in the v1 set,
  distinguishing a genuinely malformed/unrecognized query from a recognized
  `{{query ...}}` shape that exceeds v1's supported grammar (see
  `implementation/pre-mortem.md` failure #1) — the two must not render
  identically, or a user with a more complex existing query has no signal
  that the feature even noticed their block.

**Scope note (whole-block only)**: this render path applies only when a
block's entire content is a single `{{query ...}}` macro. A `{{query ...}}`
macro mixed inline with other text on the same block keeps today's
literal-text rendering — whole-block classification can't apply to it. This
matches `{{embed}}`'s current lack of inline resolution in this codebase
(per `research/features.md`) and is an explicit, acknowledged v1 limitation,
not an oversight.

### Out of Scope (v1)
- Advanced/raw Datalog queries (`{{query (and ...)}}` full Clojure query maps).
- Saved-search-as-page-type / query blocks that persist as their own page type.
- Bulk actions on query results (multi-select, bulk edit/complete).
- Table/Kanban/Calendar alternate renderers over the result set (explicitly
  named as a follow-up substrate in the backlog description).
- Query result caching/pagination for very large graphs (address if perf
  research flags it as a v1 blocker; otherwise a follow-up).

## Constraints

- **Tech stack**: KMP/Compose common code only (`commonMain`) — must work
  across Desktop/Android/iOS/Web targets already supported by `MarkdownEngine`.
- **Error handling convention**: any new repository-facing query method must
  return `Either<DomainError, T>` per this repo's Arrow convention (see root
  `CLAUDE.md` "Error handling — Arrow `Either`" section) — no thrown exceptions
  or nullable-as-error at repository boundaries.
- **Dispatcher convention**: any new read path must follow the existing
  `PlatformDispatcher.DB` / `asDbFlowList` / `asDbFlowOrNull` reactive-read
  pattern documented in root `CLAUDE.md`, not raw `asFlow()` or a hand-rolled
  polling loop.
- **No new dependencies** unless research shows the existing stack (SQLDelight
  reactive queries, Kotlin Flow) is insufficient for "live" (auto-updating)
  semantics.

## Context

### Existing Work
- Parser already recognizes `{{query ...}}` syntax and produces `MacroNode`
  (`InlineParser.kt:110-141`); this is a rendering/execution gap, not a
  parsing gap.
- `OutlinerExtensionsSpec.kt:390-401` has parse-only tests for `query` and
  `renderer` macros (`MacroNode` name + args) — these already pass; the file's
  "MacroNode is not yet in the AST, all @Ignored" section comment above them is
  stale (confirmed: `MacroNode` is implemented and these specific tests are not
  `@Ignore`'d) and should be corrected as a drive-by doc fix, not treated as
  a design input.
- No query-by-property, query-by-task-marker, or query-by-date-range method
  exists yet on `BlockRepository`/`BlockReadRepository`/`BlockSearchRepository`
  — this is new repository surface, not a wiring gap.
- `DatalogBlockRepository` (in `commonMain`, despite the name) is an in-memory
  test-fake implementing `BlockRepository`; production reads go through
  `SqlDelightBlockRepository`. Any new query capability needs both a
  SQLDelight-backed implementation and an in-memory-fake equivalent to keep
  tests green on both backends.
- **Real-usage grammar validation (checked, not just upstream docs)**: a
  search of Tyler's own Logseq-origin wiki (`~/Documents/personal-wiki`) for
  real `{{query (...)}}` usage found only two hits, neither representative:
  `logseq/pages/Logseq.md` is a reference/documentation page that mentions the
  syntax as an example, not a live functioning query, and `Prompt
  Templates.md`'s bare `{{query}}` token is an unrelated LLM-prompt-template
  placeholder. No real, live-in-use `{{query (...)}}` block exists in the
  owner's own wiki to validate v1's grammar against — the grammar remains
  sourced from upstream Logseq docs only; an acknowledged limitation, not a
  blocker. The one concrete real-world data point found is that Logseq's own
  docs also show a bare, paren-less tag/page-ref form (`{{query #tag}}`,
  `{{query [[Page Name]]}}`) — confirmed covered by
  `implementation/plan.md` Task 1.2.1e's bare `[[Page]]`/`#tag` top-level
  parsing (no head symbol or surrounding parens required). The literal
  empty-argument case `{{query}}` (as it appears, unrelated, in `Prompt
  Templates.md`) is confirmed covered too: it does not crash — `QueryParser`'s
  enumerated `InvalidSyntax` cases explicitly include an empty query body
  (Story 1.2.1 AC, Task 1.2.2c's fuzz corpus), backstopped by Task 1.2.1g's
  blanket exception guard for anything the enumerated cases miss.

### Stakeholders
Tyler Stapler is both the primary user and the only stakeholder whose outcome
is actually observable in practice, given this app's current solo/local
distribution and lack of telemetry — he owns the one migrated Logseq graph
this feature will be verified against (see Success Criteria). Other SteleKit
users migrating existing Logseq graphs that already contain `{{query}}`
blocks (silent breakage today — dead text instead of results) would benefit
identically if they exist via distribution outside this repo, but their
outcomes aren't observable to the maintainer.

## Open Questions *(resolved by Phase 2 research — see research/*.md)*

- **Grammar subset**: resolved. Per `research/features.md` (verified against
  Logseq's own docs), the current filter name is `task` (bare lowercase state
  symbols, e.g. `(task now later)`) — `todo` is a legacy alias to keep for
  back-compat, not the primary form. `(between ...)` is journal-date-range
  only, taking relative-date symbols (`-7d`, `+7d`) or `[[journal page]]`
  pairs — confirmed independently against upstream Logseq source
  (`research/build-vs-buy.md`).
- **Compound predicates**: resolved — include in v1. `research/features.md`
  found that Logseq's own "simple query" baseline examples use one level of
  `and`/`or`/`not` (e.g. tag1 AND tag2) as normal usage, not an advanced case;
  a v1 that rejects all compound predicates would fail on common real-world
  queries. Scope: one level of `and`/`or`/`not` wrapping the four base forms.
- **Live refresh granularity**: resolved — no debounce needed at the query
  layer. Per `research/architecture.md`, the query composable subscribes
  directly to a repository `Flow` (SQLDelight reactive invalidation), so it
  refreshes whenever an underlying write commits — which already happens at
  the existing 500ms block-save debounce upstream. No separate polling or
  debounce mechanism is needed in the query executor itself.
