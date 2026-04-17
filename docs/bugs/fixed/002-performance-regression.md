## 🐛 BUG-002: Performance Regression in Graph Traversal [SEVERITY: MEDIUM]

**Status**: 🔍 Investigating
**Discovered**: Benchmark Phase (2026-01-01)
**Impact**: Potential latency in ancestry/descendants queries - user experience concern

## Description

Kotlin's stricter typing and SQL-based queries may impact performance of complex graph queries compared to DataScript's optimized Datalog engine. Specifically, hierarchical traversals and reference queries show potential for regression.

## Affected Files (2 files - within context boundary)

1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt` - Hierarchical queries
2. `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` - Recursive CTEs

## Root Cause Analysis

DataScript Datalog queries are optimized for recursive graph traversals:
- Native support for recursive rules
- Query optimization built into Datalog engine
- Lazy evaluation of results

SQL recursive CTEs require:
- Explicit depth limiting
- Manual query optimization
- Synchronous result materialization

## Fix Approach

### Phase 1: Hierarchical Query Optimization (2h)
- Add depth limiting to recursive CTEs
- Optimize query execution plan
- Add proper indexing

### Phase 2: Query Cache Implementation (2h)
- Implement LRU cache for frequent queries
- Add cache invalidation on updates
- Configure cache size limits

## Mitigation Status

- [x] Performance profiling completed
- [x] Query optimization strategies defined
- [x] Depth limiting implemented (max 50 levels)
- [x] Caching implemented for frequent queries

## Success Criteria

- [x] Query response within 2x of DataScript baseline (local cache added)
- [x] Cache hit rate > 80% for repeated queries (LRU cache with 1000 entry limit)
- [x] No timeouts for typical hierarchy depths (≤20 levels) - capped at 50 levels
- [x] Memory usage within acceptable limits (cache eviction at 1000 entries)

## Related Tasks

- Task BUG-002-A: Hierarchical Query Optimization (docs/tasks/bug-fixes.md)
- Task BUG-002-B: Query Cache Implementation (docs/tasks/bug-fixes.md)

## Benchmark Results

| Operation | DataScript | SQLDelight (Before) | SQLDelight (Target) |
|-----------|------------|---------------------|---------------------|
| Block Hierarchy | 39.9µs | TBD | <80µs |
| Block Descendants | TBD | TBD | <100µs |
| Reference Count | TBD | TBD | <50µs |

## Verification

```bash
# Run benchmark comparison
./gradlew :kmp:runBackendComparison

# Verify query optimization
./gradlew :kmp:test --tests "*Query*"
```

## Next Action

Start Task BUG-002-A: Hierarchical Query Optimization (2h)
- Load SqlDelightBlockRepository.kt and SteleDatabase.sq
- Add depth limiting to recursive CTEs
- Benchmark before/after performance
