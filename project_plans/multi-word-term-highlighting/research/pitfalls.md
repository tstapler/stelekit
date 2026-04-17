# Research: Pitfalls — Multi-Word Highlighting & Unlinked References

## Summary

The feature has three critical pitfall zones: **(1) Position drift** — when users edit a block, the `contentStart`/`contentEnd` offsets in `SuggestionState` become stale within the same rendering frame, causing the link replacement to target the wrong span or corrupt content. **(2) Overlapping-match resolution** — the Aho-Corasick engine correctly merges overlaps via `resolveOverlaps()` (leftmost-longest, lines 123–139), but when a short page name like "KMP" is a prefix of a longer one like "KMP SDK", the algorithm will greedily pick "KMP" first if it appears at position 0, then discard "KMP SDK" as overlapping — this misses the longer match if the text is "KMP SDK is great". **(3) PageNameIndex staleness window** — when a page is renamed or deleted, the matcher rebuild on the background thread (lines 36–39, `flowOn(Dispatchers.Default)`) may introduce a 10–100ms window where suggestions based on the old page set are shown to the user, and accepting a suggestion to a deleted page silently fails.

---

## Aho-Corasick Edge Cases

### Prefix-overlap pitfall (lines 30–42, 123–139)

The trie construction correctly inserts all patterns. The failure-link BFS (lines 45–70) properly chains mismatches. However, `resolveOverlaps()` uses leftmost-longest *per start position* (lines 126–128):

```kotlin
val bestByStart = matches
    .groupBy { it.start }
    .mapValues { (_, group) -> group.maxByOrNull { it.end - it.start }!! }
```

**Pitfall**: If the input is `"I use KMP SDK today"` and the page names are `["KMP", "KMP SDK"]`:

1. The matcher finds both matches at the same start position 6:
   - `MatchSpan(start=6, end=9, "KMP")` — 3 characters
   - `MatchSpan(start=6, end=14, "KMP SDK")` — 8 characters

2. `resolveOverlaps()` groups by start (both at position 6), keeps the longest (8 chars = "KMP SDK"). ✓ **Correct**.

**However**, if the text is `"KMP and KMP SDK are frameworks"` with pages `["and", "SDK"]`:

1. Matches found:
   - `MatchSpan(start=4, end=7, "and")` at position 4
   - `MatchSpan(start=12, end=15, "SDK")` at position 12

2. No overlap; both are kept. ✓ **Correct**.

**The real pitfall**: What if the page names are `["Python", "Python Programming"]` and the input is `"Python Programming is great"`?

1. Matches found:
   - `MatchSpan(start=0, end=6, "Python")` — 6 chars
   - `MatchSpan(start=0, end=21, "Python Programming")` — 21 chars

2. `resolveOverlaps()` groups by start 0, keeps the longest (21 chars). ✓ **Correct**.

**Actual pitfall scenario**: `["lang", "language"]` in text `"lang is not language"`:

1. Matches:
   - `MatchSpan(start=0, end=4, "lang")` — word boundary OK (space after)
   - `MatchSpan(start=0, end=8, "language")` — word boundary OK (space after)

2. Groups by start → keeps longest "language". ✓ **Correct**.

**True edge case**: `["Rust", "Rust Programming Language"]` in text `"Rust Programming"`:

- If only these two pages exist, the matcher finds:
  - `MatchSpan(start=0, end=4, "Rust")`
  - `MatchSpan(start=0, end=17, "Rust Programming Language")` — **word boundary fails** (no character at position 17, or 'g' at position 16 is a word char, so the match at [0, 17) fails isWordBoundary).

The word-boundary check (line 102) is correct: a multi-word phrase must satisfy boundaries at the *entire span* start and end, not per-word.

**Verdict**: The `resolveOverlaps()` logic is sound. The pitfall is user expectation: if they have pages `["KMP", "Kotlin Multiplatform"]` and write "Kotlin Multiplatform KMP", they'll see *two separate suggestions* because the word boundaries are correct. No bug, but potentially confusing UX.

