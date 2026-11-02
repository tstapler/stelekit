# ADR-002: JS owns image bytes; Kotlin owns metadata; `WebBinaryStore` collaborator plus suspend binary reads on `FileSystem`

**Status**: Proposed (owner decision D4; recommended default)
**Date**: 2026-11-01
**Related**: `wasm-image-drop` ADR-002 (base64 bridge), `research/architecture.md` sections 0 to 2, `research/pitfalls.md` P1 to P3

## Context
Kotlin/Wasm has no zero-copy `ByteArray` to `Uint8Array` path, so the repo uses a base64 string bridge (`ByteBufferInterop.kt:6-37`), slow and multi-copy. The web `FileSystem` treats the graph as text; binary content is either in `bytesCache` (whole arrays in the Wasm heap), in `blobUrlCache` (image paths), or invisible in OPFS (VERIFIED, `current-state.md`). Shared commonMain services need list/size/read/rename/write for binaries.

## Decision
1. A new wasmJsMain `WebBinaryStore` owns binary content (OPFS), a metadata `BinaryIndex` (path, size, mtime, optional hash, optional display URL) and blob-URL lifecycle. `PlatformFileSystem` delegates to it with one-line overrides, following the existing collaborator pattern (`hostDirectorySync`, `GraphSyncSession`) and the repo rule that new platform capabilities are collaborators, not new `PlatformFileSystem` fields.
2. `FileSystem` (commonMain) gains **suspend** binary operations with defaults that delegate to the current sync methods, so JVM/Android/iOS behavior is unchanged: `readFileHead`, `readFileBytesBounded`, `contentHashHex`, `writeFileStream(path, FileSource)`. Metadata (`getFileSize`, `listFiles`, `fileExists`, `directoryExists`, `getLastModifiedTime`) stays synchronous and is answered from the index. Precedent: `readFileSuspend`.
3. `FileSource` is a commonMain sealed interface (`Bytes(ByteArray)`, `Handle(Any)`); web passes JS `File` handles end to end for streams and uses `Bytes` only for small payloads and non-web callers.
4. Dirty tracking for binaries records a path and no bytes; consumers (git write service, host write-through, zip export) ask the store for content when they need it, and large uploads are encoded to base64 inside JS.
5. `LocalOnlyPaths` (`.stelekit/cache/**`, `.stelekit/lfs/**`, `_inbox/**`) is a single commonMain predicate consulted by every write/sync path.

## Alternatives
| Option | Why not |
|---|---|
| Keep `bytesCache`, add `readFileBytes` returning it | Whole image set resident in the Wasm heap; already the cause of heavy reconcile (`HostDirectorySync.kt:1276-1292`). |
| Separate commonMain `BinaryAssetStore` port, leave `FileSystem` alone (option B of D4) | Cleaner in isolation but every existing caller (`AssetIndexService`, `ImageImportService`, `AssetCopier`) must change type, and test fakes diverge; larger blast radius. Acceptable fallback if the owner prefers it. |
| Make `readFileBytes` blocking on web | Impossible: OPFS and `File.arrayBuffer()` are async and the single-threaded Wasm runtime cannot block on a Promise. |

## Consequences
- Callers that today use sync `readFileBytes` for large images are migrated to the suspend API in the same epics (E3, E6); small fixed-size reads keep working.
- New pure code is tested in `commonTest`; interop in `wasmJsTest`.
- Wiring and structural tests (`LocalOnlyCallSitesStructuralTest`, `LfsStagingCallSitesStructuralTest`) pin the invariants.
