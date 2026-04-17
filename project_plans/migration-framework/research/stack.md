# Research: Stack

**Date**: 2026-04-14
**Dimension**: Technology options for the migration framework

---

## Summary

For DSL parsing, a hand-rolled Kotlin internal DSL (using lambdas / builder pattern) is the right call — it runs in commonMain without any parser tooling and avoids grammar maintenance overhead for a constrained query language. For changelog state, `kotlinx.serialization` JSON stored in a sidecar file is the natural fit given SteleKit already uses it. For diff/apply, `petertrr/kotlin-multiplatform-diff` covers text-sequence diffing across all KMP targets; block-tree structural diff should be hand-rolled against SteleKit's existing stable block identity. No KMP-native DAG library exists; a hand-rolled adjacency-map + Kahn's-algorithm implementation (~100 lines) is the pragmatic choice. For file I/O, Okio 3.x is stable and battle-tested across all targets; `kotlinx-io` is still Alpha and should be treated as a future migration path.

---

## 1. SQL/DSL Parsing in KMP commonMain

### Problem

The migration framework needs a way to express desired-state queries like "all pages tagged `#project` should have a `status::` property". The requirements explicitly say a *constrained DSL* is sufficient — a full SQL parser is out of scope.

### Options

#### Option A: Hand-rolled Kotlin internal DSL (builder pattern)

A Kotlin lambda-with-receiver DSL is the simplest solution that works everywhere:

```kotlin
migration("add-status-to-projects") {
    requires("v1-add-project-tag")
    query {
        pages { hasTag("project") }
        ensure { property("status", default = "active") }
    }
}
```

- **KMP compatibility**: Pure commonMain. No platform dependencies.
- **Maturity**: Kotlin's DSL capabilities are stable (lambdas, `@DslMarker`, type-safe builders). See Kotlin in Action Ch. 11.
- **License**: N/A (code you write).
- **Fit**: Ideal for a constrained query vocabulary. Adding new operations means adding new builder methods, not grammar changes.
- **Drawback**: Migrations are Kotlin source files, not external text files. This means they are compiled into the binary (acceptable for contributor-authored migrations, less so for end-user scripts).

#### Option B: ANTLR 4 via `Strumenta/antlr-kotlin`

- GitHub: https://github.com/Strumenta/antlr-kotlin
- Latest version: `1.0.5` (released October 1, 2025).
- ~269 GitHub stars.
- Provides ANTLR 4 runtime as a KMP library (`com.strumenta:antlr-kotlin-runtime`) plus a Gradle plugin that generates Kotlin parser code.
- Targets: JVM, Android, JS, WASM, Native (including iOS, macOS, Windows, Linux).
- License: Apache 2.0.
- **Fit**: Enables parsing external text migration files against a custom grammar. Appropriate if end-user-authored text-file migrations are a requirement.
- **Drawback**: Adds a Gradle plugin + grammar file + generated-source step. Significantly more infrastructure for a constrained DSL that could be expressed as a Kotlin builder. Overkill unless external plaintext migration files are a hard requirement.

#### Option C: `kotlinx/ast` generic AST library

- GitHub: https://github.com/kotlinx/ast (h0tk3y fork: https://github.com/h0tk3y/kotlinx-ast)
- Provides ANTLR-based AST primitives for KMP.
- Status: Low activity; not recommended as primary parser infrastructure.

#### Option D: JVM-only SQL parsers (Exposed DSL, jOOQ, Calcite)

- JetBrains Exposed provides a type-safe SQL DSL but targets JVM/JDBC only. Not available in commonMain. Cannot be used on iOS or Web.
- Apache Calcite (SQL parser / planner) is JVM-only. Not applicable.
- None of these cross KMP boundaries.

### Recommendation

**Use Option A (hand-rolled Kotlin internal DSL)** for the first production iteration.

Rationale: The requirements explicitly call for a constrained DSL; a Kotlin builder covers all required query semantics with zero parsing infrastructure, compiles into commonMain, and works on all four platforms. If contributor feedback demands external text-file migrations (e.g., shipping `.stele-migration` files alongside graph data), revisit Option B.

---

## 2. Serialization for Changelog and State

### Problem

