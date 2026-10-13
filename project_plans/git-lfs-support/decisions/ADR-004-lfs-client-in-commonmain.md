# ADR-004: The LFS batch client lives in commonMain behind ports

**Status**: Proposed
**Date**: 2026-10-11
**Deciders**: Repo owner (Tyler Stapler)
**Related**: ADR-001 (hybrid), ADR-002 (web write), `../research/stack.md` section 4, `../research/web-lfs.md` section 5

## Context
JVM and Android need an authenticated LFS batch, download, upload and verify client because JGit's sends no `Authorization` (ADR-001). The web read and write paths need the same protocol over the browser's Ktor engine. Ktor client core, content-negotiation, kotlinx-serialization-json and `ktor-client-mock` are already in `kmp/build.gradle.kts:121-123,224` and the JS engine at `:244`; okio (used by `MarkdownTargetWriter.kt:261`) gives a streaming SHA-256 in commonMain.

## Decision
One client in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/lfs/client/`:
- `LfsBatchClient(httpClient, auth: LfsAuth, endpoint: LfsEndpoint, policy: LfsRetryPolicy)` with `batch`, `download`, `upload`, `verify`.
- Ports implemented per platform: `LfsObjectStore` (streaming `openWrite(oid, size)` returning a writer that hashes while writing, verifies SHA-256 and size on `commit()`, writes to a temp file and renames atomically, supports `resumeOffset`; JVM and Android over `<gitdir>/lfs/objects`, web over OPFS) and `LfsAuth` (Basic or Bearer or None; the token is attached only to the batch and verify calls, never to object hrefs unless the action's own `header` map says so).
- Behaviour contract: connect and request timeouts, retry with backoff and `Retry-After` for 429 and 5xx, batch chunks of at most 100 objects with one halving retry on 413, per-object errors mapped by `LfsErrorMapper` (401, 403, 404, 410, 422, 429, 507, 509, quota text) to `DomainError.GitError` variants, progress callbacks, `Range` resume when the server returns `Accept-Ranges`.
- No gson, no JGit types, no `java.*` in commonMain. JGit-facing glue (`JgitLfsSetup`, `JvmLfsObjectStore`, `LfsPointerScanner`, `LfsTransferService`) lives in `jvmCommonMain/.../git/lfs/`.
- The commonMain `LfsPointer` (spec-strict, ADR-003) is the single pointer model; JGit's `LfsPointer` is used only inside differential tests.

## Alternatives rejected
| Alternative | Why not |
|---|---|
| Patch JGit via `java.net.Authenticator` or a global `HttpConnectionFactory` | Process-global, leaks credential scope across the JVM and into plain git HTTP (`../research/stack.md` section 1) |
| Separate JVM and web clients | Two protocol implementations drift; the web path would be untestable with the JVM fake server |
| Ktor engine-specific streaming types in the port | The port takes okio `Source` and `Sink` so JVM files, OPFS and test fakes all fit |

## Consequences
- Web downloads have no `Range` resume in v1 (a dropped transfer restarts; the size cap bounds it); stated to users. Orphaned host objects after a failed multi-object upload are harmless but count against host storage; stated in docs.
- The fake server fixture (Story E7.1) exercises the real client over real HTTP on the JVM; the same client is exercised on web with `MockEngine`.
- Memory: JVM and Android stream (no whole-file `ByteArray`). Web streams to OPFS when `FileSystemWritableFileStream` is available, otherwise enforces the size cap (default 50 MiB) before downloading.
