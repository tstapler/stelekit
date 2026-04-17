# Search Repository Implementation
## Full-Text Search for Logseq Knowledge Graph
## ATOMIC-INVEST-CONTEXT Framework

---

## Epic Overview

### User Value
As a Logseq user, I want to search across all blocks and pages with full-text search capabilities so that I can quickly find information in my knowledge graph.

### Success Metrics
- Search response time < 100ms for graphs with 10k+ blocks
- Support for exact phrase matching
- Support for tag-based filtering
- Case-insensitive search across all content
- Support for Boolean operators (AND, OR, NOT)

### Scope
**Included:**
- SearchRepository interface
- SqlDelightSearchRepository with FTS5
- InMemorySearchRepository for testing
- Integration with existing repository layer

**Excluded:**
- Search result ranking algorithm
- Search UI components
- Search indexing pipeline (deferred to future)

### Constraints
- Use SQLite FTS5 for full-text search
- Maintain backward compatibility with existing queries
- Keep within 3-5 file context boundary per task
- Complete within 4 hours total

---

## Story Breakdown

### Story 1: Search Repository Interface (1h)

#### Task 1.1: Define SearchRepository Interface

**Scope**: Create interface for full-text search operations

**Files** (3 files):
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SearchRepository.kt` (create)
2. `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt` (reference)
3. `docs/interfaces/search-repository-api.md` (create)

**Context**:
- Review existing repository interfaces for consistency
- Define search-specific return types
- Consider Flow-based reactive results

**Implementation**:
```kotlin
package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.Reference
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for full-text search operations.
 * Supports searching across blocks, pages, and references.
 */
interface SearchRepository {
    
    /**
     * Search blocks matching the query string.
     * Uses full-text search for fast content matching.
     * 
     * @param query Search query with optional operators
     * @param limit Maximum number of results
     * @param offset Pagination offset
     * @return Flow of search results
     */
    fun searchBlocks(
        query: String,
        limit: Int = 50,
        offset: Int = 0
    ): Flow<Result<List<BlockSearchResult>>>
    
    /**
     * Search pages matching the query string.
     * Searches page names and content.
     * 
     * @param query Search query
     * @param limit Maximum number of results
     * @return Flow of matching pages
     */
    fun searchPages(
        query: String,
        limit: Int = 20
    ): Flow<Result<List<PageSearchResult>>>
    
    /**
     * Search across all content types.
     * Aggregates results from blocks, pages, and references.
     * 
     * @param query Search query
     * @param limit Per-type result limit
     * @return Flow of combined search results
     */
    fun searchAll(
        query: String,
        limit: Int = 20
    ): Flow<Result<SearchResults>>
    
    /**
     * Get search suggestions for autocomplete.
     * 
     * @param prefix Partial query string
     * @param limit Maximum suggestions
     * @return Flow of suggested search terms
     */
    fun getSearchSuggestions(
        prefix: String,
        limit: Int = 10
    ): Flow<Result<List<String>>>
}

/**
 * Search result with relevance metadata.
 */
data class BlockSearchResult(
    val block: Block,
    val relevanceScore: Float,
    val matchedFields: List<String>,  // "content", "properties", etc.
    val highlight: String  // Matched text snippet
)

/**
 * Search result for pages.
 */
data class PageSearchResult(
    val page: Page,
    val relevanceScore: Float,
    val matchedFields: List<String>,
    val highlight: String
)

/**
 * Combined search results across all content types.
 */
data class SearchResults(
    val blocks: List<BlockSearchResult>,
    val pages: List<PageSearchResult>,
    val totalHits: Int,
    val searchTimeMs: Long
)
```

**Success Criteria**:
- [ ] Interface compiles without errors
- [ ] Documentation explains each method
- [ ] Types are consistent with existing models
- [ ] Reactive (Flow) return types for async operations

**Testing**:
- Interface can be implemented by mock classes
- Type compatibility verified

**Dependencies**: None

**Status**: ⏳ Pending

---

### Story 2: SQLDelight Search Implementation (2h)

#### Task 2.1: Add FTS Schema to Database

**Scope**: Create FTS virtual table for full-text search

**Files** (3 files):
1. `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` (modify)
2. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SearchRepository.kt` (reference)
3. `docs/schema/fts-schema.md` (create)

**Context**:
- Review existing database schema
- Design FTS table structure
- Plan triggers for automatic indexing

