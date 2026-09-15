# Multi-DB Evaluation — Requirements

## Context

SteleKit is a Kotlin Multiplatform (KMP) outliner/note-taking app (Logseq migration) with
the following production targets:

| Platform  | Runtime                 | Current DB driver              |
|-----------|-------------------------|-------------------------------|
| Desktop   | JVM (Linux/macOS/Win)   | libsql JNI (this branch)      |
| Android   | JVM / Dalvik            | libsql JNI (this branch)      |
| iOS       | Kotlin/Native ARM64     | SQLDelight + SQLiter           |
| Web/WASM  | Kotlin WASM             | SQLDelight + OPFS driver       |

The `stelekit-libsql` branch already replaced the JDBC SQLite driver with a libsql JNI
driver providing MVCC (`BEGIN CONCURRENT`) and ~24% write throughput improvement on JVM.

## Goal

Evaluate the following embedded database engines — and surface any additional strong
candidates — to determine whether any provide a compelling improvement over libsql across
the four mandatory platforms.

## Databases to Evaluate

1. **libsql** (baseline) — already implemented; provides MVCC, Rust JNI bridge
2. **LadybugDB** — graph database (https://github.com/LadybugDB/ladybug)
3. **Limbo** — Rust SQLite-compatible rewrite (https://github.com/tursodatabase/limbo)
4. **DuckDB** — embedded OLAP/analytical database
5. **ClickHouse** — column-oriented analytics database (embedded variant)
6. **LevelDB / RocksDB** — LSM-tree key-value stores (Google / Meta)
7. **Any other strong candidates** surfaced by research

## Platform Constraint (Hard)

**All platforms must be supported simultaneously.** An engine that cannot run on all four
of JVM/Desktop, Android, iOS, and WASM/Web is disqualified from "replace libsql"
recommendation status (but may still be recommended as a complementary engine for
specific platforms or query types).

## Evaluation Dimensions

### 1. Performance
- Write throughput: INSERT/UPDATE/DELETE ops/sec
- Read latency: single-row lookup, paginated scan, full-text search
- Startup time: time-to-first-query on cold start

### 2. Query Capability
- Full-text search (Logseq heavily uses block content search)
- Graph traversal queries (backlinks, page references, hierarchical blocks)
- Aggregations (journal counts, tag frequencies, page statistics)
- Complex joins (block ↔ page ↔ reference ↔ property)

### 3. Cross-Platform Portability
- Does the engine have a Kotlin/Java API for JVM?
- Does it run on Android (ART/Dalvik) without root or special permissions?
- Does it compile to Kotlin/Native or have a C interop layer for iOS?
- Does it work in WASM (browser) — single-threaded, limited memory, no native threads?

### 4. Correctness / ACID
- Transaction isolation level
- Crash recovery
- MVCC support
- Concurrent reader/writer guarantees

### 5. Integration Cost
- Does it fit the SQLDelight `SqlDriver` interface (required for code-gen queries)?
- If not, how much custom adapter code is needed?
- License compatibility (Apache 2.0 / MIT preferred)
- Maintenance status / community health

## Success Criteria

1. A scored comparison matrix covering all databases × all evaluation dimensions
2. A clear recommendation: "replace libsql", "complement libsql", or "disqualify"
   for each candidate engine — with rationale
3. If any replacement is recommended, an implementation plan covering:
   - Driver adapter code required per platform
   - Migration path from current libsql schema
   - Test coverage requirements
4. If no replacement is recommended, a statement of libsql's advantages and what
   would need to change for a future reassessment

## Out of Scope

- Remote/client-server databases (PostgreSQL, MySQL, etc.)
- Cloud-only solutions
- Engines requiring JVM-only (no cross-platform path)
- Any engine with a GPL or AGPL license incompatible with app distribution

## Stakeholders

- Primary: Tyler Stapler (sole developer)
- Affected users: Desktop, Android, iOS, and Web SteleKit users

## Current Branch State

The `stelekit-libsql` branch PR #171 is ready but not yet merged. This evaluation
should produce a recommendation on whether to:
a) Merge libsql as-is (best current option)
b) Extend the PR to add an additional engine alongside libsql
c) Replace libsql with a different engine before merging
