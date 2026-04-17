# ADR-001: FTS5 Virtual Table for Page Name Search

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler

---

## Context

Page title search is currently implemented with `SELECT * FROM pages WHERE name LIKE '%query%'`. This has three problems:

1. **No ranking** — results are alphabetically ordered regardless of match quality.
2. **Performance** — `LIKE '%query%'` cannot use the `idx_pages_name` B-tree index because the leading wildcard forces a full table scan.
3. **Tokenizer mismatch** — stemming and Unicode normalisation that `blocks_fts` provides (porter + unicode61) are unavailable for page titles.

The `blocks` table already uses an FTS5 external-content virtual table (`blocks_fts`) maintained by INSERT/UPDATE/DELETE triggers. This pattern is proven and SQLDelight-friendly.

## Decision

Add a `pages_fts` FTS5 external-content virtual table using the same `porter unicode61` tokenizer and the same trigger pattern used by `blocks_fts`.

```sql
CREATE VIRTUAL TABLE pages_fts USING fts5(
    name,
    content=pages,
    content_rowid=rowid,
    tokenize='porter unicode61'
);
```

Add a stable integer `id` column to `pages` as the FTS rowid anchor (see Known Issue 4 in the implementation plan — `pages` currently has only a TEXT `uuid` primary key and an implicit rowid that is unstable across delete+re-insert cycles). The migration must:

1. Add `id INTEGER` to `pages` (populated via `UPDATE pages SET id = rowid`).
2. Create `pages_fts` with `content_rowid=id`.
3. Create INSERT/UPDATE/DELETE triggers on `pages` referencing `new.id` / `old.id`.
4. Backfill: `INSERT INTO pages_fts(rowid, name) SELECT id, name FROM pages`.

## Alternatives Considered

### Option A: In-Memory Scoring at Repository Layer

Fetch all pages with `SELECT uuid, name FROM pages`, score each title in Kotlin using a simple n-gram or Levenshtein distance, sort in memory.

**Rejected because**:
- Does not scale past a few thousand pages without noticeable latency.
- Duplicates logic already handled by SQLite's battle-tested FTS5 engine.
- No BM25; scoring would be homegrown and harder to tune.

### Option B: Keep LIKE, Add `idx_pages_name` for Prefix

Change `LIKE '%query%'` to `LIKE 'query%'` (prefix-only) so the B-tree index is usable. Add a covering index `idx_pages_name_prefix ON pages(name COLLATE NOCASE)`.

**Rejected because**:
- Prefix-only search is worse UX (typing "taxes" does not match "2025 Taxes").
- Still no BM25 ranking.
- Does not address tokenizer / stemming capabilities.

### Option C: Separate SQLite Database for Search Index

Maintain a separate SQLite file as a search index, rebuilt asynchronously on content change.

**Rejected because**:
- Significantly increases operational complexity (two DB files, sync concerns).
- Not justified for a local-first single-user app at current scale.

## Consequences

**Positive**:
- Page search gains BM25 relevance ranking identical to block search.
- Porter stemmer handles "taxes" matching "tax".
- Performance: FTS5 uses an inverted index; large graphs benefit significantly.
- Consistency: one pattern for both entity types.

**Negative / Risks**:
- Schema migration required (adding `id` column to `pages`). All existing `.db` files on user devices must be migrated.
- `DataMappers.kt` and all `pages`-row mappers must handle the new `id` column (can be nullable/ignored if unused outside FTS).
- FTS index adds disk space (~10-20% of raw text size for typical notes corpora).
- If triggers are disabled during bulk import, FTS becomes stale — mitigated by the `rebuildFtsIndex()` recovery path documented in the implementation plan.
