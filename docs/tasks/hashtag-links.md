# Feature Plan: Hashtag Links (`#tag` / `#[[multi word tag]]`)

**Priority**: P0 — Launch-critical  
**Created**: 2026-04-16  
**Status**: Stories 1 and 2 complete; Story 3 complete. Full feature implemented.

---

## Epic Overview

### User Value

Users migrating from Logseq carry graphs where `#tag`, `#meeting`, `#project/alpha`, and `#[[multi word tag]]` are the primary categorization mechanism. Without correct parsing and navigation, every hashtag in an imported graph renders as plain text and all hashtag backlinks are silently broken. This is a data-loss regression from the user's perspective.

### Success Metrics

| Metric | Target |
|---|---|
| Hashtag-to-page navigation | Tapping `#tag` navigates to the `tag` page (creating if absent) |
| Multi-word hashtag parsing | `#[[multi word tag]]` parses as `TagNode("multi word tag")` |
| Backlink correctness | A block containing `#tag` appears in the backlinks panel of the `tag` page |
| Rename propagation | Renaming `tag` → `new-tag` rewrites `#tag` → `#new-tag` in all blocks |
| Autocomplete | Typing `#` in the editor opens the page-search autocomplete |
| Round-trip fidelity | `#tag` in source file → `#tag` in written file (no mutation to `[[tag]]`) |
| Test coverage | All new code paths covered by unit tests; BacklinkRenamer integration test extended |

### Scope

**Included:**
- `#word` simple hashtag parsing (already parses; needs multi-word fix and rename support)
- `#[[multi word tag]]` bracket-form hashtag parsing
- Hashtag participation in `BacklinkRenamer` rename operations
- `BlockEditor` `#` autocomplete trigger
- Hashtag render in `MarkdownEngine` (already works via `TAG_TAG`; verify navigation)

**Excluded:**
- Nested hashtags (e.g., `#project/subproject` namespace support) — separate feature
- Hashtag filtering in search (search already finds tags via FTS; no new work)
- Tag cloud / tag index view — separate feature

### Constraints

- File format must remain Logseq-compatible: `#tag` in source stays `#tag` on disk
- No new AST node types — `TagNode` is the correct representation
- Changes to `InlineParser` must not regress existing parser tests
- `BacklinkRenamer` changes require integration test coverage

---

## Architecture Decisions

| # | File | Decision |
|---|---|---|
| ADR-001 | `project_plans/stelekit-parity/decisions/ADR-001-hashtag-as-wikilink-alias.md` | Keep `TagNode` distinct from `WikiLinkNode`; treat as a page reference at the repository/rename layer rather than normalizing syntax at parse time. |

---

## Story Breakdown

### Story 1: Multi-Word Hashtag Parsing [~2 days] [STATUS: COMPLETE]

**User value**: `#[[Meeting Notes]]` is a valid Logseq hashtag for a page with spaces. Without this, multi-word hashtag links silently break and users see `#[[Meeting` rendered as a tag and `Notes]]` as trailing text.

**Acceptance criteria:**
- `InlineParser` parses `#[[multi word tag]]` as `TagNode("multi word tag")`
- `#[[tag]]` and `#tag` both round-trip through `reconstructContent()` with correct syntax
- `extractReferences()` includes the multi-word tag in the references list
- Existing `parseTag` behavior for simple `#word` is unchanged
- All new paths covered by unit tests in `LogseqParserTest` or a dedicated `HashtagParserTest`

---

#### Task 1.1: Extend `InlineParser.parseTag` to Handle `#[[bracket form]]` [2h] [STATUS: COMPLETE]

**Objective**: When the lexer emits `HASH` followed immediately by `L_BRACKET L_BRACKET`, delegate to a bracket-form tag parse path that reads until `R_BRACKET R_BRACKET` and emits `TagNode("multi word content")`.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/lexer/Token.kt`
- Test: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/LogseqParserTest.kt`

**Prerequisites:**
- Understanding of `InlineParser.parseTag()` (lines 419–438) and the `parseLink` method's `[[` lookahead pattern (lines 244–271)
- Understanding of `Lexer.saveState()` / `restoreState()` backtracking pattern

