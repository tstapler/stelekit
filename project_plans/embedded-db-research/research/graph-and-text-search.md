# Graph-Native and Multi-Model Embedded Databases for SteleKit KMP

**Research date**: 2026-06-19  
**Scope**: JVM/Desktop, Android, iOS, WASM  
**Scale**: ~8K page nodes, ~100K block nodes, ~50K directed edges, BM25 FTS  
**Memory budget**: ≤1 GB peak (Android OOM killer constraint)

---

## Summary Matrix

| Dimension | CozoDB | SurrealDB | Kuzu |
|---|---|---|---|
| **JVM/Desktop** | ✅ Maven Central | ✅ Maven Central (Java SDK v2.1.1) | ✅ Maven Central |
| **Android ARMv8** | ✅ AAR v0.7.2 | ❌ No documented support | ✅ API 21+, merged Apr 2025 |
| **iOS** | ⚠️ Swift only; needs C-interop | ❌ Network-only Swift SDK | ✅ iOS 14+ via kuzu-swift |
| **WASM** | ❌ In-memory only; no graph algos | ⚠️ JS only (`@surrealdb/wasm`) | ✅ `kuzu-wasm` npm (IDBFS) |
| **FTS / BM25** | ✅ No highlights, no fuzzy | ✅ BM25 + highlights (best) | ✅ BM25, no highlights |
| **Recursive traversal** | ✅ Full Datalog recursion | ❌ Fixed-depth `->` only | ✅ `*min..max acyclic` Cypher |
| **Shortest path** | ✅ Built-in (JVM/Android only) | ❌ Application-level BFS only | ⚠️ Not explicitly documented |
| **Query language** | Datalog (declarative, recursive) | SurrealQL (SQL-like, fixed-depth) | Cypher (expressive, recursive) |
| **License** | ✅ MPL-2.0 | ✅ BSL 1.1 (app embedding permitted) | ✅ MIT |
| **Stars** | ~4K | ~32K | ~4K (archived) |
| **Maintenance** | ❌ Stalled Dec 2023 | ✅ Active (v3.1.5) | ❌ Archived Oct 2025 |
| **Memory (tunable)** | Moderate, RocksDB/SQLite backends | Use `kv-rocksdb`; configurable cache | Buffer pool configurable |

---

## CozoDB

### Query Language: CozoScript (Datalog)

CozoScript is a dialect of Datalog operating on named relations. Every query has a root rule `?[...]`. Relations are referenced as `*relation_name{field: value}`. Recursion is expressed by defining a rule with the same name on multiple lines (disjunction/union). Semi-naive evaluation ensures termination on recursive queries.

**Backlinks** (all pages directly linking to page X):
```datalog
?[from_page] := *page_refs{from_page, to_page: 'X'}
```

**Transitive hierarchy** (all descendants of block B):
```datalog
descendants[child] := *block_children{parent_uuid: 'B', child}
descendants[child] := descendants[ancestor], *block_children{parent_uuid: ancestor, child}

?[child] := descendants[child]
```

**2-hop backlinks** (all pages reaching page X within 2 hops):
```datalog
hop1[from] := *page_refs{from_page: from, to_page: 'X'}
hop2[from] := *page_refs{from_page: from, to_page: mid},
              *page_refs{from_page: mid, to_page: 'X'},
              from != 'X'

?[page] := hop1[page]
?[page] := hop2[page]
```

**Shortest path** (built-in fixed rules for weighted/unweighted graphs):
```datalog
start[] <- [['page_A']]
end[]   <- [['page_B']]
?[src, dst, cost, path] <~ ShortestPathDijkstra(*page_refs[], start[], end[])
```

For unweighted shortest path: substitute `ShortestPathBFS`. Both are built-in fixed rules. **Critical caveat**: these built-in fixed rules are NOT compiled into the WASM build — WASM is in-memory-only Datalog recursion without graph algorithms.

**Complex query: descendants of page P that reference page Q**:
```datalog
desc[child] := *block_parent{child_uuid: child, parent_uuid: 'P'}
desc[child] := desc[ancestor], *block_parent{child_uuid: child, parent_uuid: ancestor}

?[block_uuid, content] :=
    desc[block_uuid],
    *blocks{uuid: block_uuid, content},
    *block_refs{from_block: block_uuid, to_page: 'Q'}
```

### Stability and Current Version

Current version: **v0.7.6** (released December 11, 2023).

