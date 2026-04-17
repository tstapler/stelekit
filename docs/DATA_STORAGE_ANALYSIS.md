# Logseq Data Storage Analysis: Clojure vs KMP

## Executive Summary

This document analyzes **why** the Clojure implementation stores data in the EAV (Entity-Attribute-Value) format via Datascript, and provides recommendations for the KMP SQLDelight implementation.

---

## How the Clojure Implementation Stores Data

### 1. The Datom Model

Logseq (Clojure) uses **Datascript**, which is a ClojureScript port of Datomic. The fundamental unit of data is a **datom**:

```
[entity-id attribute value transaction added?]

Example:
[:db/add 123 :block/uuid #uuid "a1b2c3d4-e5f6-..." true]
[:db/add 123 :block/content "Hello world" 456 true]
[:db/add 123 :block/page 789 456 true]
[:db/add 789 :block/name "hello" 456 true]
```

### 2. Why EAV (Entity-Attribute-Value)?

The EAV model is not arbitrary - it solves specific problems:

#### Problem: Flexible Schema
```
Traditional SQL:    ALTER TABLE blocks ADD COLUMN custom_field TEXT;
Datascript:         Just start using :block/custom-field - no migration needed!
```

**Key insight**: Logseq users can add arbitrary properties to blocks (tags, aliases, custom metadata). EAV handles this naturally.

#### Problem: Query Flexibility

Datalog queries can traverse relationships efficiently:

```clojure
;; Find all blocks that reference a page
(d/q '[:find ?block
      :where
       [?page :block/name "tasks"]
       [?block :block/page ?page]]
     db)

;; Find all blocks with a specific tag
(d/q '[:find ?block
      :where
       [?block :block/tags :logseq.class/Todo]]
     db)

;; Complex: Find pages with untagged blocks
(d/q '[:find ?page
      :where
       [?page :block/name]
       [?block :block/page ?page]
       (not [?block :block/tags])]
     db)
```

#### Problem: Temporal Data

Every datom includes a transaction ID, enabling:
- Point-in-time queries (`db-as-of tx-id`)
- Historical analysis
- Undo/redo (retract datoms)

### 3. The Three Indexes

Datascript maintains three indexes for different query patterns:

| Index | Order | Use Case |
|-------|-------|----------|
| **:eavt** | Entity → Attribute → Value | Entity lookups, schema introspection |
| **:avet** | Attribute → Value → Entity | Finding entities by attribute value |
| **:aevt** | Attribute → Entity → Value | Finding all values of an attribute |

```clojure
;; :eavt - Get all facts about entity 123
(d/datoms db :eavt 123)
;; => [{:e 123, :a :block/uuid, :v #uuid ...}
;;     {:e 123, :a :block/content, :v "..."}
;;     {:e 123, :a :block/page, :v 789}]

;; :avet - Find all blocks with :block/tags :logseq.class/Todo
(d/datoms db :avet :block/tags :logseq.class/Todo)

;; :aevt - Get all :block/title values (for autocomplete)
(d/datoms db :aevt :block/title)
```

### 4. SQLite as Persistence Layer

Datascript is in-memory. For persistence, Logseq exports datoms to SQLite:

```sql
-- SQLite stores datoms in a special format
-- This is NOT the relational tables you might expect!

-- The actual storage (simplified):
CREATE TABLE datoms (
  e INTEGER NOT NULL,   -- entity id
  a TEXT NOT NULL,      -- attribute (e.g., :block/title)
  v TEXT NOT NULL,      -- value (Transit-encoded)
  tx INTEGER NOT NULL,  -- transaction id
  added BOOLEAN NOT NULL
);

-- Indexes match the three Datascript indexes
CREATE INDEX idx_eavt ON datoms (e, a, v);
CREATE INDEX idx_avet ON datoms (a, v, e);
CREATE INDEX idx_aevt ON datoms (a, e, v);
```

### 5. Why Not Relational Tables?

This is the critical question. Logseq COULD use relational tables:

```sql
CREATE TABLE blocks (
  id INTEGER PRIMARY KEY,
  uuid TEXT UNIQUE,
  content TEXT,
  page_id INTEGER,
  parent_id INTEGER,
  properties TEXT  -- JSON
);

CREATE TABLE pages (
  id INTEGER PRIMARY KEY,
  uuid TEXT UNIQUE,
  name TEXT UNIQUE,
  properties TEXT
);
```

**But they chose EAV because:**

