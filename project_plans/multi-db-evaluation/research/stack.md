# Multi-DB Evaluation — Stack Research

## Summary Table

| Engine | Language | License | JVM | Android | iOS | WASM/Kotlin | SQLDelight | Integration Effort |
|---|---|---|---|---|---|---|---|---|
| **libsql** (baseline) | C / Rust | MIT | JNI (done) | JNI (done) | SQLiter | OPFS driver | Yes (current) | Done |
| **Turso** (Limbo successor) | Rust | MIT | JDBC (beta, Maven Central) | Not yet | Not yet | JS WASM only | No | High |
| **LadybugDB** | C++ | MIT | Maven Central | Build from source (C++) | Swift SDK exists | WASM (browser) | No | High |
| **DuckDB** | C++ | MIT | JDBC (stable 1.5.4) | Experimental (NDK) | Not supported | duckdb-wasm (JS only) | No | High |
| **ClickHouse** | C++ | Apache 2.0 | JDBC (server mode only) | No | No | No | No | Blocked |
| **RocksDB / LevelDB** | C++ | Apache 2.0 / BSD | RocksJava (official) | NDK build | C++ interop only | No | No | High/Blocked |
| **Realm Kotlin** | C++ / Kotlin | Apache 2.0 | JVM (deprecated sync) | Yes | Yes (K/N) | No | No | High |

---

## Engine Details

## libsql (Baseline)

**Language:** C (SQLite fork) + Rust cdylib JNI layer  
**License:** MIT  
**Stars / Activity:** 16.8k stars, active (last server release Feb 2025)

**JVM API:** Official Rust driver; TypeScript, Go, Python clients. No official Java/Kotlin SDK from Turso. SteleKit's current branch implements a custom JNI bridge to the Rust cdylib — this is already working and tested.

**Android:** JNI cdylib compiled for Android ABI; already working in this branch.

**iOS:** No libsql JNI path on iOS. The current approach keeps SQLDelight + SQLiter for iOS (the iOS ARM64 target does not use the JNI driver).

**WASM:** Not a Kotlin-WASM target; the OPFS SQLDelight driver handles the web target.

**SQLDelight compatibility:** Yes — wrapped behind a `SqlDriver` adapter (already implemented).

**Integration effort:** Done. This is the active branch.

**Notes:** The libsql README explicitly recommends new projects look at `tursodatabase/turso` (formerly Limbo) instead, as libsql's `BEGIN CONCURRENT` MVCC is inherited from the SQLite fork whereas Turso is a ground-up Rust rewrite with native async. libsql itself is still actively maintained.

---

## Turso Database (formerly Limbo)

**Language:** Rust (ground-up SQLite-compatible rewrite)  
**License:** MIT  
**Stars / Activity:** 19.2k stars, extremely active (v0.6.1, May 2026; 18k commits, 108 open PRs)  
**Repository:** https://github.com/tursodatabase/turso

**JVM API:** `bindings/java` — a JDBC driver built on Rust JNI (`rs_src/`), published to Maven Central as `tech.turso:turso`. Status is actively developed but explicitly "not yet published to Maven Central" as a stable artifact (requires local build + `publishToMavenLocal`). The README notes it is in progress.

**Android:** Not yet. The Java binding currently builds for `macos_x86`, `macos_arm64`, `windows`, and `linux_x86` only. Android ABI cross-compilation is not listed. Given Turso is Rust, Android support is possible via cross-compilation but has not been done.

**iOS:** No binding. The Swift/Kotlin/Native path is absent from the repository.

**WASM:** JavaScript/WASM binding via `@tursodatabase/database` (npm). This is the browser WASM path but targets JS, not Kotlin/WASM.

**SQLDelight compatibility:** No JDBC-to-SqlDriver adapter exists. Would require implementing a `SqlDriver` shim on top of the Turso JDBC driver (same effort as wrapping the existing libsql JNI).

**Features vs libsql:** Native `BEGIN CONCURRENT` MVCC, async I/O via io_uring, experimental full-text search (tantivy), vector search, CDC, broader `ALTER` support. SQLite file format and C API compatible.

**Integration effort:** High. The Java JDBC driver is beta / not yet Maven Central stable. Android and iOS paths are absent. Would need 2–3 months of work to reach feature parity with current libsql JNI bridge across all four platforms.

**Verdict:** Compelling future target (native Rust, truly concurrent writes), but not production-ready for KMP today. Monitor for v1.0 stability and Android build support.

---

## LadybugDB (formerly Kuzu)

**Language:** C++ (75.5%), Cypher (19.8%)  
**License:** MIT  
**Stars / Activity:** 1.3k stars, v0.17.1 (Jun 2, 2026), actively maintained  
**Repository:** https://github.com/LadybugDB/ladybug

**Description:** Embedded property graph database with Cypher query language. Formerly Kuzu. Columnar disk-based storage, CSR adjacency lists, vectorized query processor, serializable ACID transactions.

**JVM API:** Maven Central (`com.ladybugdb:lbug`). Java binding is available.

**Android:** C++ library; no prebuilt Android ABI artifacts. Would require NDK cross-compilation from the C++ source using CMake (feasible but manual).

