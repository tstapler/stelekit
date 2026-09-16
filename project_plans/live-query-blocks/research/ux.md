# UX Research: Live Query Blocks

Agent 5 (UX), SDD Phase 2 research fan-out. Companion to `requirements.md`.

## 0. Repo-architecture finding that shapes every UX recommendation below

`MacroNode` today is rendered inline, as plain text spans inside a single
`AnnotatedString` built by `renderNodes()`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt:231-236`),
which is then displayed by one `BasicText` composable in `WikiLinkText`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockViewer.kt:131-224`).
Click regions are done via `AnnotatedString` string-annotations + hit-testing
on tap offset, not real composables. **A multi-row result list with a header,
count, and per-item click targets cannot be expressed inside that
`AnnotatedString`** — it needs a block-level render path, the same tier as
`TableBlock`, `CodeFenceBlock`, and `BlockquoteBlock`.

The good news: that tier already exists and has an established contract. In
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt:360-397`,
`BlockTypes.TABLE`, `CODE_FENCE`, `BLOCKQUOTE`, `HEADING`, `ORDERED_LIST_ITEM`,
and `THEMATIC_BREAK` each dispatch to their own `@Composable` block
(`TableBlock`, `CodeFenceBlock`, etc.), all taking `content: String` and an
`onStartEditing: () -> Unit` callback that flips the block into
`BlockEditor`'s raw-markdown edit mode (`isEditing` state, `BlockItem.kt:48`,
`205-210`, `315-321`). A `QueryBlock` composable should follow this exact
pattern: a new dispatch arm (either a new `BlockTypes.QUERY`, or detection of
"block content is a single top-level `{{query ...}}` macro" done ahead of the
existing `when`), consuming `onStartEditing` identically to its siblings.
This is a UX finding, not just an implementation detail, because it settles
question 2 below for free: **the click-to-edit affordance every other rich
block already has is exactly the mechanism for "click to reveal raw query
source."** No new interaction pattern needs inventing.

## 1. Comparable UX patterns

**Logseq (upstream)** — a `{{query}}`/`#+BEGIN_QUERY` block renders as its own
chrome: a header row (Logseq's own CSS calls it `.th`) holding the results
count, a settings/gear icon, and a table-view toggle icon, above the result
list. The header is prominent enough that a user filed "[Live query block
looks bulky](https://discuss.logseq.com/t/live-query-block-looks-bulky/16433)"
against the default theme, and the community response was CSS tweaks to
shrink/hide parts of that header (collapse the title row, drop the toggle
icons) — i.e., **the shipped default over-invests in chrome relative to the
content**, a documented complaint worth avoiding by keeping SteleKit's header
to count + a single collapse affordance, not a toolbar.
Results collapse via a block-level `:collapsed? true` flag, but child blocks
inside a query are *always* fully collapsed regardless of their own state in
the source page — collapse state does not round-trip
([Default Collapsed State for Grouped Query Results #12274](https://github.com/logseq/logseq/issues/12274),
[Configurable option to collapse blocks inside queries by default #4337](https://github.com/logseq/logseq/issues/4337)).
Empty results are handled inconsistently: some queries show a literal
"No matched result" message, others render nothing at all, which the
community itself flags as confusing
([Why does some advanced query display "No matched results" and some simply
don't show](https://discuss.logseq.com/t/why-does-some-advanced-query-display-no-matched-results-and-some-simply-dont-show/28780)).
**Takeaway: always render an explicit empty state, never silently omit the
block** — silence reads as broken, not as "zero results."

**Obsidian Dataview** — a `TASK`-type query renders as a live, interactive
checkbox list indistinguishable in styling from a normal Obsidian task list
(checking a box in the query result writes back to the source file); other
query types (`TABLE`, `LIST`) render as plain tables/lists with essentially no
extra chrome beyond a subtle "this came from a query" framing. Dataview's
model is minimal-chrome, maximal-fidelity-to-native-rendering — the opposite
end of the spectrum from Logseq's header-heavy default.
[Dataview guide](https://www.dsebastien.net/the-complete-guide-to-dataview-in-obsidian/),
[GitHub](https://github.com/blacksmithgu/obsidian-dataview).

**Notion linked database views** — the heaviest-chrome comparator: a linked
view carries its own visible filter/sort/group toolbar and view-type switcher
inline in the page, because a linked view is explicitly a *first-class,
reconfigurable* object, not a passive result list.
[Notion views doc](https://www.notion.com/help/views-filters-and-sorts). This
is over-scoped for SteleKit v1 (read-only, no in-place reconfiguration per
requirements' Out-of-Scope), but confirms the pattern: once a query view gets
inline filter controls, users expect it to be edited from those controls, not
via raw source — a UX contract SteleKit is explicitly not taking on for v1.

**Trilium saved search / Table / Board(Kanban) views** — architecturally the
outlier: a Trilium saved-search's results materialize as actual **sub-notes**
under the saved-search note in the tree
([Saved Search docs](https://docs.triliumnotes.org/user-guide/note-types/saved-search)),
and Table/Board views are separate "Collection" note types that redisplay an
existing set of notes in a different layout
([Board View](https://docs.triliumnotes.org/User%20Guide/User%20Guide/Note%20Types/Collections/Board%20View)).
Because results appear as ordinary tree nodes, Trilium's own docs and
community discussion don't distinguish "computed" from "authored" content
visually — **this is a UX antipattern for SteleKit's purposes**: it invites
users to try editing/reordering a live result as if it were real content,
which the requirements explicitly rule out (read-only, no bulk actions).
Confirms rec. #2 below: a query block must look visually distinct so it never
invites edit gestures on the *results* themselves.

## 2. User mental model

- **Distinct-from-authored-content, yes.** Every comparator except Trilium
  gives query results some visual separation (Logseq's bordered `.th` header
  block, Dataview's subtle query-origin styling, Notion's toolbar chrome).
  Recommend a bordered/card treatment using the repo's existing
  `StelekitTheme.colors` surface tokens (same family used for
  `blockRefBackground` in `BlockViewer.kt:93`) rather than inventing a new
  color — a subtle background + thin border reads as "computed," a full
  Material `Card` elevation would read as too heavy for an inline block
  (echoing the "bulky" complaint against Logseq's default).
- **Raw query revealed via click-to-edit, not shown by default** — confirmed
  by the existing repo pattern (section 0): `onStartEditing` is already wired
  through every block-type composable to flip into `BlockEditor`'s raw
  markdown mode. A `QueryBlock` should accept and use the same callback:
  clicking the block's *header* (not an individual result row, which should
  navigate to that block/page) reveals `{{query (todo TODO)}}` for editing,
  exactly like clicking a rendered table reveals its markdown source in
  `TableBlock`. No separate "view source" affordance needs designing.
- Users should see the query itself in the header (Logseq shows the query
  form or a custom `:title`, not just an opaque "Query Results" label) — this
  builds trust that the list is that specific query's output, not some
  unrelated cached list.

## 3. Accessibility

- **Result-count-change announcement.** Compose's semantics API has
  `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`, the direct
  analog of an ARIA live region — apply it to the header count text
  (`"N results"`), not the whole list, so a screen reader announces only the
  count delta on auto-refresh rather than re-reading the entire result set.
- **Platform accessibility parity is uneven and worth flagging as a real
  constraint, not an afterthought.** Compose Multiplatform's desktop (JVM)
  accessibility bridge and iOS accessibility support are comparatively recent
  additions (JetBrains: [Compose Multiplatform 1.6.0 – iOS
  Accessibility](https://blog.jetbrains.com/kotlin/2024/02/compose-multiplatform-1-6-0-release/)),
  and as of the current docs, web-target accessibility explicitly does not
  yet support "interop and container views with scrolls and sliders, and
  traversal indexes"
  ([Kotlin Multiplatform accessibility
  docs](https://kotlinlang.org/docs/multiplatform/compose-accessibility.html)).
  A scrollable, live-updating result list is close to the exact shape called
  out as unsupported on web. Recommend: verify `liveRegion` semantics
  actually reach the OS accessibility tree on Desktop and JS/Wasm targets
  before relying on it as the sole announcement mechanism — a JVM/Desktop
  ciCheck screenshot test won't catch an accessibility-tree gap, this needs a
  manual screen-reader smoke test per target, called out explicitly in the
  validation plan.
- **Keyboard navigation into/out of the list.** The block's outer container
  should be a single tab stop (matching how `BlockEditor`/`BlockViewer`
  already sit in the page's block-to-block tab order), with Enter/Space on a
  focused result row navigating to that block, and Escape returning focus to
  the query block's header — mirroring existing `FocusRequester` handoff
  patterns already used for edit-mode entry/exit in `BlockItem.kt:94,
  205-210`. Don't make every result row an independent tab stop in the page's
  primary tab sequence if the list can be long (see truncation below) —
  arrow-key roving-tabindex within the list, Tab to exit, is the standard
  pattern for embedded lists inside a larger document.
- **Focus must survive auto-refresh.** Requirements' "no polling, react to
  Flow updates" reactive-read pattern means a query result list can
  re-collect and recompose while the user is mid-edit *elsewhere on the same
  page*. The result list must never call `requestFocus()` on its own
  recomposition (only explicit user action should move focus into it) — this
  is the same hazard `BlockItem.kt`'s existing `LaunchedEffect(isEditing)`
  guards against for block-to-block edit-focus handoff (`BlockItem.kt:205`),
  and the query block should reuse rather than duplicate that discipline.

## 4. Error / edge states

| State | Recommended treatment | Rationale |
|---|---|---|
| Zero results | Explicit "No matching blocks" text inside the block's normal chrome (never omit the block) | Logseq's inconsistent hide-vs-message behavior is a documented point of user confusion (§1) |
| Malformed query (parses as `MacroNode` but args don't match any supported grammar) | Render the literal `{{query ...}}` text, current fallback behavior, with a small "unsupported query" affix/icon rather than crashing or going silent | Matches requirements' explicit "graceful fallback" success criterion; reuses the existing generic-`MacroNode` text branch as the fallback path so it's a true no-op for already-passing cases |
| Unsupported-but-recognized query form (e.g. a `(between ...)` interpreted as generic dates, or a Datalog-map form) | Same literal-text fallback, distinguishable from "malformed" only if research/engineering find it cheap; not worth a third visual state for v1 | Keeps the error-state surface small — two states (results-or-empty vs fallback-text) are easier to keep consistent than three |
| Large result set needing truncation | Show first N (Logseq-comparable defaults land around 20-50 before requiring `:collapsed?`/pagination) plus a "+K more — open full results" affordance that either expands in place or is deferred entirely (requirements list pagination as a v1-optional, punt-if-needed item) | Prevents one query block from dominating page scroll; an unbounded list under a live Flow is also the perf risk flagged in requirements' Out-of-Scope note on caching/pagination |

None of these four states should look "broken" — the deliberate contrast is
zero-results (calm, expected-outcome message) vs fallback-text (visually
identical to today's dead-macro rendering, so a user migrating a graph full
of unsupported query forms sees no *regression* from current behavior, just
no upgrade yet).

## 5. Jobs-to-be-done

- **Functional job**: find blocks matching a standing condition (open TODOs,
  blocks tagged `#project`, blocks with a given page-property) without
  re-running manual search — the query block is a saved, always-current
  search embedded at the point of use (e.g., a daily-journal page's "open
  tasks" query), which is the same job Trilium's saved-search and Dataview's
  TASK query solve for their respective users.
- **Emotional job**: trust that what's on screen is *current*, not a stale
  snapshot from whenever the page was last opened — this is the whole reason
  "live" (reactive-Flow-driven, no manual refresh) is a hard requirement, not
  a nice-to-have; a query block that silently goes stale after an edit
  elsewhere is worse than no query block, because it looks authoritative
  while being wrong. This is also why the live-region count announcement
  (§3) matters emotionally, not just for compliance: hearing/seeing the count
  change is the user's confirmation that "live" is actually working.
  - The corollary risk (Logseq's "looks bulky" thread, §1): if the visual
    chrome needed to convey "this is live and trustworthy" is too heavy, users
    perceive the feature as clutter and want to hide it — the header should
    earn its vertical space with the count/collapse affordance only, not a
    settings/toolbar surface (Notion-style), which v1 doesn't need since
    editing goes through raw-source click-to-edit, not inline reconfiguration.
- **Social job**: minor for v1 given read-only scope and no page-type/export
  work in scope, but worth naming: if a page containing a query block is
  shared or exported (e.g., to a static site, PDF, or another Logseq/SteleKit
  user), the *rendered result set at export time* becomes what the recipient
  sees — there's no "live" on their end. This isn't a v1 requirement to solve,
  but the fallback/malformed-query rendering choice (literal text vs. an
  "unsupported" placeholder) should read sensibly as a frozen snapshot too,
  since export freezes whatever was on screen.

## Summary of concrete recommendations for Phase 3 planning

1. Implement as a new block-level composable (`QueryBlock`, alongside
   `TableBlock`/`CodeFenceBlock`) dispatched from `BlockItem.kt`'s `when`, not
   as an addition to `MarkdownEngine.kt`'s inline `renderNodes` — the current
   generic `MacroNode` branch there (`MarkdownEngine.kt:231-236`) is correctly
   left untouched for non-`query` macros as the requirements specify.
2. Reuse the existing `onStartEditing` callback contract for click-to-edit of
   raw query source — no new interaction to design or explain to users.
3. Header shows: the query itself (or literal source text), a result count
   with `LiveRegionMode.Polite` semantics, and a collapse toggle. No
   settings/filter toolbar (that's Notion's job, explicitly out of scope).
4. Subtle bordered/tinted container (reuse `StelekitTheme.colors` surface
   tokens) so the block reads as computed, distinct from authored outline
   content, without Logseq's "bulky" over-chrome.
5. Always render one of exactly two non-nominal states — explicit empty
   message, or literal-fallback-text for anything unsupported/malformed —
   never a silently-omitted block.
6. Verify screen-reader behavior manually per platform target before trusting
   `liveRegion` semantics; Compose Multiplatform's non-Android accessibility
   support is newer and has documented gaps (web scrolling containers
   specifically) that a JVM `ciCheck` screenshot test cannot catch.
7. Guard against auto-refresh stealing focus, using the same
   `LaunchedEffect(isEditing)`-style discipline `BlockItem.kt` already applies
   to edit-mode focus handoff.