**Implementation approach:**
1. In `parseTag(token: Token)`, after the `if (currentToken.type == TokenType.TEXT)` check, add an `else if (currentToken.type == TokenType.L_BRACKET)` branch.
2. Save lexer state for backtracking.
3. Advance past first `L_BRACKET`; check for a second `L_BRACKET`. If not found, restore state and return `TextNode("#")`.
4. Advance past second `L_BRACKET`; accumulate tokens until `R_BRACKET R_BRACKET` or EOF/NEWLINE.
5. Consume both `R_BRACKET` tokens; return `TagNode(accumulated.toString())`.
6. If `R_BRACKET R_BRACKET` is not found before EOF/NEWLINE, restore state and return `TextNode("#")`.

**Validation strategy:**
- Unit: `InlineParser("#[[Meeting Notes]]").parse()` → `[TagNode("Meeting Notes")]`
- Unit: `InlineParser("#tag").parse()` → `[TagNode("tag")]` (regression)
- Unit: `InlineParser("#[[unclosed")` → `[TextNode("#"), TextNode("[[unclosed")]` (fallback)
- Unit: `InlineParser("#[[Page|alias]]").parse()` — decide: treat `|` as part of page name or strip. Logseq does not support alias syntax on hashtags; treat the whole content including `|` as the page name, or truncate at `|`. Recommended: truncate at `|` and take left side (consistent with `WikiLinkNode` alias behavior).
- Run `./gradlew jvmTest --tests "*.LogseqParserTest"` and `./gradlew jvmTest --tests "*.HashtagParserTest"`

**Success criteria**: All tests pass; no regressions in `ParsingPropertyTest`.

**INVEST check:**
- Independent: No other tasks required first
- Negotiable: Alias-in-hashtag edge case can be decided during implementation
- Valuable: Unblocks multi-word hashtag navigation
- Estimable: 2h with high confidence
- Small: Single function extension (~30 lines)
- Testable: Fully unit-testable in `commonTest`

---

#### Task 1.2: Update `reconstructContent()` for Bracket-Form Hashtag Round-Trip [1h] [STATUS: COMPLETE]

**Objective**: `MarkdownParser.reconstructContent()` already handles `TagNode` by emitting `#${node.tag}`. Verify this is correct for multi-word tags: `TagNode("multi word")` must serialize as `#[[multi word]]`, not `#multi word`.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` (lines 109–218, `reconstructContent`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt`
- Test: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parser/MarkdownParserTest.kt`

**Prerequisites:**
- Completion of Task 1.1
- Understanding of `reconstructContent()` structure (switch over `InlineNode` subtypes)

**Implementation approach:**
1. Locate the `is TagNode` branch in `reconstructContent()` (currently: `sb.append("#"); sb.append(node.tag)`).
2. Change to: if `node.tag` contains a space (or any character that would require bracket form), emit `#[[${node.tag}]]`; otherwise emit `#${node.tag}`.
3. The condition: `node.tag.contains(' ')` is the minimal check; consider also checking for characters that terminate simple hashtags (`,`, `.`, `!`, `?`, `"`, `;`).

**Validation strategy:**
- Unit: `reconstructContent([TagNode("idea")])` → `"#idea"`
- Unit: `reconstructContent([TagNode("Meeting Notes")])` → `"#[[Meeting Notes]]"`
- Round-trip test: parse `#[[Meeting Notes]]` → serialize → parse again → same `TagNode`
- Run `./gradlew jvmTest --tests "*.MarkdownParserTest"`

**Success criteria**: Round-trip fidelity for both simple and bracket-form hashtags.

**INVEST check:**
- Independent: Depends on Task 1.1 only for the test fixtures
- Valuable: Prevents data mutation on save
- Estimable: 1h
- Small: ~5 lines change + 3 test cases
- Testable: Unit-testable in `commonTest`

---

### Story 2: Hashtag Participation in Backlink Rename [~1 day] [STATUS: COMPLETE]

**User value**: When a user renames the page `meeting` to `meeting-notes`, all `#meeting` references across the graph should become `#meeting-notes`. Without this, renamed pages leave broken hashtag links that silently point nowhere.

**Acceptance criteria:**
- `BacklinkRenamer.execute()` rewrites `#oldname` → `#newname` in block content
- `BacklinkRenamer.execute()` rewrites `#[[old name]]` → `#[[new name]]` in block content
- The rename preview count includes blocks containing hashtag references (not just wikilinks)
- Existing `replaceWikilink` behavior is unchanged (no regression)
- Integration test in `BacklinkRenamerTest` covers hashtag rewrite

---

#### Task 2.1: Implement `replaceHashtag` Function in `BacklinkRenamer.kt` [2h] [STATUS: COMPLETE]

