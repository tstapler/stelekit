# ADR-001: Web image storage backend: OPFS working store, optional host-folder mirror, git as the durable remote

**Status**: Proposed (owner decision D1; recommended default)
**Date**: 2026-11-01
**Deciders**: Repo owner (Tyler Stapler)
**Related**: `research/stack.md` sections 2 and 4, `research/pitfalls.md` P5 to P7, `docs/adr/ADR-015-wasm-git-data-api-write-back.md`

## Context
Web already stores the graph in OPFS and optionally mirrors to a user-picked host folder (File System Access, Chromium only) with git write-back through the REST API (VERIFIED: `PlatformFileSystem.kt`, `HostDirectorySync.kt`, `WasmGitWriteService.kt`). Images currently go to OPFS by a side path (`WasmMediaAttachmentService.kt:98`). The owner asked which browser storage backend to use.

## Decision
1. **OPFS is the working store for binary assets**, accessed through `WebBinaryStore` (ADR-002).
2. **The host-folder mirror remains the optional durable local copy** (Chromium). Binary write-through streams `File` objects; it never loads images into the Kotlin heap.
3. **Git (GitHub/GitLab) remains the only cross-device durable copy**, with LFS where the repo uses it.
4. **IndexedDB is not used for image bytes.** It stays what it is today: small structured data and directory handles.
5. Persistence is requested with `navigator.storage.persist()` on the first successful attach, and surfaced by a storage panel (E10).

## Alternatives
| Option | Why not |
|---|---|
| IndexedDB blob store | Same origin quota as OPFS (INFERRED), extra copies on read/write, a second persistence story, slower for large blobs; no benefit over OPFS. |
| Host folder as the only store | Chromium only; Firefox and Safari have no `showDirectoryPicker`; would exclude most mobile users. |
| Cache API | Meant for request/response pairs, awkward for rename/list; eviction semantics similar. |

## Consequences
- Safari/Firefox users without a host folder rely on OPFS plus git; the browser-only banner must say so for images (E10.2.1a).
- Where `createWritable` is missing (Safari main thread, INFERRED) a worker-based sync-access-handle write path is required (owner decision D2, spike S3).
- Storage choices are reversible: the store sits behind `PlatformFileSystem` delegation, so a backend swap does not touch commonMain services.