| Requirement | EAV Solution | Relational Solution |
|-------------|--------------|---------------------|
| Flexible properties | Native (just add datoms) | JSON column or EAV tables |
| Datalog queries | Native support | Complex recursive CTEs |
| Schema evolution | Zero migration | ALTER TABLE needed |
| Temporal queries | Native (tx included) | Complex history tables |
| References | Native refs | Foreign keys |

---

## The KMP Implementation Tradeoffs

### Current KMP Schema (Relational)

```sql
CREATE TABLE pages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    namespace TEXT,
    file_path TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    properties TEXT
);

CREATE TABLE blocks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    page_id INTEGER NOT NULL,
    parent_id INTEGER,
    left_id INTEGER,
    content TEXT NOT NULL,
    level INTEGER NOT NULL DEFAULT 0,
    position INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    properties TEXT
);
```

### Benchmark Results (Current)

| Operation | IN_MEMORY | DATASCRIPT | SQLDELIGHT |
|-----------|-----------|------------|------------|
| Block Hierarchy | 60.1µs | 26.1µs | **1.48ms** |
| Block Children | 59.0µs | 29.5µs | **259.4µs** |
| Page Get All | 44.5µs | 27.5µs | **1.03ms** |

**SQLDelight is 10-50x slower** for hierarchical queries due to:
1. Recursive CTE overhead
2. Missing materialized path pattern
3. No Datalog optimization

---

## Recommendation: Hybrid Approach for KMP

### Option A: Enhanced Relational (Recommended for KMP)

Keep relational schema but add optimizations:

```sql
-- Materialized path for O(1) ancestor queries
ALTER TABLE blocks ADD COLUMN path TEXT;  -- "/123/456/789"

-- Recursive CTE alternative: stored hierarchy
CREATE TABLE block_hierarchy (
    ancestor_id INTEGER,
    descendant_id INTEGER,
    depth INTEGER,
    PRIMARY KEY (ancestor_id, descendant_id)
);
```

**Pros**: Simple, SQL-standard, good for most queries
**Cons**: Hierarchy traversal still slower than Datalog

### Option B: EAV with SQLDelight

Implement EAV pattern with SQLDelight:

```sql
-- EAV storage (like Datascript)
CREATE TABLE datoms (
    e INTEGER NOT NULL,
    a TEXT NOT NULL,
    v TEXT NOT NULL,  -- Transit-encoded
    tx INTEGER NOT NULL,
    added BOOLEAN NOT NULL
);

-- Entity view for common queries
CREATE VIEW entities AS
SELECT e, 
       MAX(CASE WHEN a = 'type' THEN v END) as type,
       MAX(CASE WHEN a = 'uuid' THEN v END) as uuid
FROM datoms 
GROUP BY e;
```

**Pros**: Can restore from SQLite, Datalog-like queries possible
**Cons**: Complex, loses SQL benefits

### Option C: Two-Layer (Logseq-style)

1. **SQLDelight** for persistence (EAV format)
2. **In-memory graph** for queries (custom or Datascript port)

This is exactly what Logseq does. For KMP:

```kotlin
// Persistence layer (SQLDelight)
class DatomStorage(private val database: SteleDatabase) {
    fun insertDatoms(datoms: List<Datom>)
    fun getDatoms(e: Long): List<Datom>
    fun queryDatoms(a: String, v: String): List<Datom>
}

// Query layer (in-memory)
class InMemoryGraph(private val storage: DatomStorage) {
    fun entity(id: Long): Entity
    fun query(datalog: String): List<Entity>
    fun transact(txData: List<Datom>)
}
```

---

## Analysis: Why Datascript Wins for This Workload

### 1. Hierarchical Data is Graph-Like

```
Block 123
├── Block 456 (child)
│   ├── Block 789 (grandchild)
│   └── Block 790 (grandchild)
└── Block 457 (child)
```

**Datascript approach**:
```clojure
;; Single Datalog query, optimized internally
(d/q '[:find (pull ?block [*])
       :where
        [?root :block/uuid "123"]
        [?block :block/parent ?parent]
        [?parent :block/_children ?root]]
     db)
```

**SQL approach**:
```sql
WITH RECURSIVE hierarchy AS (
    SELECT id, uuid, content, 0 as depth
    FROM blocks WHERE uuid = '123'
    UNION ALL
    SELECT b.id, b.uuid, b.content, h.depth + 1
    FROM blocks b
    JOIN hierarchy h ON b.parent_id = h.id
)
SELECT * FROM hierarchy;
```

**Why Datascript wins**: The query planner optimizes Datalog. SQL requires full CTE evaluation.

