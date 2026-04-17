# Logseq Architecture: Datascript + SQLite Hybrid

## Overview

Logseq uses a **dual-database architecture** that combines the speed of an in-memory Datascript database with the persistence of SQLite. This design enables fast queries and reactive UI updates while ensuring data survives app restarts.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Application Runtime                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   ┌─────────────────┐                                               │
│   │   Datascript    │  ← Hot database (in-memory)                   │
│   │   (fast read)   │    - Indexed for queries                      │
│   │                 │    - Reactive listeners                        │
│   │   :memory       │    - Datalog queries                          │
│   └────────┬────────┘                                               │
│            │ reads                                                  │
│            │ writes                                                 │
│    ┌───────┴───────┐                                               │
│    ▼               ▼                                                │
│ ┌───────────┐  ┌─────────────┐                                     │
│ │ Datascript│  │   SQLite    │  ← Cold database (persistence)       │
│ │ :memory   │  │   (backup)  │    - Datom format storage            │
│ │           │  │             │    - Import/export support           │
│ └───────────┘  └─────────────┘                                     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Why This Design?

| Database | Purpose | Strengths |
|----------|---------|-----------|
| **Datascript** | Query engine | Fast reads, Datalog, reactive, flexible schema |
| **SQLite** | Persistence | ACID, backup, cross-platform, import/export |

### The Problem Solved

1. **Performance**: Reading from files on every query is too slow
2. **Reactivity**: UI needs to update when data changes
3. **Persistence**: Data must survive app restarts
4. **Flexibility**: Schema evolves without migrations

## Data Flow

### Startup (Cold Start)

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ Markdown Files  │────▶│ graph-parser    │────▶│ Datascript      │
│ (pages/, .md)   │     │ parse-file      │     │ :memory         │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                                                         │
                                                         ▼ Export
                                               ┌─────────────────┐
                                               │ SQLite (datoms) │
                                               │ persistence     │
                                               └─────────────────┘
```

### Startup (Warm Start - Restore from SQLite)

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ SQLite datoms   │────▶│ d/conn-from-    │────▶│ Datascript      │
│ (fast restore)  │     │ datoms          │     │ :memory         │
└─────────────────┘     └─────────────────┘     └─────────────────┘
```

### Runtime (Read)

```
User Query ─────▶ Datascript (in-memory)
                    │ Datalog query
                    ▼
              Fast response (cached)
```

### Runtime (Write)

```
User Edit ─────▶ Datascript transact!
                    │ 
        ┌─────────┼─────────┐
        ▼         ▼         ▼
   Listeners  In-memory  Background
   notify     update     sync to SQLite
```

## Key Components

### 1. Graph Parser (`deps/graph-parser/`)

Parses markdown files into Datascript entities:

```
File: pages/hello.md
─────────────────────
# Hello World

- This is a block
- Another block

─────────────────────
Parsed to:
{:block/name "hello-world"
 :block/title "Hello World"
 :block/uuid #uuid "..."
 :block/file {:file/path "pages/hello.md"}}

{:block/uuid #uuid "..."
 :block/content "This is a block"
 :block/parent #uuid "..."}
```

**Files of interest**:
- `deps/graph-parser/src/logseq/graph_parser.cljs:59` - `parse-file` entry point
- `deps/graph-parser/src/logseq/graph_parser/extract.cljc:272` - `extract` function
- `deps/graph-parser/src/logseq/graph_parser/block.cljs` - Block AST conversion

### 2. Datascript Database (`deps/db/`)

**Datascript** is a ClojureScript port of Datomic - an immutable database that stores facts as "datoms":

```clojure
;; A datom is: [entity attribute value transaction added?]
;; Example datoms:
[:db/add 123 :block/uuid #uuid "a1b2c3d4"]
[:db/add 123 :block/content "Hello"]
[:db/add 123 :block/parent 456]
[:db/add 456 :block/type "page"]
```

**Query with Datalog**:
```clojure
;; Find all blocks under a page
(d/q '[:find ?block
      :where
       [?page :block/name "hello"]
       [?block :block/page ?page]]
     db)
```

**Files of interest**:
- `deps/db/src/logseq/db.cljs:149` - `transact!` function
- `deps/db/src/logseq/db.cljs:51` - `transact-sync` (syncs to SQLite)
- `deps/db/src/logseq/db.cljs:24` - `conn-from-datoms` (restore from SQLite)

### 3. SQLite Persistence Layer (`src/main/frontend/persist_db/`)

Stores datoms in SQLite for persistence:

```sql
-- SQLite schema for datom storage
CREATE TABLE datoms (
  e INTEGER NOT NULL,      -- entity id
  a TEXT NOT NULL,         -- attribute
  v TEXT NOT NULL,         -- value (transit encoded)
  tx INTEGER NOT NULL,     -- transaction
  added BOOLEAN NOT NULL,  -- true for add, false for retract
  UNIQUE (e, a, v, tx)
);

-- Indexes for fast queries
CREATE INDEX idx_datoms_eav ON datoms (e, a, v);
CREATE INDEX idx_datoms_av ON datoms (a, v);
```

**Files of interest**:
- `src/main/frontend/db/restore.cljs` - Restore graph from SQLite
- `src/main/frontend/persist_db/browser.cljs` - Browser persistence
- `src/main/frontend/worker/db_worker.cljs` - DB worker thread

### 4. Transaction Pipeline