**Critical caveat**: The README explicitly warns: "Versions before 1.0 do not promise syntax/API stability or storage compatibility." The project has had **zero commits since December 11, 2023** — over 18 months of inactivity at time of research. There is one primary contributor (zh217). 42 open issues, 5 stale PRs. The project is effectively abandoned at pre-1.0.

### Java API (`cozo-java`)

Maven coordinate: `io.github.cozodb:cozo_java:0.7.5`

```java
import org.cozodb.CozoDb;

// In-memory
CozoDb db = new CozoDb();
// SQLite persistent
CozoDb db = new CozoDb("sqlite", "/path/to/db");

String resultJson = db.query("?[] <- [['hello', 'world!']]");
// With parameters
String result = db.query("?[name] := *pages{name: $n}", Map.of("n", "Home"));
db.close();
```

Full API: `query(script)`, `query(script, params)`, `exportRelations(names)`, `importRelations(json)`, `backup(path)`, `restore(path)`, `registerCallback(relation, callback)`.

**Critical limitation**: The JVM JAR does NOT bundle native binaries. At runtime it searches `~/.cozo_java_native_lib/` and **downloads the native lib from GitHub Releases if absent**. This is incompatible with Android and offline deployments unless the native library is pre-bundled.

### Platform Coverage

**Android**: Separate artifact `io.github.cozodb:cozo_android:0.7.2`. Supported ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`. Native `.so` files are bundled in the AAR. Storage backends: in-memory and SQLite only (no RocksDB in the prebuilt AAR). **Version mismatch**: Android AAR is at v0.7.2; JVM library is at v0.7.5.

**iOS**: Official support via Swift Package Manager / CocoaPods `pod 'CozoSwiftBridge', '~> 0.7.1'`. Supported: macOS (ARM + Intel), iOS (iPhone, iPad, simulators). Storage backends: in-memory and SQLite only. **KMP implication**: No Kotlin/Native binding exists. Requires writing Kotlin/Native C-interop against `cozo-lib-c` (C API available in repo but no published KMP layer). Custom work required.

**WASM**: Target `wasm32-unknown-unknown`, published as `cozo-lib-wasm` on npm. Persistence: **in-memory only** — no SQLite backend, no OPFS. Graph algorithm built-in rules (`ShortestPathDijkstra`, `PageRank`, `BFS`, `DFS`) are NOT compiled into the WASM build. Only pure recursive Datalog works in WASM. API is synchronous and blocks the main thread.

### Full-Text Search

Built-in since v0.7.0, using Tantivy tokenizers.

**Create FTS index**:
```datalog
::fts create blocks:fts {
    extractor: content,
    extract_filter: !is_null(content),
    tokenizer: Simple,
    filters: [Lowercase, Stemmer('english'), Stopwords('en')]
}
```

**Query with BM25-style scoring**:
```datalog
?[score, block_uuid, content] :=
    ~blocks:fts {block_uuid, content | query: $q, k: 20, bind_score: score}
:order -score
```

Query operators: `AND`, `OR`, `NOT`, prefix (`hell*`), `NEAR/N(term1 term2)`, term boosting (`term^2`).

**Gaps vs. SQLite FTS5**:
- No built-in snippet/highlight function (term position arrays are stored and could support manual highlighting, but there is no `highlight()` equivalent)
- No fuzzy/typo tolerance operator
- Index update behavior on mutations is well-defined (incremental, not full rebuild)

### Memory Footprint

From README benchmarks (RocksDB backend): ~50 MB peak for 1.6M rows. At SteleKit scale (~160K total entities), estimated well under 100 MB including FTS. SQLite backend (Android) uses OS-managed page cache — likely lower.

### License and Maturity

- **License**: Mozilla Public License 2.0 (MPL-2.0). Weak copyleft. App distribution permitted; modifications to MPL files themselves must be shared.
- **GitHub stars**: ~4,000
- **Last commit**: December 11, 2023
- **Production use**: No documented major deployments. Marketed for RAG/LLM knowledge graphs. Not in widespread production use.

---

## SurrealDB

### Embedded Mode API

SurrealDB can run as an in-process library without any network server. Backend options are selected via Cargo feature flags:
- `kv-mem` — in-memory (no persistence)
- `kv-rocksdb` — RocksDB file-backed (production persistent)
- `kv-surrealkv` — SurrealDB's own KV store with optional time-travel versioning

**Rust API (embedded)**:
```rust
use surrealdb::Surreal;
use surrealdb::engine::local::RocksDb;

