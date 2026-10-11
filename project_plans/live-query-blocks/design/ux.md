# UX Design: Live Query Blocks

Phase 3 UX design artifact. Inputs: `project_plans/live-query-blocks/requirements.md`,
`project_plans/live-query-blocks/research/ux.md`. Consistent with the component
names already fixed by `project_plans/live-query-blocks/implementation/plan.md`
(`QueryBlock`, `BlockTypes.QUERY`, `MAX_QUERY_RESULTS_DISPLAY`) — this doc does not
re-litigate those, it specifies what the user sees and does at each surface.

## Surface inventory

| # | Surface | Treatment |
|---|---|---|
| 1 | Rendered query-result block (collapsed/expanded, row navigation) | Full (interactive) |
| 2 | Click-to-edit raw query source | Full (interactive) |
| 3 | Empty-result state | Condensed |
| 4 | Malformed (4a) vs. recognized-but-unsupported (4b) query fallback states — kept visually distinguishable per pre-mortem P1 #1 | Condensed |
| 5 | Large-result-set truncation state | Condensed |

Surfaces 1 and 2 get a wireframe, an interaction flow, and an error/edge-case
table because a user clicks into them. Surfaces 3-5 are outcomes the user
observes, not interactions with independent controls beyond what Surface 1
already defines (the header's collapse toggle and edit-click still apply to
all three) — condensing them avoids re-describing that shared chrome three
times.

---

## Surface 1: Rendered query-result block (collapsed/expanded)

### Wireframe

Expanded (default state — see interaction flow, step 1):

```
┌───────────────────────────────────────────────────────────┐
│ (task now later)                            4 results  ▾  │  ← header: click text = edit (Surface 2)
├───────────────────────────────────────────────────────────┤     click ▾ = collapse
│ • Finish quarterly report          #project  [[Work]]      │  ← click row = navigate
│ • Call dentist to reschedule                  [[Health]]   │
│ • Draft blog post outline            #writing               │
│ • Review PR #482                      #project [[Work]]    │
└───────────────────────────────────────────────────────────┘
```

Collapsed:

```
┌───────────────────────────────────────────────────────────┐
│ (task now later)                            4 results  ▸  │
└───────────────────────────────────────────────────────────┘
```

Loading (pre-first-`Flow`-emission window — see interaction flow, step 1):

```
┌───────────────────────────────────────────────────────────┐
│ (task now later)                                        ▾  │
├───────────────────────────────────────────────────────────┤
│ Loading query results...                                    │
└───────────────────────────────────────────────────────────┘
```

Container uses a subtle bordered/tinted `Box` (existing `StelekitTheme.colors`
surface tokens, same family as `blockRefBackground`) — not a Material `Card`
elevation — per `research/ux.md` §2's "computed, not authored" recommendation
and its warning against Logseq's "bulky" default chrome. The result-row body
sits inside a max-height scrollable region (roughly 8-10 visible row heights,
matching common list/dropdown UI conventions) independent of the header, so
up to `MAX_QUERY_RESULTS_DISPLAY` (50) rendered rows scroll within a bounded
height instead of dominating the page — directly addressing the same "bulky
chrome" concern for large result sets, not just the container styling.

### Interaction flow

