# ADR-011: ktoml-core for Section Manifest TOML Parsing

## Status
Accepted

## Context

The Graph Sections feature (graph-namespaces epic) introduces a `.stele-sections` configuration file at the graph root. The format is TOML (FR-1.1), chosen because it is human-editable, supports arrays of tables (`[[section]]`), and is a well-established configuration format. The requirement also covers NFR-4: the file must be valid TOML and human-editable.

SteleKit currently has no TOML library. It targets four platforms simultaneously: JVM/Desktop, Android, iOS (Kotlin/Native), and Kotlin/WASM. Any TOML library must have production-quality support on all four targets, because the manifest must be readable on every platform where a section-filtered graph can be opened.

### Requirements on the library

1. Must support `wasmJs` target — the WASM build needs to parse the manifest to present the section selector before any content is fetched.
2. Must support kotlinx.serialization `@Serializable` data classes — the codebase already uses kotlinx.serialization for JSON (`GraphInfo`, etc.); TOML parsing should integrate with the same framework.
3. Must support arrays of tables (`[[section]]` syntax) and string arrays (`paths = [...]`).
4. Must support `ignoreUnknownNames = true` for forward compatibility — new manifest fields added in future versions must not break old client parses.
5. MIT or Apache 2.0 license — compatible with SteleKit's Elastic-2.0 license.

### Options evaluated

| Library | WASM/JS | kotlinx.serialization | Arrays of tables | License | Notes |
|---------|---------|----------------------|-----------------|---------|-------|
| **ktoml-core 0.7.1** | Yes (explicit) | Yes (`@Serializable`) | Yes | MIT | 557 stars; active; TOML 1.0 compliance suite; released Aug 2025 |
| tomlkt 0.6.0 | Unconfirmed | Yes | Yes | Apache 2.0 | Smaller community; no explicit `wasmJs` confirmation |
| kotlinx.serialization (native TOML) | N/A | N/A | N/A | Apache 2.0 | No TOML backend exists in the library |
| Hand-rolled parser | Yes | N/A | Manual | — | ~100 LOC; no dependency risk; no kotlinx.serialization integration |

### ktoml module breakdown

`ktoml` splits into two modules:
- **`ktoml-core`**: pure Kotlin common; no Java dependencies; supports JVM, JS, Native, `wasmJs`, `wasmWasi`. Provides `Toml.decodeFromString<T>()` and `Toml.encodeToString(value)`.
- **`ktoml-file`**: adds okio for file-path–based I/O. JVM and Native only; not available for `wasmJs`.

SteleKit reads `.stele-sections` via its own `FileSystem` abstraction (`PlatformFileSystem.kt`, `OpfsInterop.kt`) rather than the filesystem path directly. File contents are already a `String` by the time the parser is called, so `ktoml-file` is never needed for parsing — only `ktoml-core`.

`ktoml-file` is added to JVM and Android as a convenience for the `SectionManifestParser.writeFile` path (writes TOML back to disk), but this is optional; `ktoml-core` alone is sufficient for all four platforms.

## Decision

Use **`ktoml-core:0.7.1`** in `commonMain` for all TOML parsing and encoding of the section manifest. Optionally add **`ktoml-file:0.7.1`** to `jvmMain` and `androidMain` for file-path–based writing.

```kotlin
// kmp/build.gradle.kts
// commonMain
implementation("com.akuleshov7:ktoml-core:0.7.1")

// jvmMain + androidMain (for direct file-path write convenience)
implementation("com.akuleshov7:ktoml-file:0.7.1")
```

Parsing pattern:
```kotlin
val tomlConfig = TomlConfig(ignoreUnknownNames = true)
val manifest: SectionManifest = Toml(config = tomlConfig).decodeFromString(tomlString)
```

Writing pattern:
```kotlin
val tomlString = Toml.encodeToString(manifest)
fileSystem.writeFile("$graphPath/.stele-sections", tomlString)
```

## Rationale

1. **`wasmJs` support is confirmed and exclusive**: `ktoml-core` is the only KMP TOML library that explicitly documents `wasmJs` target support. `tomlkt` does not confirm this. Without `wasmJs` support, the WASM build cannot parse the manifest for section selection.

2. **kotlinx.serialization integration avoids custom parsing code**: The `.stele-sections` format (arrays of tables, string arrays, nullable fields) maps directly to `@Serializable data class SectionManifest(val section: List<GraphSection>)`. Parsing is a one-liner; no grammar implementation required.

3. **`ignoreUnknownNames = true` enables forward compatibility**: Future manifest versions can add fields (e.g., `syncMode`, `encryptionKey`) without breaking clients running older SteleKit versions.

4. **Active maintenance**: v0.7.1 was released August 2025; it targets Kotlin 2.2.0 (same as SteleKit). The project has a TOML 1.0 compliance test suite.

5. **MIT license**: Compatible with SteleKit's Elastic-2.0 license and the existing dependency set.

## Alternatives Rejected

### tomlkt
Not confirmed to support `wasmJs`. With an unconfirmed platform target, adding this library risks a compilation failure or runtime error on the WASM build that would only surface at integration time. `ktoml` is strictly superior on the documented criteria.

### Hand-rolled parser
A hand-rolled parser for the exact `.stele-sections` grammar is approximately 100 LOC. It avoids any dependency risk and would work on all platforms. However, it provides no kotlinx.serialization integration (requiring manual field mapping), no TOML 1.0 compliance guarantees, and no forward-compatibility mechanism. The total development cost (parser + unit tests + future maintenance as the format evolves) exceeds the cost of integrating `ktoml-core`. **The hand-rolled option remains viable if `ktoml-core` introduces a `wasmJs` regression.** If that occurs, the `SectionManifestParser` API surface is small enough that the parser implementation can be swapped without changing call sites.

### Native TOML in kotlinx.serialization
Not available. kotlinx.serialization supports JSON, CBOR, and ProtoBuf. No TOML format backend exists. Not applicable.

## Consequences

- `ktoml-core:0.7.1` is added to `commonMain` dependencies. Jar size is ~150 KB. No native library; no JNI; no performance concern.
- `ktoml-file:0.7.1` is added to `jvmMain` and `androidMain` only. It brings `okio` as a transitive dependency; okio is already a transitive dependency of SQLDelight so this adds no new artifact.
- All TOML parsing for the section manifest flows through `SectionManifestParser` (a single object). If a future library upgrade is needed, only that object changes.
- The WASM build's `ktoml-core` usage is covered by `MigrationRunnerSchemaSyncTest`-style compile-time verification: any `wasmJs` target failure surfaces as a Bazel build failure for `//kmp:web_app`.
