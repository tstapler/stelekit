# Logseq KMP - Production Database Setup

This document explains how to use the production-ready SQLDelight setup for the Logseq Kotlin Multiplatform migration.

## 🚀 Quick Start

### 1. Initialize the Database

```kotlin
import dev.stapler.stelekit.DatabaseConfig

// Initialize production database
val database = DatabaseConfig.initializeDatabase()

// Repositories are now automatically configured to use SQLDelight
val blockRepo = dev.stapler.stelekit.repository.Repositories.block()
val pageRepo = dev.stapler.stelekit.repository.Repositories.page()
```

### 2. Load Your Personal Logseq Data

```kotlin
import dev.stapler.stelekit.loader.LogseqDataLoader

// Load data from your personal wiki
val dataLoader = LogseqDataLoader(
    blockRepo, pageRepo, propertyRepo, referenceRepo
)

val result = dataLoader.loadGraph("~/Documents/personal-wiki/logseq")
result.fold(
    onSuccess = { stats ->
        println("Loaded ${stats.pagesLoaded} pages, ${stats.blocksLoaded} blocks")
    },
    onFailure = { error ->
        println("Error loading data: ${error.message}")
    }
)
```

### 3. Run the Application

```bash
# Run the production setup (loads your personal data)
./gradlew :kmp:jvmRun

# This will:
# 1. Initialize SQLDelight database
# 2. Load your personal Logseq data
# 3. Run performance validation
# 4. Show database statistics
```

## 📊 Database Configuration

### Platform-Specific Database Locations

The database automatically uses appropriate locations for each platform:

- **Windows**: `%APPDATA%\Logseq\logseq.db`
- **macOS**: `~/Library/Application Support/Logseq/logseq.db`
- **Linux**: `~/.local/share/logseq/logseq.db` (or `$XDG_DATA_HOME/logseq/logseq.db`)

### Database Optimizations

The setup includes several SQLite optimizations:

```kotlin
// WAL mode for better concurrency
PRAGMA journal_mode=WAL;

// Memory optimizations
PRAGMA cache_size=1000000;  // 1GB cache
PRAGMA mmap_size=268435456; // 256MB memory mapping
PRAGMA temp_store=MEMORY;
```

### Schema Management

The database automatically handles schema creation and migrations:

- **Version checking**: Tracks schema versions to avoid unnecessary migrations
- **Automatic setup**: Creates tables and indexes on first run
- **Migration support**: Framework ready for future schema changes

## 🏗️ Architecture Overview

### Repository Pattern Implementation

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Business      │    │   Repository     │    │   SQLDelight    │
│   Logic         │────│   Interfaces     │────│   Database      │
│                 │    │                  │    │                 │
│ • Block Ops     │    │ • BlockRepo      │    │ • block_queries │
│ • Page Ops      │    │ • PageRepo       │    │ • page_queries  │
│ • References    │    │ • ReferenceRepo  │    │ • ref_queries   │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

### Key Components

1. **DatabaseConfig**: Production database setup and lifecycle management
2. **RepositoryFactory**: Creates repository instances with backend selection
3. **LogseqDataLoader**: Parses markdown files and loads hierarchical data
4. **SQLDelight Repositories**: Type-safe database operations with CTEs

## 🔧 Usage Examples

### Basic CRUD Operations

```kotlin
// Create a page
val page = Page(
    id = generateId(),
    uuid = "page-uuid",
    name = "My Page",
    namespace = null,
    filePath = "/pages/my-page.md",
    createdAt = Clock.System.now(),
    updatedAt = Clock.System.now(),
    properties = mapOf("tags" to "important")
)

pageRepo.savePage(page)

// Create a hierarchical block structure
val rootBlock = Block(
    id = generateId(),
    uuid = "root-uuid",
    pageId = page.id,
    content = "Root block",
    level = 0,
    position = 0,
    // ... other fields
)

val childBlock = Block(
    id = generateId(),
    uuid = "child-uuid",
    pageId = page.id,
    parentId = rootBlock.id,
    content = "Child block",
    level = 1,
    position = 0,
    // ... other fields
)

blockRepo.saveBlock(rootBlock)
blockRepo.saveBlock(childBlock)
```

### Querying Hierarchical Data

```kotlin
// Get block with all its children
val hierarchy = blockRepo.getBlockHierarchy("root-uuid").first()
hierarchy.forEach { (block, depth) ->
    println("${"  ".repeat(depth)}- ${block.content}")
}

// Get ancestors of a block
val ancestors = blockRepo.getBlockAncestors("deep-child-uuid").first()
println("Ancestors: ${ancestors.map { it.content }}")

// Get all references to a block
val references = referenceRepo.getAllReferences("block-uuid").first()
println("Incoming refs: ${references.incoming.size}")
println("Outgoing refs: ${references.outgoing.size}")
```