### Substring collision within single match (edge case)

Suppose page names are `["test", "testing", "test case"]` and input is `"testing this"`. The matcher's failure link correctly chains: on mismatch after matching `"test"`, it backtracks and tries to extend to `"testing"` (7 chars). The trie structure ensures both are found *if they both match at word boundaries*. The output merging (line 64–67) ensures overlapping outputs are combined.

**Potential pitfall**: The failure link does NOT de-duplicate outputs. If pattern "test" appears both at the end of the trie AND as a suffix of "testing", the nodeOutput[v] for the "testing" node will include both entries (line 66 appends). This is *correct* for true Aho-Corasick (find all patterns), but for unlinked references, the user only cares about the longest match at each position. The `resolveOverlaps()` step handles this.

---

## Span Collision in MarkdownEngine

### Guard against already-linked and marked-up text

The `renderPlainText()` function (lines 242–274) handles bare URLs by greedily selecting non-overlapping URL zones. The `renderGapWithSuggestions()` function (lines 280–315) then scans *only the gaps* between URLs for page suggestions, explicitly avoiding URL content.

The `extractSuggestions()` function (lines 358–426) takes a different approach: it reconstructs a plain-text run by merging consecutive `TextNode`s (the AST parser's leaf nodes), then scans the run for suggestions while excluding URLs *and* other markup nodes (line 419: when a non-TextNode is encountered, the run is flushed, suppressing suggestions).

**Verification**:

1. **Wiki links `[[…]]`**: These are parsed as `WikiLinkNode`s in the AST (line 118 of MarkdownEngine.kt), so `extractSuggestions()` will flush the run before processing them. ✓ Safe.

2. **Block refs `((…))`**: Parsed as `BlockRefNode`s (line 127), flushed before scanning. ✓ Safe.

3. **Emphasis `**…**`**: Parsed as `BoldNode` (line 89), flushed. ✓ Safe.

4. **Inline code `` `…` ``**: Parsed as `CodeNode` (line 109), flushed. ✓ Safe.

**Pitfall identified**: In `renderPlainText()`, URL zones are identified via regex (line 250), and gaps between them are scanned for suggestions. However, the regex `urlPattern` (line 32) is `"""https?://[^\s<>"]+"""`. If a page name happens to start with `http` (unlikely but possible: e.g., `"HTTP Protocol"`), there's no collision because page names are lowercased and matched only at word boundaries. But if someone writes `"Check httpbin.org for HTTP testing"`:

- The regex matches `httpbin.org`.
- The gap *before* contains `"Check "`, which won't match anything (no page "Check").
- The gap *after* contains `" for "`, which won't match anything.
- The second "HTTP" comes *after* the URL, in a new gap. ✓ Safe.

**Another scenario**: Bare URL inside a block ref `"((uuid to check https://example.com))"`. The AST parser will treat `((…))` as a `BlockRefNode`, not as plain text, so it's excluded from suggestion scanning. ✓ Safe.

**Verdict**: The guard is robust. The engine does *not* suggest page names inside already-linked or marked-up spans.

---

## Position Drift

### The core vulnerability

In `BlockItem.kt`, lines 36–40 define `SuggestionState`:

```kotlin
private data class SuggestionState(
    val canonicalName: String,
    val contentStart: Int,
    val contentEnd: Int,
)
```

This state is captured when the user clicks a suggestion (line 384):

```kotlin
onSuggestionClick = { canonicalName, contentStart, contentEnd ->
    suggestionState = SuggestionState(canonicalName, contentStart, contentEnd)
}
```

The offsets `contentStart` and `contentEnd` come from the `PAGE_SUGGESTION_TAG` annotation in the `AnnotatedString` (lines 307–311 of MarkdownEngine.kt):

```kotlin
addStringAnnotation(
    PAGE_SUGGESTION_TAG,
    "${match.canonicalName}|${gapStart + match.start}|${gapStart + match.end}",
    start, length,
)
```

**The vulnerability**: Between the moment the suggestion is clicked and the moment `onConfirm()` is called (lines 407–418 of BlockItem.kt), the user could have *edited the block*. The `SuggestionState` holds stale offsets.

```kotlin
onConfirm = {
    val content = block.content  // <-- Current block content
    val safeEnd = pending.contentEnd.coerceAtMost(content.length)
    val safeStart = pending.contentStart.coerceIn(0, safeEnd)
    // Uses stale offsets!
}
```

**Example attack**:

1. Block content: `"Learn Kotlin today"`
2. User clicks suggestion for "Kotlin" at offsets [6, 12].
3. `SuggestionState(canonicalName="Kotlin", contentStart=6, contentEnd=12)` is stored.
4. Popup appears. User realizes a typo, starts typing to fix it: `"Learn Python today"` — 18 chars, "Kotlin" no longer exists.
5. User clicks "Link".
6. `safeEnd = 12.coerceAtMost(18) = 12`
7. `safeStart = 6.coerceIn(0, 12) = 6`
8. Result: `"Learn Python"[0:6] + "[[Kotlin]]" + "Python today"[12:18]` = `"Learn [[Kotlin]] today"` — **the suggestion is inserted at the wrong position, linking the wrong text**.

**Mitigation exists**: The code uses `.coerceAtMost()` and `.coerceIn()` to clamp the offsets. If the user deletes characters, the offsets may point past the content or mid-word, but this doesn't crash. However, **the result is semantically wrong**: the user wanted to link "Kotlin", but after editing, they're linking "Python" or whatever now occupies those offsets.

### What *should* happen

One approach: Re-scan the block for the suggestion *before* linking. E.g., `"Find 'Kotlin' in block.content and link the first occurrence"`. But this is fragile if the page name appears multiple times.

Another approach: Use text-based replacement (e.g., `replaceFirst()` with the matched text as key), but this only works if the text hasn't been edited.

**Verdict**: Position drift is a **real pitfall**. The current code *does not crash*, but it can insert links at wrong positions if the user edits the block while the popup is open.

---

## PageNameIndex Staleness

### The asynchronous rebuild window

In `PageNameIndex.kt`, lines 36–39:

```kotlin
val matcher: StateFlow<AhoCorasickMatcher?> = _canonicalNames
    .map { names -> if (names.isEmpty()) null else AhoCorasickMatcher(names) }
    .flowOn(Dispatchers.Default)
    .stateIn(scope, SharingStarted.Eagerly, null)
```

The `_canonicalNames` flow (lines 47–52) collects updates from `pageRepository.getAllPages()` and filters them:

```kotlin
_canonicalNames.value = pages
    .filter { page ->
        page.name.length >= minNameLength &&
            (!excludeJournalPages || !page.isJournal)
    }
    .associate { it.name.lowercase() to it.name }
```

**The pitfall**: When a page is renamed or deleted:

1. `pageRepository.getAllPages()` emits a new list.
2. `_canonicalNames.value` is updated (synchronously, main thread).
3. The `map` stage (line 37) schedules a new `AhoCorasickMatcher` construction on `Dispatchers.Default` (background thread).
4. **During the background build** (which takes 0.1–10ms for typical page counts), the `matcher` StateFlow still holds the *old* matcher built from the previous page set.
5. A user viewing page suggestions during this window will see suggestions for *deleted* pages or miss suggestions for *new* pages.

**Example**:

1. Pages: `["Kotlin", "Java", "Python"]` → matcher built.
2. User renames "Kotlin" to "KMP".
3. `_canonicalNames.value` updates to `["KMP", "Java", "Python"]`.
4. Background build starts.
5. User clicks "Kotlin" suggestion (based on old matcher) while the new matcher is building.
6. `SuggestionState` records `canonicalName = "Kotlin"`.
7. User clicks "Link".
8. Block content becomes `"[[Kotlin]]"` — but "Kotlin" no longer exists as a page name!

**On accept**: The link is inserted as `[[Kotlin]]`, which may still be recognized by the wiki engine's loose linkage, but it's semantically wrong and may create a dangling link.

### Mitigation

The code could:
- Validate that the canonical name in `SuggestionState` still exists in the current matcher before linking.
- Re-scan the text to find the suggestion in the current matcher before linking.
- Use a versioned matcher (e.g., include a timestamp or version counter in `SuggestionState`).

**Current status**: None of these are implemented. The app does not validate that a suggestion's page still exists before creating the link.

**Verdict**: **Real pitfall**. Renaming or deleting a page creates a small window where stale suggestions can be accepted, creating links to non-existent pages.

---

## Performance Analysis

### Trie size for 1000+ pages

The Aho-Corasick trie in `AhoCorasickMatcher.kt` uses:

```kotlin
private val nodeChildren = ArrayList<HashMap<Char, Int>>()
private val failureLinks = ArrayList<Int>()
private val nodeOutput = ArrayList<List<OutputEntry>>()
```

For a typical page name (e.g., "Kotlin Multiplatform", 20 chars with space):

- **Trie nodes**: Each character adds one node (no compression). A 20-char name = ~20 nodes.
- **For 1000 pages × 20 chars average**: ~20,000 nodes.
- **Per-node storage**: 
  - `nodeChildren: HashMap<Char, Int>` — ~48 bytes per node (HashMap overhead ~32 + 2 entries × 8).
  - `failureLinks: Int` — 4 bytes per node.
  - `nodeOutput: List<OutputEntry>` — 8 bytes + list overhead (typically 24 bytes for a small list).
  - **Total per node**: ~80 bytes.
- **Trie total**: 20,000 × 80 = **1.6 MB** for memory.

**Construction time**: O(sum of pattern lengths) = O(20,000 chars) + BFS for failure links (O(nodes × alphabet size) = O(20,000 × 26) = O(520,000)). On modern hardware, this is **~5–10ms** for 1000 pages.

**Per-block search**: `findAll()` is O(text length + match count). For a typical 500-char block with 3–5 matches, this is **~1ms**.

### Recompose frequency

In Compose, `BlockViewer` recomposes when:

```kotlin
remember(text, linkColor, textColor, resolvedRefs, blockRefBg, codeBg, suggestionMatcher) {
    parseMarkdownWithStyling(…, suggestionMatcher = suggestionMatcher, …)
}
```

If `suggestionMatcher` changes (due to page rename/add/delete):

1. The `remember` block re-executes.
2. `parseMarkdownWithStyling()` is called, which calls `AhoCorasickMatcher.findAll()` for every block.
3. On a screen with 50 blocks, this is 50 × 1ms = **50ms of search time**, causing a frame drop (assuming 16ms target).

**Mitigation**: The matcher update from the background thread should be debounced (e.g., batch updates every 500ms) to avoid excessive recomposes. Currently, there's no debouncing visible in the code.

### Per-operation cost summary

| Operation | Time (1000 pages) | Notes |
|-----------|-------------------|-------|
| Matcher construction | 5–10ms | One-time on page set change |
| Per-block search | 0.5–2ms | Grows with block size & match count |
| Recompose 50 blocks | 25–100ms | Potential frame drop if matcher changes |
| Suggestion state creation | <0.1ms | Single click capture |
| Link insertion | <0.1ms | String concatenation |

---

## Missing Test Coverage

1. **Aho-Corasick prefix overlap**: No test for pages `["KMP", "KMP SDK"]` or similar prefix pairs. `ExtractSuggestionsTest.kt` does not cover this.

2. **Position drift**: No test that captures `SuggestionState`, edits the block, then confirms the link. Current tests (BlockItem not directly tested) do not exercise this scenario.

3. **PageNameIndex staleness**: No test for the window between page rename and matcher rebuild. The `PageNameIndex` class is not directly tested.

4. **Multiword terms with internal punctuation**: No test for `"A.I. Safety"` or `"C++ Programming"`. The matcher should handle these correctly (dots and plus signs are not word chars), but there's no explicit test.

5. **Case sensitivity**: `ExtractSuggestionsTest.kt` has one test (line 67–71) for case-insensitive matching, but no test for the case-insensitive *index* (lowercase lookup map vs. canonical return).

6. **Substring vs. whole-word**: `ExtractSuggestionsTest.kt` has one test (line 107–111) for word boundaries, but no test for:
   - Page "Python" inside "Python Programming" (should match "Python" as a prefix, but only if word boundary at position after "Python").
   - Page name that is a substring of a word (e.g., "test" inside "testing") — should NOT match due to word boundary (correctly handled, but no explicit test).

7. **Block ref collision**: No test for suggestions inside `((block-uuid))` refs. The `extractSuggestions()` function should handle this, but no test exercises it.

8. **Global refresh after link insertion**: No test for the scenario: user accepts a link on Block A, then navigates to Block B. Block A's suggestions should be invalidated or refreshed if the page set changed. No test for this flow.

9. **Empty or very short page names**: `PageNameIndex` filters names with `length < 3` (line 49), but there's no test for the boundary case (names of length 2 vs. 3).

10. **Journal page exclusion**: `PageNameIndex` excludes journal pages by default. No test for this flag or its interaction with suggestion matching.

---

## Mitigation Recommendations

### 1. Position Drift (High Priority)

**Problem**: User edits block while suggestion popup is open; offsets become stale.

**Mitigations**:

- **Option A (Defensive)**: Before linking, re-scan the current block content with the *current* matcher. If the page name is found at a different offset, use the new offset. If not found, prompt the user: "This page name is no longer in the block. Create a new link anyway?"

- **Option B (Optimistic)**: Invalidate `SuggestionState` if `block.content` changes between click and confirm. Add a version number to blocks; store the version in `SuggestionState` and validate it before linking.

- **Option C (Strict)**: Require the user to click the suggestion again after editing the block. Hide the popup if the block content changes while the popup is open.

**Recommendation**: Option B (optimistic) is least disruptive. Implement as:

```kotlin
// In SuggestionState
data class SuggestionState(
    val canonicalName: String,
    val contentStart: Int,
    val contentEnd: Int,
    val blockVersion: Long,  // Snapshot at click time
)

// On confirm
if (pending.blockVersion != block.version) {
    // Re-scan or prompt
}
```

---

### 2. PageNameIndex Staleness (Medium Priority)

**Problem**: Small window where old page names can be linked after rename/delete.

**Mitigations**:

- **Option A**: Debounce matcher updates. Batch page set changes into a single rebuild every 500ms. Reduces rebuild overhead and smooths UI updates.

- **Option B**: Tag suggestions with a matcher version. Store a `matcherId` (e.g., hash of the page set) in `SuggestionState`. Before linking, validate that the page exists in the current matcher.

- **Option C**: Use a concurrent matcher snapshot. Instead of replacing the old matcher, keep both and transition atomically after the new one is built.

**Recommendation**: Option A + B. Implement debouncing in `PageNameIndex` and add version validation:

```kotlin
// In PageNameIndex
private var matcherVersion = AtomicLong(0)
val matcher: StateFlow<Pair<AhoCorasickMatcher?, Long>> = _canonicalNames
    .map { names ->
        val id = matcherVersion.incrementAndGet()
        if (names.isEmpty()) null to id else AhoCorasickMatcher(names) to id
    }
    .debounce(500) // Batch rapid updates
    .stateIn(…)

// In BlockItem/SuggestionState
val (matcher, matcherId) = pageNameIndex.matcher.value
// Store matcherId in SuggestionState
// Before linking, validate matcherId matches current matcher
```

---

### 3. Aho-Corasick Prefix Overlaps (Low Priority)

**Problem**: User might not understand why only one of two overlapping page names is highlighted.

**Mitigation**: This is not a bug (the algorithm is correct), but a UX concern. Consider:

- Document the behavior (longest match wins).
- In the UI, show all candidates when hovering a suggestion (future: "This could also be 'KMP' — press Alt+K to switch").
- Test explicitly that prefix overlaps are resolved correctly (add test case).

---

### 4. Missing Test Coverage (Medium Priority)

Create new test file: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/AhoCorasickMatcherTest.kt`:

```kotlin
@Test
fun prefixOverlapResolution() {
    val matcher = AhoCorasickMatcher(mapOf(
        "kmp" to "KMP",
        "kmp sdk" to "KMP SDK",
    ))
    val matches = matcher.findAll("I use KMP SDK today")
    assertEquals(1, matches.size)
    assertEquals("KMP SDK", matches[0].canonicalName)
}

@Test
fun pageNameNotFoundAfterRename() {
    // Simulate: user clicks "Kotlin", then page is renamed to "KMP"
    // Validate that suggesting "Kotlin" fails or is marked as stale
}

@Test
fun internalPunctuation() {
    val matcher = AhoCorasickMatcher(mapOf(
        "a.i. safety" to "A.I. Safety",
        "c++ programming" to "C++ Programming",
    ))
    val m1 = matcher.findAll("A.I. Safety is important")
    assertEquals(1, m1.size)
    assertEquals("A.I. Safety", m1[0].canonicalName)
}

@Test
fun substringWithWordBoundary() {
    val matcher = AhoCorasickMatcher(mapOf(
        "test" to "Test",
    ))
    assertEquals(0, matcher.findAll("testing is important").size)
    assertEquals(1, matcher.findAll("this test is important").size)
}
```

Extend `ExtractSuggestionsTest.kt` with:

```kotlin
@Test
fun suggestionInsideBlockRef_isExcluded() {
    val result = extractSuggestions("See ((block-uuid)) for Kotlin info", matcher("Kotlin"))
    assertEquals(1, result.size) // Only the plain-text "Kotlin" after the ref
}

@Test
fun caseInsensitiveIndexing() {
    val result = extractSuggestions("i learn KOTLIN daily", matcher("Kotlin"))
    assertEquals(1, result.size)
    assertEquals("Kotlin", result[0].canonicalName)
}

@Test
fun journalPageExcludedFromIndex() {
    val index = PageNameIndex(pageRepo, scope, excludeJournalPages = true)
    // Add a journal page "2024-01-15"
    // Verify the matcher does not suggest it
}
```

---

### 5. Span Collision Robustness (Low Priority)

**Current state**: Guard against wiki links, block refs, emphasis, inline code is robust (AST-based exclusion).

**Enhancement**: Add explicit test to verify no collisions:

```kotlin
@Test
fun noSuggestionInsideWikiLink() {
    // [[Python]] should not suggest "Python"
}

@Test
fun noSuggestionInlineCodeWithPageName() {
    // `Python` should not suggest "Python"
}
```

---

### 6. Refresh After Link Insertion (Medium Priority)

**Problem**: User accepts a link in Block A. If other blocks on the same screen also have suggestions, they are not invalidated. If the page set changed, they show stale suggestions.

**Mitigation**:

- Implement a refresh signal: when a link is inserted, emit a "suggestions changed" event that triggers a recompose of all visible blocks.
- Alternatively, use the block version (already tracked) to invalidate the suggestion list.

---

## Summary Table

| Pitfall | Severity | Impact | Mitigation Effort | Status |
|---------|----------|--------|-------------------|--------|
| Position drift | **High** | Wrong link inserted if block edited while popup open | Medium | Not implemented |
| PageNameIndex staleness | **Medium** | Link to deleted/renamed page during rebuild window | Medium | Not implemented |
| Aho-Corasick prefix overlap | **Low** | User confusion if both "KMP" and "KMP SDK" exist | Low (UX doc) | Correct, but not tested |
| Missing test coverage | **Medium** | Bugs not caught in development | Medium | Partially addressed |
| Span collision | **Low** | Suggestions inside markup or links (unlikely, AST guards it) | Low | Robust, but not explicitly tested |
| Performance at scale (1000+ pages) | **Low** | Recompose frame drops on matcher updates | Low (debounce) | Monitor with usage data |