### 2. Property Flexibility

Users add properties freely:

```markdown
- TODO #task #urgent #today
  due:: 2024-01-15
  estimate:: 2h
  @person:: john
```

**Datascript**: Just insert datoms
```clojure
[[:db/add -1 :block/tags :logseq.class/Todo]
 [:db/add -1 :block/tags :tag/task]
 [:db/add -1 :block/tags :tag/urgent]
 [:db/add -1 :block/tags :tag/today]
 [:db/add -1 :block/property "due" "2024-01-15"]
 [:db/add -1 :block/property "estimate" "2h"]
 [:db/add -1 :block/refs [:block/uuid "john"]]]
```

**Relational SQL**: JSON column or additional tables
```kotlin
// Current KMP approach
properties = "due:2024-01-15,estimate:2h"  // Parse on read!
```

### 3. Query Composition

Datalog composes naturally:

```clojure
;; Find pages with untagged TODO blocks that are overdue
(d/q '[:find (pull ?page [:block/title :block/name])
       :where
        [?page :block/name]
        [?block :block/page ?page]
        [?block :block/tags :logseq.class/Todo]
        (not [?block :block/tags :tag/done])
        [?block :block/property "due" ?due]
        [(.before ?due #时光)]
       :sort-by ?page]
     db)
```

Equivalent SQL requires multiple joins or application-level filtering.

---

## Decision Matrix for KMP

| Factor | Weight | Relational | EAV | Two-Layer |
|--------|--------|------------|-----|-----------|
| Query performance | High | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| Implementation complexity | Medium | ⭐⭐⭐ | ⭐⭐ | ⭐ |
| Persistence compatibility | Medium | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| SQL tooling support | Low | ⭐⭐⭐ | ⭐⭐ | ⭐ |
| Logseq compatibility | Low | ⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| Long-term maintainability | High | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ |

### Recommended: Option A (Enhanced Relational)

For most use cases, stick with relational but add:

1. **Materialized path** for hierarchy queries
2. **Property table** for flexible properties
3. **Graph layer** for complex traversals

```sql
-- Enhanced schema
CREATE TABLE blocks (
    id INTEGER PRIMARY KEY,
    uuid TEXT UNIQUE,
    page_id INTEGER,
    parent_id INTEGER,
    content TEXT,
    level INTEGER,
    position INTEGER,
    
    -- Materialized path for O(1) ancestor/descendant queries
    path TEXT,                    -- "/123/456/789"
    path_depth INTEGER,           -- 2 for depth 2
    
    -- For flat queries
    created_at INTEGER,
    updated_at INTEGER,
    properties TEXT
);

-- Indexes
CREATE INDEX idx_blocks_path ON blocks (path);
CREATE INDEX idx_blocks_page_position ON blocks (page_id, position);

-- Property table for flexible properties
CREATE TABLE block_properties (
    id INTEGER PRIMARY KEY,
    block_id INTEGER NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    FOREIGN KEY (block_id) REFERENCES blocks(id) ON DELETE CASCADE,
    UNIQUE(block_id, key)
);
```

### Recommended: Option C (Two-Layer) for Full Compatibility

If you need to share data with Logseq or need Datalog-like queries:

1. **SQLDelight** stores EAV datoms
2. **In-memory graph** indexes for queries
3. **Sync** on startup/shutdown

---

## Files of Interest

| File | Purpose |
|------|---------|
| `deps/db/src/logseq/db/file_based/schema.cljs` | Datascript schema definitions |
| `deps/db/src/logseq/db/frontend/schema.cljs` | Frontend schema extensions |
| `deps/db/src/logseq/db.cljs:105` | `transact-sync` - writes to Datascript |
| `deps/db/src/logseq/db.cljs:149` | `transact!` - main transaction function |
| `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` | KMP schema |
| `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt` | KMP implementation |

---

## Summary

| Question | Answer |
|----------|--------|
| **Why EAV?** | Schema flexibility, Datalog queries, temporal data |
| **Why not relational?** | Complex schema evolution, poor graph query performance |
| **What does SQLite store?** | Datoms (EAV triples), not relational tables |
| **KMP recommendation** | Enhanced relational with materialized path OR two-layer EAV |

The key insight is that **Logseq's data model is inherently graph-like** - blocks reference other blocks, pages have namespaces, properties are flexible. Datascript's EAV model is purpose-built for this. The KMP implementation should embrace either:
1. Enhanced relational (simpler, acceptable performance)
2. Two-layer (full compatibility, Datalog-like queries)
