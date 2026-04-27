# Multi-Word Term Highlighting & Unlinked References — Implementation Plan

**Epic**: Surface multi-word page-name suggestions inline and provide per-page + global flows for reviewing and accepting unlinked references.  
**Branch**: `stelekit-multi-word-term-highliting`  
**Status**: Ready for implementation  
**Created**: 2026-04-14

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Requirements Traceability](#requirements-traceability)
3. [Architecture Overview](#architecture-overview)
4. [Dependency Graph](#dependency-graph)
5. [ADR Index](#adr-index)
6. [Epic Breakdown](#epic-breakdown)
   - [Story 1: Fix MarkdownEngine Position Tracking](#story-1-fix-markdownengine-position-tracking)
   - [Story 2: Position Drift Guard in BlockItem](#story-2-position-drift-guard-in-blockitem)
   - [Story 3: Test Coverage — AhoCorasick & Extraction](#story-3-test-coverage--ahocorasick--extraction)
   - [Story 4: Per-Page Unlinked References Panel Hardening](#story-4-per-page-unlinked-references-panel-hardening)
   - [Story 5: Global Unlinked References Screen](#story-5-global-unlinked-references-screen)
   - [Story 6: Navigation & Command Palette Integration](#story-6-navigation--command-palette-integration)
7. [Known Issues](#known-issues)
8. [Non-Functional Requirements](#non-functional-requirements)
9. [Test Strategy](#test-strategy)
10. [Integration Checkpoints](#integration-checkpoints)

---

## Executive Summary

The `AhoCorasickMatcher` already handles multi-word patterns correctly. The visible failure — multi-word page names not highlighted or linked at the wrong position — traces to a single bug: `MarkdownEngine.renderNode` (line 80) uses `String.indexOf` to map TextNode positions back to original content offsets. When a block contains repeated text, `indexOf` returns the wrong occurrence, producing stale `contentStart`/`contentEnd` values stored in `PAGE_SUGGESTION_TAG` annotations. Fixing this one line unlocks correct multi-word highlighting for all blocks.

On top of the bug fix, this epic adds:

1. A **version stamp** to `SuggestionState` that validates the block has not been edited between click and confirm, preventing wrong-position link insertion in concurrent-edit scenarios.
2. Comprehensive **test coverage** for the Aho-Corasick matcher, extraction pipeline, and edge cases currently untested.
3. **Hardened per-page unlinked references** in `ReferencesPanel`: matched substrings highlighted within panel entries, count badge on collapsed header.
4. A new **global unlinked references screen** that streams results across all pages in the active graph, with per-occurrence accept/reject backed by the existing `DatabaseWriteActor` write path.
5. **Navigation integration**: command palette entry and sidebar item for the global screen.

---

## Requirements Traceability

| Requirement | Story | Tasks |
|---|---|---|
| Multi-word page names highlighted in BlockViewer | S1 | T1.1, T1.2 |
| Correct character positions for [[…]] wrap | S1, S2 | T1.1, T2.1, T2.2 |
| Per-page panel surfaces multi-word matches | S4 | T4.1, T4.2 |
| Global unlinked references flow | S5 | T5.1–T5.4 |
| Accept wraps matched text, persists to disk | S2, S5 | T2.2, T5.3 |
| Reject skips (no silent auto-link) | S2, S5 | T2.1, T5.3 |
| No latency regression (200+ blocks) | S1, S3 | T1.2, T3.1 |
| Command palette + sidebar access to global flow | S6 | T6.1, T6.2 |

---

## Architecture Overview

```
UI Layer
  BlockItem.kt              ← SuggestionState with capturedContent guard (ADR-002)
  BlockViewer.kt            ← unchanged; receives suggestionMatcher from parent
  ReferencesPanel.kt        ← matched substring highlight, count badge (Story 4)
  GlobalUnlinkedReferencesScreen.kt   ← NEW (Story 5)
  LeftSidebar.kt            ← new nav item (Story 6)
  App.kt                    ← new Screen.GlobalUnlinkedReferences route (Story 6)

ViewModel Layer
  StelekitViewModel.kt      ← new command registration (Story 6)
  GlobalUnlinkedReferencesViewModel.kt  ← NEW (Story 5, ADR-003)

Domain Layer
  MarkdownEngine.kt         ← forward cursor fix in renderNode + extractSuggestions (ADR-001)
  AhoCorasickMatcher.kt     ← no changes needed
  PageNameIndex.kt          ← no changes needed

State/Navigation
  AppState.kt               ← add Screen.GlobalUnlinkedReferences (Story 6)

Tests
  AhoCorasickMatcherTest.kt     ← NEW (Story 3)
  ExtractSuggestionsTest.kt     ← extended (Story 3)
  PageNameIndexTest.kt          ← NEW (Story 3)
  GlobalUnlinkedRefsViewModelTest.kt  ← NEW (Story 5)
```

---

## Dependency Graph

```
T1.1 (forward cursor fix)
  |
  +-- T1.2 (verify multi-word highlights render) -------+
  |                                                      |
T2.1 (capturedContent in SuggestionState)               |
  |                                                      |
  +-- T2.2 (confirm / dismiss guard)                    |
  |                                                      v
T3.1 (AhoCorasickMatcherTest)           T4.1 (panel matched-substring highlight)
T3.2 (ExtractSuggestionsTest expansion)   |
T3.3 (PageNameIndexTest)               T4.2 (panel count badge)
                                          |
                           T5.1 (GlobalUnlinkedReferencesViewModel)
                             |
                           T5.2 (GlobalUnlinkedReferencesScreen composable)
                             |
                           T5.3 (accept/reject wired to writeActor)
                             |
                           T5.4 (ViewModel test)
                             |
                           T6.1 (Screen route + App.kt router)
                           T6.2 (command palette + sidebar)
```

Stories 1 and 2 are prerequisites for Stories 3, 4, and 5. Story 6 depends on Story 5. Stories 3 and 4 can be developed in parallel with Story 5 once Story 1 is done.

---

## ADR Index

| ADR | Decision | File |
|---|---|---|
| ADR-001 | Replace `indexOf` with a forward-only cursor in `MarkdownEngine.renderNode` and `extractSuggestions` to fix multi-word position mapping for blocks with repeated text. | `project_plans/multi-word-term-highlighting/decisions/ADR-001-forward-cursor-position-tracking.md` |
| ADR-002 | Add `capturedContent: String` to `SuggestionState` and validate it on confirm to prevent wrong-position link insertion when the block is edited while the popup is open. | `project_plans/multi-word-term-highlighting/decisions/ADR-002-suggestion-state-version-stamp.md` |
| ADR-003 | Implement global unlinked references as a dedicated `GlobalUnlinkedReferencesViewModel` with streaming cross-page aggregation, scoped to the screen's coroutine scope, dispatching accepts through `DatabaseWriteActor`. | `project_plans/multi-word-term-highlighting/decisions/ADR-003-global-unlinked-refs-viewmodel.md` |

---

## Epic Breakdown

---

### Story 1: Fix MarkdownEngine Position Tracking

**Goal**: Multi-word page names are highlighted at the correct position in all blocks, including blocks with repeated tokens.

**Acceptance Criteria**:
- A block containing "New Year New Year" with a page "New Year" shows two separate highlighted spans, each at the correct character range.
- A block containing unique tokens ("Learn Kotlin today") continues to highlight correctly.
- All existing `ExtractSuggestionsTest` cases pass without modification.

---

#### Task 1.1 — Forward-Cursor Fix in MarkdownEngine

**Estimate**: 1h  
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`  
**Concepts to load**: ADR-001, `RenderContext.searchFrom`, `renderNode` depth-0 branch, `extractSuggestions` node loop

**Change**:

In `renderNode` (around line 80), replace:
```kotlin
val origStart = ctx.original.indexOf(node.content, ctx.searchFrom)
    .takeIf { it >= 0 } ?: ctx.searchFrom
ctx.searchFrom = origStart + node.content.length
```
with:
```kotlin
val origStart = ctx.searchFrom
ctx.searchFrom += node.content.length
```

In `extractSuggestions` (around line 407), replace:
```kotlin
val nodeOrigStart = content.indexOf(node.content, searchFrom).takeIf { it >= 0 } ?: continue
searchFrom = nodeOrigStart + node.content.length
```
with:
```kotlin
val nodeOrigStart = searchFrom
searchFrom += node.content.length
```

**Validation**:
- Run `./gradlew jvmTest --tests "*.ExtractSuggestionsTest"` — all existing tests pass.
- Manual smoke test: open a page in the desktop app, view a block with a multi-word page name; it should be highlighted with a subtle underline.

---

#### Task 1.2 — Verify Multi-Word Rendering End-to-End

**Estimate**: 1h  
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt` (read-only during this task)  
**Concepts to load**: `parseMarkdownWithStyling`, `renderGapWithSuggestions`, `PAGE_SUGGESTION_TAG` annotation format

**Work**: Write a focused inline test in `ExtractSuggestionsTest.kt` that:
1. Constructs an `AhoCorasickMatcher` with `{"andrew underwood" -> "Andrew Underwood"}`.
2. Calls `extractSuggestions("I met Andrew Underwood yesterday", matcher)`.
3. Asserts one `MatchSpan` with `start=6, end=22, canonicalName="Andrew Underwood"`.

Also write a repeated-token test:
1. Matcher with `{"new year" -> "New Year"}`.
2. Input: `"New Year New Year"`.
3. Assert two spans: `[0,8]` and `[9,17]`.

**Validation**: Both new tests pass. No regression in existing suite.

**Integration checkpoint**: After T1.1 + T1.2, multi-word highlighting is functionally correct. Proceed to Story 2.

---

### Story 2: Position Drift Guard in BlockItem

**Goal**: Accepting a suggestion after editing the block does not insert the link at a stale/wrong position.

**Acceptance Criteria**:
- If block content changes between suggestion click and confirm, the popup is dismissed with a user-visible message and no link is written.
- If content has not changed, the link wraps exactly the highlighted text.

---

#### Task 2.1 — Add capturedContent to SuggestionState

**Estimate**: 1h  
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`  
**Concepts to load**: ADR-002, `SuggestionState` data class (line 36), `onSuggestionClick` callback (line 384), `LaunchedEffect` patterns in Compose

**Change**: Extend `SuggestionState`:
```kotlin
private data class SuggestionState(
    val canonicalName: String,
    val contentStart: Int,
    val contentEnd: Int,
    val capturedContent: String,
)
```

Update the click handler to capture `block.content` at click time:
```kotlin
onSuggestionClick = { canonicalName, contentStart, contentEnd ->
    suggestionState = SuggestionState(canonicalName, contentStart, contentEnd, block.content)
}
```

Add a secondary auto-dismiss guard using `LaunchedEffect`:
```kotlin
LaunchedEffect(block.content) {
    if (suggestionState != null && suggestionState?.capturedContent != block.content) {
        suggestionState = null
        // optionally set a transient "block was edited" flag
    }
}
```

**Validation**: Compiles. Existing suggestion click + confirm flow (no edits) still inserts the link correctly.

---

#### Task 2.2 — Guard onConfirm Against Stale State

**Estimate**: 1h  
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`  
**Concepts to load**: `onConfirm` callback location, `onContentChange` signature, snackbar / status message pattern used elsewhere in the app

**Change**: In the `onConfirm` lambda (around line 407):
```kotlin
onConfirm = {
    val pending = suggestionState ?: return@onConfirm
    if (pending.capturedContent != block.content) {
        suggestionState = null
        // Surface user-visible message: "Block was edited — tap the suggestion to re-link."
        // Use the existing notification/snackbar mechanism or a transient `showStaleHint` flag.
    } else {
        val safeEnd = pending.contentEnd.coerceAtMost(block.content.length)
        val safeStart = pending.contentStart.coerceIn(0, safeEnd)
        val newContent = block.content.substring(0, safeStart) +
            "[[${pending.canonicalName}]]" +
            block.content.substring(safeEnd)
        onContentChange(newContent, block.version + 1)
        suggestionState = null
    }
}
```

Locate the existing snackbar / toast mechanism (search for `showSnackbar` or `statusMessage` usage in `App.kt` / `StelekitViewModel`) and reuse it for the stale-hint message. If no reusable mechanism exists at this call site, a simple `var showStaleHint by remember { mutableStateOf(false) }` with a `Text` below the popup is sufficient for v1.

**Validation**:
- Manual test: click a suggestion, type a character in the block, click Link — popup closes with hint, no link is written.
- Manual test: click a suggestion, do not edit, click Link — link is written at the correct position.

**Integration checkpoint**: After Story 2, suggestion acceptance is safe against concurrent edits. Stories 3, 4, and 5 can proceed in parallel.

---

### Story 3: Test Coverage — AhoCorasick & Extraction

**Goal**: Cover all known edge cases in the matching pipeline with automated tests so regressions are caught immediately.

**Acceptance Criteria**:
- `AhoCorasickMatcherTest.kt` exists and covers: multi-word patterns, prefix-overlap resolution (longest wins), word-boundary enforcement, case-insensitive matching, internal punctuation, empty input.
- `ExtractSuggestionsTest.kt` covers: repeated tokens (the bug case), suggestions inside block refs (excluded), suggestions inside wiki links (excluded), inline code exclusion, case-insensitive index round-trip.
- `PageNameIndexTest.kt` exists and covers: journal page exclusion, minimum name length, rebuild triggered on page set change.
- `./gradlew jvmTest` passes cleanly.

---

#### Task 3.1 — AhoCorasickMatcherTest.kt

**Estimate**: 2h  
**Files**: 
- CREATE `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcherTest.kt`  
**Concepts to load**: `AhoCorasickMatcher.findAll`, `resolveOverlaps` logic, `isWordBoundary` semantics

**Test cases**:

```
multiWordPatternFound          — "Andrew Underwood" matched in "I met Andrew Underwood yesterday"
prefixOverlapLongestWins       — pages ["KMP", "KMP SDK"], text "I use KMP SDK today" → one match "KMP SDK"
prefixOverlapShortWhenLongAbsent — pages ["KMP", "KMP SDK"], text "Use KMP only" → one match "KMP"
wordBoundaryPreventsSubstringMatch — page "test", text "testing is fun" → zero matches
wordBoundaryAllowsExactWord    — page "test", text "run the test today" → one match
caseInsensitiveMatch           — page canonical "Kotlin", text "learning kotlin daily" → match canonical "Kotlin"
internalPunctuation            — page "A.I. Safety", text "A.I. Safety is real" → one match (dots are non-word chars, boundary at start and end)
emptyInput                     — findAll("") → empty list
noPatterns                     — AhoCorasickMatcher(emptyMap()).findAll("anything") → empty list
multipleNonOverlapping         — pages ["Java", "Kotlin"], text "Java and Kotlin" → two matches
resolveOverlapsCorrect         — pages ["lang", "language"], text "learn lang and language" → two matches (no overlap, correct positions)
```

**Validation**: `./gradlew jvmTest --tests "*.AhoCorasickMatcherTest"` passes all cases.

---

#### Task 3.2 — ExtractSuggestionsTest Expansion

**Estimate**: 2h  
**Files**: 
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/ExtractSuggestionsTest.kt` (extend)  
**Concepts to load**: `extractSuggestions` function signature, `InlineParser` AST structure, wiki link / block ref node types

**New test cases to add**:

```
repeatedTokenTwoMatches        — page "New Year", text "New Year New Year" → two spans [0,8] and [9,17]
repeatedTokenDifferentMatches  — page "Andrew Underwood", text "Andrew Underwood and Andrew Underwood" → two spans
suggestionInsideWikiLinkExcluded — text "[[Python]] is great", page "Python" → zero matches
suggestionInsideInlineCodeExcluded — text "`Kotlin` rocks", page "Kotlin" → zero matches
suggestionInsideBlockRefExcluded — text "See ((uuid)) for Python info", page "Python" → one match (only the plain-text "Python")
caseInsensitiveIndexRoundTrip  — page canonical "Kotlin", text "learn KOTLIN daily" → one match, canonicalName == "Kotlin"
journalPageNameNotSuggested    — (integration with PageNameIndex filter, may be PageNameIndexTest instead)
```

**Validation**: All existing tests pass; new cases pass.

---

#### Task 3.3 — PageNameIndexTest.kt

**Estimate**: 2h  
**Files**: 
- CREATE `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/PageNameIndexTest.kt`  
**Concepts to load**: `PageNameIndex` constructor, `PageRepository.getAllPages()` as `Flow<Result<List<Page>>>`, coroutine test utilities (`runTest`, `Turbine` or manual `first()` collection)

**Test cases**:

```
journalPagesExcludedByDefault  — repo emits one journal page + one normal page → matcher contains only normal page
journalPagesIncludedWhenFlagOff — excludeJournalPages=false → matcher includes journal page
minNameLengthFilterApplied     — page name "AB" (2 chars) excluded; "ABC" (3 chars) included
rebuildOnPageSetChange         — emit first page set, collect matcher; emit updated page set, collect new matcher; assert new matcher contains new pages
emptyPageSetYieldsNullMatcher  — repo emits empty list → matcher is null
```

**Validation**: `./gradlew jvmTest --tests "*.PageNameIndexTest"` passes.

**Integration checkpoint**: After Story 3, the domain layer is fully covered with tests. Proceed to Stories 4, 5, 6.

---

### Story 4: Per-Page Unlinked References Panel Hardening

**Goal**: `ReferencesPanel` shows a count badge when collapsed and highlights the matched substring within each unlinked reference entry, matching Obsidian's per-entry UX.

**Acceptance Criteria**:
- Collapsed "Unlinked References" section header shows `(N)` count when N > 0.
- Each unlinked reference block entry highlights the matching page-name substring using the same suggestion colour as inline hints.
- Existing linked references behaviour is unchanged.

---

#### Task 4.1 — Matched Substring Highlight in Panel Entries

**Estimate**: 2–3h  
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ReferencesPanel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt` (read-only)  
**Concepts to load**: `ReferencePageGroup` composable, how block content is rendered in the panel, `parseMarkdownWithStyling` with `suggestionMatcher` parameter, `StelekitViewModel.suggestionMatcher` StateFlow

**Work**:

The `ReferencesPanel` currently renders block content as plain text or via a minimal text component. Pass the active `suggestionMatcher` down to the panel (add parameter `suggestionMatcher: AhoCorasickMatcher?`) and use `parseMarkdownWithStyling(..., suggestionMatcher = suggestionMatcher)` when rendering each block's content within the unlinked references list. The matched substring will automatically receive the `PAGE_SUGGESTION_TAG` annotation and the suggestion `SpanStyle` (underline + tinted background).

No click-to-link is needed within the panel entry itself for v1 — the highlight is informational, showing which text matched. The "Link" button at the entry level (Task 4.2 future enhancement — out of scope for v1; accept happens via navigating to the block or via the global screen).

**Validation**:
- Open a page. In the unlinked references panel, verify that the page name appears highlighted (underline + tint) within each block entry.
- Verify no crash when `suggestionMatcher` is null (panel loaded before index is built).

---

#### Task 4.2 — Count Badge on Collapsed Header

**Estimate**: 1h  
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ReferencesPanel.kt`  
**Concepts to load**: `AnimatedVisibility` usage for expand/collapse in the panel, current header composable, total count field availability

**Work**: The panel already loads `unlinkedBlocks` and tracks `hasMoreUnlinked`. To display a count badge:
1. Track a `totalUnlinkedCount` state. On the first page fetch (`unlinkedOffset == 0`), record `blocks.size` (and if `hasMoreUnlinked`, show `"${blocks.size}+"` to indicate more exist).
2. Modify the collapsed header `Text` from `"Unlinked References"` to `"Unlinked References (${totalUnlinkedCount}${if (hasMoreUnlinked && !isExpanded) "+" else ""})"` when count > 0.

**Validation**: Collapse the panel on a page with unlinked references; count badge visible. Count is 0 or absent when there are no unlinked references.

**Integration checkpoint**: After Story 4, the per-page panel is fully hardened. Story 5 can proceed.

---

### Story 5: Global Unlinked References Screen

**Goal**: A new screen lists unlinked references across all pages in the active graph, with per-occurrence accept and reject.

**Acceptance Criteria**:
- `GlobalUnlinkedReferencesScreen` is accessible (wired in Story 6).
- Results load progressively; a spinner is shown during the first load.
- Each entry shows: source page name, block content with matched substring highlighted, "Link" button, "Skip" button.
- Clicking "Link" wraps the matched text in `[[PageName]]` in the source block, removes the entry from the list, and persists via `DatabaseWriteActor`.
- Clicking "Skip" removes the entry from the list without writing anything.
- Load More button fetches the next batch.

---

#### Task 5.1 — GlobalUnlinkedReferencesViewModel

**Estimate**: 3–4h  
**Files**:
- CREATE `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/GlobalUnlinkedReferencesViewModel.kt`  
**Concepts to load**: ADR-003, `BlockRepository.getUnlinkedReferences(pageName, limit, offset)`, `DatabaseWriteActor.Command` sealed class, `PageRepository.getAllPages()`, coroutine `async`/`awaitAll` pattern

**State model**:
```kotlin
data class GlobalUnlinkedRefsState(
    val results: List<UnlinkedRefEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
)

data class UnlinkedRefEntry(
    val block: Block,
    val targetPageName: String,
    val matchStart: Int,
    val matchEnd: Int,
    val capturedContent: String,  // for stale-guard (ADR-002)
)
```

**Key methods**:
- `loadInitial()` — clears state, fetches first batch across all pages
- `loadMore()` — continues from current page cursor
- `acceptSuggestion(entry)` — validates `capturedContent == entry.block.content`, performs wrap, dispatches `SaveBlockCommand`, removes entry from list
- `rejectSuggestion(entry)` — removes entry from list

For accept, the wrap logic:
```kotlin
val wrapped = entry.block.content.substring(0, entry.matchStart) +
    "[[${entry.targetPageName}]]" +
    entry.block.content.substring(entry.matchEnd)
```

Dispatch through `writeActor` (nullable; fallback to `blockRepository.saveBlock` if null).

**Validation**: Compiles. ViewModel constructor-injectable with test repositories.

---

#### Task 5.2 — GlobalUnlinkedReferencesScreen Composable

**Estimate**: 3h  
**Files**:
- CREATE `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/GlobalUnlinkedReferencesScreen.kt`  
**Concepts to load**: `LazyColumn` + `items`, `CircularProgressIndicator`, how `ReferencesPanel` renders blocks (read for pattern), `suggestionMatcher` threading from `StelekitViewModel`, `AnnotatedString` rendering via `BasicText` or `Text`

**Layout structure**:
```
Column {
  Text("Unlinked References", style = headlineSmall)
  if loading && empty -> CircularProgressIndicator
  if not loading && empty -> Text("No unlinked references across all pages.")
  else -> LazyColumn {
    items(state.results) { entry ->
      UnlinkedRefCard(entry, onAccept, onReject)
    }
    if (hasMore && !loading) item { Button("Load More") }
    if (loading && results.nonEmpty) item { CircularProgressIndicator (small) }
  }
  state.errorMessage?.let { Text(it, color = error) }
}
```

`UnlinkedRefCard` composable:
- Source page name as a clickable chip/link (navigates to that page)
- Block content rendered with `parseMarkdownWithStyling(..., suggestionMatcher = suggestionMatcher)` so the matched substring is highlighted
- Row with "Link" (`FilledTonalButton`) and "Skip" (`TextButton`) at the trailing edge

Pass `suggestionMatcher` into the screen from the call site (already available as `viewModel.suggestionMatcher.collectAsState()` in `App.kt`).

**Validation**:
- Screen renders without crashing when results list is empty.
- Screen renders without crashing when `suggestionMatcher` is null.
- "Link" and "Skip" buttons call the correct ViewModel methods.

---

#### Task 5.3 — Accept/Reject Write Path Wiring

**Estimate**: 2h  
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/GlobalUnlinkedReferencesViewModel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt` (read-only — inspect `Command` sealed class)  
**Concepts to load**: `DatabaseWriteActor` command types, `GraphWriter.queueSave` vs actor dispatch, how `BlockItem` currently dispatches saves (read `BlockItem.kt` lines 407–430)

**Work**: In `acceptSuggestion`, find the correct `DatabaseWriteActor.Command` variant for saving a block (likely `SaveBlock` or equivalent). If the actor command requires a graph path, thread `currentGraphPath: String` from `StelekitViewModel.uiState.value.currentGraphPath` through to the ViewModel via a lambda or constructor parameter.

After dispatch, remove the entry from `state.results` and emit a brief success toast using a `successMessage: String?` field on the state (auto-cleared after 2s via `delay(2000); _state.update { it.copy(successMessage = null) }`).

**Validation**:
- Accept a suggestion on the global screen; verify the block file on disk contains the `[[…]]` wrap.
- Skip a suggestion; verify no file is modified.

---

#### Task 5.4 — GlobalUnlinkedReferencesViewModel Tests

**Estimate**: 2h  
**Files**:
- CREATE `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/screens/GlobalUnlinkedReferencesViewModelTest.kt`  
**Concepts to load**: In-memory `BlockRepository` / `PageRepository` (use `RepositoryFactory.inMemory()` or existing test doubles), `runTest`, `Turbine` or direct state collection

**Test cases**:
```
loadInitialPopulatesResults    — repo has 2 pages × 2 unlinked blocks each → state.results.size == 4
loadInitialEmptyGraph          — no pages → state.results is empty, state.hasMore is false
acceptRemovesEntryFromList     — accept first entry → state.results.size decreases by 1
acceptDispatchesToWriteActor   — writeActor receives SaveBlockCommand with wrapped content
acceptStaleGuardPreventsWrite  — modify block.content after creating entry; call acceptSuggestion → writeActor NOT called, entry removed with error message
rejectRemovesEntryFromList     — reject first entry → state.results.size decreases by 1, writeActor NOT called
loadMoreAppendsResults         — call loadInitial, then loadMore → results accumulate
```

**Validation**: `./gradlew jvmTest --tests "*.GlobalUnlinkedReferencesViewModelTest"` passes.

**Integration checkpoint**: After Story 5, the global screen is fully functional. Proceed to Story 6.

---

### Story 6: Navigation & Command Palette Integration

**Goal**: The global unlinked references screen is reachable from the command palette and the left sidebar.

**Acceptance Criteria**:
- Typing "unlinked" in the command palette shows "Open Unlinked References" as the first result.
- The left sidebar has a nav item "Unlinked References" that navigates to the global screen.
- Navigating to the screen and back preserves navigation history (back button works).

---

#### Task 6.1 — Add Screen Route and Router Case

**Estimate**: 1h  
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`  
**Concepts to load**: `sealed class Screen` in `AppState.kt`, `ScreenRouter` when-expression in `App.kt` (around line 584), `RepositorySet` parameter threading

**Changes**:

In `AppState.kt`, add inside `sealed class Screen`:
```kotlin
data object GlobalUnlinkedReferences : Screen()
```

In `App.kt`, add to the `when (currentScreen)` block:
```kotlin
is Screen.GlobalUnlinkedReferences -> GlobalUnlinkedReferencesScreen(
    pageRepository = repos.pageRepository,
    blockRepository = repos.blockRepository,
    writeActor = repos.writeActor,
    graphPath = appState.currentGraphPath,
    suggestionMatcher = suggestionMatcher,
    onNavigateTo = { viewModel.navigateTo(it) },
)
```

**Validation**: Calling `viewModel.navigateTo(Screen.GlobalUnlinkedReferences)` renders the new screen without crashing.

---

#### Task 6.2 — Command Palette Entry and Sidebar Item

**Estimate**: 1h  
**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (find `updateCommands` or equivalent)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/LeftSidebar.kt` (or equivalent sidebar file)  
**Concepts to load**: How existing commands are registered (`Command` data class in `AppState.kt`), how the sidebar items are declared

**Work**:

In `StelekitViewModel`, locate where commands are built (search for `Command(id =` or `commands =`) and add:
```kotlin
Command(
    id = "global-unlinked-refs",
    label = "Open Unlinked References",
    shortcut = null,
    action = { navigateTo(Screen.GlobalUnlinkedReferences) }
)
```

In the sidebar composable, add a navigation item consistent with existing items (same icon style, same `onClick = { onNavigate(Screen.GlobalUnlinkedReferences) }` pattern). Use `Icons.AutoMirrored.Filled.List` or `Icons.Default.Link` — match whatever icon style is already in use.

**Validation**:
- Open command palette (`Cmd+K` / `Ctrl+K`), type "unlinked" — entry appears and navigates to the screen.
- Click sidebar item — navigates to the screen.
- Press back — returns to previous screen.

---

## Known Issues

### Bug (Critical) — Fixed in Story 1: indexOf Position Drift for Repeated Text

**Severity**: High  
**Status**: Addressed by Task 1.1  
**Description**: `MarkdownEngine.renderNode` line 80 uses `String.indexOf` to map `TextNode` content back to the original string. For blocks containing repeated tokens (e.g., `"New Year New Year"`), subsequent identical nodes resolve to the same first-occurrence offset, producing wrong `contentStart`/`contentEnd` values. The `[[…]]` wrap is inserted at the wrong position.  
**Files**: `MarkdownEngine.kt` line 80, `extractSuggestions` line 407  
**Prevention**: The forward-cursor replacement (Task 1.1) is a one-line fix; the new repeated-token tests (Task 1.2, Task 3.2) ensure regression coverage.

---

### Bug (High) — Position Drift on Concurrent Edit

**Severity**: High  
**Status**: Addressed by Story 2  
**Description**: `SuggestionState` captures offsets at click time. If the user edits the block before clicking "Link", the offsets are stale and the wrap is inserted at the wrong position, corrupting block content.  
**Files**: `BlockItem.kt` lines 36–40, 384–418  
**Prevention**: `capturedContent` field + equality guard on confirm (Task 2.2). Secondary `LaunchedEffect` auto-dismiss guard (Task 2.1).

---

### Risk (Medium) — PageNameIndex Staleness Window

**Severity**: Medium  
**Status**: Not fully addressed in this epic (deferred)  
**Description**: When a page is renamed or deleted, the `AhoCorasickMatcher` on the background thread takes 5–100ms to rebuild. During this window, suggestions for the old page name can still appear and be accepted, creating links to non-existent pages.  
**Mitigation in this epic**: The `capturedContent` guard prevents wrong-position insertion but does not validate that the canonical name still exists as a page. A full fix requires querying `PageRepository.getPageByName(canonicalName)` before the write — deferred to a follow-up.  
**Files**: `PageNameIndex.kt`, `BlockItem.kt`

---

### Risk (Low) — Global Screen Load Time for Large Graphs

**Severity**: Low  
**Status**: Mitigated by streaming aggregation (ADR-003)  
**Description**: For graphs with 500+ pages, the initial load in `GlobalUnlinkedReferencesViewModel` iterates all pages sequentially. At ~2ms per page fetch (SQLite + in-memory regex), 500 pages = ~1s total.  
**Mitigation**: First 50 results appear immediately. Parallel `async { }` batching of 5 pages at a time can halve wall-clock time if needed. `CircularProgressIndicator` is shown during load.  
**Files**: `GlobalUnlinkedReferencesViewModel.kt`

---

### Risk (Low) — SQL Fallback Over-Fetches for Multi-Word Names

**Severity**: Low  
**Status**: Functionally correct; not fixed in this epic  
**Description**: `selectBlocksWithContentLikePaginated("%$pageName%", ...)` uses a plain substring LIKE with no phrase-boundary enforcement. For multi-word names this can return blocks where the words appear in different parts of the content. The in-memory regex filter removes false positives before they reach the UI, so no incorrect results escape — but the SQL round-trip is broader than necessary.  
**Deferred fix**: Change the LIKE to `"% $pageName %"` with space guards, or accept the current behaviour. Low priority given correctness is maintained by the regex filter.  
**Files**: `SqlDelightBlockRepository.kt`, `SteleDatabase.sq`

---

### Risk (Low) — Prefix-Overlap UX Confusion

**Severity**: Low  
**Status**: Algorithm is correct; user education deferred  
**Description**: If pages "KMP" and "KMP SDK" both exist and the block text is "Use KMP and KMP SDK", `resolveOverlaps` correctly produces two separate suggestions (one per occurrence). Users with both pages may be surprised that "KMP SDK" does not absorb the adjacent "KMP". This is correct Aho-Corasick leftmost-longest behaviour, not a bug.  
**Mitigation**: Document in a tooltip or help text. No code change needed.

---

## Non-Functional Requirements

| Attribute | Target | Measurement |
|---|---|---|
| Highlight latency (200 blocks, 1000 pages) | < 16ms per recompose frame | `MarkdownEngine` benchmark or manual frame-time check |
| Global screen initial load (500 pages) | < 2s to first 50 results | Stopwatch during manual test |
| `AhoCorasickMatcher` construction (1000 pages) | < 20ms | Unit test with `measureTime` |
| Memory overhead of `SuggestionState.capturedContent` | Negligible for blocks < 10 KB | No measurement needed |
| No new Gradle dependencies | Zero additions | `git diff kmp/build.gradle.kts` shows no dependency changes |

---

## Test Strategy

### Unit Tests (fast, no I/O)

| File | Coverage Target |
|---|---|
| `AhoCorasickMatcherTest.kt` | Multi-word, prefix-overlap, word-boundary, case, internal punctuation, empty |
| `ExtractSuggestionsTest.kt` (extended) | Repeated tokens, markup exclusion, case round-trip |
| `PageNameIndexTest.kt` | Journal exclusion, min-length, rebuild on change |
| `GlobalUnlinkedReferencesViewModelTest.kt` | Load, accept, reject, stale guard, writeActor dispatch |

### Integration Tests

- `SuggestionState` stale guard: simulate block edit between click and confirm (manual or ViewModel-level test).
- `GlobalUnlinkedReferencesScreen` renders without crash with empty / populated state (Compose UI test if available, otherwise manual).

### Manual Smoke Tests (per story)

Each story's integration checkpoint defines the manual test. At minimum:
1. Open a page with a multi-word page name in the block content; verify inline highlight.
2. Click the highlight; verify the popup; confirm; verify `[[…]]` wrap at correct position.
3. Open the global unlinked references screen; verify results appear; accept one; verify file on disk.

---

## Integration Checkpoints

| After | Checkpoint | Go/No-Go Criteria |
|---|---|---|
| Story 1 (T1.1 + T1.2) | Multi-word highlighting correct | All `ExtractSuggestionsTest` pass; manual: multi-word page name highlighted in block viewer |
| Story 2 (T2.1 + T2.2) | Position drift guard in place | Manual: edit block while popup open → popup closes, no link written |
| Story 3 (T3.1–T3.3) | Domain layer fully tested | `./gradlew jvmTest` clean; new test files exist and pass |
| Story 4 (T4.1 + T4.2) | Panel hardened | Manual: panel shows highlighted match + count badge |
| Story 5 (T5.1–T5.4) | Global screen functional | Manual: navigate to screen, see results, accept one, verify disk write |
| Story 6 (T6.1 + T6.2) | Navigation wired | Manual: command palette "unlinked" → screen; sidebar item → screen; back navigation works |