let db = Surreal::new::<RocksDb>("path/to/database-folder").await?;
db.use_ns("main").use_db("main").await?;
```

**Java SDK** (`com.surrealdb:surrealdb:2.1.1` on Maven Central, **Apache 2.0 license**):
```java
try (Surreal db = new Surreal()) {
    db.connect("surrealkv://path/to/database");
    db.useNs("app").useDb("main");
    List<Block> results = db.select("block", Block.class);
}
```

The Java SDK bundles JNI native libraries for JVM desktop targets. **Important mismatch**: Java SDK is at v2.1.1 while the core engine is at v3.1.5. Java SDK may expose v2 engine internals while v3 SurrealQL docs describe features not yet available via the Java binding.

### SurrealQL Graph Traversal

SurrealDB stores graph edges using `RELATE`. Traversal uses `->` (outgoing) and `<-` (incoming) arrow syntax inline in `SELECT`.

**Setup**:
```sql
RELATE page:home->links_to->page:about;
RELATE block:b1->references->page:about;
RELATE block:root->contains->block:b1;
```

**Backlinks (1 hop)**:
```sql
SELECT <-links_to<-page AS backlink_pages FROM page:about FETCH backlink_pages;
```

**2-hop backlinks** (pages reaching page X within 2 hops):
```sql
LET $hop1 = SELECT VALUE id FROM page WHERE ->links_to->(page WHERE id = page:about);
LET $hop2 = SELECT VALUE id FROM page
    WHERE ->links_to->page->links_to->(page WHERE id = page:about);
RETURN array::union($hop1, $hop2);
```

**CRITICAL LIMITATION: No recursive traversal.** The `->` operator is fixed-depth. There is no `*` depth pattern, no recursive CTE equivalent, no Datalog-style recursion. For arbitrary-depth block hierarchies (a fundamental requirement for a Logseq-like outliner with unlimited nesting), you must either:
1. Iterate in application code (round-trips per depth level), or
2. Denormalize ancestor arrays and maintain them on every write, or
3. Use a JavaScript stored function

This is the disqualifying gap for SteleKit's block hierarchy traversal.

**Fixed-depth descendants of page P that reference page Q** (works up to a hardcoded depth only):
```sql
LET $direct = SELECT VALUE id FROM block
    WHERE id INSIDE (SELECT VALUE ->contains->block FROM page:p).flatten()
    AND ->references->(page WHERE id = page:q);
LET $grandchildren = SELECT VALUE id FROM block
    WHERE id INSIDE (SELECT VALUE ->contains->block->contains->block FROM page:p).flatten()
    AND ->references->(page WHERE id = page:q);
RETURN array::union($direct, $grandchildren);
```

**Shortest path**: No built-in. Would require application-level BFS loop.

### Full-Text Search (Best-in-Class)

SurrealDB has the strongest built-in FTS of the three databases: BM25 ranking AND snippet highlighting in a single query.

**Index definition**:
```sql
DEFINE ANALYZER content_analyzer
    TOKENIZERS class
    FILTERS ascii, lowercase, snowball(english);

DEFINE INDEX block_content_idx
    ON TABLE block
    COLUMNS content
    FULLTEXT ANALYZER content_analyzer
    BM25(1.2, 0.75)
    HIGHLIGHTS;
```

**Query with BM25 ranking and highlights**:
```sql
SELECT id, content,
    search::score(1) AS relevance,
    search::highlight('<mark>', '</mark>', 1) AS highlighted_content
FROM block
WHERE content @1@ 'logseq blocks'
ORDER BY relevance DESC
LIMIT 20;
```

Optional `DEFER` indexing decouples write latency from index updates.

**Fuzzy/typo tolerance**: Not integrated with BM25. Available separately via `string::similarity::fuzzy()` but not part of BM25 ranking — must be composed manually.

### Platform Coverage

**Android**: Official documentation makes no mention of Android. The Java SDK ships JNI native libs for JVM desktop targets (Linux x86_64, macOS, Windows) but Android ABI (`aarch64-linux-android`) is not mentioned or documented. The `kv-rocksdb` backend requires RocksDB cross-compiled for the Android NDK — no confirmed community reports of a successful Android build exist. **This is the single biggest gap for KMP use.**

**iOS (embedded)**: Not supported for embedded mode. The Swift SDK connects to a remote SurrealDB server via WebSocket only — it is not an in-process library. No embedded iOS mode exists or is documented. Custom compilation would require cross-compiling the Rust crate to `aarch64-apple-ios` and building a C/Swift framework — no established path.

**WASM**: `@surrealdb/wasm` npm package provides embedded SurrealDB in the browser:
```javascript
import { Surreal } from 'surrealdb';
import { createWasmEngines } from '@surrealdb/wasm';

