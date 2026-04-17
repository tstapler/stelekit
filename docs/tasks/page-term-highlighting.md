# Page Term Highlighting — Implementation Plan

**Feature**: Wikilink suggestion — highlight page-name matches in block view mode and offer one-click conversion to `[[PageName]]`  
**Status**: Implemented (commit 684d46c23, April 2026)  
**Target**: JVM + Android (KMP commonMain)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Functional Requirements](#functional-requirements)
3. [Non-Functional Requirements](#non-functional-requirements)
4. [Architecture Overview](#architecture-overview)
5. [Dependency Graph](#dependency-graph)
6. [Story Breakdown](#story-breakdown)
   - [Story 1: Page Name Index](#story-1-page-name-index)
   - [Story 2: Text Matching Algorithm](#story-2-text-matching-algorithm)
   - [Story 3: UI Highlight Rendering](#story-3-ui-highlight-rendering)
   - [Story 4: Click-to-Link UX](#story-4-click-to-link-ux)
7. [Known Issues](#known-issues)
8. [ADR Index](#adr-index)
9. [Test Strategy](#test-strategy)

---

## Executive Summary

When a user views a block in read mode, any plain-text substring that matches an existing page name should be visually distinguished (a subtle underline or tinted background), and tapping it should show a confirmation popup offering to wrap the text in `[[PageName]]`. The content on disk is never changed until the user explicitly confirms.

The three leverage points that make this safe and fast are:

1. A `PageNameIndex` that materialises a case-folded, sorted list of all page names into an in-memory `MutableStateFlow`. The index is kept in sync with `PageRepository` events — no polling.
2. An `AhoCorasickMatcher` that runs in O(n + m) over each block's plain-text segments (after stripping existing wikilinks, tags, and markdown spans from the match surface). This replaces a naive O(n·m) string-scan that would stall rendering on large pages.
3. Highlight rendering is a pure extension of the existing `parseMarkdownWithStyling` / `tokenizeMarkdown` pipeline in `MarkdownEngine.kt`. A new `InlineToken.PageSuggestion` token carries the candidate span; `WikiLinkText` gains an optional `pageSuggestions` parameter.

The change set is strictly additive in the UI layer (a new optional parameter on `BlockRenderer` / `WikiLinkText`) and introduces one new class per architectural layer.

---

## Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| PTH-01 | Plain-text substrings matching an existing page name are visually highlighted in block view mode | Must |
| PTH-02 | Existing `[[PageName]]` wikilinks are NOT re-highlighted — only unlinked occurrences are candidates | Must |
| PTH-03 | Clicking / tapping a highlighted suggestion shows an inline confirmation popup with "Link as [[PageName]]" and "Dismiss" | Must |
| PTH-04 | Confirming a suggestion wraps the matched text in `[[…]]` and saves the change via the existing `onContentChange` callback | Must |
| PTH-05 | Dismissing a suggestion hides the popup without modifying content | Must |
| PTH-06 | Highlighting applies in view mode only; edit mode is unaffected (the existing `[[` autocomplete handles the edit-mode workflow) | Must |
| PTH-07 | Page name matching is case-insensitive; the replacement preserves the page name's canonical casing, not the matched casing | Must |
| PTH-08 | Tags (`#PageName`) are excluded from suggestion highlighting | Should |
| PTH-09 | Matches inside inline code spans (`` `…` ``) and markdown links (`[text](url)`) are excluded from suggestions | Should |
| PTH-10 | Suggestions are suppressed for page names shorter than 3 characters to avoid noise | Should |
| PTH-11 | The feature can be toggled on/off via a user-facing setting in Advanced Settings | Could |
| PTH-12 | Multiple non-overlapping suggestions per block are all highlighted simultaneously | Must |

---

## Non-Functional Requirements

| Attribute | Target |
|-----------|--------|
| Matching latency (p95) | < 5 ms for a single block up to 2 000 characters on JVM |
| Index build time | < 50 ms for 5 000 page names on JVM |
| Index update latency | < 10 ms from page creation/rename to index reflecting the change |
| Memory overhead | < 2 MB for a 5 000-page graph (index is a plain string list) |
| Recomposition safety | `PageNameIndex` exposed as `StateFlow<Set<String>>`; Compose reads it as `collectAsState()` |
| Backward compatibility | All existing `BlockRenderer` / `WikiLinkText` callers continue to work unchanged (new parameters are optional with default no-op values) |
| Testability | Matcher and index are plain Kotlin objects with no Compose dependency; fully unit-testable |
| KMP compliance | No platform-specific code in `commonMain`; Aho-Corasick built in pure Kotlin |

---

## Architecture Overview

### Current Data Flow (view mode)

```
Page loaded
  -> BlockList renders each Block
  -> BlockRenderer (isEditing=false) -> BlockItem -> BlockViewer
  -> WikiLinkText
  -> parseMarkdownWithStyling (MarkdownEngine.kt)
  -> AnnotatedString rendered by BasicText
  -> tap detected via pointerInput -> onLinkClick(pageName)
```

### Target Data Flow

```
Graph opened / page renamed / page created
  -> PageRepository emits updated page list
  -> PageNameIndex.rebuild(pages): Set<String>   [coroutine, background]
  -> _pageNames: MutableStateFlow<Set<String>>

Page loaded (view mode)
  -> StelekitViewModel exposes pageNames: StateFlow<Set<String>>
  -> BlockRenderer receives pageSuggestions: Set<String>          [optional, defaults emptySet()]
  -> BlockItem passes pageSuggestions down to BlockViewer
  -> WikiLinkText
  -> parseMarkdownWithStyling(text, ..., pageSuggestions)
      -> tokenizeMarkdown finds wikilinks, tags, code spans first
      -> for each InlineToken.Text gap: AhoCorasickMatcher.findAll(text, pageNames)
      -> overlapping wikilink / tag regions excluded
      -> InlineToken.PageSuggestion tokens inserted into gap
  -> AnnotatedString with PAGE_SUGGESTION annotation for each candidate span
  -> BasicText renders highlighted spans
  -> tap detected: PAGE_SUGGESTION annotation hit -> show LinkSuggestionPopup

LinkSuggestionPopup (new composable)
  -> "Link as [[PageName]]" button
      -> replaces matched substring with [[pageName]] in block content
      -> calls onContentChange(newContent, newVersion)
  -> "Dismiss" button -> hides popup, no change
```

### Component Map

```
┌──────────────────────────────────────────────────────────────────┐
│ UI Layer                                                          │
│  BlockRenderer.kt    (add pageSuggestions: Set<String> param)    │
│  BlockViewer.kt      (thread pageSuggestions to WikiLinkText)    │
│  WikiLinkText        (thread to parseMarkdownWithStyling)        │
│  MarkdownEngine.kt   (InlineToken.PageSuggestion, new annotation)│
│  LinkSuggestionPopup.kt  (new composable — confirm / dismiss)    │
└──────────────────────────┬───────────────────────────────────────┘
                           │ StateFlow<Set<String>>
┌──────────────────────────▼───────────────────────────────────────┐
│ ViewModel Layer                                                   │
│  StelekitViewModel.kt  (expose pageNames: StateFlow<Set<String>>)│
└──────────────────────────┬───────────────────────────────────────┘
                           │ getAllPages() / page events
┌──────────────────────────▼───────────────────────────────────────┐
│ Domain Layer                                                      │
│  PageNameIndex.kt  (new — coroutine-driven index, StateFlow)     │
│  AhoCorasickMatcher.kt  (new — pure Kotlin, KMP-safe)            │
└──────────────────────────┬───────────────────────────────────────┘
                           │ PageRepository.getAllPages()
┌──────────────────────────▼───────────────────────────────────────┐
│ Repository / Database Layer                                       │
│  PageRepository (interface — no change)                          │
│  SqlDelightPageRepository / InMemoryPageRepository (no change)   │
└──────────────────────────────────────────────────────────────────┘
```

---

## Dependency Graph

```
Level 0 (no blockers):
  Story 1 — Page Name Index           [new PageNameIndex + AhoCorasickMatcher]
  Story 2 — Text Matching Algorithm   [extends MarkdownEngine tokenizer]

Level 1 (requires Story 1 + Story 2):
  Story 3 — UI Highlight Rendering    [wires index + matcher into WikiLinkText]

Level 2 (requires Story 3):
  Story 4 — Click-to-Link UX         [LinkSuggestionPopup + content mutation]
```

Critical path: **Story 1 + Story 2 (parallel) → Story 3 → Story 4**

---

## Story Breakdown

---

### Story 1: Page Name Index

**Goal**: Maintain an up-to-date, case-folded set of all page names in memory, refreshed whenever the page list changes.

**Acceptance Criteria**:
- `PageNameIndex` exposes `pageNames: StateFlow<Set<String>>`
- Page names shorter than 3 characters are excluded from the index
- After `PageRepository.getAllPages()` emits a new list, the index reflects the change within 10 ms
- Page names are lowercased in the index for matching; canonical casing is preserved in a separate lookup map
- `StelekitViewModel` initialises `PageNameIndex` and exposes `pageNames` to the UI
- `InMemoryPageRepository` is used in unit tests — no database dependency in index tests

**Tasks**:

#### Task 1.1 — Implement `PageNameIndex`
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/PageNameIndex.kt`  
**Effort**: 2 h

```kotlin
class PageNameIndex(
    private val pageRepository: PageRepository,
    private val scope: CoroutineScope
) {
    // lowercase name -> canonical name
    private val _canonicalNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Lowercase keys for fast matching; callers resolve canonical casing via canonicalName(). */
    val lowercaseNames: StateFlow<Set<String>> = _canonicalNames.map { it.keys }.stateIn(
        scope, SharingStarted.Eagerly, emptySet()
    )

    fun canonicalName(lowercase: String): String? = _canonicalNames.value[lowercase]

    init {
        scope.launch {
            pageRepository.getAllPages().collect { result ->
                result.getOrNull()?.let { pages ->
                    _canonicalNames.value = pages
                        .filter { it.name.length >= MIN_NAME_LENGTH }
                        .associate { it.name.lowercase() to it.name }
                }
            }
        }
    }

    companion object {
        const val MIN_NAME_LENGTH = 3
    }
}
```

Key design notes:
- `getAllPages()` is a `Flow` that already re-emits on SQLDelight mutations — no polling needed.
- The `Map<String, String>` (lowercase -> canonical) is replaced atomically via `value =`; no locking needed because `MutableStateFlow` is thread-safe.
- Journal page names (e.g., `2026-04-12`) should be included because users may reference them.

#### Task 1.2 — Wire `PageNameIndex` into `StelekitViewModel`
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/LogseqViewModel.kt`  
**Effort**: 1 h

Add `PageNameIndex` as a constructor-injected dependency (lazy-initialised if not provided, for backward compat). Expose:

```kotlin
val pageNames: StateFlow<Set<String>> = pageNameIndex.lowercaseNames
```

Thread `pageNames` into the `AppState` or expose it as a separate `StateFlow` on the ViewModel. The latter is preferred to avoid inflating `AppState` with matching data.

#### Task 1.3 — Unit tests for `PageNameIndex`
**Files**: new `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/PageNameIndexTest.kt`  
**Effort**: 1 h

Test cases:
- Page with name length < 3 is excluded from index
- Page added to repository appears in `lowercaseNames` after `TestScope.advanceUntilIdle()`
- Page renamed: old lowercase key removed, new lowercase key present
- Page deleted: key removed from index
- `canonicalName("meeting notes")` returns `"Meeting Notes"` when that is the canonical form
- Two pages whose names are case variants (`"Tags"` / `"tags"`) — last writer wins (acceptable; document behaviour)

---

### Story 2: Text Matching Algorithm

**Goal**: Given a `Set<String>` of lowercase page names and a block's text, find all non-overlapping plain-text spans that match a page name, excluding spans that fall inside existing wikilinks, tags, code spans, or markdown links.

**Acceptance Criteria**:
- `AhoCorasickMatcher.findAll(text, lowercase page names)` returns a list of `MatchSpan(start, end, canonicalName)`
- Matches inside `[[…]]`, `#tag`, `` `code` ``, or `[text](url)` regions are excluded
- Overlapping matches are resolved by choosing the longest match (leftmost-longest greedy)
- Matching is case-insensitive; the returned `canonicalName` reflects the page's stored casing
- Performance: < 5 ms for a 2 000-character block against a 5 000-name index on JVM

**Tasks**:

#### Task 2.1 — Implement `AhoCorasickMatcher`
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcher.kt`  
**Effort**: 3 h

Pure Kotlin implementation of the Aho-Corasick string-search algorithm. The automaton is built once per set of patterns and reused across all blocks.

```kotlin
class AhoCorasickMatcher(patterns: Collection<String>) {
    // patterns assumed to be already lowercased
    fun findAll(text: String): List<IntRange>  // ranges in lowercased text
}

data class MatchSpan(val start: Int, val end: Int, val lowercasePattern: String)
```

Build contract:
- Trie construction: O(sum of pattern lengths)
- Search: O(text length + number of matches)
- The automaton is immutable after construction — safe to share across coroutines.

The `PageNameIndex` rebuilds the `AhoCorasickMatcher` whenever the page list changes and publishes the new instance via a `StateFlow<AhoCorasickMatcher?>`.

Implementation guidance:
- Use a `HashMap<Char, Int>` per node (not an array) to keep memory reasonable for Unicode page names.
- Build failure links (suffix links) with a BFS pass after trie insertion.
- The `findAll` method should return all end-positions with their matched pattern, then resolve overlaps in a post-processing pass (keep longest match at each start position).

#### Task 2.2 — Exclusion zone computation
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcher.kt` (or a companion utility)  
**Effort**: 1 h

Before running the Aho-Corasick search, compute a list of `IntRange` exclusion zones from the block content using the existing `MarkdownPatterns` regexes:

```kotlin
fun computeExclusionZones(text: String): List<IntRange> {
    val zones = mutableListOf<IntRange>()
    MarkdownPatterns.wikiLinkPattern.findAll(text).forEach { zones += it.range }
    MarkdownPatterns.tagPattern.findAll(text).forEach { zones += it.range }
    MarkdownPatterns.codePattern.findAll(text).forEach { zones += it.range }
    MarkdownPatterns.linkPattern.findAll(text).forEach { zones += it.range }
    MarkdownPatterns.imagePattern.findAll(text).forEach { zones += it.range }
    return zones.sortedBy { it.first }
}
```

After `AhoCorasickMatcher.findAll`, filter out any `MatchSpan` whose range overlaps any exclusion zone.

#### Task 2.3 — Integrate matcher into `MarkdownEngine`
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`  
**Effort**: 2 h

Add `InlineToken.PageSuggestion(val displayText: String, val canonicalPageName: String)` to the sealed class.

Extend `tokenizeMarkdown` to accept an optional `AhoCorasickMatcher?` parameter (default `null`). When non-null:

1. After the existing overlap-resolution pass builds `selected` matches, collect all gap `InlineToken.Text` ranges.
2. For each gap, run the matcher on the lowercased gap text.
3. Translate gap-relative positions back to absolute positions in `text`.
4. Filter using exclusion zones (wikilinks / tags / code already captured in `selected`).
5. Insert `InlineToken.PageSuggestion` tokens into the gap, splitting the remaining `Text` token around them.

The `parseMarkdownWithStyling` function gains:
```kotlin
fun parseMarkdownWithStyling(
    text: String,
    linkColor: Color,
    textColor: Color,
    blockRefBackgroundColor: Color = ...,
    resolvedRefs: Map<String, String> = emptyMap(),
    codeBackground: Color = ...,
    suggestionMatcher: AhoCorasickMatcher? = null,        // NEW — optional
    suggestionColor: Color = Color.Unspecified             // NEW — optional
): AnnotatedString
```

The `PageSuggestion` token renders with a distinct annotation tag `PAGE_SUGGESTION_TAG = "PAGE_SUGGESTION"` and a subtle visual style (dashed underline or light background tint — decided in Story 3).

#### Task 2.4 — Unit tests for `AhoCorasickMatcher`
**Files**: new `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcherTest.kt`  
**Effort**: 2 h

Test table:
- Single pattern match in plain text
- Multiple non-overlapping matches in one string
- Overlapping patterns: longer match wins
- Match inside `[[wikilink]]` region — excluded
- Match inside `` `code` `` — excluded
- Match inside `#tag` — excluded
- Case-insensitive: pattern `"meeting notes"`, text `"Meeting Notes today"` — match found, canonical name preserved
- Pattern at start of string, at end of string, exact-string match
- Page name containing `/` (namespace separator like `"Cordless/Corded"`)
- Empty pattern set — no matches, no exception
- Empty text — no matches, no exception
- Unicode page name (e.g., `"über die natur"`)

---

### Story 3: UI Highlight Rendering

**Goal**: `WikiLinkText` / `BlockViewer` / `BlockRenderer` visually distinguish page-name suggestion spans using the `PAGE_SUGGESTION_TAG` annotation, with a dashed underline style consistent with the SteleKit theme.

**Acceptance Criteria**:
- Suggestion spans render with a dashed underline and a very light tint of `linkColor` (alpha 0.08–0.12) in view mode
- Existing wikilinks, tags, and bold/italic formatting are visually unaffected
- Suggestion rendering is disabled when `pageSuggestions` is `null` or empty (no performance cost)
- The `BlockRenderer` public API gains `pageSuggestions: Set<String> = emptySet()` — all existing call sites compile unchanged
- `WikiLinkText` gains `suggestionMatcher: AhoCorasickMatcher? = null` — all existing call sites unchanged
- The `remember` key on `parseMarkdownWithStyling` includes `suggestionMatcher` identity to avoid stale renders

**Tasks**:

#### Task 3.1 — Thread `AhoCorasickMatcher` from ViewModel to `WikiLinkText`
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockRenderer.kt`, `BlockViewer.kt`, `BlockItem.kt`  
**Effort**: 2 h

Add optional parameters through the call chain:

```
BlockRenderer(pageSuggestions: Set<String> = emptySet(), ...)
  -> BlockItem(pageSuggestions: Set<String> = emptySet(), ...)
    -> BlockViewer(pageSuggestions: Set<String> = emptySet(), ...)
      -> WikiLinkText(suggestionMatcher: AhoCorasickMatcher? = null, ...)
```

`BlockViewer` constructs the `AhoCorasickMatcher` from `pageSuggestions` via `remember(pageSuggestions)`:

```kotlin
val suggestionMatcher = remember(pageSuggestions) {
    if (pageSuggestions.isEmpty()) null
    else AhoCorasickMatcher(pageSuggestions)
}
```

Note: the `AhoCorasickMatcher` is rebuilt only when the page-name set changes, not on every recomposition. Because `pageSuggestions` is a `Set<String>`, Compose's structural equality check on the `remember` key prevents unnecessary rebuilds.

#### Task 3.2 — Render `PageSuggestion` tokens in `MarkdownEngine`
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`  
**Effort**: 1 h

In `parseMarkdownWithStyling`, add a case for `InlineToken.PageSuggestion`:

```kotlin
is InlineToken.PageSuggestion -> {
    val start = length
    withStyle(
        SpanStyle(
            textDecoration = TextDecoration.Underline,    // dashed via DrawStyle workaround or solid underline
            color = textColor.copy(alpha = 0.85f),
            background = suggestionColor.copy(alpha = 0.08f)
        )
    ) { append(token.displayText) }
    addStringAnnotation(PAGE_SUGGESTION_TAG, token.canonicalPageName, start, length)
}
```

The `suggestionColor` defaults to `Color.Unspecified`; callers pass `linkColor.copy(alpha = 1f)` so the tint matches the link palette.

Note on dashed underline: Compose `SpanStyle` does not natively support dashed underlines. Use a solid underline with reduced alpha, or a `drawBehind` modifier on the container — document the limitation and accept solid underline for v1, with a deferred enhancement note.

#### Task 3.3 — Handle `PAGE_SUGGESTION_TAG` taps in `WikiLinkText`
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockViewer.kt`  
**Effort**: 1 h

In `WikiLinkText.pointerInput`, add priority handling for `PAGE_SUGGESTION_TAG` before the existing `onClick` fallback:

```kotlin
val suggestion = annotations.firstOrNull { it.tag == PAGE_SUGGESTION_TAG }

when {
    wikiLink != null -> onLinkClick(wikiLink.item)
    tag != null -> onLinkClick(tag.item)
    suggestion != null -> onSuggestionClick(suggestion.item, tapOffset)   // NEW
    link != null -> onUrlClick(link.item)
    url != null -> onUrlClick(url.item)
    else -> onClick()
}
```

`WikiLinkText` gains two new optional parameters:
```kotlin
onSuggestionClick: (canonicalPageName: String, tapOffset: Offset) -> Unit = { _, _ -> },
```

The `tapOffset` is forwarded to position the confirmation popup in Story 4.

#### Task 3.4 — Connect ViewModel `pageNames` flow to `BlockRenderer` call sites
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsScreen.kt` (or equivalent block-list screens)  
**Effort**: 1 h

At the call sites that render `BlockRenderer`, collect `viewModel.pageNames` with `collectAsState()` and pass the resulting `Set<String>` as `pageSuggestions`. The collection happens once per screen, not per block.

```kotlin
val pageNames by viewModel.pageNames.collectAsState()

// Inside BlockList / LazyColumn:
BlockRenderer(
    block = block,
    pageSuggestions = pageNames,
    ...
)
```

---

### Story 4: Click-to-Link UX

**Goal**: When the user taps a page-name suggestion, a small popup with "Link as [[PageName]]" and "Dismiss" buttons appears near the tap position. Confirming wraps the match in `[[…]]` and saves via the existing `onContentChange` pipeline.

**Acceptance Criteria**:
- The popup appears within 100 ms of the tap
- The popup is dismissed by tapping outside it, pressing Escape (desktop), or tapping "Dismiss"
- Confirming replaces exactly the matched substring with `[[CanonicalPageName]]` (not the matched casing)
- Multiple suggestions in the same block each show their own popup on tap (one at a time)
- The block transitions to the view-mode rendered version immediately after confirmation (the existing reactive update pipeline handles this)
- The feature setting "Show page-name suggestions" (PTH-11) gates this entire flow when disabled

**Tasks**:

#### Task 4.1 — Implement `LinkSuggestionPopup`
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/LinkSuggestionPopup.kt`  
**Effort**: 2 h

```kotlin
@Composable
fun LinkSuggestionPopup(
    canonicalPageName: String,
    anchorOffset: Offset,             // from tapOffset in WikiLinkText
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
)
```

Use Compose `Popup` with `popupPositionProvider` anchored to `anchorOffset`. Content:
- Title: `"Link as [[${canonicalPageName}]]"` (one line, medium weight)
- Two buttons: filled "Link" and outlined "Dismiss"
- Dismiss on outside tap via `onDismissRequest = onDismiss`
- Desktop: consume `Escape` key event to call `onDismiss`

Keep the popup surface minimal — a `Surface` with `RoundedCornerShape(8.dp)` and `elevation = 4.dp`, padded to 12 dp.

#### Task 4.2 — Wire popup into `BlockItem` / `BlockViewer`
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`, `BlockViewer.kt`  
**Effort**: 2 h

`BlockItem` holds popup state:
```kotlin
var suggestionState by remember { mutableStateOf<SuggestionState?>(null) }

data class SuggestionState(
    val canonicalPageName: String,
    val matchedText: String,
    val anchorOffset: Offset
)
```

`WikiLinkText` calls `onSuggestionClick(canonicalPageName, tapOffset)` which sets `suggestionState` in `BlockItem`. When non-null, `LinkSuggestionPopup` is displayed as an overlay.

On confirm: `BlockItem` computes `newContent = block.content.replaceFirst(matchedText, "[[$canonicalPageName]]")` and calls `onContentChange(newContent, onLocalVersionIncrement())`. Sets `suggestionState = null`.

On dismiss: sets `suggestionState = null`.

**Important safety note**: `replaceFirst` is fragile if the matched text appears multiple times. Instead, carry the `start` and `end` character offsets from the `MatchSpan` through to `SuggestionState` and perform a positional replacement:

```kotlin
val newContent = block.content.substring(0, matchStart) +
    "[[$canonicalPageName]]" +
    block.content.substring(matchEnd)
```

This requires threading `matchStart` and `matchEnd` through `WikiLinkText` → `onSuggestionClick` → `SuggestionState`. Update `WikiLinkText` signature accordingly.

To recover `matchStart`/`matchEnd` from the `AnnotatedString`, store them in the annotation value using a compact encoding: `"pageName|startOffset|endOffset"` or a dedicated annotation tag pair.

#### Task 4.3 — Add "Show page-name suggestions" setting (PTH-11)
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`, settings screen  
**Effort**: 1 h

Add `showPageNameSuggestions: Boolean = true` to `AppState`. Persist via `PlatformSettings`. Wire the checkbox in Advanced Settings. When `false`, `BlockRenderer` receives `pageSuggestions = emptySet()` regardless of the index content.

#### Task 4.4 — Integration test for suggestion-to-link flow
**Files**: new `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/integration/PageTermHighlightingTest.kt`  
**Effort**: 2 h

Using `InMemoryPageRepository` and `InMemoryBlockRepository`:
1. Create pages "Meeting Notes" and "Project Alpha"
2. Create a block with content `"discussed Project Alpha in Meeting Notes today"`
3. Build `PageNameIndex`, verify both names are indexed
4. Run `AhoCorasickMatcher.findAll` — verify two spans found
5. Simulate confirm on "Project Alpha" span — verify block content becomes `"discussed [[Project Alpha]] in Meeting Notes today"`
6. Run matcher again on new content — verify "Project Alpha" is now in an exclusion zone (inside `[[…]]`) and not re-suggested

---

## Known Issues

### Bug 1: `replaceFirst` corrupts content when matched text appears multiple times [SEVERITY: High]

**Description**: If a block contains "taxes taxes taxes" and the user page-name-links the second occurrence, `replaceFirst("taxes", "[[Taxes]]")` always replaces the first occurrence, not the one the user tapped.

**Mitigation**: Store `matchStart` / `matchEnd` absolute character offsets in `SuggestionState` and perform a positional substring replacement (see Task 4.2). Add a test case: block with repeated page-name occurrence, confirm on second occurrence.

**Files Affected**: `BlockItem.kt`, `LinkSuggestionPopup.kt`, `BlockViewer.kt`

---

### Bug 2: `AhoCorasickMatcher` rebuild cost on large graphs [SEVERITY: Medium]

**Description**: The `AhoCorasickMatcher` is rebuilt in `BlockViewer` via `remember(pageSuggestions)`. If `pageNames` emits a new `Set<String>` on every page view (because `getAllPages()` is re-queried), every open block rebuilds its matcher. On a 5 000-page graph, construction is ~5–10 ms per block — multiplied by 20 visible blocks this is 100–200 ms of wasted work per navigation.

**Mitigation**:
- Lift matcher construction to a single `remember` at the `BlockList` or page-view level, not per block. Pass the built `AhoCorasickMatcher?` (not the raw `Set<String>`) into `BlockViewer`.
- In `PageNameIndex`, use `distinctUntilChanged()` on the `getAllPages()` flow to suppress no-op emissions.
- Add a benchmark test that measures matcher construction time for 1 000 / 5 000 / 10 000 names.

**Files Affected**: `PageNameIndex.kt`, `BlockViewer.kt`, call sites in screen composables

---

### Bug 3: Popup positioned off-screen on small viewports [SEVERITY: Medium]

**Description**: The `Popup` anchored to `tapOffset` may render outside the visible window area on narrow screens (phone portrait) or when tapping near the edge of the screen.

**Mitigation**: Use `PopupPositionProvider` that clamps the popup to the window bounds. Compose's `DropdownMenu` internally uses `DropdownMenuPositionProvider` which already handles this — reuse that pattern. Add a UI test that simulates a tap near the right edge and verifies the popup fits inside the window.

**Files Affected**: `LinkSuggestionPopup.kt`

---

### Bug 4: Race condition between index update and block render [SEVERITY: Low]

**Description**: When a new page is created, `PageNameIndex` updates `lowercaseNames` asynchronously. A block rendered before the update completes will not highlight the new page name. On the next recomposition (triggered by the `StateFlow` update), the suggestion appears.

**Current exposure**: One missed highlight per page creation event, corrected within one recomposition cycle.

**Mitigation**: This is acceptable — the `StateFlow` update drives recomposition automatically. Document the one-frame delay in code comments. No additional handling needed.

**Files Affected**: `PageNameIndex.kt`

---

### Bug 5: Page names containing regex metacharacters break exclusion-zone computation [SEVERITY: Low]

**Description**: `computeExclusionZones` uses `MarkdownPatterns.wikiLinkPattern` regex. If a page name contains characters that are special in the block content regex (e.g., `(`, `)`, `[`), the exclusion zone computation remains unaffected — the exclusion zones are computed from the raw block text, not from page names. The Aho-Corasick matcher uses literal string comparison, not regex. No issue here.

However, if a page name itself contains `]]` (which `Validation.validateName` does not prevent), the wikilink `[[Page]]Name]]` render would break. This is a pre-existing issue in the model layer.

**Mitigation**: Add a guard in `PageNameIndex` that excludes names containing `[[` or `]]`. The existing `Validation.validateName` already rejects backslashes but not brackets — document this gap for a follow-up fix in `Validation`.

**Files Affected**: `PageNameIndex.kt`, `Models.kt` (future follow-up)

---

### Bug 6: Journal date pages generating excessive suggestions [SEVERITY: Low]

**Description**: Journal page names like `2026-04-12` are 10 characters long (above the 3-char minimum) and will match any block containing date strings. A block like `"report due 2026-04-12, submitted 2026-04-13"` would generate two suggestion highlights pointing to specific journal pages.

**Mitigation**: Add an optional `excludeJournalPages: Boolean = true` flag to `PageNameIndex` that filters out pages where `isJournal == true`. Default `true` for view-mode suggestions. Expose as a user setting if there is demand. Document the trade-off: linking to journal pages is valid, but the noise of date-string matching usually outweighs the value.

**Files Affected**: `PageNameIndex.kt`, `AppState.kt`

---

## ADR Index

| ADR | Title | File |
|-----|-------|------|
| ADR-005 | Page name index and matching strategy | `project_plans/stelekit/decisions/ADR-005-page-name-index-strategy.md` |
| ADR-006 | Suggestion highlight rendering approach | `project_plans/stelekit/decisions/ADR-006-suggestion-highlight-rendering.md` |

---

## Test Strategy

### Unit Tests (no Compose, no database)

| Test Class | Coverage |
|------------|----------|
| `PageNameIndexTest` | Index build, min-length filter, journal exclusion, canonical casing lookup, incremental update from Flow |
| `AhoCorasickMatcherTest` | All match cases: single, multi, overlapping, exclusion zones, Unicode, empty inputs |
| `MarkdownEnginePageSuggestionTest` | `parseMarkdownWithStyling` with suggestion matcher: annotation tags present at correct offsets; suggestions absent inside wikilinks / tags / code |

### Integration Tests (in-memory repositories, no Compose)

| Test Class | Coverage |
|------------|----------|
| `PageTermHighlightingTest` | End-to-end flow: create pages, build index, match block content, simulate confirm, verify content mutation, verify re-match exclusion |

### UI Tests (Compose test harness, JVM)

| Test Class | Coverage |
|------------|----------|
| `LinkSuggestionPopupTest` | Popup renders with correct page name label; confirm calls `onConfirm`; dismiss calls `onDismiss`; outside tap dismisses |

### Manual Acceptance Checklist

- [ ] Open a page containing text that matches an existing page name — suggestion highlight visible
- [ ] Tap suggestion — popup appears with correct "Link as [[PageName]]" label
- [ ] Tap "Link" — content updates to include `[[PageName]]`; the span is no longer highlighted (now a wikilink)
- [ ] Tap "Dismiss" — popup disappears, content unchanged
- [ ] Open a block that already has `[[PageName]]` — no double-highlight on the wikilink
- [ ] Block containing `#tag` where tag matches a page name — no suggestion highlight inside the tag
- [ ] Block containing `` `code span` `` matching a page name — no suggestion inside code
- [ ] Disable "Show page-name suggestions" in Advanced Settings — no highlights appear
- [ ] Create a new page — existing open blocks update their suggestions within one second
- [ ] Rename a page — old name suggestions disappear, new name suggestions appear
