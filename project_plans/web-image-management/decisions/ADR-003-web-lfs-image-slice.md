# ADR-003: Web LFS for images: this program executes the web slice of git-lfs-support, reusing its approved decisions

**Status**: Proposed (owner decision D11; recommended default)
**Date**: 2026-11-01
**Related**: `project_plans/git-lfs-support` ADR-001..004 and `research/web-lfs.md`; PR #514 (`daceba333d`, JVM/Android transfer)

## Context
- Web today has only the LFS safety floor: rules fetched from the remote, guard holds raw files at LFS-tracked paths, blob gate (`WasmGitWriteService.kt:129-213`, `git/lfs/WasmLfsRulesSource.kt`), pinned by `LfsStagingCallSitesStructuralTest.kt:110-114`. VERIFIED.
- PR #514 built JVM/Android transfer in `jvmCommonMain` (`LfsBatchClient.kt` imports `java.io.File` and ktor `javaio`). `git-lfs-support` ADR-004 planned the client in commonMain; that was not executed. VERIFIED.
- Browser constraints (VERIFIED 2026-10-11 in `git-lfs-support/research/web-lfs.md`): github.com and gitlab.com batch preflights carry no CORS headers, so authenticated batch/upload from a browser is blocked; `media.githubusercontent.com` and GitLab `api/v4/.../raw?lfs=true` are CORS-open for reads; Bitbucket and Azure DevOps preflights pass.
- Owner decisions already recorded in that ADR header: O1 no proxy of any kind (Proxy mode rejected); O2 the Converter workflow mode is approved as an opt-in advanced mode.

## Decision
1. Web LFS image support is delivered by this program (epic E9) rather than by a second plan building a second client. `git-lfs-support` Phase 3 (E3 read, E4 write) is treated as the design input; where this plan's tasks differ, this plan wins and the older plan gets a one-line superseded pointer (task 11.1e).
2. The LFS protocol core (models, error mapping, retry policy, transport port) moves to commonMain; `java.io` glue stays in `jvmCommonMain`. The JVM suite (`LfsBatchClientStatusTest`, `JvmGitRepositoryLfsTransferTest`, `FakeLfsServer`) is the contract test for the shared core.
3. **Read**: pointer detection in commonMain; fetch objects through `media.githubusercontent.com` (GitHub) or `raw?lfs=true` (GitLab), the batch endpoint when the CORS probe passes; verify SHA-256 and size; cache content-addressed in OPFS as local-only data; display resolves pointer to the cached object while the working-tree file stays the pointer.
4. **Write**: `Direct` (probe passes), `Converter` (opt-in, O2), `ReadOnly` (default). The guard and `WasmLfsBlobGate` stay in force. The Converter's staging ref is the single documented exception; the structural test changes to assert exactly one staging call site behind the opt-in flag, never a general loosening.
5. No SteleKit-hosted infrastructure, no proxy, no third-party CDN for scripts.

## Alternatives
| Option | Why not |
|---|---|
| Keep two plans and sequence git-lfs-support first | Two owners for one client; the image slice needs the read path first anyway. Acceptable if the owner prefers (D11 option B); task list stays valid. |
| Web read-only forever | Leaves LFS-tracked image repos unusable for attaching on web; owner asked for "a real way" in `git-lfs-support`. |
| User-owned proxy | Rejected by owner O1. |

## Consequences
- Real-host validation (private repos, Bitbucket/Azure object hrefs, Converter on both hosts) is an owner-run wall-clock blocker (B4, B5).
- Image UX adds placeholder states for not-downloaded LFS objects and a pre-attach hint when the destination is LFS-tracked.
