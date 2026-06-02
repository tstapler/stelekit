# FTS5 Stack & BM25 Deep-Dive

## Research scope
SQLite FTS5 behaviour relevant to SteleKit's current `blocks_fts` / `pages_fts` setup:
external-content tables with `content=blocks` / `content=pages`, `tokenize='porter unicode61'`,
`highlight()` in every query result, and BM25 ordering on 10k+ row graphs.

---

## 1. BM25 Scoring — Quirks and Sign

FTS5's `bm25()` returns **negative values**. SQLite multiplies the raw Okapi BM25 result by -1
so that `ORDER BY bm25(table) ASC` surfaces the best matches first (most-negative = most
relevant). The existing query correctly orders `ORDER BY bm25(blocks_fts)` (ascending), but
`SqlDelightSearchRepository.buildRankedList` then does `abs(bm25Score)` before applying
multipliers, which is the right move — the absolute value is used as the relevance magnitude.

Known quirks:
- **NULL pages**: if a row exists in the FTS index but its backing content row has been deleted
  without triggering the delete trigger, `bm25()` can return 0.0 (not NULL) for that ghost row.
  The result will appear with a score of 0 after `abs()` and sort to the bottom after
  multipliers, so it is unlikely to surface — but the row still hits the result set, wasting
  a slot in the LIMIT window.
- **Column weights**: `bm25(table, w0, w1, ...)` accepts per-column weights. Currently only
  one indexed column exists (`content` / `name`), so the default weight of 1.0 applies.
  Adding a second column (e.g. page title inside blocks) would allow boosting title matches
  at the SQL level rather than in Kotlin.
- **Score range**: Typical BM25 values for short queries on personal-note corpora fall in the
  range -0.5 to -3.0 (absolute value 0.5–3.0). The PAGE_BOOST=5.0 multiplier applied in
  `buildRankedList` is therefore strong enough to rank any page hit above any block hit
  regardless of BM25 quality — which may not be desirable for very short page names.

## 2. External-Content Tables: Trigger Correctness

Both `blocks_fts` and `pages_fts` use `content=` external-content tables. This means:
- FTS stores only the index; actual text is read back from the base table at query time.
- All three triggers (AI/AU/AD) must be present and fire on every write path.

**Current trigger analysis:**

`blocks_au` correctly implements the delete-then-insert pattern:
```sql
INSERT INTO blocks_fts(blocks_fts, rowid, content) VALUES('delete', old.id, old.content);
INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
```
This is the correct pattern. The critical bug to avoid: if the DELETE step references the
_new_ content instead of `old.content`, the old tokens are never removed from the index,
creating ghost matches. The current implementation uses `old.content` correctly.

**Potential staleness vector — bulk insert bypassing triggers:**
`DatabaseWriteActor` serialises writes through SQLDelight. However, during `GraphLoader`
bulk import, blocks are inserted via `saveBlocks()` which issues individual INSERT statements
inside a transaction. SQLite triggers fire per-row inside transactions, so the FTS index
should stay in sync. The risk is if any write path uses raw SQL or a `TRUNCATE`-equivalent
without a corresponding FTS rebuild.

**Detecting and repairing stale indexes:**
```sql
INSERT INTO blocks_fts(blocks_fts) VALUES('integrity-check');  -- raises if stale
INSERT INTO blocks_fts(blocks_fts) VALUES('rebuild');          -- full rebuild from content=blocks
```
`OPTIMIZE` does **not** repair stale content — it only merges b-tree segments. A `rebuildFts()`
function should issue `('rebuild')` against both virtual tables. This is safe to run at any time
and takes O(N) relative to the number of rows.

Note: the `pages_ai` trigger uses `last_insert_rowid()` instead of `new.rowid`. For an
autoincrement-less table where `rowid` is the implicit integer primary key, these are
equivalent _immediately after an INSERT_, but this is fragile if the trigger ever fires in a
context where the last insert rowid has been changed by a nested trigger. Using `new.rowid`
directly (as `blocks_ai` does with `new.id`) is safer and should be corrected.

