# ADR-002: Ranking Strategy — BM25 + Title-Match Bonus

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler

---

## Context

Search currently returns results in two groups: pages (alphabetical) then blocks (insertion order). Neither group is sorted by relevance. Users must scan both groups linearly.

When a user types "meeting notes", a page titled "Meeting Notes" is almost certainly more relevant than a block containing "I took meeting notes last Tuesday" — but the current model treats them identically.

FTS5 provides the `rank` auxiliary column (exposed via `bm25(table)` or implicitly as `rank` in the `ORDER BY` position). This score is a negative float where values closer to zero are more relevant.

## Decision

Use a **hybrid ranking model**:

1. **BM25 as the base score** — use FTS5's built-in `rank` column for both `pages_fts` and `blocks_fts`.
2. **Title-match bonus** — page results receive an additive bonus of `+0.3` (on a 0–1 normalised scale) to ensure title matches consistently outrank body matches for the same query terms.
3. **Merged sorted list** — `SearchRanker.merge()` interleaves page and block results into a single ranked list, replacing the current sections-based presentation.

### Normalisation

FTS5 `rank` values are negative floats. Convert to a 0–1 normalised score:

```
normalisedScore = 1.0 / (1.0 - rawRank)
```

This maps `rawRank = 0` (no match) to 0 and approaches 1 as relevance increases. It is monotonically increasing with respect to `|rawRank|`, preserving relative order.

Apply the title bonus after normalisation:

```
finalScore = normalisedScore + PAGE_TITLE_BONUS    (for page results)
finalScore = normalisedScore                        (for block results)
```

`PAGE_TITLE_BONUS = 0.3` is a tuning constant, extractable to a configuration value.

### Multi-Token Implicit OR

For multi-word queries without phrase syntax, `FtsQueryBuilder` emits `token1 OR token2 OR ... OR lastToken*`. This allows partial-match documents to appear at lower rank. BM25 naturally ranks documents matching more tokens higher.

## Alternatives Considered

### Option A: Pure BM25, No Bonus

Rely entirely on BM25 scores from SQLite. A block with "Meeting Notes" in its content body would rank on equal footing with a page titled "Meeting Notes".

**Rejected because**: In a personal knowledge management app, the user's primary navigation intent is usually to open a page. Giving page titles a structural advantage matches user mental models (Logseq, Obsidian, Notion all prioritise title matches).

### Option B: Recency Boost (BM25 + updated_at decay)

Add a time-decay factor: `finalScore = bm25Score * exp(-lambda * daysSinceUpdate)`.

**Deferred (not rejected)**: Recency is valuable for journal-heavy use but penalises stable reference pages (project definitions, glossaries) that haven't been edited recently. Leave recency as a future configurable toggle. The `SearchRanker` abstraction makes this easy to add later without changing the interface.

### Option C: TF-IDF in Kotlin (no SQLite ranking)

Fetch raw results from SQLite unranked, compute TF-IDF scores in Kotlin.

**Rejected because**: Requires fetching significantly more rows from SQLite to have material to rank, increasing latency. SQLite's BM25 implementation is efficient and battle-tested. Implementing TF-IDF correctly in Kotlin adds maintenance burden with no quality advantage over FTS5 BM25.

### Option D: Keep Sections (Pages group + Blocks group), Sort Within Each

Maintain the current "Pages" / "Blocks" section structure but sort within each by BM25.

**Partially accepted**: The `SearchDialog` may choose to render sections for clarity; this is a UI decision separate from the data model. The repository layer returns a flat ranked list; the ViewModel can re-impose sections if the UI design requires it. This keeps the ranking logic clean and the UI flexible.

## Consequences

**Positive**:
- Dramatically better result ordering for all common queries.
- Title-match bias matches user expectations in a PKM tool.
- `SearchRanker` is a pure Kotlin class — fully unit-testable without SQLite.
- Future ranking signals (recency, reference count) can be added to `SearchRanker` without changing the SQL layer.

**Negative / Risks**:
- The normalisation formula `1 / (1 - rank)` must be validated against actual FTS5 rank value ranges. If SQLite returns rank values outside the expected range (e.g., positive values for corner cases), the formula may produce unexpected results. Add an assertion or clamping in `SearchRanker`.
- `PAGE_TITLE_BONUS = 0.3` is a magic number. It should be a named constant with a comment explaining the derivation.
- Removing sections from the default view is a UX change — some users may expect the grouped layout. The FilterBar (Story 5) provides an opt-in scoped view as a migration path.