**Implementation**:
```sql
-- Full-Text Search virtual table for blocks
CREATE VIRTUAL TABLE blocks_fts USING fts5(
    content,
    content=blocks,
    content_rowid=id,
    tokenize='porter unicode61'
);

-- Triggers to keep FTS index synchronized
CREATE TRIGGER blocks_ai AFTER INSERT ON blocks BEGIN
    INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
END;

CREATE TRIGGER blocks_ad AFTER DELETE ON blocks BEGIN
    INSERT INTO blocks_fts(blocks_fts, rowid, content) 
    VALUES('delete', old.id, old.content);
END;

CREATE TRIGGER blocks_au AFTER UPDATE ON blocks BEGIN
    INSERT INTO blocks_fts(blocks_fts, rowid, content) 
    VALUES('delete', old.id, old.content);
    INSERT INTO blocks_fts(rowid, content) VALUES (new.id, new.content);
END;

-- Search queries using FTS5
searchBlocksFts:
SELECT 
    b.*,
    bm.rank AS relevance_score,
    snippet(bm, 0, '<em>', '</em>', '...', 10) AS highlight
FROM blocks_fts bm
JOIN blocks b ON b.id = bm.rowid
WHERE bm MATCH :query || '*'
ORDER BY bm.rank
LIMIT :limit OFFSET :offset;

searchBlocksFtsHighlight:
SELECT 
    b.*,
    bm.rank AS relevance_score,
    highlight(blocks_fts, 2, '<em>', '</em>') AS highlight
FROM blocks_fts bm
JOIN blocks b ON b.id = bm.rowid
WHERE blocks_fts MATCH :query
ORDER BY bm.rank
LIMIT :limit;

-- Page search using FTS on page names and content
searchPagesFts:
SELECT 
    p.*,
    (SELECT COUNT(*) FROM blocks b WHERE b.page_id = p.id) AS block_count
FROM pages p
WHERE p.name LIKE '%' || :query || '%'
   OR p.properties LIKE '%' || :query || '%'
ORDER BY 
    CASE WHEN p.name LIKE :query || '%' THEN 0 ELSE 1 END,
    p.name
LIMIT :limit;
```

**Success Criteria**:
- [ ] FTS table created successfully
- [ ] Triggers maintain index on data changes
- [ ] Search queries return results
- [ ] Highlight functionality works

**Testing**:
- Schema creation test
- Trigger verification test
- Basic search functionality test

**Dependencies**: Task 1.1

**Status**: ⏳ Pending

---

#### Task 2.2: Implement SqlDelightSearchRepository

**Scope**: Create SQL-based search implementation

**Files** (4 files):
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightSearchRepository.kt` (create)
2. `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` (reference)
3. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/DataMappers.kt` (reference)
4. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SearchRepository.kt` (implement)

**Context**:
- Review DataMappers for entity conversion
- Implement FTS query execution
- Handle result mapping and highlighting

**Implementation**:
```kotlin
package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.Result.Companion.success

/**
 * SQLDelight-based implementation of SearchRepository.
 * Uses SQLite FTS5 for full-text search capabilities.
 */