### Advanced Graph Queries

```kotlin
// Find blocks that reference a specific concept
val referencingBlocks = referenceRepo.getIncomingReferences("important-concept-uuid").first()

// Get most connected blocks in the graph
val connectedBlocks = referenceRepo.getMostConnectedBlocks(10).first()

// Search blocks by content
val searchResults = searchRepo.searchBlocksByContent("kotlin").first()
```

## 🧪 Testing

### Unit Tests

```kotlin
@Test
fun `test block hierarchy operations`() = runBlocking {
    // Test with in-memory repository
    val blockRepo = Repositories.block(GraphBackend.IN_MEMORY)

    // Create test hierarchy
    val root = createTestBlock("root", level = 0)
    val child1 = createTestBlock("child1", parentId = root.id, level = 1)
    val child2 = createTestBlock("child2", parentId = root.id, level = 1)

    // Save blocks
    blockRepo.saveBlock(root)
    blockRepo.saveBlock(child1)
    blockRepo.saveBlock(child2)

    // Test hierarchy retrieval
    val hierarchy = blockRepo.getBlockHierarchy(root.uuid).first()
    assertEquals(3, hierarchy.size)
    assertEquals(0, hierarchy[0].depth) // root
    assertEquals(1, hierarchy[1].depth) // child1
    assertEquals(1, hierarchy[2].depth) // child2
}
```

### Integration Tests

```kotlin
@Test
fun `test full data loading pipeline`() = runBlocking {
    // Test with SQLDelight (when available)
    val database = createTestDatabase()
    Repositories.configure(database)

    val dataLoader = LogseqDataLoader(
        Repositories.block(),
        Repositories.page(),
        Repositories.property(),
        Repositories.reference()
    )

    // Test loading sample data
    val result = dataLoader.loadGraph("test-data/")
    assertTrue(result.isSuccess)

    val stats = result.getOrThrow()
    assertTrue(stats.pagesLoaded > 0)
    assertTrue(stats.blocksLoaded > 0)
}
```

## 📈 Performance Characteristics

### Expected Performance (based on analysis)

| Operation | Expected Time | Notes |
|-----------|---------------|-------|
| Block Lookup | 50-200μs | B-tree index |
| Block Children | 200-500μs | JOIN with CTE |
| Hierarchy (50 levels) | 1000-3000μs | Recursive CTE |
| Reference Query | 100-300μs | Foreign key lookup |
| Page Search | 300-800μs | Full-text search |

### Optimizations Included

1. **Database Indexes**: Optimized for common query patterns
2. **Connection Pooling**: Efficient connection management
3. **Prepared Statements**: Cached query plans
4. **Memory Mapping**: Large database files handled efficiently
5. **WAL Mode**: Better concurrency for reads/writes

## 🚀 Migration Path

### Phase 1: SQLDelight Production (Current)
- ✅ Production database setup
- ✅ Repository abstraction layer
- ✅ Data loading pipeline
- ✅ Performance validation
- **Goal**: Working Logseq KMP with SQLDelight

### Phase 2: Graph Database Optimization (Future)
- 🔄 Implement Kuzu backend
- 🔄 Side-by-side performance comparison
- 🔄 Migration planning for power users
- **Goal**: 2-3x performance improvement for complex graphs

## 🔧 Troubleshooting

### Common Issues

**Database File Locked**
```bash
# Close all Logseq instances
# Delete lock files if necessary
rm ~/.local/share/logseq/logseq.db-lock
```

**Out of Memory**
```kotlin
// Increase JVM memory
java -Xmx2g -jar your-app.jar
```

**Migration Errors**
```kotlin
// Reset database (development only)
DatabaseConfig.recreateTables(driver, database)
```

### Debug Information

```kotlin
// Get database statistics
val stats = DatabaseConfig.getDatabaseStats(database)
println(stats)

// Check repository backend
println("Using backend: ${Repositories.block()::class.simpleName}")
```

## 📚 Additional Resources

- [SQLDelight Documentation](https://sqldelight.github.io/)
- [Logseq Data Format](https://logseq.github.io/page/logseq-data-format)
- [SQLite Performance Tuning](https://www.sqlite.org/optimization.html)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)

---

**Ready to migrate your Logseq to Kotlin Multiplatform! 🚀**