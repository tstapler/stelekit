# Migration Plan: Search & Queries

## 1. Discovery & Requirements
Search is how users find content. Queries (Simple and Advanced) allow dynamic content aggregation.

### Existing Artifacts
- `src/main/frontend/components/search.cljs`: UI for the search modal (Cmd+K).
- `src/main/frontend/components/query.cljs`: Rendering of query results.
- `src/main/frontend/worker/search.cljs`: Background worker for indexing/searching.

### Functional Requirements
- **Global Search (Cmd+K)**: Fuzzy search for pages and blocks.
- **Simple Queries**: `{{query (and [[tag1]] [[tag2]])}}`.
- **Advanced Queries**: Datalog queries `{:find [?b] :where ...}`.
- **Live Update**: Query results update as data changes.

### Non-Functional Requirements
- **Speed**: Search results < 50ms.
- **Freshness**: New blocks appear in search immediately.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **SearchEngine**: Interface for search operations.
    - Implementation 1: **FTS5 (SQLite)** for full-text search.
    - Implementation 2: **Datalog Engine** (Ported or new implementation) for structural queries.
- **QueryParser**: Parse `{{query ...}}` strings.
- **IndexManager**: Keep the search index in sync with the DB.

### UI Layer (Compose Multiplatform)
- **Component**: `SearchModal` (Popup dialog).
- **Component**: `QueryBlock` (Embedded result list).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Compatibility: Datalog Dialect [SEVERITY: Critical]
- **Description**: Logseq uses Datascript (Clojure). Porting this to Kotlin/SQLite is non-trivial. Existing user queries rely on specific Datascript behavior.
- **Mitigation**:
    - Option A: Embed a Datalog engine in Kotlin (e.g., Datahike-like or a custom port).
    - Option B: Transpile Datalog to Recursive CTEs in SQLite (Complex).
    - *Decision*: Likely need to maintain a Datalog compatibility layer or embed a lightweight Datalog engine.

### 🐛 Performance: Indexing Lag [SEVERITY: Medium]
- **Description**: If indexing happens on the main thread, UI freezes.
- **Mitigation**: Run indexing in a background Coroutine/Worker. Use SQLite's FTS5 which is very fast.

## 4. Implementation Roadmap

### Phase 1: Text Search
- [ ] Implement SQLite FTS5 schema.
- [ ] Implement `SearchService.search(query: String)`.
- [ ] Create `SearchModal` UI.

### Phase 2: Simple Queries
- [ ] Implement parser for `{{query ...}}`.
- [ ] Map simple queries (AND/OR/NOT) to SQL queries.

### Phase 3: Advanced Queries (Datalog)
- [ ] Research/Prototype Datalog-on-Kotlin solution.
- [ ] Implement execution engine.

## 5. Migration Checklist
- [ ] **Logic**: Full-text search works via FTS5.
- [ ] **Logic**: Simple queries (tags) work.
- [ ] **UI**: Search modal opens/closes and shows results.
- [ ] **Tests**: Query correctness tests.
- [ ] **Parity**: Advanced Datalog queries (Deferred to later phase if needed, but critical for power users).