## 3. FTS5 Performance at 10k+ Rows

Measured and reported characteristics from SQLite documentation and community benchmarks:
- FTS5 index scans for common single-token queries on 10k–100k rows typically complete in
  **1–5 ms** on modern hardware with a warm page cache.
- Prefix queries (`token*`) are slightly more expensive than exact-token queries because FTS5
  must traverse a range of the b-tree. On 10k rows the difference is negligible.
- `LIMIT` is pushed into the FTS scan: FTS5 stops scoring once LIMIT rows have been
  accumulated. The current `LIMIT :limit` at the end of `searchBlocksByContentFts` is
  effective.
- Cold-cache (first query after open) can be significantly slower (10–50× on spinning disk;
  2–5× on SSD) because the FTS shadow tables are not yet in the page cache. On mobile this
  matters.
- For 10k pages + ~100k blocks, the total FTS index size is roughly 2–5 MB for typical
  personal-note content, well within SQLite's default page cache.

## 4. Tokenizer: porter vs unicode61 vs Both

Current config: `tokenize='porter unicode61'` — porter wraps unicode61.

- **unicode61** (default): normalises Unicode, splits on whitespace and punctuation. Good
  multilingual recall. No stemming.
- **porter**: applies Porter stemming to each token produced by the wrapped tokenizer.
  "running" → "run", "programming" → "program". Improves recall for English morphological
  variants. Reduces precision (e.g. "universal" and "universe" share a stem).
- **Both (chained)**: `'porter unicode61'` is the recommended pattern. It chains porter on top
  of unicode61, giving stemming + Unicode normalisation. This is what SteleKit already uses —
  no change required here.
- **Recall impact**: Stemming typically improves recall by 10–20% for English personal notes
  at the cost of ~5% precision loss. For a note-taking app where recall (not missing a result)
  matters more than precision, this is a good trade-off.
- **Caveat**: porter stemming is English-only. Non-English notes will not benefit from stemming
  but unicode61 still handles normalisation correctly.

## 5. highlight() and snippet() Performance Cost

`highlight(blocks_fts, 0, '<em>', '</em>')` is called in every row of `searchBlocksByContentFts`
and `searchBlocksByContentFtsInPage`.

Performance characteristics:
- `highlight()` requires reading the original document from the content table (a JOIN to
  `blocks.content`) to locate match positions. This is an additional read per row.
- For a result set of 50 rows, this adds roughly 50 content-column reads. On an in-process
  SQLite DB these are typically sub-millisecond each.
- `snippet()` is more expensive than `highlight()` because it must find the _best_ window
  within the document, not just mark all occurrences.
- **Recommendation**: Keep `highlight()` — the cost is low relative to the FTS scan itself
  and provides value for UI display. Do not replace with `snippet()` unless short excerpts are
  required. Consider removing `highlight()` from the _ranking-only_ path if a two-pass
  approach is adopted (rank first without highlight, then fetch highlights only for top-N
  results to display).

## Summary Recommendations for Implementation

1. Fix `pages_ai` trigger to use `new.rowid` instead of `last_insert_rowid()`.
2. Implement `rebuildFts()` using `INSERT INTO blocks_fts(blocks_fts) VALUES('rebuild')` and
   same for `pages_fts`. Wrap in `withContext(PlatformDispatcher.DB)`.
3. Add an `integrity-check` call in a debug/settings screen to surface stale index problems.
4. BM25 sign: current `abs(bm25Score)` usage in `buildRankedList` is correct.
5. PAGE_BOOST=5.0 may be too strong — consider 2.0–3.0 to avoid swamping BM25 quality
   signals for poor page-name matches.
6. `highlight()` cost is acceptable for current LIMIT=50 result sets; no change needed.