The framework needs to persist:
1. Which migrations have run (version + checksum + timestamp).
2. Enough metadata to detect re-runs and flag checksum drift.

### Options

#### Option A: `kotlinx.serialization` JSON — **Recommended**

- GitHub: https://github.com/Kotlin/kotlinx.serialization
- Full KMP support: JVM, Android, JS, Native, WASM.
- Formats in-box: JSON, CBOR, Properties, Protobuf.
- Latest stable: 1.7.x (actively maintained by JetBrains).
- License: Apache 2.0.
- SteleKit almost certainly already depends on `kotlinx.serialization` (it is the standard KMP serialization library).
- JSON is human-readable, diffable in git, and requires no binary tooling to inspect.
- A sidecar `.stele-migration-log.json` alongside each graph directory would mirror the pattern already used for the op log sidecar.

```kotlin
@Serializable
data class MigrationRecord(
    val id: String,
    val checksum: String,
    val appliedAt: Long,   // epoch millis
    val status: MigrationStatus  // APPLIED | FAILED | REVERTED
)
```

#### Option B: CBOR (via `kotlinx.serialization-cbor`)

- Binary, more compact than JSON.
- Not human-readable; harder to debug and diff in git.
- The CBOR module is still in experimental status in kotlinx.serialization.
- Not recommended for a changelog that benefits from readability.

#### Option C: SQLDelight table

- SteleKit already uses SQLDelight 2.3.2 — a `migration_log` table could store run history.
- Pros: queryable, transactional writes, indexed.
- Cons: The migration log is graph-scoped, not app-scoped. Each graph has its own SQLDelight DB (via `RepositorySet`). This is actually fine — each graph DB would carry its own migration log table.
- The bigger question: do you want the migration engine to depend on the SQLDelight graph DB being open? If migrations run before graph load (a common pattern in schema migration tools), SQLDelight creates a circular dependency. A flat file log avoids this.

#### Option D: Flat `.properties` / TOML files

- Simple, no library needed.
- No schema, harder to evolve, no first-class type safety.
- Not recommended.

### Recommendation

**Option A: `kotlinx.serialization` JSON in a sidecar file.**

Keeps the migration log adjacent to the graph (portable), human-readable, git-diffable, and avoids a circular dependency with the graph DB. If the SQLDelight DB is already open at migration time, Option C is a viable alternative for queryability — but start with flat JSON for simplicity.

---

## 3. Diff Engine Approaches

### Problem

