# Bug Fixes - Atomic Task Breakdown
## ATOMIC-INVEST-CONTEXT Framework

---

## BUG-001: Plugin Compatibility Layer Design

### Task BUG-001-A: PluginMetadata Model Definition (1h)

**Scope**: Create data model for plugin-specific metadata storage

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/model/PluginMetadata.kt` (create)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt` (reference)
- `docs/models/plugin-schema.md` (create)

**Context**:
- Understand existing Property model for consistency
- Review DataScript flexible document patterns
- Consider serialization requirements (kotlinx.serialization)

**Implementation**:
```kotlin
package dev.stapler.stelekit.model

import kotlinx.serialization.Serializable

/**
 * Metadata storage for plugin-specific data.
 * Preserves plugin ecosystem compatibility during DataScript → SQL migration.
 */
@Serializable
data class PluginMetadata(
    val pluginId: String,
    val entityType: EntityType,
    val entityUuid: String,
    val key: String,
    val value: String,  // JSON-encoded for flexibility
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    enum class EntityType {
        BLOCK,
        PAGE,
        GRAPH
    }
}
```

**Success Criteria**:
- [ ] PluginMetadata data class compiles
- [ ] Serialization tests pass
- [ ] Documentation explains usage patterns

**Testing**:
- Unit tests for serialization/deserialization
- Integration test with sample plugin data

**Dependencies**: None

**Status**: ⏳ Pending

---

### Task BUG-001-B: Migration Utilities Implementation (2h)

**Scope**: Create utilities to migrate plugin data from DataScript format

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/Migration.kt` (create)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/model/PluginMetadata.kt` (reference)
- `docs/migration/plugin-migration-guide.md` (create)

**Context**:
- Review DataScript entity format for plugin data
- Define migration patterns from EAV model to relational
- Handle edge cases (missing data, type mismatches)

**Implementation**:
```kotlin
package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.PluginMetadata

/**
 * Utilities for migrating plugin data from DataScript to SQL format.
 */
object PluginMigration {
    
    /**
     * Migrate plugin data from DataScript entities to PluginMetadata table.
     * Returns count of migrated records and any errors encountered.
     */
    suspend fun migratePluginData(
        datascriptEntities: List<Map<String, Any>>,
        targetRepository: PropertyRepository
    ): MigrationResult {
        val migrated = mutableListOf<PluginMetadata>()
        val errors = mutableListOf<MigrationError>()
        
        for (entity in datascriptEntities) {
            try {
                val metadata = entity.toPluginMetadata()
                targetRepository.saveProperty(metadata.toProperty())
                migrated.add(metadata)
            } catch (e: Exception) {
                errors.add(MigrationError(entity, e.message ?: "Unknown error"))
            }
        }
        
        return MigrationResult(migrated, errors)
    }
    
    data class MigrationResult(
        val migratedRecords: List<PluginMetadata>,
        val errors: List<MigrationError>
    )
    
    data class MigrationError(
        val entity: Map<String, Any>,
        val message: String
    )
}
```

**Success Criteria**:
- [ ] Migration utilities compile
- [ ] Sample data migrates successfully
- [ ] Error handling for edge cases
- [ ] Migration result includes count and errors

**Testing**:
- Unit tests for migration logic
- Integration test with mock DataScript data
- Error handling verification

**Dependencies**: BUG-001-A

**Status**: ⏳ Pending

---

### Task BUG-001-C: SQL Schema for Plugin Data (1h)

**Scope**: Add plugin_data table to SQLDelight schema

**Files**:
- `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` (modify)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightPropertyRepository.kt` (reference)
- `docs/schema/plugin-data-table.md` (create)

**Context**:
- Review existing schema patterns (properties table)
- Define indexes for plugin data queries
- Ensure foreign key relationships

**Implementation**:
```sql
-- Plugin-specific metadata storage
CREATE TABLE plugin_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    plugin_id TEXT NOT NULL,
    entity_type TEXT NOT NULL CHECK(entity_type IN ('BLOCK', 'PAGE', 'GRAPH')),
    entity_uuid TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(plugin_id, entity_type, entity_uuid, key),
    FOREIGN KEY (entity_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
);

