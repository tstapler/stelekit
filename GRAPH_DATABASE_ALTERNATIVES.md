# Embedded Graph Database Alternatives for Logseq Migration

Based on extensive research into graph databases that can run in-process with Java/Kotlin, here are the top alternatives to DataScript that could work for Logseq's hierarchical block structure and reference relationships.

## **Repository Pattern Recommendation**

Given your query analysis showing 80% simple operations and 15% hierarchical traversals, I recommend implementing a **repository abstraction layer** that allows testing multiple graph database backends. This approach lets you:

1. Start with one database implementation
2. Easily swap databases for performance comparison
3. Maintain clean separation between business logic and data storage
4. Keep the door open for future optimizations

## **Top Embedded Graph Database Candidates**

### **1. Kuzu - Highest Recommendation** ⭐⭐⭐⭐⭐

**Why it fits Logseq perfectly:**
- **Embedded/in-process**: Runs directly in your application
- **Cypher query language**: Industry standard, more maintainable than Datalog
- **Columnar storage**: Optimized for analytical workloads like graph traversals
- **Multi-platform**: Works with JVM, native, and JavaScript
- **MIT license**: Permissive open source

**Key Features:**
- **Performance**: Extremely fast joins and traversals
- **Scalability**: Handles graphs with billions of edges
- **ACID transactions**: Full transactional support
- **Vectorized queries**: Hardware-optimized execution

**Java/Kotlin Integration:**
```kotlin
// Kotlin example from their docs
val db = Database("./logseq-db")
val conn = Connection(db)

// Create schema for Logseq's block hierarchy
conn.query("""
    CREATE NODE TABLE Block(
        id INTEGER PRIMARY KEY, 
        uuid STRING, 
        content STRING,
        level INTEGER,
        PRIMARY KEY (uuid)
    );
    CREATE REL TABLE Parent_Of(FROM Block TO Block);
""")

// Query hierarchical relationships
val result = conn.query("""
    MATCH (parent:Block)-[:Parent_Of*]->(child:Block)
    WHERE parent.uuid = 'root-uuid'
    RETURN child.content
""")
```

**Migration Path:**
- **Hierarchical queries**: Use Cypher's variable-length path matching (`-[:Parent_Of*]->`)
- **References**: Direct relationship queries
- **Performance**: Should excel at Logseq's graph traversal patterns

### **2. Neo4j Embedded** ⭐⭐⭐⭐

**Why it could work:**
- **Embedded mode**: Can run in-process (though not its primary use case)
- **Cypher queries**: Mature, expressive query language
- **Rich ecosystem**: Extensive tooling and community
- **ACID transactions**: Full transactional guarantees

**Java Integration:**
```java
// Neo4j embedded example
DatabaseManagementService managementService = 
    new DatabaseManagementServiceBuilder(databaseDirectory)
        .build();
GraphDatabaseService db = managementService.database(DEFAULT_DATABASE_NAME);

// Cypher queries for Logseq patterns
try (Transaction tx = db.beginTx()) {
    Result result = tx.execute("""
        MATCH (parent:Block {uuid: $rootUuid})
        MATCH path = (parent)-[:PARENT_OF*]->(child:Block)
        RETURN child.content, length(path) as depth
        ORDER BY depth
    """, Map.of("rootUuid", rootUuid));
}
```

**Considerations:**
- **Licensing**: Community edition has limitations, Enterprise is expensive
- **Performance**: Good but Kuzu may be faster for analytical queries
- **Complexity**: More heavyweight than pure embedded solutions

### **3. Bitsy Graph Database** ⭐⭐⭐⭐

**Why it could work:**
- **Pure Java**: Lightweight, embeddable graph database
- **TinkerPop/Gremlin**: Standard graph query language
- **ACID transactions**: Full transactional support
- **Simple API**: Easy to integrate

**Java Integration:**
```java
// Bitsy example
Graph graph = BitsyGraph.open("./logseq-db");

// Create Logseq schema
VertexType blockType = graph.createVertexType("Block");
EdgeType parentType = graph.createEdgeType("parent_of");

// Add blocks with hierarchy
Vertex rootBlock = graph.addVertex(blockType, "uuid", rootUuid);
Vertex childBlock = graph.addVertex(blockType, "uuid", childUuid);
graph.addEdge(parentType, rootBlock, childBlock);

// Query hierarchy
Iterator<Vertex> descendants = graph.traversal()
    .V().has("uuid", rootUuid)
    .repeat(__.out("parent_of"))
    .emit();
```

**Strengths:**
- **Lightweight**: Minimal dependencies
- **Standards-compliant**: Uses TinkerPop/Gremlin
- **ACID**: Full transactional support

### **4. OrientDB Embedded** ⭐⭐⭐

**Why it could work:**
- **Multi-model**: Document + Graph capabilities
- **SQL-like queries**: Familiar syntax
- **ACID transactions**: Full transactional support
- **Embedded mode**: Can run in-process

