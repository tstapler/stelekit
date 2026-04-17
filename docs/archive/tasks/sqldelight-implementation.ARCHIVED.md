# SQLDelight Repository Implementation

## Objective
Implement full SQLDelight repository backend with actual SQL queries, replacing stub implementations and enabling comparison between all three backends (IN_MEMORY, DATASCRIPT, SQLDelight).

## Status
**NOT STARTED** - SQLDelight repository stubs exist but return empty/placeholder results

## Priority
**HIGH** - Complete the three-backend comparison and enable full benchmarking

## Files Required (Context Boundary: 5 files)
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt` - NEW
2. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightPageRepository.kt` - NEW  
3. `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` - EXISTS
4. `kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/com/logseq/kmp/db/SteleDatabaseQueries.kt` - GENERATED
5. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/RepositoryFactoryImpl.kt` - EXISTS

## Prerequisites
- [x] Build system functional
- [x] SQLDelight schema defined
- [x] IN_MEMORY and DATASCRIPT backends working
- [x] Benchmark framework operational
- [x] Tests passing

## Atomic Steps

### Step 1: Implement SqlDelightBlockRepository (1.5h)
- [ ] Create `SqlDelightBlockRepository.kt` with database connection
- [ ] Implement `getBlockByUuid()` using `selectBlockByUuid` query
- [ ] Implement `getBlockChildren()` using `selectBlockChildren` query
- [ ] Implement `getBlockHierarchy()` with recursive CTE
- [ ] Implement `saveBlock()` using `insertBlock` query
- [ ] Implement `deleteBlock()` using `deleteBlockById` query

### Step 2: Implement SqlDelightPageRepository (1h)
- [ ] Create `SqlDelightPageRepository.kt` with database connection
- [ ] Implement `getPageByUuid()` using `selectPageByUuid` query
- [ ] Implement `getPageByName()` using `selectPageByName` query
- [ ] Implement `getPagesInNamespace()` using `selectPagesByNamespace` query
- [ ] Implement `getAllPages()` using `selectAllPages` query
- [ ] Implement `getRecentPages()` using `selectRecentlyUpdatedPages` query

### Step 3: Update RepositoryFactory (0.5h)
- [ ] Add SqlDelight driver initialization method
- [ ] Wire SQLDelight repositories in factory
- [ ] Ensure proper lifecycle management

### Step 4: Update AllBackendsComparisonBenchmark (0.5h)
- [ ] Add SQLDelight to comparison
- [ ] Ensure proper database setup/teardown
- [ ] Run three-way comparison

### Step 5: Validate and Test (0.5h)
- [ ] Run all tests verify pass
- [ ] Run benchmark comparison
- [ ] Document performance results

## Validation Approach
- Unit tests: `RepositoryBenchmarkTest.kt` passes
- Integration test: `AllBackendsComparisonBenchmark.kt` runs with SQLDelight included
- Benchmark output: Three-way comparison table shows all backends

## Completion Criteria
- [ ] All SqlDelight repository methods return actual data
- [ ] Three-way benchmark (IN_MEMORY vs DATASCRIPT vs SQLDelight) runs successfully
- [ ] `./gradlew :kmp:runBackendComparison` shows all three backends
- [ ] All existing tests pass

## Dependency Visualization
```
IN_MEMORY (✅ Complete)
    ↓
DATASCRIPT (✅ Complete)  
    ↓
SQLDelight Implementation (📍 NEXT TASK)
    ↓
Three-Backend Benchmark (📍 After SQLDelight)
    ↓
Graph Query Optimization (Optional)
```

## Success Metrics
- Time to implement: 4 hours maximum
- Context files: 5 (within 3-5 limit)
- Test coverage: All existing tests pass
- Benchmark output: Complete three-way comparison

## Notes
- SQLDelight 2.0 API changes require careful handling
- JDBC driver needed for in-memory database in tests
- Recursive CTEs needed for hierarchy queries
- Consider using test databases with proper setup/teardown