CREATE INDEX idx_plugin_data_plugin_id ON plugin_data(plugin_id);
CREATE INDEX idx_plugin_data_entity ON plugin_data(entity_type, entity_uuid);
CREATE INDEX idx_plugin_data_key ON plugin_data(key);
```

**Success Criteria**:
- [ ] Schema compiles without errors
- [ ] Unique constraint prevents duplicates
- [ ] Indexes support common query patterns
- [ ] Documentation explains schema usage

**Testing**:
- Schema creation test
- Unique constraint verification
- Index performance test

**Dependencies**: BUG-001-A

**Status**: ⏳ Pending

---

### Task BUG-001-D: Integration and Verification (1h)

**Scope**: End-to-end integration test for plugin compatibility

**Files**:
- `kmp/src/commonTest/kotlin/com/logseq/kmp/migration/PluginCompatibilityTest.kt` (create)
- `docs/testing/plugin-migration-tests.md` (create)

**Context**:
- Complete migration flow testing
- Verify data integrity post-migration
- Performance verification

**Implementation**:
```kotlin
@Test
fun `plugin data migrates with full fidelity`() = runTest {
    // Given: Sample plugin data in DataScript format
    val pluginData = listOf(
        mapOf(
            "pluginId" to "logseq-plugin-todo",
            "entityType" to "BLOCK",
            "entityUuid" to "block-uuid-123",
            "key" to "todoStatus",
            "value" to "\"DONE\""
        )
    )
    
    // When: Migration is executed
    val result = PluginMigration.migratePluginData(
        pluginData,
        sqlDelightPropertyRepository
    )
    
    // Then: All records migrated successfully
    assertEquals(1, result.migratedRecords.size)
    assertTrue(result.errors.isEmpty())
    
    // And: Data can be retrieved correctly
    val retrieved = sqlDelightPropertyRepository
        .getProperty("block-uuid-123", "todoStatus")
        .first()
    
    assertTrue(retrieved.isSuccess)
}
```

**Success Criteria**:
- [ ] Integration tests pass
- [ ] Data integrity verified
- [ ] Documentation complete

**Testing**:
- Full migration flow test
- Error recovery test
- Performance benchmark

**Dependencies**: BUG-001-A, BUG-001-B, BUG-001-C

**Status**: ⏳ Pending

---

## BUG-002: Graph Query Optimization

### Task BUG-002-A: Hierarchical Query Optimization (2h)

**Scope**: Optimize recursive CTE queries for block hierarchy traversal

**Files**:
- `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` (modify)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt` (modify)
- `kmp/src/jvmMain/kotlin/com/logseq/kmp/benchmark/RepositoryBenchmark.kt` (reference)

**Context**:
- Review current hierarchical query implementation
- Analyze benchmark results for regression areas
- Identify optimization opportunities

**Implementation**:
```sql
-- Optimized block hierarchy query with depth limiting
getBlockHierarchy:
WITH RECURSIVE hierarchy(block_uuid, depth, path) AS (
    SELECT uuid, 0, uuid::TEXT
    FROM blocks
    WHERE uuid = :startUuid
    
    UNION ALL
    
    SELECT b.uuid, h.depth + 1, h.path || '/' || b.uuid
    FROM blocks b
    INNER JOIN hierarchy h ON b.parent_id = (
        SELECT id FROM blocks WHERE uuid = h.block_uuid
    )
    WHERE h.depth < :maxDepth  -- Prevent deep recursion
    AND h.depth < 20  -- Hard safety limit
)
SELECT b.* FROM blocks b
INNER JOIN hierarchy h ON b.uuid = h.block_uuid
ORDER BY h.depth, b.position;

-- Optimized descendants query
getBlockDescendants:
WITH RECURSIVE descendants(uuid, level) AS (
    SELECT uuid, 0 FROM blocks WHERE uuid = :startUuid
    UNION ALL
    SELECT b.uuid, d.level + 1
    FROM blocks b
    INNER JOIN descendants d ON b.parent_id = (
        SELECT id FROM blocks WHERE uuid = d.uuid
    )
    WHERE d.level < :maxLevel
)
SELECT b.* FROM blocks b
INNER JOIN descendants d ON b.uuid = d.uuid
WHERE b.uuid != :startUuid  -- Exclude root
ORDER BY b.level, b.position;
```

**Success Criteria**:
- [ ] Queries compile without errors
- [ ] Depth limiting prevents performance degradation
- [ ] Benchmark shows improvement in hierarchical queries
- [ ] No regression in existing functionality