**iOS:** Swift SDK exists (`swift-ladybug`). This is notable — iOS support is better than most alternatives here.

**WASM:** WASM bindings listed in the README (browser WASM, JS-oriented). Not Kotlin/WASM.

**SQLDelight compatibility:** No. LadybugDB uses Cypher, not SQL. It cannot plug into SQLDelight's `SqlDriver` interface without a complete custom adapter layer that translates SQL to Cypher — this is not feasible.

**FTS / vector:** Native full-text search and vector index — strong advantages for block content search.

**Integration effort:** High. Cypher ≠ SQL means SQLDelight code-gen is unusable. All queries would need to be rewritten or wrapped in a Cypher translation layer. Platform support gaps on Android and WASM (Kotlin/WASM) are blockers.

**Verdict:** Interesting for graph traversal (backlinks, page references) but a fundamental mismatch with the SQLDelight-based architecture. Cannot replace libsql across all four platforms without a major architecture change.

---

## DuckDB

**Language:** C++ (core), MIT license  
**License:** MIT  
**Stars / Activity:** Very high community usage; v1.5.4 current (2026)  
**Repository:** https://github.com/duckdb/duckdb

**JVM API:** Official JDBC driver on Maven Central (`org.duckdb:duckdb_jdbc`). Full JDBC 4.1 implementation with DuckDB-specific extensions (Appender, Arrow export/import, batch writer). Stable and production-ready on desktop.

**Android:** Experimental. Official build instructions exist using the Android NDK (CMake cross-compilation to `arm64-v8a` or `x86_64`). No prebuilt Android `.aar` or `.so` artifacts published to Maven; requires custom build. The docs label this "experimental."

**iOS:** Not supported. No official iOS build instructions or Swift binding. DuckDB's tertiary clients page lists Dart, Julia, PHP, and Swift (macOS) but not iOS ARM64.

**WASM:** `duckdb-wasm` — full-featured, compiled to WebAssembly, available as `@duckdb/duckdb-wasm` on npm. Runs in browser. Limitations: single-threaded by default, 4 GB memory cap. This is JS WASM, not Kotlin/WASM; cannot be used directly in a Kotlin/WASM target without a JS interop bridge.

**SQLDelight compatibility:** No. DuckDB uses its own extended SQL dialect, not SQLite. A `SqlDriver` adapter wrapping DuckDB's JDBC would be possible for JVM, but the iOS gap is a hard blocker.

**Query capability:** Excellent for OLAP, aggregations, FTS extension, graph query helpers, columnar scans. Potentially faster than SQLite for analytical queries (page statistics, journal counts, tag frequency).

**ACID / transactions:** Serializable isolation, MVCC. Single writer, multiple readers (similar to libsql without `BEGIN CONCURRENT`).

**Integration effort:** High for JVM (JDBC adapter needed), Blocked for iOS (no path), Medium for WASM (JS-only).

**Verdict:** Strong for desktop analytics but blocked on iOS. Cannot be the sole engine across all four platforms. Potential as a complementary analytics engine on JVM-only if aggregation queries become a bottleneck.

---

## ClickHouse (Embedded)

**Language:** C++ (Apache 2.0 for community edition, BSL for enterprise)  
**License:** Apache 2.0 (ClickHouse Open Source)

**Embedded variant:** ClickHouse does not have an embedded library mode analogous to SQLite/DuckDB. The `clickhouse-local` tool can run in-process from a CLI perspective, but there is no public `libclickhouse` C API designed for embedding in a mobile or desktop app. All Java SDKs (ClickHouse Java Client, JDBC driver) require connecting to a running ClickHouse server over HTTP/TCP.

**JVM API:** `com.clickhouse:clickhouse-jdbc` — connects to a remote ClickHouse server. Not suitable for embedded use.

**Android:** No.  
**iOS:** No.  
**WASM:** No.

**Integration effort:** Blocked. ClickHouse is not an embedded database engine. It is explicitly out of scope per the requirements ("Remote/client-server databases").

**Verdict:** Disqualified. Not an embedded database.

---

## LevelDB / RocksDB

**LevelDB**  
**Language:** C++ (Google)  
**License:** BSD 3-Clause  
**Repository:** https://github.com/google/leveldb

**RocksDB**  
**Language:** C++ (Meta / Facebook)  
**License:** Apache 2.0 (dual-licensed with GPLv2; pick Apache 2.0)  
**Stars / Activity:** 31.8k stars, v11.1.1 (Apr 2026), very active  
**Repository:** https://github.com/facebook/rocksdb

**JVM API:** RocksDB has `RocksJava` — official Java bindings in `java/` subdirectory (8% of the codebase is Java). Published to Maven Central as `org.rocksdb:rocksdbjni`. Supports Linux/macOS/Windows with bundled native libraries.

**Android:** RocksDB can be cross-compiled for Android using NDK + CMake. No prebuilt AAR artifact on Maven. Several third-party guides exist; the Java binding (`rocksdbjni`) does not ship an Android-specific AAR.