Two diff operations are needed:
1. **Text/sequence diff**: Detecting which lines/blocks changed between current and desired state (like Flyway's checksum detection).
2. **Block-tree structural diff**: Comparing a tree of blocks against a desired tree to produce a minimal change set (the core Atlas-style "desired state" engine).

### Options for text/sequence diff

#### `petertrr/kotlin-multiplatform-diff` — **Recommended**

- GitHub: https://github.com/petertrr/kotlin-multiplatform-diff
- Maven: `io.github.petertrr:kotlin-multiplatform-diff`
- Port of `java-diff-utils` to KMP.
- Targets: JVM, JS, Native.
- Supports Myers' diff algorithm for computing deltas, generating unified diffs, applying patches.
- License: Apache 2.0.
- Status: Active; most recent release built against Kotlin 2.x.
- **Fit**: Good for line-level diff of migration file content (checksum drift detection) and for generating human-readable before/after diffs in the audit log.

#### `andrewbailey/Difference`

- GitHub: https://github.com/andrewbailey/Difference
- ~50 stars. KMP Myers' diff on lists (not text specifically).
- Last meaningful release: 1.1.1.
- Less actively maintained than `petertrr/kotlin-multiplatform-diff`.
- Not recommended over petertrr.

#### `java-diff-utils` (JVM only)

- The original Java library `io.github.java-diff-utils:java-diff-utils`.
- JVM only. Not usable in commonMain.

### Options for structural (block-tree) diff

No KMP library for AST/tree structural diffing was found in the ecosystem. The available options are:

#### Hand-rolled desired-state diff against SteleKit block model

SteleKit already has:
- Stable block identity (from `feat(storage): stable block identity`).
- An op log and undo infrastructure.
- A `diff-merge` capability.

The migration engine's desired-state diff can be implemented as a traversal over the block tree that compares each block against the desired property/tag state declared in the migration spec. This is not a generic tree diff — it is domain-specific (Markdown block properties and tags), which makes it much simpler than a general-purpose tree diff algorithm.

The algorithm is essentially:
1. For each block in scope (as defined by the migration query): check if desired properties/tags are present.
2. Emit `AddProperty`, `RemoveProperty`, `AddTag`, `RemoveTag` operations for divergences.
3. Apply operations using the existing op log infrastructure.

#### GumTree (JVM only)

- GumTree is a Java library for AST diff. JVM only, not KMP-compatible.
- Not applicable.

### Recommendation

Use `petertrr/kotlin-multiplatform-diff` for text/sequence diffing (checksum detection, human-readable audit output). For block-tree structural diff, hand-roll a domain-specific comparator against SteleKit's block model — the existing op log and stable block identity provide all required primitives.

---

## 4. DAG / Dependency Graph

### Problem

Inspired by Sqitch, migrations may declare `requires` / `conflicts` relationships. The engine must topologically sort and validate the migration DAG before execution.

### Options

#### Dedicated KMP graph libraries

No production-ready, KMP-compatible DAG library was found in the ecosystem. Search results for "DAG KMP commonMain" return only tangential results (module dependency plugins, DI frameworks). The kmp-awesome curated list does not list a graph/DAG library.

#### Hand-rolled adjacency map + Kahn's algorithm

Kahn's algorithm for topological sort is well-understood, O(V + E), and trivially implementable in ~100 lines of pure Kotlin:

```kotlin
class MigrationDag {
    private val edges = mutableMapOf<String, MutableSet<String>>() // id → dependencies
    
    fun add(id: String, requires: List<String>) { ... }
    fun topologicalOrder(): List<String> { /* Kahn's BFS */ }
    fun detectCycles(): List<String> { /* return empty if valid */ }
}
```

- **KMP compatibility**: Pure commonMain, no dependencies.
- **Fit**: The DAG for a migration system is simple: nodes are migrations, edges are `requires` relationships. No need for a general-purpose graph library.

#### JGraphT (JVM only)

- JVM-only. Not applicable for KMP.

### Recommendation

**Hand-roll** the DAG using an adjacency map and Kahn's algorithm. The implementation is small, has no external dependencies, and fits in a single file. The migration DAG problem domain does not require a general-purpose graph library.

---

## 5. File I/O in KMP commonMain

### Problem

The migration engine must read migration definition files and the changelog sidecar across all platforms.

### Options

#### Okio 3.x — **Recommended for now**

- GitHub: https://github.com/square/okio
- Latest stable: 3.16.x (active release in 2025, maintained by Square/Cash App).
- License: Apache 2.0.
- Targets: JVM, Android, iOS/macOS/tvOS (native), JS (Node.js via `NodeJsFileSystem`).
- `FileSystem.SYSTEM` is available on JVM, Android, and all native targets. JS (browser) has no system filesystem — acceptable since file-based migrations are a CLI/desktop concern.
- Well-tested, large community, used in production by OkHttp, Retrofit, and many KMP apps.
- SteleKit may already depend on Okio transitively (via Ktor or SQLDelight).

**Caveats:**
- JS (browser) has no system filesystem. Migrations in a browser context would need a virtual filesystem or would simply be out of scope (the requirements state Desktop/JVM is the first target).
- `NodeJsFileSystem` exists for Node.js JS target if needed.

#### `kotlinx-io` — Future path, not yet stable

- GitHub: https://github.com/Kotlin/kotlinx-io
- Current stable: 0.8.0 (updated Kotlin to 2.2, Dokka to 2.0.0).
- **API stability: Alpha.** The API is subject to change and is not yet considered stable.
- Provides `kotlinx.io.files.FileSystem` and `Path` primitives.
- Based on Okio conceptually; a bridge module `kotlinx-io-okio` exists for interop.
- Targets: JVM, Android, iOS, JS, WASM.
- **Fit**: Promising long-term, but Alpha stability is a risk for production-quality migration tooling.

#### `expect/actual` with platform file APIs

- Using `expect actual fun readFile(path: String): String` with platform-specific implementations.
- Works everywhere but requires writing platform implementations for JVM, Android, iOS, JS separately.
- Creates ongoing maintenance burden. Not recommended when Okio covers all targets.

#### KMPFile (`zacharee/KMPFile`)

- GitHub: https://github.com/zacharee/KMPFile
- Thin wrapper providing a Java-`File`-like API for KMP.
- Uses `kotlinx-io` internally; supports JVM, Android, iOS.
- Less battle-tested than Okio.

### Recommendation

**Use Okio 3.x.** It is the most mature, production-stable cross-platform file I/O library in the KMP ecosystem. Plan to migrate to `kotlinx-io` once it reaches stable API status (likely when it exits Alpha). Since SteleKit targets Desktop JVM first, the `FileSystem.SYSTEM` API is immediately available.

---

## Recommendations

Ranked technology choices for the migration framework, in priority order:

| Concern | Choice | Artifact / Version | Notes |
|---|---|---|---|
| Migration DSL | Hand-rolled Kotlin internal DSL | (no dependency) | Builder pattern in commonMain; zero parser infrastructure |
| Changelog serialization | `kotlinx.serialization` JSON | `1.7.x` | Sidecar `.stele-migration-log.json` per graph; human-readable, git-diffable |
| Text / sequence diff | `petertrr/kotlin-multiplatform-diff` | Latest Kotlin 2.x release | Apache 2.0; port of java-diff-utils; JVM + JS + Native |
| Block-tree structural diff | Hand-rolled domain diff | (no dependency) | Leverages SteleKit stable block identity + op log primitives |
| Migration DAG | Hand-rolled Kahn's algorithm | (no dependency) | ~100 lines; adjacency map + BFS topological sort |
| File I/O | Okio 3.x | `3.16.x` | Apache 2.0; `FileSystem.SYSTEM` on JVM/Android/Native; JS-Node.js via `NodeJsFileSystem` |

### What to avoid

- **ANTLR / antlr-kotlin**: Only needed if external text-file migration authoring is a requirement. Adds grammar tooling overhead for no gain over a Kotlin builder DSL.
- **Exposed / Ktorm / jOOQ**: JVM-only; cannot be used in commonMain.
- **kotlinx-io**: Alpha API; wait until it stabilizes before adopting in production.
- **SQLDelight for changelog log**: Creates a circular dependency if migrations run before graph DB open; use flat JSON sidecar instead.

---

## Sources

- [GitHub - Strumenta/antlr-kotlin](https://github.com/Strumenta/antlr-kotlin)
- [Maven Central - com.strumenta:antlr-kotlin-runtime](https://central.sonatype.com/artifact/com.strumenta/antlr-kotlin-runtime)
- [GitHub - petertrr/kotlin-multiplatform-diff](https://github.com/petertrr/kotlin-multiplatform-diff)
- [GitHub - andrewbailey/Difference](https://github.com/andrewbailey/Difference)
- [GitHub - Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
- [GitHub - square/okio](https://github.com/square/okio)
- [Okio Multiplatform docs](https://square.github.io/okio/multiplatform/)
- [Okio File System docs](https://square.github.io/okio/file_system/)
- [Okio Change Log](https://square.github.io/okio/changelog/)
- [GitHub - Kotlin/kotlinx-io](https://github.com/Kotlin/kotlinx-io)
- [kotlinx-io FileSystem API](https://kotlinlang.org/api/kotlinx-io/kotlinx-io-core/kotlinx.io.files/-file-system/)
- [Kotlin in Action Ch. 11 — DSL construction](https://livebook.manning.com/book/kotlin-in-action/chapter-11)
- [GitHub - terrakok/kmp-awesome (curated KMP library list)](https://github.com/terrakok/kmp-awesome)
- [Building a parser with ANTLR and Kotlin - Strumenta](https://tomassetti.me/building-and-testing-a-parser-with-antlr-and-kotlin/)
- [Mastering Topological Sort in Kotlin - Medium](https://medium.com/@chetanshingare2991/mastering-topological-sort-in-kotlin-a-complete-guide-9b21af20ecef)
- [Difftastic Manual — Tree Diffing](https://difftastic.wilfred.me.uk/tree_diffing.html)