const db = new Surreal({ engines: { ...createWasmEngines() } });
await db.connect('indxdb://my-database');   // IndexedDB persistence
// or: 'mem://' for in-memory
```
Persistence via IndexedDB (`indxdb://`), not OPFS. Works in browser ES modules and Web Workers. **Kotlin/WASM gap**: WASM support is JavaScript-centric; Kotlin/WASM would need JS interop — no native Kotlin/WASM engine build exists.

### API Stability

v1→v2 and v2→v3 both introduced breaking changes. The embedded API shape is stable within a major version. The Java SDK at v2.1.1 may not expose v3 SurrealQL features. `FULLTEXT` replaced `SEARCH` as the preferred keyword in v3.0+.

### BSL 1.1 License Analysis

**The sole restriction**: you cannot build a hosted "Database as a Service" product on top of SurrealDB without an enterprise license. Specifically: providing database functionality to third parties who create, manage, or control their own schemas or tables.

**App embedding is explicitly permitted**: SurrealDB's official FAQ states: "SurrealDB is free to use for all development, pre-production, and production use. The only restriction is offering a commercial DBaaS without an enterprise licence."

**Note**: The `surrealdb.java` SDK is itself Apache 2.0 — only the core engine carries BSL 1.1.

**Conversion schedule** (each version → Apache 2.0 after ~4 years):
- v1.x: ~2027
- v2.x: ~2028–2029
- v3.0 (2025): January 1, 2030

Google Play and App Store distribution of an app embedding SurrealDB is unambiguously permitted.

### Memory Footprint

With `kv-rocksdb`, data is disk-backed with a configurable block cache (RocksDB default: 8 MB). HNSW vector index cache defaults to 256 MiB (`SURREAL_HNSW_CACHE_SIZE`) — disable or reduce for Android. Tokio async runtime + engine startup: estimated 20–50 MB. With a configured cache ceiling, total footprint can be kept well within 1 GB.

### License and Maturity

- **License**: BSL 1.1 (core engine); Apache 2.0 (Java SDK). App distribution permitted.
- **GitHub stars**: ~32,400
- **Version**: v3.1.5 (active; breaking changes across major versions)
- **Production use**: Used by multiple organizations. Well-funded company. Most active of the three.

---

## Kuzu

### CRITICAL: Repository Archived

**The `kuzudb/kuzu` GitHub repository was archived by the owner on October 10, 2025.** The repo is now read-only with no future bug fixes or security patches. Final release: **v0.11.3** (October 10, 2025). The README says "Kuzu is working on something new!" with no disclosure of what. ~4,000 stars, 493 forks at archive time.

This is a fatal risk for adoption in any long-lived project. All findings below are accurate as of the archive date.

### Java Binding

Maven Central: `com.kuzudb:kuzu:0.11.3` (Java 11+ required). The JAR bundles pre-compiled native libraries for all supported platforms — no separate native dependency needed at runtime.

```java
Database db = new Database("/path/to/db");
// Optional: cap buffer pool to 256 MB (critical for Android)
Database db = new Database("/path/to/db", 256L * 1024 * 1024);

Connection conn = new Connection(db);
QueryResult result = conn.query("MATCH (n:Page) RETURN n");
while (result.hasNext()) {
    FlatTuple row = result.getNext();
    // NOTE: FlatTuple is reused per row — call Value.clone() to store across iterations
    Value pageId = row.getValue(0).clone();
}

// Prepared statements
PreparedStatement ps = conn.prepare("MATCH (n:Page {id: $id}) RETURN n");
conn.execute(ps, Map.of("id", someId));
```

### Graph Schema Model

Explicit DDL. Every node and relationship has exactly one label. Schema defined upfront:

```cypher
CREATE NODE TABLE Page (
    id STRING PRIMARY KEY,
    title STRING,
    is_journal BOOLEAN DEFAULT false,
    content STRING
);
CREATE NODE TABLE Block (
    uuid STRING PRIMARY KEY,
    content STRING
);
CREATE REL TABLE Links (FROM Page TO Page);
CREATE REL TABLE HasBlock (FROM Page TO Block);
CREATE REL TABLE ParentOf (FROM Block TO Block, MANY_MANY);
CREATE REL TABLE References (FROM Block TO Page);
```

### Cypher Query Examples

**2-hop backlinks** (pages linking to page X within 2 hops):
```cypher
MATCH (linker:Page)-[:Links*1..2]->(target:Page)
WHERE target.id = $pageId AND linker.id <> target.id
RETURN DISTINCT linker.id, linker.title, length(path) AS hops;
```

**Complex query: descendants of page P that reference page Q**:
```cypher
MATCH (p:Page)-[:HasBlock]->(root:Block)-[:ParentOf* acyclic 0..30]->(descendant:Block),
      (descendant)-[:References]->(q:Page)
WHERE p.id = $pageId
  AND q.id = $targetPageId
RETURN DISTINCT descendant.uuid, descendant.content;
```

`*0..` includes the root block itself. `acyclic` prevents cycles (critical for graph safety). `*1..30` bounds depth for query planning safety. This is the most natural and complete expression of the SteleKit block traversal query across all three databases.

### Platform Coverage

**Android**: Officially supported as of April 2025 (before archival). Key merged PRs:
- PR #5248: "Build Java bindings for Android ARMv8-A platform" (Apr 19, 2025)
- PR #5168: C++ stdlib static link fix for Android NDK (Mar 31, 2025)
- PR #5147: Static link extensions for WASM and Android NDK (Mar 28, 2025)

System requirements state: "The Java API also works on the Android ARMv8-A platform. The precompiled binaries are compiled targeting API level 21 (Android 5.0+)." ARMv7 (32-bit) is explicitly **not** supported (issue #4870 is unresolved and will stay open indefinitely given the archive).

Whether the main `com.kuzudb:kuzu` Maven Central JAR bundles Android ARM64 native libs alongside desktop libs, or whether a separate Android artifact is needed, requires hands-on inspection of the JAR manifest.

**iOS**: Supported via `kuzu-swift` package targeting iOS 14+. System requirements: "Swift 5.9 or later; macOS v11 or later; iOS v14 or later." No pre-built `aarch64-apple-ios` `.xcframework` artifact confirmed in releases (only `libkuzu-osx-universal.tar.gz` in GitHub Releases); Swift Package Manager handles the iOS build step. For KMP, a Kotlin/Native C-interop against the `kuzu-c` C API would be needed (wrapping `kuzu-swift` is also viable but more layered).

**WASM**: npm package `kuzu-wasm`. Three variants:
1. **Default**: No multithreading, MEMFS (in-memory), no cross-origin isolation required. Browser + Node.js.
2. **Multithreaded**: Requires COOP/COEP headers. Larger binary.
3. **Node.js**: NODEFS (real filesystem), CommonJS.

Built with Emscripten (`wasm32-unknown-emscripten`).

Persistence in browser uses **IDBFS** (IndexedDB-backed filesystem), not OPFS. IDBFS has slower performance and smaller practical size limits than OPFS for large databases. Whether OPFS can be substituted requires investigation.

```javascript
import kuzu from "kuzu-wasm";
const db = await kuzu.Database(path);
const conn = await kuzu.Connection(db);
const result = await conn.query("MATCH (n:Page) RETURN n");
```

### Full-Text Search

Built-in `fts` extension, pre-bundled in v0.11.3 (no `INSTALL` or `LOAD` required in the embedded Java API).

```cypher
CALL CREATE_FTS_INDEX('Block', 'block_fts', ['content'],
    stemmer := 'porter');

CALL QUERY_FTS_INDEX('Block', 'block_fts', 'logseq blocks',
    conjunctive := false, K := 1.2, B := 0.75, top := 20)
RETURN node, score
ORDER BY score DESC;
```

Parameters: `stemmer` (28+ languages, Snowball-based), `stopwords` (custom table/CSV), `K`/`B` (BM25 tuning), `top`, `conjunctive` (AND mode).

**Limitations**:
- FTS indexes on node tables only (not relationship tables)
- Only `STRING` columns indexable
- `CREATE_FTS_INDEX` cannot run inside a multi-statement transaction
- **No snippet highlighting** — returns node + score; application must extract snippets
- Index rebuild behavior on mutations not documented; likely manual rebuild required

### Memory Footprint

Columnar disk-based storage with CSR adjacency lists. Memory is primarily the buffer pool (defaults to 80% of system RAM). **Must set an explicit cap for Android**:

```java
Database db = new Database("/path/to/db", 256L * 1024 * 1024); // 256 MB cap
```

At SteleKit's scale (100K nodes is small vs. Kuzu's analytical targets), memory with a configured cap is well within 1 GB.