**Objective**: Add a `replaceHashtag(content, oldName, newName)` function parallel to `replaceWikilink`. Apply both rewrites during `execute()`. Extend `preview()` to count blocks with either form.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BacklinkRenamer.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockRepository.kt` (for `getLinkedReferences` — check if it queries tags too)
- Test: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BacklinkRenamerTest.kt`

**Prerequisites:**
- Understanding of `replaceWikilink` (lines 37–41) and the `execute()` flow (lines 84–121)
- Understanding of how `BlockRepository.getLinkedReferences` queries blocks — it may need extension to also find hashtag-referenced blocks

**Implementation approach:**
1. Add `internal fun replaceHashtag(content: String, oldName: String, newName: String): String`:
   - Replace `#[[${oldName}]]` with `#[[${newName}]]` using exact string replacement (Logseq page names are case-sensitive in the bracket form).
   - Replace `#${oldName}` with `#${newName}` using a regex that anchors the end of the match at a word boundary or whitespace/punctuation to avoid partial matches (e.g., `#meetings` must not be rewritten when renaming `meeting`).
   - Regex: `#${Regex.escape(oldName)}(?=[\\s,\\.!?;"\\[\\]]|$)` applied to content.
2. In `execute()`, after `replaceWikilink` is applied to each block, also apply `replaceHashtag`.
3. In `preview()` and the affected-block collection: check if `getLinkedReferences` already includes blocks found via `TagNode` references. If not, also query blocks containing the literal `#${oldName}` string and union the results.

**Validation strategy:**
- Unit (pure function): `replaceHashtag("check #meeting notes", "meeting", "sync")` → `"check #sync notes"`
- Unit: `replaceHashtag("check #meetings today", "meeting", "sync")` → `"check #meetings today"` (no partial match)
- Unit: `replaceHashtag("#[[Meeting Notes]] recap", "Meeting Notes", "Syncs")` → `"#[[Syncs]] recap"`
- Integration: Extend `BacklinkRenamerTest` — create a block `"#alpha notes"`, rename page `alpha` → `beta`, verify content becomes `"#beta notes"`
- Run `./gradlew jvmTest --tests "*.BacklinkRenamerTest"`

**Success criteria**: Integration test passes; existing `ReplaceWikilinkTest` passes without modification.

**INVEST check:**
- Independent: Does not depend on Story 1 tasks
- Negotiable: Exact regex anchoring rule negotiable during implementation
- Valuable: Prevents broken hashtag links after rename
- Estimable: 2h
- Small: ~30 lines new code + 1 integration test extension
- Testable: Pure function + integration test

---

### Story 3: Hashtag Autocomplete in BlockEditor [~1 day] [STATUS: COMPLETE]

**User value**: Typing `#` in the editor should trigger the same page-search autocomplete popup that `[[` triggers. Users expect to be able to type `#ide` and select `idea` to insert `#idea`. Without this, the only way to create a hashtag link is to type the full name manually, making hashtags harder to use than wikilinks.

**Acceptance criteria:**
- Typing `#` followed by letters in `BlockEditor` triggers the `AutocompleteMenu` with matching page names
- Selecting an autocomplete suggestion inserts `#selectedPage` (simple form if single word) or `#[[selected page]]` (bracket form if multi-word) at the cursor
- The autocomplete popup dismisses on space or when the `#` trigger is no longer present
- Existing `[[` autocomplete behavior is unchanged
- Unit test or snapshot test verifies the trigger detection logic

---

#### Task 3.1: Add `#` Autocomplete Trigger Detection in `BlockEditor.kt` [2h] [STATUS: COMPLETE]

**Objective**: The `onValueChange` handler in `BlockEditor.kt` already detects `[[` via regex and sets `AutocompleteState`. Add a parallel detection path for `#word` that also sets `AutocompleteState` with the partial tag name as the query.

**Context boundary:**
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/AutocompleteMenu.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

**Prerequisites:**
- Understanding of the existing `[[` trigger detection (lines 71–91 of `BlockEditor.kt`)
- Understanding of `AutocompleteState` data class structure

**Implementation approach:**
1. After the existing `val match = Regex("\\[\\[([^\\]]*)$").find(textBeforeCursor)` check, add a second check:
   `val hashMatch = Regex("#([^\\s#\\[\\](),!?;.\"']*)$").find(textBeforeCursor)`