**iOS:** C++ source only. No Swift or Kotlin/Native binding exists. Would require C interop from Kotlin/Native (manually wrapping C++ headers), which is feasible but significant engineering effort.

**WASM:** No. RocksDB relies on POSIX threading, mmap, and native file I/O — none of which are available in WASM's single-threaded sandbox.

**SQLDelight compatibility:** No. RocksDB/LevelDB are key-value stores, not relational SQL engines. Using them as a backing store would require implementing a custom SQL layer on top (essentially writing a SQL engine), which is impractical.

**Query capability:** Pure key-value (byte key → byte value). No SQL, no FTS, no joins, no aggregations out of the box. Excellent write throughput for sequential workloads (LSM-tree), good point lookup performance.

**Integration effort:** High for JVM (API mismatch — not SQL), Blocked for iOS/WASM.

**Verdict:** Not suitable as a replacement for libsql. The key-value model is incompatible with SQLDelight's SQL code-gen, and FTS/graph traversal would need to be reimplemented on top. Might be interesting as a WAL/compaction backend in a hybrid design, but that is speculative.

---

## Additional Candidates Researched

### Realm Kotlin SDK

**Language:** C++ (Realm Core) + Kotlin (96.1% in SDK layer)  
**License:** Apache 2.0  
**Repository:** https://github.com/realm/realm-kotlin  
**Stars:** 1.1k

**Status:** The MongoDB Atlas Device Sync + Realm SDK was deprecated in September 2024. The community maintains a `3.0.0+` version on the `community` branch without sync features.

**JVM:** Works on JVM (desktop) via `library-base` module. Uses Kotlin/Native underneath.  
**Android:** Yes — primary target. AAR on Maven Central.  
**iOS:** Yes — Kotlin/Native ARM64 (major advantage). KMP-first design.  
**WASM:** No. Realm does not support WASM.

**SQLDelight compatibility:** No. Realm uses an object-oriented data model (`RealmObject`, `RealmQuery`) with NSPredicate-like query language. Not SQL. All queries, schema definitions, and write patterns would need to be rewritten.

**Integration effort:** High. API mismatch (object model vs. SQL), no WASM support, deprecation risk.

**Verdict:** Best cross-platform coverage of any alternative (JVM + Android + iOS), but incompatible with SQLDelight's SQL code-gen, no WASM, and the project is in a reduced-maintenance community state.

### sqlite-jdbc (xerial)

**Language:** Java (wraps SQLite via JNI)  
**License:** Apache 2.0  
**Maven:** `org.xerial:sqlite-jdbc`

This is the standard JDBC SQLite driver for JVM. It does not add MVCC or `BEGIN CONCURRENT`. It is what SteleKit used before the current libsql JNI branch. No WASM path, no iOS path without a different driver. Inferior to libsql in write throughput.

**Verdict:** Strictly worse than the current libsql JNI approach. No reason to regress.

### redb (Rust embedded key-value)

**Language:** Rust, MIT license  
**Repository:** https://github.com/cberner/redb

Pure Rust key-value store with ACID transactions. No SQL, no FTS. No Java binding. No WASM support. Same fundamental mismatch as RocksDB.

**Verdict:** Not applicable.

---

## Cross-Platform Coverage Matrix

| Engine | Desktop JVM | Android | iOS (K/N) | WASM (Kotlin) | Full KMP Coverage |
|---|---|---|---|---|---|
| libsql (current) | Yes (JNI) | Yes (JNI) | Yes (SQLiter) | Yes (OPFS) | **Yes** |
| Turso | Beta JDBC | No | No | JS-WASM only | No |
| LadybugDB | Yes (Maven) | Manual NDK | Yes (Swift) | JS-WASM only | No |
| DuckDB | Yes (JDBC) | Experimental | No | JS-WASM only | No |
| ClickHouse | No (server) | No | No | No | No |
| RocksDB | Yes (JNI) | Manual NDK | Manual C++ interop | No | No |
| Realm Kotlin | Yes | Yes | Yes (K/N) | No | No |

---

## Key Findings Summary

1. **No candidate engine achieves full four-platform KMP coverage today.** libsql (the current branch) is the only option that simultaneously provides JVM, Android, iOS, and WASM support within the SQLDelight `SqlDriver` abstraction. All alternatives either lack iOS, lack WASM/Kotlin, or require a complete SQLDelight bypass.

2. **Turso (formerly Limbo) is the most credible future upgrade path.** It is a ground-up Rust rewrite of SQLite with native MVCC, tantivy FTS, and a JDBC Java binding that mirrors the libsql JNI approach already implemented in SteleKit. The primary gaps — Android ABI cross-compilation and a stable Maven publication — are engineering work rather than architectural blockers. Recommend tracking for a reassessment when v1.0 ships or Android support lands.

3. **LadybugDB and DuckDB offer complementary strengths (graph traversal, OLAP aggregations) but cannot replace libsql.** LadybugDB's Cypher-only API and DuckDB's iOS absence both disqualify them as sole replacements. If SteleKit adds heavy analytical queries (tag frequency, journal statistics, backlink graph traversal), either could serve as a secondary, JVM-only read query engine alongside libsql.
