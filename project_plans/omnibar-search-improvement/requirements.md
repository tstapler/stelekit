# Omnibar Search Improvement — Requirements

## Problem Statement

The SteleKit Omnibar (SearchDialog + CommandPalette) has four identified pain points on large graphs (10k+ pages):

1. **Results feel wrong** — ranking doesn't surface the most relevant pages/blocks. Old or unrelated results appear first. The existing BM25 + recency-decay + graph-distance model is present but apparently insufficient.
2. **Feels slow/laggy** — noticeable delay between typing and results appearing on large graphs.
3. **Missing results** — known pages/blocks don't appear even with correct search terms.
4. **No performance visibility** — HistogramWriter and InstrumentedSearchRepository exist but no benchmarks or CI-visible latency assertions.

## Current Architecture (as-is)

- **FTS5 virtual tables**: `blocks_fts` and `pages_fts` with porter+unicode61 tokenizer.
- **FtsQueryBuilder**: AND-first, OR-fallback, prefix wildcard, phrase-quote preservation.
- **Ranking** (`buildRankedList`): BM25 base + PAGE_BOOST(5.0) + recency half-life (14 days) + graph-distance boost (3.0). No visit-frequency or recently-visited signal.
- **Debounce**: 300ms in SearchViewModel.
- **Performance**: `HistogramWriter.record("search", durationMs)` + OpenTelemetry spans. No benchmark tests.
- **Fallback**: LIKE-based `selectPagesByNameLike` if FTS5 throws.
- **VectorSearch**: embedding + cosine similarity abstraction exists but is a placeholder.

## Goals

### G1 — Recency-of-Visit Ranking Signal
Boost pages and blocks the user has recently navigated to. Recently visited results should rank higher than equally-relevant but unvisited content.

- Track navigation events (page views) with timestamps.
- Apply a visit-recency multiplier on top of the current ranking formula.
- Half-life configurable (default: 3 days — shorter than the current edit-recency half-life of 14 days).
- Must not require external ML models or on-device embeddings.

### G2 — Exact Title Match Always Wins
A page whose name exactly matches the query string (case-insensitive) must appear as the first result, regardless of BM25 score or other signals.

### G3 — Improved FTS Coverage (missing results fix)
Investigate and fix cases where known pages/blocks are absent from FTS results:
- Check that FTS5 triggers fire correctly on all write paths (GraphLoader, GraphWriter, DatabaseWriteActor).
- Ensure content-table sync (external-content FTS) is never stale after a write.
- Add a `rebuildFts()` repair function callable from settings/debug.

### G4 — Performance Optimization (large graphs, 10k+ pages)
Target: p99 search latency < 100ms on a synthetic 10k-page graph.

- Profile the current hot path: FTS5 query → BM25 scoring → `buildRankedList` → neighbour lookup.
- Identify and eliminate N+1 queries (particularly `selectNeighbourPageUuids` called per result).
- Consider caching neighbour sets or graph-distance scores.
- Reduce result-set size with LIMIT pushed into SQL before ranking.

### G5 — Performance Testing Suite
Two layers:

**Layer A — Integration latency assertions (jvmTest / CI)**
- Synthetic graph generator: insert N pages + M blocks via repository, build FTS index.
- Parameterized test: N=100, 1000, 10000 pages.
- Assert p99 latency < 200ms at 10k pages (measured over 100 query executions).
- Assert cold-start FTS query latency < 500ms at 10k pages.

**Layer B — Microbenchmarks (JMH via Gradle)**
- Benchmark `SqlDelightSearchRepository.searchWithFilters()` on 10k-page graph.
- Benchmark `FtsQueryBuilder.build()` for complex queries.
- Benchmark `buildRankedList()` merge step.
- Output: p50/p95/p99 per benchmark; stored in `kmp/build/reports/benchmarks/`.

## Non-Goals

- Semantic/vector search (VectorSearch exists but is explicitly out of scope here).
- Cross-graph federated search.
- UI redesign beyond ranking order changes.
- CommandPalette fuzzy-filter changes (separate from search repository).

## Acceptance Criteria

| ID | Criterion |
|----|-----------|
| AC1 | Navigating to a page records a visit timestamp. |
| AC2 | A page visited < 1 hour ago ranks above an equally-BM25-scored page never visited. |
| AC3 | Exact title match is always the first result. |
| AC4 | All existing `jvmTest` search tests pass without modification. |
| AC5 | `rebuildFts()` runs without error on a 10k-page graph. |
| AC6 | jvmTest synthetic-graph latency test passes: p99 < 200ms at 10k pages. |
| AC7 | JMH benchmark task runs and produces output (no regression gate yet — establishes baseline). |
| AC8 | `InstrumentedSearchRepository` decorator properly records span attributes including `ranking.visit_boost`. |

## Constraints

- Kotlin Multiplatform — ranking logic must compile to all targets (JVM, Android, iOS, WASM).
- SQLDelight 2.3.2 — no raw JDBC; all SQL via `.sq` files.
- Arrow `Either` — all repository methods must use `Either<DomainError, T>`.
- `@DirectSqlWrite` enforcement — all writes via `DatabaseWriteActor` or `RestrictedDatabaseQueries`.
- JMH benchmarks are JVM-only (`:kmp:jvmBenchmark` source set or separate Gradle module).
- No new external dependencies without ADR.

## Out-of-Scope Dependencies

- `InferenceWorker` / remote embedding endpoint — not required.
- Changes to `CommandPalette` fuzzy-filter scoring.