```
User Edit → frontend.handler.editor
                │
                ▼
         ldb/transact! (deps/db/src/logseq/db.cljs:149)
                │
    ┌───────────┴───────────┐
    ▼                       ▼
d/transact!            delete-blocks/
                       update-refs-history-
                       and-macros
    │                       │
    └───────────┬───────────┘
                ▼
        dc/store-after-transact!
        (sync to SQLite)
                │
                ▼
        Listeners notified
        (UI updates)
```

## The Datom Format

Datascript stores everything as **datoms** - atomic facts:

```clojure
;; Entity 123 is a block
[:db/add 123 :db/ident :block]

;; Entity 123 has UUID "a1b2c3"
[:db/add 123 :block/uuid #uuid "a1b2c3d4-e5f6-..."]

;; Entity 123 is on page 456
[:db/add 123 :block/page 456]

;; Entity 456 is a page named "hello"
[:db/add 456 :block/name "hello"]
```

### Entity-Attribute-Value (EAV) Pattern

This is the fundamental data model:

```
Entity 123: Block
├── :db/ident = :block
├── :block/uuid = #uuid "..."
├── :block/content = "Hello"
├── :block/page = 456
└── :block/parent = 789
```

### Schema Definition

```clojure
;; From deps/db/src/logseq/db/file_based/schema.cljs
(def schema
  {:block/uuid {:db/unique :db.unique/identity}
   :block/name {:db/unique :db.unique/identity}
   :block/page {:db/type :db.type/ref
                :db/cardinality :db.cardinality/one}
   :block/children {:db/type :db.type/ref
                    :db/cardinality :db.cardinality/many}
   ...})
```

## Startup Performance

| Phase | Operation | Typical Time |
|-------|-----------|--------------|
| 1 | Scan graph directory | 10-100ms |
| 2 | Parse markdown files | 100-1000ms |
| 3 | Build Datascript datoms | 50-200ms |
| 4 | Export to SQLite | 100-500ms |
| **Total** | **Graph load** | **~1-2 seconds** |

### Warm Start (Restore from SQLite)

When SQLite exists, startup is faster:

| Phase | Operation | Typical Time |
|-------|-----------|--------------|
| 1 | Read datoms from SQLite | 200-500ms |
| 2 | Rebuild Datascript | 50-100ms |
| **Total** | **Restore** | **~300-600ms** |

## Implications for KMP Migration

### Current State

| Component | Technology | Notes |
|-----------|------------|-------|
| Query engine | Datascript | In-memory, Datalog |
| Parser | ClojureScript | Parses markdown to AST |
| Persistence | SQLite (datoms) | Backup/sync |

### Migration Considerations

#### Option 1: Datom-style Storage (Recommended for compatibility)

Store data as datom triples in SQLDelight:

```sql
-- Tables
CREATE TABLE datoms (
  e INTEGER NOT NULL,
  a TEXT NOT NULL,
  v TEXT NOT NULL,
  tx INTEGER NOT NULL,
  added BOOLEAN NOT NULL
);

CREATE TABLE schema (
  attr TEXT PRIMARY KEY,
  type TEXT,
  unique_value TEXT
);
```

**Pros**:
- Can restore from existing SQLite
- Aligns with Logseq architecture
- Flexible schema

**Cons**:
- Loses SQL query benefits
- Complex migration

#### Option 2: Relational Storage (Current KMP impl)

Store data in native tables:

```sql
CREATE TABLE blocks (
  id INTEGER PRIMARY KEY,
  uuid TEXT UNIQUE,
  content TEXT,
  page_id INTEGER,
  parent_id INTEGER
);
```

**Pros**:
- Natural SQL queries
- Simpler implementation
- Better SQL performance

**Cons**:
- Need separate persistence strategy
- Doesn't align with existing data

### Recommended Approach

For KMP migration, use **Option 2 (Relational)** with these addons:

1. **Periodic export** - Export to JSON/transit for backup
2. **Import capability** - Read from graph-parser output
3. **Async indexing** - Don't block UI during load
4. **Query optimization** - Add indexes for common queries

## Code Navigation

```
Logseq Repository Structure
├── deps/
│   ├── graph-parser/          # Parse markdown → AST → Datascript
│   │   └── src/logseq/graph_parser/
│   │       ├── graph_parser.cljs    # parse-file entry
│   │       ├── extract.cljc         # extract function
│   │       ├── block.cljs           # AST → Datom conversion
│   │       └── mldoc.cljc           # Markdown parsing
│   │
│   └── db/                    # Datascript + SQLite sync
│       └── src/logseq/db/
│           ├── db.cljs              # transact!, conn-from-datoms
│           ├── sqlite/              # SQLite persistence
│           └── frontend/
│               └── entity_util.cljs
│
├── src/main/frontend/
│   ├── db/
│   │   ├── restore.cljs       # Restore from SQLite
│   │   └── conn.cljs          # Connection management
│   │
│   └── persist_db/
│       ├── persist_db.cljs    # Persistence protocol
│       └── browser.cljs       # Browser implementation
│
└── docs/
    └── ARCHITECTURE.md        # This file
```

## References

- [Datascript Documentation](https://github.com/tonsky/datascript)
- [Datomic Data Model](https://docs.datomic.com/on-prem/data-model.html)
- [Logseq Graph Parser](deps/graph-parser/)
- [KMP Repository Implementation](kmp/src/commonMain/kotlin/com/logseq/kmp/repository/)
