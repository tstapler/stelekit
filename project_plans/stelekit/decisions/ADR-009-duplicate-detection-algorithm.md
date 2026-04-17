# ADR-009: Duplicate Detection Algorithm — Proportional Edit Distance

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler  
**Feature**: Knowledge Graph Maintenance — Story 3 (Duplicate Page Detector)

---

## Context

The duplicate page detector needs to identify page pairs whose names are likely the same concept but differ due to typos, alternate spellings, or slight reformulations. Several algorithmic approaches were considered:

**Option A — Fixed edit distance (Levenshtein)**: any pair with `editDistance <= k` (e.g., k = 3) is flagged. Simple to implement but produces excessive false positives for short names.

**Option B — Proportional edit distance**: threshold scales with name length: `maxDistance = floor(name.length * sensitivity)`. Short names have a tighter threshold; longer names allow more variation. Adjustable sensitivity (e.g., 0.15 / 0.25 / 0.35) gives users control.

**Option C — Common-prefix heuristic + edit distance**: a two-pass approach. Pass 1 flags lexicographically adjacent pairs sharing a long common prefix (cheap, O(n log n)). Pass 2 runs edit distance only on remaining candidates below a length-ratio threshold. Faster for large graphs.

**Option D — Embedding similarity (semantic)**: convert page names to embeddings (e.g., via a local model or trigram TF-IDF) and flag pairs above a cosine similarity threshold. Catches semantic duplicates ("Meeting Notes" vs "Notes from Meetings") but requires a significant dependency or a local compute budget incompatible with the mobile targets.

**Option E — Trigram Jaccard similarity**: split names into trigrams and compute Jaccard index. Effective for detecting transpositions and moderate edits; O(n^2) but fast per-pair.

---

## Decision

**Option C — Common-prefix heuristic + proportional edit distance** is adopted.

The combined approach provides:
- Fast O(n log n) detection for the most common case (names that are near-identical up to a suffix, e.g., "Project Alpha" vs "Project Alpha 2026" should NOT be flagged as duplicates — this is why `MAX_EDIT_DISTANCE = 3` alone is insufficient for long names).
- Proportional edit distance for the remaining pairs, avoiding the false-positive explosion of a fixed threshold on short names.
- A user-accessible sensitivity control (Low / Medium / High) that maps to proportional multipliers (0.15 / 0.25 / 0.35).

Option D (embedding similarity) is explicitly out of scope for v1: it would require a dependency that exceeds the complexity budget and creates binary size concerns for mobile targets. It is logged as a future enhancement.

Option E (trigram Jaccard) is a viable alternative to edit distance, particularly for detecting transpositions. It can be substituted in a later iteration if edit distance produces too many false negatives for rearranged-word duplicates.

---

## Algorithm Specification

### Step 1: Filtering

Exclude from consideration:
- Pages where `is_journal = 1`
- Pages where `name.length < 4` (constant `MIN_NAME_LENGTH`)
- Pages with namespace separators in different positions (e.g., `Project/Alpha` and `Research/Alpha` are structurally different; compare only the final namespace component if both are namespaced, or skip cross-namespace pairs)

### Step 2: Normalization

Normalize each name before comparison:
```
normalized = name.trim().lowercase().replace(Regex("\\s+"), " ")
```
Normalization is only for comparison — original casing is preserved in `DuplicateCandidate`.

### Step 3: Common-Prefix Pass

Sort all normalized names lexicographically. Slide a window of 2 over adjacent pairs. If two adjacent names share a common prefix of length >= `COMMON_PREFIX_MIN` (default 5) AND their normalized forms differ (otherwise they are actually the same page, blocked by the DB UNIQUE constraint), add them as a candidate with edit distance computed lazily.

### Step 4: Edit-Distance Pass

For all pairs NOT already captured in Step 3, and where `abs(nameA.length - nameB.length) <= MAX_EDIT_DISTANCE` (a cheap pre-filter), compute Levenshtein distance. Use a standard DP implementation with early termination when the running cost exceeds the threshold.

Threshold per pair: `floor(min(nameA.length, nameB.length) * sensitivity)`, where:
- Low sensitivity = 0.15 (catches only near-identical names)
- Medium sensitivity = 0.25 (default)
- High sensitivity = 0.35 (wider net, more false positives)

Minimum threshold: 1 (a pair with edit distance 0 is a true duplicate — impossible under the DB UNIQUE constraint, but defensive).
Maximum threshold: 3 (cap — names longer than 12 chars are not compared beyond edit distance 3 even at high sensitivity, to avoid noisy results for long descriptive names).

### Step 5: Scoring and Sorting

Each `DuplicateCandidate` gets a `similarityScore`:
```
similarityScore = 1.0f - (editDistance.toFloat() / max(nameA.length, nameB.length).toFloat())
```

Candidates are sorted by `editDistance ASC`, then `similarityScore DESC` as a tiebreaker.

---

## Complexity

For a graph with N pages:
- Filtering and normalization: O(N)
- Common-prefix pass (after sort): O(N log N) for sort, O(N) for scan
- Edit-distance pass: O(k * L^2) per candidate pair, where L = max name length and k = number of candidate pairs not caught by prefix pass. In practice, k << N^2 because the length-ratio pre-filter eliminates most pairs.
- Total: O(N log N) dominated by the sort, with a practical constant factor well under 1 second for N <= 10 000 on JVM.

---

## Consequences

**Positive**:
- No external dependencies — pure Kotlin, KMP-safe.
- Proportional threshold eliminates the false-positive problem for short names.
- Common-prefix pre-filter makes the algorithm practical for large graphs without O(n^2) edit-distance computation over all pairs.
- User-configurable sensitivity gives power users control without changing algorithm fundamentals.

**Negative / Risks**:
- Does not detect semantic duplicates ("Meeting Notes" vs "Notes from Meetings"). This is an explicit non-goal for v1 and is documented in the UI with a note: "This tool detects name-similarity duplicates. Semantic duplicates (same concept, different phrasing) must be found manually."
- The common-prefix pass may miss pairs that are duplicates but not lexicographically adjacent after normalization (e.g., "Alpha Project" and "Project Alpha" — edit distance is high but they are likely duplicates). This is acceptable noise for v1.
- Levenshtein DP with early termination is still O(L^2) per pair. For page names up to 100 characters and MAX_EDIT_DISTANCE = 3, the actual loop iterations per pair is O(3 * 100) = 300, which is fast enough.