| Step | User action | System response |
|---|---|---|
| 1 | Page containing a `{{query ...}}` block loads | `QueryBlock` composes, subscribes to the query's result `Flow`. `collectAsState(initial = null)` means there is a real, if brief, window before the first emission arrives — during that window the block renders the loading state (muted "Loading query results..." text, same bordered chrome, wireframe above) rather than a blank container or the 0-results empty state (0 results is a resolved answer; loading is no answer yet). Once the first emission lands, renders expanded by default (collapse state is local composable state, not persisted to file — v1 does not round-trip a `:collapsed?` property, matching the requirements' read-only/no-new-persistence scope) |
| 2 | Click the `▾`/`▸` icon | Toggles collapsed/expanded locally; no write, no navigation, no re-query |
| 3 | Click a result row | Navigates to that block's page, identical to existing block-reference click-through elsewhere in the app |
| 4 | An edit anywhere in the graph changes the match set | `Flow` re-emits; header count text recomposes (e.g. "4 results" → "5 results") with `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on that text node only; row list updates; no `requestFocus()` fires anywhere in this path |
| 5 | Click the header text itself (not the icon, not a row) | Hands off to Surface 2 (click-to-edit) |

### Error and edge-case handling

| Case | User sees | Exit path |
|---|---|---|
| Repository read fails (e.g. `DomainError.ReadFailed` from a closed-DB race during graph switch, per `catchDbError()`) | Block renders a one-line "Unable to load results" message inside the same bordered chrome — not a blank block, not a crash | Automatic: the next successful `Flow` emission replaces the error message with real results; no manual retry control needed |
| User clicks a result row whose target block was deleted by a concurrent edit between render and click | Navigation is a no-op; a transient inline note ("Block no longer exists") appears at the row's position for the remainder of that composition, then the row disappears on the next `Flow` emission | The row's own disappearance on refresh is the exit path — no modal, no dead end |
| Rapid collapse/expand toggling while a background write is in flight | Toggle state and result content update independently; no dropped clicks, no flicker-triggered focus loss | N/A — not an error state, but a case worth a UI test: toggling must never depend on the in-flight `Flow` value |

### Acceptance criteria

- Before the first `Flow` emission arrives, the block shows a loading state — muted "Loading query results..." text inside the same bordered chrome — never a blank block and never the 0-results empty-state text.
- Result rows render inside a max-height scrollable region (~8-10 visible rows) rather than an unbounded list, so a 50-row result set never visually dominates the page; the header and collapse toggle remain outside the scrollable region and always visible.
- User can expand or collapse a query block in exactly 1 click.
- User can navigate from any result row to its source block/page in exactly 1 click.
- A live data change updates the visible result count and list without any user action and without moving keyboard or screen-reader focus away from wherever the user currently has it on the page.
- A repository read failure shows the literal text "Unable to load results" and self-resolves on the next successful data refresh — no user-facing retry button is required, and the block never renders blank.
- Keyboard: the query block is a single tab stop in the page's tab order; once focused, arrow keys rove between result rows (roving tabindex), Enter/Space activates the focused row's navigation, and Tab exits the block to the next page element without visiting every row as a separate stop.
- Screen reader: the collapse icon exposes an accessible label ("Collapse query results" / "Expand query results" depending on state); the result-count text is a polite live region so a screen reader announces only the count change, not a full list re-read, on refresh.
- Color contrast of header text and border against the tinted container background is ≥ 4.5:1 in both light and dark theme variants.
- No dead ends: every state reachable from this surface (results, load-error) has a described exit — automatic recovery for load-error, and the collapse/navigate/edit affordances remain available at all times.

---

## Surface 2: Click-to-edit raw query source

### Flow diagram

```
[QueryBlock, results/empty/fallback rendered]
        │  click header text
        ▼
[onStartEditing() fires — same callback contract as TableBlock/CodeFenceBlock]
        │
        ▼
[BlockEditor: raw markdown text field, content = "{{query (task now later)}}"]
        │
        ├─ Escape ──────────────► discard edits, revert to prior content,
        │                          re-render prior rendered state unchanged
        │
        └─ commit (blur / Tab / Enter per existing BlockEditor semantics)
               │
               ▼
        [content saved via existing 500ms debounce write path]
               │
               ▼
        [QueryBlock re-parses new content, briefly re-enters the
         loading state while the Flow rebuilds, then renders whichever
         of the results / empty / malformed-fallback 4a /
         unsupported-form-fallback 4b states the new query produces]