2. If `hashMatch != null`, use `hashMatch.groupValues[1]` as the query for `AutocompleteState`. The popup position calculation (cursor rect lookup) is identical to the `[[` path.
3. When an autocomplete item is selected from the `#` path: insert `#pageName` if the page name is a single word (no spaces); insert `#[[page name]]` if multi-word. The insertion replaces the `#query` text from `hashMatch.range.first` to cursor position.
4. The existing `else { onAutocompleteStateChange(null) }` branch already clears autocomplete when neither trigger is active — no change needed there.

**Validation strategy:**
- Unit (pure logic): Extract the trigger detection into a testable pure function `detectAutocompleteMode(text: String, cursor: Int): AutocompleteMode?` and unit-test it
- Manual: Type `#ide` → popup appears with pages matching "ide"; select `idea` → inserts `#idea`
- Manual: Type `#[[` → (bracket form triggers same popup); select `Meeting Notes` → inserts `#[[Meeting Notes]]`
- Regression: Type `[[` → existing autocomplete behavior unchanged
- Run `./gradlew jvmTest --tests "*.BlockStateManagerTest"`

**Success criteria**: Autocomplete triggers on `#` prefix; inserted text uses correct simple/bracket form; `[[` autocomplete unaffected.

**INVEST check:**
- Independent: Does not require Stories 1 or 2
- Negotiable: Whether `#[[` triggers autocomplete is negotiable (it should, for consistency)
- Valuable: Makes hashtag creation as ergonomic as wikilink creation
- Estimable: 2h
- Small: ~20 lines new code in one function
- Testable: Pure function extraction enables unit testing

---

## Known Issues (Identified During Planning)

### BUG-001: Partial Hashtag Match in Rename [SEVERITY: High]

**Description**: The naive regex `#${oldName}` without word-boundary anchoring would rewrite `#meetings` when renaming `meeting`. This is a data-corruption risk.

**Mitigation:**
- Use a negative lookahead that requires the character after the hashtag word to be a non-word character or end of string: `#${Regex.escape(oldName)}(?=[\\s,\\.!?;"\\[\\]]|$)`
- Add a dedicated unit test: rename `"meeting"` → `"sync"` in content `"#meetings #meeting #meeting-notes"` → only `#meeting` should be rewritten

**Files likely affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BacklinkRenamer.kt` — `replaceHashtag` implementation

**Prevention strategy:**
- Code review checklist: verify regex includes negative lookahead
- Property-based test: generate random page names with common prefixes; verify no prefix match

**Related tasks**: Task 2.1

---

### BUG-002: `#[[bracket form]]` Not Handled by Current `extractReferences()` [SEVERITY: Medium]

**Description**: `extractReferences()` in `MarkdownParser.kt` already adds `TagNode.tag` to references. But if the InlineParser currently emits `TextNode("#")` followed by `TextNode("[[Meeting Notes]]")` for `#[[Meeting Notes]]` (because `parseTag` only handles `TEXT` tokens after `#`), then the reference is never extracted.

**Mitigation:**
- Task 1.1 fixes the parser to emit `TagNode("Meeting Notes")` correctly
- After Task 1.1, `extractReferences()` automatically picks up multi-word tags with no further changes
- Add a parser test that verifies `#[[Meeting Notes]]` appears in `ParsedBlock.references`

**Files likely affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` — `extractReferences` (no change needed once Task 1.1 is done)

**Prevention strategy:**
- Integration test: load a page with `#[[Tag Name]]`, verify the `Tag Name` page appears in the references panel

**Related tasks**: Task 1.1

---

### BUG-003: Autocomplete Insertion Position Mismatch for `#[[` Form [SEVERITY: Low]

**Description**: When inserting an autocomplete result via the `#` trigger, the replacement range is computed from `hashMatch.range.first` (the `#` character) to the cursor. If the user typed `#[[partial`, the replacement range includes the `[[` characters. The inserted text must be `#[[Full Name]]`, not `#[[Full Name` (missing closing brackets).

**Mitigation:**
- The insertion logic must always reconstruct the full canonical form: `#pageName` or `#[[page name]]`
- The replacement range must extend from the `#` character to the current cursor (consuming everything the user typed as the trigger)
- Unit test: simulate the user typing `#[[Meet` with cursor at end; select `Meeting Notes` → result is `#[[Meeting Notes]]`

**Files likely affected:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt` — autocomplete insertion handler

**Prevention strategy:**
- The insertion handler should be a pure function of `(originalText, triggerStart, selectedPageName) -> String` for easy unit testing

**Related tasks**: Task 3.1

---

## Dependency Visualization

```
Story 1: Parser                Story 2: Rename              Story 3: Autocomplete
─────────────────────         ─────────────────────        ─────────────────────