### License and Maturity

- **License**: MIT (fully permissive)
- **GitHub stars**: ~4,000 (archived)
- **Last commit**: October 10, 2025
- **Production use**: Kùzu Cloud (SaaS) was the primary commercial deployment. The pivot to "something new" is the implied reason for archival.

---

## Other Databases

### IndraDB (Rust, MPL-2.0)

~2,500 GitHub stars. Last release: v5.0.0, August 2025.

**Why not suitable**: The query model is a custom Rust API using composable query objects (`SpecificEdgeQuery`, `PipeQuery`) — not Datalog, Cypher, or any declarative query language. Multi-hop traversals require multiple application round-trips. No FTS built-in. No official Android/iOS/WASM cross-compilation targets. No JNI wrapper. The `sled` backend is explicitly marked "not production-ready". Tiny community engagement for a library touching the core data layer.

**Verdict**: Not suitable for SteleKit. No FTS, no ergonomic graph query language, no confirmed mobile build path, negligible community.

### Oxigraph (Rust, MIT/Apache-2.0)

~2,700+ GitHub stars. Actively maintained.

An RDF triplestore implementing SPARQL 1.1. Every property becomes a triple (no native concept of node properties), which explodes the SteleKit data model unnecessarily. SPARQL is expressive but is a W3C semantic web standard — not ergonomic for "find all blocks under page P referencing page Q." No BM25 FTS (regex filter only). WASM npm package exists; persistent storage via RocksDB on server.

**Verdict**: Wrong tool for a property graph domain. The semantic web model does not map naturally to the SteleKit block/page model.

### Neo4j Embedded (Java, AGPL-3.0)

Embedded mode is available (Maven: `org.neo4j:neo4j`, 2026.x CalVer).

**Hard blockers**:
- **AGPL-3.0 license**: Any application distributing Neo4j Community Edition must open-source the entire application under AGPL-3.0. Enterprise Edition requires a paid commercial license. Neither is viable for a personal note-taking app.
- **JVM-only**: Pure Java; no iOS or WASM support is architecturally possible without a full rewrite.
- **Android**: Officially unsupported. Heavy transitive dependency tree (Netty, Jetty, Bolt transport) estimated at 50–100 MB+ in JARs — far too heavy for an Android APK.

**Verdict**: AGPL license blocker + JVM-only architecture are disqualifying.

### ArangoDB