```

### Interaction flow

| Step | User action | System response |
|---|---|---|
| 1 | Click the header text of a `QueryBlock` (collapsed or expanded) | `onStartEditing()` fires — identical mechanism `TableBlock`/`CodeFenceBlock` already use; block flips into `BlockEditor`'s raw markdown edit mode |
| 2 | User sees and edits `{{query (task now later)}}` as plain text | Standard block-editing affordances apply (cursor, selection, autocomplete if any) — no query-specific editing UI is introduced |
| 3a | User presses Escape | Edit discarded, block reverts to its previously rendered state — no write occurs |
| 3b | User commits (existing block-commit gesture: blur/Tab/Enter per `BlockEditor`'s standard rules) | Content persisted through the existing write path; `QueryBlock` re-parses and re-renders based on the new content (briefly showing the loading state again as the `Flow` rebuilds for the new `content` key, before settling into the new result) |
| 4 | New content matches a supported grammar form | Results/empty state renders (Surface 1) |
| 5a | New content is malformed (unrecognized shape) | Malformed-fallback renders (Surface 4a, condensed below) — no error dialog |
| 5b | New content is a recognized-but-unsupported grammar form | Unsupported-form fallback renders (Surface 4b, condensed below) — no error dialog |

### Error and edge-case handling

| Case | User sees | Exit path |
|---|---|---|
| Edit breaks the macro syntax entirely (e.g. deletes closing `}}`) | Block renders as whatever the new content now parses as (plain text, or another macro) — same behavior as editing any other block's markdown, no query-specific warning | Click into the block again and re-edit, same as any markdown block |
| Edit produces a recognized-but-unsupported query form | Unsupported-form placeholder rendering (Surface 4b) | Click header again to further edit |
| A concurrent external file change (disk conflict) lands while the user is mid-edit | Existing app-wide `DiskConflict` resolution flow applies — this feature does not introduce a new conflict-resolution UI | Existing conflict-resolution exit paths already cover this |

### Acceptance criteria

- User can reach raw-query edit mode in exactly 1 click (the header), from either the collapsed or expanded state.
- User can cancel an in-progress edit in exactly 1 keypress (Escape), with the block guaranteed to revert to its prior rendered content — no partial-write state is observable.
- After committing an edit, the block briefly re-enters the loading state before settling into one of the results/empty/malformed-fallback/unsupported-form-fallback states, with no manual refresh and no page reload.
- No dead ends: a user who edits into a broken or unsupported query is never stuck — the same 1-click header affordance that got them into edit mode is always available to fix it.
- Keyboard-only users can trigger edit mode without a mouse: the header is reachable via Tab and activates edit mode via Enter/Space, matching the existing keyboard contract for `TableBlock`/`CodeFenceBlock` headers.

---

## Surface 3: Empty-result state (condensed)

Representative sample (rendered inside the same chrome as Surface 1):

```
┌───────────────────────────────────────────────────────────┐
│ (page-property status "blocked")             0 results  ▾ │
├───────────────────────────────────────────────────────────┤
│ No matching blocks                                          │
└───────────────────────────────────────────────────────────┘
```

Acceptance criteria:
- The block is always rendered with its normal bordered chrome — zero results never produces a blank/omitted block (this is the documented Logseq complaint this design explicitly avoids, per `research/ux.md` §1).
- The message text is the literal string "No matching blocks", not a generic blank area or a raw "0" with no explanation.
- The header's collapse toggle and click-to-edit affordances remain fully functional in this state — an empty result is not a dead end.
- The "0 results" count still uses the same `LiveRegionMode.Polite` semantics as any other count, so a screen reader announces if the count later changes from 0 to nonzero.
- Visual treatment (border, tint, typography) is identical to the non-empty results state — only the body content differs.

---

## Surface 4: Malformed (4a) vs. recognized-but-unsupported (4b) query fallback states (condensed)

Pre-mortem P1 #1 (`implementation/pre-mortem.md`) found that collapsing every
parse failure into one pixel-identical literal-text rendering makes "v1
doesn't support this yet" indistinguishable from "still silently broken" —
the exact dead-text failure this feature exists to fix. This surface is
therefore two distinguishable states, keyed on the `DomainError.ParseError`
subtype `QueryParser` returns (`plan.md` Domain Glossary):

### 4a. Malformed input (`DomainError.ParseError.InvalidSyntax`)

Representative sample:

```
{{query (frobnicate xyz)}}
```

Rendered as literal monospace text, visually identical to today's pre-feature
rendering of any unrecognized macro (`MarkdownEngine.kt`'s existing generic
`MacroNode` branch), with no bordered chrome — this state is a deliberate
no-op relative to current behavior for input the parser cannot recognize at
all (unknown head symbol, unbalanced parens, empty body, or other genuinely
malformed input).

### 4b. Recognized-but-unsupported query shape (`DomainError.ParseError.UnsupportedForm`)

Representative sample:

```
┌───────────────────────────────────────────────────────────┐
│ Unsupported query — showing raw text:                       │
│ {{query (between -7d +7d)}}                                  │
└───────────────────────────────────────────────────────────┘
```

Rendered inside the same bordered chrome as Surfaces 1/3/5, with muted
(secondary-foreground-color) body text — visibly different from both the
results state and the malformed-fallback state (4a) — so a user can tell
"the app noticed this query but doesn't support it yet" apart from "the app
hasn't touched this at all." Reached when the parser recognizes the macro's
head symbol (`task`/`page-property`/`between`/`and`/`or`/`not`) but the
argument shape exceeds v1's supported grammar — e.g. two-level `and`/`or`
nesting, a top-level `(not ...)`, or `between`'s relative-date form.

### Acceptance criteria

- Malformed input (4a) renders pixel-identical to the existing generic-macro literal-text fallback already used for `{{embed}}`/`{{renderer}}` and any other unrecognized macro — no new icon or affix.
- Recognized-but-unsupported input (4b) renders visibly differently from 4a: muted-style text plus the "Unsupported query — showing raw text:" prefix, inside the same bordered container chrome as Surfaces 1/3/5 — not the plain, chrome-less text treatment used for 4a.
- Neither state ever crashes the page render or silently drops the block's content — 4b still shows the literal query text underneath the prefix.
- A user migrating a Logseq graph sees the correct signal for each case: no regression from pre-feature behavior for genuinely malformed queries (4a — same dead text as before), and a distinguishable "not yet supported" signal for recognized-but-out-of-scope forms (4b) instead of silence.
- Both states remain click-to-edit (Surface 2) — a user can fix the query text from either state exactly as from the results state.

---

## Surface 5: Large-result-set truncation state (condensed)

Representative sample:

```
┌───────────────────────────────────────────────────────────┐
│ (task now later)                         200+ results  ▾  │
├───────────────────────────────────────────────────────────┤
│ • Finish quarterly report          #project  [[Work]]      │
│ • Call dentist to reschedule                  [[Health]]   │
│   … (48 more rows) …                                        │
│ • Review PR #482                      #project [[Work]]    │
├───────────────────────────────────────────────────────────┤
│ +150 more                                                    │
└───────────────────────────────────────────────────────────┘
```

`+150 more` is static text in v1 (no click-to-expand-in-place or pagination —
result-set pagination is explicitly out of scope per requirements). The exit
path for a user who needs the remaining 150 is to narrow the query via
click-to-edit (Surface 2) — concretely, this means editing the raw
`{{query ...}}` text directly, using the same click-to-edit block-editing
affordance every block already supports (Surface 2). v1 provides no in-app
query-builder UI, so narrowing a query means editing the DSL text by hand;
this is stated explicitly here rather than left implicit, since the primary
audience (users migrating an existing Logseq graph) may not yet be fluent in
editing the query DSL, and "narrow your query" should not be read as implying
a UI affordance that doesn't exist.

**Header count vs. true total (consistency note, cross-artifact BLOCKER #2)**:
`QueryExecutor.DEFAULT_LIMIT` caps every repository read at 200 rows with no
separate `COUNT(*)`-style query anywhere in the plan (adding one would be
scope creep per requirements.md's Out-of-Scope list). The header count is
therefore the *fetched* count, not a guaranteed true total: `"200+ results"`
(with a `+`) when the fetch hit the 200-row ceiling — signaling the true
total may be higher and is not being computed — versus a plain `"83
results"` (no `+`) when the fetched count is below the ceiling, which is
guaranteed exhaustive. The wireframe above shows `"200+ results"` because a
200-row fetched set (the `DEFAULT_LIMIT` ceiling) is exactly the case where
the true total is unknown.