**Testing**:
- Query correctness test
- Depth limit verification
- Performance benchmark comparison

**Dependencies**: None

**Status**: ⏳ Pending

---

### Task BUG-002-B: Query Cache Implementation (2h)

**Scope**: Add intelligent caching for frequent graph queries

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/cache/BlockCache.kt` (modify)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/cache/CacheCore.kt` (reference)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt` (modify)

**Context**:
- Review existing cache implementation
- Define cache invalidation strategy for graph updates
- Implement cache warming for common queries

**Implementation**:
```kotlin
/**
 * Enhanced block cache with graph-aware caching strategy.
 */
class GraphAwareBlockCache(
    config: CacheConfig,
    private val repository: BlockRepository
) : BlockCache(config, repository) {
    
    private val hierarchyCache = LruCache<String, List<Block>>(100)
    private val descendantsCache = LruCache<String, List<Block>>(50)
    
    override suspend fun getBlockHierarchy(
        blockUuid: String,
        depthLimit: Int
    ): Result<List<Block>> {
        val cacheKey = "hierarchy:$blockUuid:$depthLimit"
        
        // Check cache first
        hierarchyCache.get(cacheKey)?.let { return Result.success(it) }
        
        // Fetch from repository
        val result = repository.getBlockHierarchy(blockUuid, depthLimit)
        
        // Cache successful results
        result.getOrNull()?.let { hierarchyCache.put(cacheKey, it) }
        
        return result
    }
    
    override suspend fun invalidateBlock(blockUuid: String) {
        super.invalidateBlock(blockUuid)
        
        // Invalidate related cache entries
        hierarchyCache.removeAll { key -> 
            key.startsWith("hierarchy:$blockUuid") ||
            key.contains(":$blockUuid:")
        }
        descendantsCache.removeAll { key ->
            key.contains(":$blockUuid:")
        }
    }
}
```

**Success Criteria**:
- [ ] Cache hit rate > 80% for repeated queries
- [ ] Cache invalidation works correctly
- [ ] Memory usage within limits
- [ ] Performance benchmark shows improvement

**Testing**:
- Cache hit rate test
- Cache invalidation test
- Memory usage verification

**Dependencies**: BUG-002-A

**Status**: ⏳ Pending

---

## Dependency Summary

```
Bug Fixes - Dependency Graph
═══════════════════════════════════════════════════

BUG-001: Plugin Compatibility
├─ Task A: PluginMetadata Model [1h] ⏳
├─ Task B: Migration Utilities [2h] ⏳ ← depends on A
├─ Task C: SQL Schema [1h] ⏳ ← depends on A
└─ Task D: Integration Tests [1h] ⏳ ← depends on B, C

BUG-002: Query Optimization  
├─ Task A: Hierarchical Query Optimization [2h] ⏳
└─ Task B: Query Cache Implementation [2h] ⏳ ← depends on A

Available for Parallel Execution:
- BUG-001-A and BUG-002-A can run in parallel
- Both are 1-2 hour tasks with no shared dependencies
```

---

## Success Criteria (All Bug Fixes)

### Quantitative Metrics
- [ ] 100% of HIGH severity bugs addressed
- [ ] 100% of MEDIUM severity bugs addressed
- [ ] Query performance within 2x of DataScript baseline
- [ ] Plugin data migration preserves 100% of records
- [ ] Cache hit rate > 80% for repeated queries

### Qualitative Criteria
- [ ] Code review approval
- [ ] Tests pass without flakiness
- [ ] Documentation complete and accurate
- [ ] No regression in existing functionality
- [ ] Integration tests verify end-to-end functionality

---

## Context Preparation

### For BUG-001 Tasks
**Estimated setup time**: 30 minutes

**Files to load**:
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt` (200 lines)
2. `deps/common/src/logseq/common/schema.cljs` (150 lines)
3. `src/main/frontend/plugin.cljs` (100 lines)

### For BUG-002 Tasks
**Estimated setup time**: 20 minutes

**Files to load**:
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt` (250 lines)
2. `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` (150 lines)
3. `kmp/src/commonMain/kotlin/com/logseq/kmp/cache/BlockCache.kt` (100 lines)

---

*Generated: January 1, 2026*
*Framework: ATOMIC-INVEST-CONTEXT*