class SqlDelightSearchRepository(
    private val database: SteleDatabase
) : SearchRepository {
    
    private val blockQueries = database.blockQueries
    private val pageQueries = database.pageQueries
    
    override fun searchBlocks(
        query: String,
        limit: Int,
        offset: Int
    ): Flow<Result<List<BlockSearchResult>>> = flow {
        try {
            val results = blockQueries.searchBlocksFts(
                query = escapeFtsQuery(query),
                limit = limit.toLong(),
                offset = offset.toLong()
            ).executeAsList()
            
            val searchResults = results.map { row ->
                BlockSearchResult(
                    block = Block(
                        id = row.id,
                        uuid = row.uuid,
                        pageId = row.page_id,
                        parentId = row.parent_id,
                        leftId = row.left_id,
                        content = row.content,
                        level = row.level,
                        position = row.position,
                        createdAt = row.created_at,
                        updatedAt = row.updated_at,
                        properties = parseProperties(row.properties)
                    ),
                    relevanceScore = row.relevance_score?.toFloat() ?: 0f,
                    matchedFields = listOf("content"),
                    highlight = row.highlight ?: ""
                )
            }
            
            emit(success(searchResults))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }
    
    override fun searchPages(
        query: String,
        limit: Int
    ): Flow<Result<List<PageSearchResult>>> = flow {
        try {
            val results = pageQueries.searchPagesFts(
                query = query,
                limit = limit.toLong()
            ).executeAsList()
            
            val searchResults = results.map { row ->
                PageSearchResult(
                    page = Page(
                        id = row.id,
                        uuid = row.uuid,
                        name = row.name,
                        namespace = row.namespace,
                        filePath = row.file_path,
                        createdAt = row.created_at,
                        updatedAt = row.updated_at,
                        properties = parseProperties(row.properties)
                    ),
                    relevanceScore = if (row.name.contains(query)) 1.0f else 0.5f,
                    matchedFields = if (row.name.contains(query)) listOf("name") else listOf("properties"),
                    highlight = row.name
                )
            }
            
            emit(success(searchResults))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }
    
    override fun searchAll(
        query: String,
        limit: Int
    ): Flow<Result<SearchResults>> = flow {
        val startTime = System.currentTimeMillis()
        
        val blockResults = mutableListOf<BlockSearchResult>()
        val pageResults = mutableListOf<PageSearchResult>()
        
        searchBlocks(query, limit).collect { blockResult ->
            blockResult.getOrNull()?.let { blockResults.addAll(it) }
        }
        
        searchPages(query, limit).collect { pageResult ->
            pageResult.getOrNull()?.let { pageResults.addAll(it) }
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        
        emit(success(SearchResults(
            blocks = blockResults,
            pages = pageResults,
            totalHits = blockResults.size + pageResults.size,
            searchTimeMs = totalTime
        )))
    }
    
    override fun getSearchSuggestions(
        prefix: String,
        limit: Int
    ): Flow<Result<List<String>>> = flow {
        // Simplified suggestion implementation
        val suggestions = listOf(
            "$prefix",
            "${prefix}*",  // Wildcard suggestion
        )
        emit(success(suggestions.take(limit)))
    }
    
    private fun escapeFtsQuery(query: String): String {
        // Escape special FTS5 characters
        return query
            .replace("\"", "\"\"")
            .replace("'", "''")
    }
    
    private fun parseProperties(json: String?): Map<String, String> {
        // Parse JSON properties string
        return emptyMap()  // Simplified
    }
}
```

**Success Criteria**:
- [ ] Repository implementation compiles
- [ ] FTS queries return results
- [ ] Result mapping works correctly
- [ ] Error handling is robust

**Testing**:
- Integration test with FTS queries
- Result mapping verification
- Error handling test

**Dependencies**: Task 1.1, Task 2.1

**Status**: ⏳ Pending

---

### Story 3: In-Memory Search Implementation (1h)

#### Task 3.1: Implement InMemorySearchRepository

**Scope**: Create in-memory search for testing and fallback

**Files** (3 files):
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/InMemorySearchRepository.kt` (create)
2. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SearchRepository.kt` (implement)
3. `kmp/src/commonTest/kotlin/com/logseq/kmp/repository/InMemorySearchRepositoryTest.kt` (create)

**Context**:
- Use same interface as SQL implementation
- Implement basic text matching for testing
- Support same result types

**Implementation**:
```kotlin
package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.Result.Companion.success

/**
 * In-memory search implementation for testing and fallback.
 * Uses simple substring matching for basic search capability.
 */
class InMemorySearchRepository(
    private val blocks: List<Block> = emptyList(),
    private val pages: List<Page> = emptyList()
) : SearchRepository {
    
    override fun searchBlocks(
        query: String,
        limit: Int,
        offset: Int
    ): Flow<Result<List<BlockSearchResult>>> = flow {
        val results = blocks
            .filter { it.content.contains(query, ignoreCase = true) }
            .drop(offset)
            .take(limit)
            .map { block ->
                BlockSearchResult(
                    block = block,
                    relevanceScore = calculateRelevance(block.content, query),
                    matchedFields = listOf("content"),
                    highlight = highlightMatch(block.content, query)
                )
            }
        emit(success(results))
    }
    
    override fun searchPages(
        query: String,
        limit: Int
    ): Flow<Result<List<PageSearchResult>>> = flow {
        val results = pages
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(limit)
            .map { page ->
                PageSearchResult(
                    page = page,
                    relevanceScore = if (page.name.contains(query)) 1.0f else 0.5f,
                    matchedFields = listOf("name"),
                    highlight = page.name
                )
            }
        emit(success(results))
    }
    
    override fun searchAll(
        query: String,
        limit: Int
    ): Flow<Result<SearchResults>> = flow {
        val startTime = System.currentTimeMillis()
        
        val blockResults = mutableListOf<BlockSearchResult>()
        val pageResults = mutableListOf<PageSearchResult>()
        
        searchBlocks(query, limit).collect { result ->
            result.getOrNull()?.let { blockResults.addAll(it) }
        }
        
        searchPages(query, limit).collect { result ->
            result.getOrNull()?.let { pageResults.addAll(it) }
        }
        
        emit(success(SearchResults(
            blocks = blockResults,
            pages = pageResults,
            totalHits = blockResults.size + pageResults.size,
            searchTimeMs = System.currentTimeMillis() - startTime
        )))
    }
    
    override fun getSearchSuggestions(
        prefix: String,
        limit: Int
    ): Flow<Result<List<String>>> = flow {
        val suggestions = blocks
            .map { extractWords(it.content) }
            .flatten()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .distinct()
            .take(limit)
        emit(success(suggestions))
    }
    
    private fun calculateRelevance(content: String, query: String): Float {
        val lowerContent = content.lowercase()
        val lowerQuery = query.lowercase()
        
        return when {
            lowerContent.contains(lowerQuery) -> 1.0f
            lowerContent.contains(lowerQuery.split(" ").first()) -> 0.5f
            else -> 0.1f
        }
    }
    
    private fun highlightMatch(content: String, query: String): String {
        val pattern = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        return pattern.replace(content) { "<em>${it.value}</em>" }
    }
    
    private fun extractWords(content: String): List<String> {
        return content.split(Regex("\\W+"))
            .filter { it.length > 3 }
            .distinct()
    }
}
```

**Success Criteria**:
- [ ] Implementation compiles
- [ ] Returns same result types as SQL implementation
- [ ] Tests verify basic search functionality
- [ ] Can be used for unit testing

**Testing**:
- Basic search test
- Result type verification
- Edge case handling

**Dependencies**: Task 1.1

**Status**: ⏳ Pending

---

## Dependency Visualization

```
Search Repository Implementation
═══════════════════════════════════════════════════

Story 1: Interface Definition [1h]
└─ Task 1.1: SearchRepository Interface ⏳

Story 2: SQL Implementation [2h]
├─ Task 2.1: FTS Schema ⏳ ← depends on 1.1
└─ Task 2.2: SqlDelightSearchRepository ⏳ ← depends on 2.1

Story 3: In-Memory Implementation [1h]
└─ Task 3.1: InMemorySearchRepository ⏳ ← depends on 1.1

Integration Point: RepositoryFactory
└─ Add createSearchRepository method
```

---

## Success Criteria

### Functional Requirements
- [ ] Search blocks by content text
- [ ] Search pages by name
- [ ] Full-text search with FTS5
- [ ] Search result highlighting
- [ ] Search suggestions for autocomplete

### Performance Requirements
- [ ] Response time < 100ms for 10k blocks
- [ ] Memory usage < 50MB for index
- [ ] Supports incremental updates

### Code Quality
- [ ] All tests pass
- [ ] Code review approved
- [ ] Documentation complete
- [ ] Follows repository pattern conventions

---

## Context Preparation

### For Task 1.1 (Interface Definition)
**Files to load** (~300 lines):
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt`
2. `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt`
3. `docs/ARCHITECTURE_DATASCRIPT_SQLITE.md` (search patterns)

### For Task 2.1 (FTS Schema)
**Files to load** (~200 lines):
1. `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq`
2. SQLite FTS5 documentation (external)

### For Task 2.2 (SQL Implementation)
**Files to load** (~400 lines):
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt`
2. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/DataMappers.kt`
3. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightPageRepository.kt`

---

## Estimated Effort

| Task | Hours | Status |
|------|-------|--------|
| 1.1 Interface Definition | 1 | ⏳ Pending |
| 2.1 FTS Schema | 1 | ⏳ Pending |
| 2.2 SQL Implementation | 2 | ⏳ Pending |
| 3.1 In-Memory Implementation | 1 | ⏳ Pending |
| **Total** | **5** | - |

*Note: Total exceeds 4-hour guideline - consider splitting Story 2 into two parallel tasks.*

---

*Generated: January 1, 2026*
*Framework: ATOMIC-INVEST-CONTEXT*
