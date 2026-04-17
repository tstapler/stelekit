# Task: Advanced Search & Query System Implementation

## Overview
Implement comprehensive search functionality including full-text search, advanced query builder, and result management to match Logseq's powerful search capabilities.

## Current State
- KMP has basic `VectorSearch.kt` and `DatalogQuery.kt` placeholders
- Missing: Complete search UI, indexing, query builder, search result management

## Implementation Tasks

### 1. **Search Index Management**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/search/indexing/`

**Components**:
- `SearchIndexer.kt` - Build and maintain search indices
- `BlockIndexer.kt` - Index block content
- `PageIndexer.kt` - Index page titles and properties
- `FullTextIndexer.kt` - Full-text search indexing
- `IndexManager.kt` - Coordinate all indexing operations

**Features**:
- Incremental index updates
- Background indexing for large graphs
- Index rebuild functionality
- Cross-platform search optimization
- Multiple language support

### 2. **Query Engine**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/search/query/`

**Components**:
- `QueryParser.kt` - Parse advanced query syntax
- `QueryExecutor.kt` - Execute parsed queries
- `DatalogEngine.kt` - Datalog query support
- `QueryOptimizer.kt` - Optimize query performance
- `QueryValidator.kt` - Validate query syntax

**Features**:
- Datalog query language support
- Property-based queries
- Date range queries
- Tag and namespace queries
- Custom query functions

### 3. **Search UI Components**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/search/ui/`

**Components**:
- `SearchInterface.kt` - Main search composable
- `QueryBuilder.kt` - Visual query builder
- `SearchResults.kt` - Display search results
- `SearchFilters.kt` - Filter and refine results
- `SearchHistory.kt` - Search history management

**Features**:
- Real-time search suggestions
- Query syntax highlighting
- Result preview and navigation
- Save and load searches
- Keyboard shortcuts for search

### 4. **Specialized Search Types**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/search/types/`

**Components**:
- `PageSearch.kt` - Page-specific search
- `BlockSearch.kt` - Block content search
- `FileSearch.kt` - File system search
- `TemplateSearch.kt` - Template search
- `AssetSearch.kt` - Asset and media search

**Features**:
- Context-aware search
- Fuzzy matching
- Regular expression support
- Search across multiple content types
- Advanced filtering options

### 5. **Search Results Management**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/search/results/`

**Components**:
- `ResultProcessor.kt` - Process and format results
- `ResultRanker.kt` - Rank search results
- `ResultExporter.kt` - Export search results
- `ResultCache.kt` - Cache frequently used results

**Features**:
- Relevance ranking
- Highlight search terms
- Group and sort results
- Export to various formats
- Result analytics

### 6. **Vector and Semantic Search**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/search/vector/`

**Components**:
- `EmbeddingGenerator.kt` - Generate text embeddings
- `VectorIndex.kt` - Vector similarity search
- `SemanticSearch.kt` - Semantic meaning search
- `EmbeddingManager.kt` - Manage embedding lifecycle

**Features**:
- AI-powered semantic search
- Find similar content
- Concept-based search
- Multilingual semantic support
- Performance optimization for vector search

## Integration Points

### With Existing KMP Code:
- Extend existing `VectorSearch.kt` and `DatalogQuery.kt`
- Integrate with repository layer for data access
- Use `GraphRepository` for query execution
- Connect to `NotificationManager` for search feedback

### With UI Components:
- Update `CommandPalette.kt` with advanced search
- Integrate with `Sidebar.kt` for search navigation
- Add search to `PageView.kt` for in-page search

## Search Syntax Support

### Datalog Queries:
```clojure
{{query (and [[project]] (task NOW))}}
{{query (property key value)}}
{{query (between -7d +7d)}}
```

### Advanced Search:
- Full-text: `"search term"`
- Page links: `[[page name]]`
- Block references: `((block-id))`
- Tags: `#tag`
- Properties: `property::value`

## Performance Considerations

### Indexing Strategy:
- Lazy indexing for large graphs
- Incremental updates only
- Background processing
- Memory-efficient storage

### Query Optimization:
- Query result caching
- Index-based filtering
- Pagination for large result sets
- Asynchronous query execution

## Migration from ClojureScript

### Files to Reference:
- `src/main/frontend/search.cljs`
- `src/main/frontend/components/query/`
- `src/main/frontend/components/cmdk/`
- `src/main/frontend/search/agency.cljs`
- `src/main/frontend/search/protocol.cljs`

### Key Functions to Port:
- `block-search` function
- `template-search` function
- `file-search` function
- `rebuild-indices!` function
- Query parsing logic

## Testing Strategy

### Unit Tests:
- Test query parsing and validation
- Test indexing operations
- Test search result ranking
- Test vector similarity calculations

### Integration Tests:
- Test search with real graph data
- Test query execution performance
- Test index rebuilding
- Test concurrent search operations

### Performance Tests:
- Large graph search performance
- Memory usage during indexing
- Query response time benchmarks
- Vector search accuracy tests

## Success Criteria

1. Users can search across all content types
2. Advanced Datalog queries work correctly
3. Query builder is intuitive and powerful
4. Search results are relevant and well-ranked
5. Vector search provides meaningful semantic results
6. Performance is acceptable for large graphs
7. Search syntax is compatible with existing Logseq

## Dependencies

### External Libraries:
- Full-text search engine (Lucene equivalent)
- Vector similarity library
- Text processing libraries
- Query parsing libraries

### Internal Dependencies:
- Complete repository layer
- Graph model with relationships
- Notification system
- Caching infrastructure

## Platform Considerations

### Desktop:
- Can use more memory for indices
- Background indexing available
- Rich keyboard shortcut support

### Mobile:
- Memory-constrained indexing
- Touch-optimized search UI
- Simplified query builder
- Offline search capability

### Web:
- Browser storage limits
- Client-side indexing
- Progressive web app considerations
- Search result caching