Task 1.1                       Task 2.1                      Task 3.1
[#[[bracket]] parse]           [replaceHashtag +            [# trigger detection
      │                         BacklinkRenamer]              in BlockEditor]
      │                              │
      ▼                              │
Task 1.2                        (independent
[reconstructContent              of Story 1)
 round-trip fix]

Stories 1, 2, and 3 are PARALLEL — no cross-story dependency.
Within Story 1: Task 1.2 depends on Task 1.1 (multi-word tags must parse before round-trip can be tested).
```

---

## Integration Checkpoints

**After Story 1 (Parser):**
- Load a Logseq graph containing `#[[multi word tag]]` — verify it renders as a clickable link pointing to the correct page
- Verify the page round-trips: save and reload; `#[[multi word tag]]` remains in the file as-is (not mutated to `[[multi word tag]]`)

**After Story 2 (Rename):**
- Rename a page that is referenced via `#tag` in multiple blocks
- Verify all `#tag` occurrences are rewritten to `#newname` in both DB and disk files
- Verify `#[[multi word tag]]` is also rewritten when the referenced page is renamed

**After Story 3 (Autocomplete):**
- Type `#mee` in a block editor — verify autocomplete popup shows `Meeting Notes` (if that page exists)
- Select `Meeting Notes` — verify `#[[Meeting Notes]]` is inserted
- Type `#idea` — verify `#idea` (simple form) is inserted

**Final feature validation:**
- End-to-end: open a Logseq graph export, navigate to a page that is only referenced via `#tag` (no `[[tag]]`), verify it appears in the backlinks panel of the tag page
- Rename test: rename a tag page, verify all hashtag references are updated across all pages

---

## Context Preparation Guide

### Task 1.1 — Parser Extension
Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt` — focus on `parseTag()` (lines 419–438) and `parseLink()` (lines 244–271, the `[[` lookahead pattern to mirror)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/lexer/Token.kt` — `TokenType` enum for `HASH`, `L_BRACKET`, `R_BRACKET`
- `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/LogseqParserTest.kt` — existing test style to follow

Concepts to understand:
- The `saveState()` / `restoreState()` backtracking pattern used throughout `InlineParser`
- Why `parseTag` checks `currentToken.type == TokenType.TEXT` (the lexer emits `TEXT` for word characters after `#`)

### Task 1.2 — Round-Trip Serializer
Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` — focus on `reconstructContent()` (lines 109–218)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt` — `TagNode` definition

Concepts to understand:
- The two-pass design: `MarkdownParser` (in `parser/`) converts AST back to a string for storage; `InlineParser` (in `parsing/`) parses that string again at render time

### Task 2.1 — BacklinkRenamer Extension
Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BacklinkRenamer.kt` — all of it (~140 lines)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BacklinkRenamerTest.kt` — test style to follow
- `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/ReplaceWikilinkTest.kt` — the parallel unit test for `replaceWikilink` to mirror

Concepts to understand:
- `getLinkedReferences` returns blocks that have the page name in their `references` list (populated from `extractReferences()`); hashtag tags already go into this list via `TagNode` handling in `extractReferences()`, so the existing query may already return hashtag-referencing blocks
- The `replaceWikilink` regex escape pattern to mirror for `replaceHashtag`

### Task 3.1 — BlockEditor Autocomplete
Files to load:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt` — focus on `onValueChange` handler (lines 60–92), the `[[` trigger detection pattern
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/AutocompleteMenu.kt` — `AutocompleteState` data class structure

Concepts to understand:
- `AutocompleteState(query, rect)` — how the query string drives the page search and how the cursor rect positions the popup
- How the selected item from `AutocompleteMenu` is inserted back into the text field (look for the `onItemSelected` callback in `BlockItem.kt`)

---

## Success Criteria

- [ ] All three stories completed and integration checkpoints passed
- [ ] `#tag` taps navigate to the tag page in both view mode and after clicking autocomplete selection
- [ ] `#[[multi word tag]]` parses correctly and navigates
- [ ] Backlink panel shows blocks with `#tag` references for the tag's page
- [ ] Page rename rewrites `#oldname` and `#[[old name]]` across all blocks
- [ ] `#` autocomplete trigger is functional and inserts correct syntax form
- [ ] `./gradlew jvmTest` passes with no regressions
- [ ] Round-trip fidelity: no `#tag` → `[[tag]]` mutations in saved files
- [ ] Test coverage on new code paths ≥ 80%
- [ ] Code review approved