No embedded mode exists. ArangoDB is exclusively a client-server database requiring a running `arangod` daemon. Multiple open GitHub issues (#2090, #4198, #5389, #20875) requesting embedded or mobile modes have been open for years without resolution. Memory-mapped I/O is noted as problematic on Android.

**Verdict**: Not applicable. No embedded mode; unsuitable for any mobile or desktop embedded use case.

---

## Graph Query Language Comparison

For the canonical SteleKit query: **"find all blocks that are descendants of page P AND reference page Q"**

### CozoDB (Datalog)

```datalog
desc[child] := *block_parent{child_uuid: child, parent_uuid: 'page_P'}
desc[child] := desc[ancestor], *block_parent{child_uuid: child, parent_uuid: ancestor}

?[block_uuid, content] :=
    desc[block_uuid],
    *blocks{uuid: block_uuid, content},
    *block_refs{from_block: block_uuid, to_page: 'page_Q'}
```

**Characteristics**: Recursive rules are natural Datalog. The `desc` rule is a reusable transitive closure that can be shared across queries. Pattern-matching style. No depth bound required — semi-naive evaluation handles cycles. Verbosity is low once the recursion pattern is learned. Less familiar to most developers than Cypher.

### SurrealDB (SurrealQL)

```sql
-- Fixed to 3 levels only; does not generalize to arbitrary depth
LET $desc1 = SELECT VALUE id FROM block
    WHERE id INSIDE (SELECT VALUE ->contains->block FROM page:p).flatten()
    AND ->references->(page WHERE id = page:q);

LET $desc2 = SELECT VALUE id FROM block
    WHERE id INSIDE (SELECT VALUE ->contains->block->contains->block FROM page:p).flatten()
    AND ->references->(page WHERE id = page:q);

-- Must repeat for each additional depth level
RETURN array::union($desc1, $desc2);
```

**Characteristics**: Cannot express arbitrary-depth traversal at all. Must enumerate depth levels in application code or pre-materialize ancestor arrays in the schema. This is a fundamental query capability gap for SteleKit's unbounded nesting model. Even with pre-materialized ancestors (denormalization), the maintenance burden on every write is significant.

### Kuzu (Cypher)

```cypher
MATCH (p:Page)-[:HasBlock]->(root:Block)
            -[:ParentOf* acyclic 0..30]->(descendant:Block),
      (descendant)-[:References]->(q:Page)
WHERE p.id = $pageId AND q.id = $targetPageId
RETURN DISTINCT descendant.uuid, descendant.content;
```

**Characteristics**: Single declarative query. `*0..30` expresses arbitrary bounded depth (30 is a reasonable practical limit for any outliner). `acyclic` prevents cycles. Familiar Cypher syntax known to most graph database users. Kuzu's columnar storage was designed for exactly this pattern — adjacency traversal over large edge sets.

### Performance Expectations

For SteleKit's scale (~100K blocks, ~50K `ParentOf` edges):
- **CozoDB**: Semi-naive Datalog evaluation over an in-process relation. Traversal performance is not well-benchmarked at this scale; expected adequate but no published numbers.
- **SurrealDB**: Cannot do this query natively; any workaround requires multiple network-less round-trips or denormalization.
- **Kuzu**: CSR adjacency lists are optimized for exactly this traversal pattern. For 100K nodes (a small graph by Kuzu's analytical targets), this query should execute in <10 ms.

---

## Recommendation

**None of the three databases provides the complete package** required for SteleKit: active maintenance + confirmed KMP platform coverage (JVM + Android + iOS + WASM) + arbitrary-depth recursive graph traversal + BM25 FTS with snippet highlights. However, the analysis reveals a clear ordering:

### Best graph+FTS option if platform gaps were resolved: Kuzu

**Why**: Kuzu has the strongest overall story across graph query expressiveness (Cypher `*0..30 acyclic`), platform coverage (JVM ✅, Android API 21+ ✅, iOS 14+ ✅, WASM ✅), Java API quality (bundled natives, single JAR), memory tunability (buffer pool cap), and MIT license. The FTS extension is built-in with BM25 scoring and configurable Snowball stemmers.

**The fatal flaw**: The repository was archived October 10, 2025. Adopting Kuzu v0.11.3 means committing to a frozen dependency with no future bug fixes, security patches, or platform updates.

**Practical path if Kuzu is chosen despite archival**: Fork `kuzudb/kuzu` at v0.11.3, pin to that commit, verify Android ARM64 native lib bundling in the Maven JAR, and plan to own any platform maintenance needed. The codebase is MIT and the pre-archival PRs resolved the main Android/WASM blockers.

### Best option for long-term project health: None (continue with libsql + recursive CTEs)

**SurrealDB** is the most actively maintained (32K stars, v3.1.5, commercial backing) and has the best FTS (BM25 + highlights). But its lack of recursive graph traversal is a fundamental architectural gap for a Logseq-like app, and its Android embedded support is unconfirmed. Until SurrealDB adds recursive traversal and confirmed Android embedded support, it cannot replace the current SQL approach for hierarchy queries — it would require keeping the recursive CTE workaround anyway.

**CozoDB** has the most expressive query language (full Datalog + built-in shortest path) but is abandoned at pre-1.0 with a non-persistence WASM build and no graph algorithms in WASM. Embedding a stalled pre-1.0 project is a significant risk.

### Decision Triggers

Revisit this evaluation if any of the following occur:
1. **Kuzu announces a successor** (their "something new") that inherits the v0.11.3 Android/iOS/WASM platform work
2. **SurrealDB adds recursive traversal** (variable-depth `->` with `*` syntax) — this would make it the strongest single option
3. **CozoDB publishes v1.0** with a new maintainer, WASM persistence via OPFS, and graph algorithms in WASM
4. **A community fork of Kuzu** emerges with active maintenance

Until then, the current libsql + SQLDelight + recursive CTEs architecture is the lower-risk path for a one-developer project that needs to ship on all four platforms.