**Considerations:**
- **Acquisition by SAP**: Future uncertain
- **Complexity**: More features than needed for Logseq
- **Performance**: Good but may have overhead

### **5. Custom SQLDelight + Graph Extensions** ⭐⭐⭐

**Why it could work:**
- **Already in your project**: No new dependencies
- **Type safety**: Compile-time verification
- **Multiplatform**: Works across all targets
- **Performance**: Excellent for simple queries

**Enhanced Approach:**
```kotlin
// Repository interface with graph capabilities
interface GraphRepository {
    fun getBlockHierarchy(rootUuid: String): Flow<List<BlockWithDepth>>
    fun getBlockReferences(blockUuid: String): Flow<List<BlockReference>>
    fun findBlocksByContent(query: String): Flow<List<Block>>
}

// SQLDelight with recursive CTEs for hierarchy
private const val GET_BLOCK_HIERARCHY = """
    WITH RECURSIVE block_tree AS (
        SELECT id, uuid, content, parent_id, 0 as depth
        FROM blocks 
        WHERE uuid = ?
        
        UNION ALL
        
        SELECT b.id, b.uuid, b.content, b.parent_id, bt.depth + 1
        FROM blocks b
        JOIN block_tree bt ON b.parent_id = bt.id
    )
    SELECT * FROM block_tree ORDER BY depth, id
"""
```

## **Repository Pattern Implementation**

Here's how to structure your repositories for easy backend swapping:

```kotlin
// Core repository interfaces
interface BlockRepository {
    fun getBlockByUuid(uuid: String): Flow<Block?>
    fun getBlockHierarchy(rootUuid: String): Flow<List<BlockWithDepth>>
    fun saveBlock(block: Block): Flow<Result<Unit>>
    fun deleteBlock(uuid: String): Flow<Result<Unit>>
}

interface PageRepository {
    fun getPageByUuid(uuid: String): Flow<Page?>
    fun getPagesInNamespace(namespace: String): Flow<List<Page>>
    fun savePage(page: Page): Flow<Result<Unit>>
}

interface ReferenceRepository {
    fun getBlockReferences(blockUuid: String): Flow<List<BlockReference>>
    fun addReference(fromUuid: String, toUuid: String, type: ReferenceType): Flow<Result<Unit>>
}

// Factory for backend selection
class RepositoryFactory {
    fun createBlockRepository(backend: GraphBackend): BlockRepository {
        return when (backend) {
            GraphBackend.KUZU -> KuzuBlockRepository(kuzuConnection)
            GraphBackend.SQLDELIGHT -> SqlDelightBlockRepository(database)
            GraphBackend.NEO4J -> Neo4jBlockRepository(neo4jDb)
        }
    }
}
```

## **Performance Testing Strategy**

Create a test harness to compare implementations:

```kotlin
class GraphPerformanceTest {
    @Test
    fun `test block hierarchy retrieval performance`() {
        val backends = listOf(
            GraphBackend.KUZU,
            GraphBackend.SQLDELIGHT, 
            GraphBackend.NEO4J
        )
        
        backends.forEach { backend ->
            val repo = repositoryFactory.createBlockRepository(backend)
            val duration = measureTime {
                // Load test data with 1000+ blocks in hierarchy
                val hierarchy = repo.getBlockHierarchy(rootUuid).first()
                assert(hierarchy.size > 1000)
            }
            println("$backend: ${duration.inWholeMilliseconds}ms")
        }
    }
}
```

## **Migration Strategy**

### **Phase 1: Repository Abstraction (2-3 weeks)**
1. Define repository interfaces based on your query analysis
2. Implement SQLDelight version (already partially done)
3. Create test harness with realistic data
4. Verify interface completeness

### **Phase 2: Alternative Backend Implementation (3-4 weeks)**
1. Choose primary alternative (Kuzu recommended)
2. Implement repositories for chosen backend
3. Performance testing against SQLDelight baseline
4. Integration testing with existing business logic

### **Phase 3: Backend Selection & Optimization (2-3 weeks)**
1. Compare performance results
2. Select winning backend based on metrics
3. Optimize schema and queries for chosen backend
4. Full integration testing

## **Recommendation Summary**

**Primary Recommendation: Kuzu**
- Best performance for graph traversals
- Modern, actively maintained
- Excellent fit for Logseq's hierarchical data model
- Multiplatform support

**Fallback: Enhanced SQLDelight**
- Already invested, lower risk
- Good performance for simple queries
- Can handle hierarchy with CTEs
- Guaranteed to work across all platforms

**Repository Pattern Benefits:**
- Easy backend swapping for performance testing
- Clean separation of concerns
- Future-proof architecture
- Enables gradual migration

This approach gives you both immediate progress (continue with SQLDelight) and a path to potentially significant performance improvements (Kuzu) without major rewrites.</content>
<parameter name="filePath">GRAPH_DATABASE_ALTERNATIVES.md