Acceptance criteria:
- The block shows exactly the first `MAX_QUERY_RESULTS_DISPLAY` rows, never an unbounded list, protecting page scroll and render cost on large graphs.
- The truncation line states the exact remaining count relative to the fetched set ("+150 more"), not a vague "more results" with no number.
- The truncation line is not a dead end even though it is non-interactive in v1: the header's click-to-edit affordance is still the documented way to narrow the query, and this is stated adjacent to the truncation line's acceptance criteria so it isn't mistaken for a missing feature.
- The header's live result count reflects the fetched result count (bounded by the repository's `DEFAULT_LIMIT`, currently 200), not an unbounded true total. When the fetched count equals `DEFAULT_LIMIT`, the header shows a `+` suffix (e.g. "200+ results") to make the ceiling explicit rather than implying exhaustiveness; below the ceiling, the exact count is shown with no `+` (it is guaranteed to be the true total). Either way, the live-region announcement on refresh stays meaningful and honest about what's known.
- The truncation line's exit path is documented as manual query-text editing via click-to-edit (Surface 2), not an in-app query-builder UI — v1 provides none — so the guidance sets an honest expectation for users not yet fluent in the query DSL.

---

## Cross-surface UX acceptance criteria

These apply to all five surfaces collectively and are the ones a reviewer
should check regardless of which specific state is on screen:

1. No dead ends — every state described above (loading, results, load-error, empty, malformed-fallback 4a, unsupported-form-fallback 4b, truncated) has a stated exit path, and none requires closing/reopening the page to recover. Loading's exit path is automatic: it resolves to one of the other states on the first `Flow` emission, typically well under a second for a local SQLite read.
2. All interactive elements (collapse icon, result rows, header edit-click) are reachable and operable via keyboard alone, without a mouse.
3. The query block is exactly one tab stop in the page's primary tab sequence; internal row navigation uses roving tabindex (arrow keys), not N additional primary tab stops, so a page with a long query result doesn't degrade keyboard navigation of the rest of the page.
4. Screen-reader labels exist for: the collapse toggle (state-dependent label), the result count (polite live region), and each result row (announces the block's text content, not just a generic "list item").
5. Color contrast of all header/body text against the block's tinted background is ≥ 4.5:1 in both light and dark theme variants.
6. A live auto-refresh (triggered by a data change anywhere in the graph) never moves keyboard or screen-reader focus away from whatever the user currently has focused elsewhere on the page.
7. Visual treatment is consistent across the three "boxed" states that share the same container chrome (results, empty, unsupported-form-fallback 4b) so a user learns the block's shape once; the malformed-fallback state (4a) is a deliberate exception — it stays outside that chrome, pixel-identical to today's pre-feature literal-text rendering, so existing dead-query text doesn't visually change until the query is fixed (Surface 4).
8. Platform accessibility parity is unverified by automated tests alone (per `research/ux.md` §3, Compose Multiplatform's non-Android accessibility bridges are newer and web-target scrolling-container support has documented gaps) — a manual screen-reader smoke test per target (Desktop, Android, iOS, Web) is required before shipping and should be tracked as a explicit checklist item in the validation plan, not assumed to be covered by `ciCheck` screenshots.

**Platform note — mobile screen readers and roving tabindex (item 3 above)**:
roving tabindex (arrow-key navigation within the block, Tab exits to the next
page element) is a desktop/keyboard-input authoring pattern. Touch-based
mobile screen readers (TalkBack on Android, VoiceOver on iOS) navigate via
swipe gestures over the platform accessibility tree, not tab order, so
roving tabindex is not itself a meaningful mechanism on those platforms. The
underlying requirement — every result row and the header are individually
reachable and readable — still holds on mobile: each row is its own Compose
semantics node with a content description, and standard Compose
accessibility support (not the tabindex mechanism specifically) is what
makes it swipe-navigable. Item 3 above should be read as a desktop-input
implementation detail of that broader requirement, not a claim that mobile
screen readers use tab order.

---

## Summary

- **5 surfaces designed**: 2 full (interactive) treatments — rendered query-result block (collapsed/expanded, row navigation) and click-to-edit raw query source — plus 3 condensed treatments — empty-result, malformed-vs-unsupported fallback (two distinguishable sub-states, 4a/4b, per pre-mortem P1 #1), and large-result-set truncation (header count is the fetched, ceiling-aware count, not an unbounded true total — per cross-artifact BLOCKER #2).
- **38 UX acceptance criteria** written, by section: Surface 1 (10), Surface 2 (5), Surface 3 (5), Surface 4 (5), Surface 5 (5), cross-surface (8). Total: 10+5+5+5+5+8 = 38.
