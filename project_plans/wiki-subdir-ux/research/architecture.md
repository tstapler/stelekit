# Architecture Research: Wiki Subdir UX

## 1. Problem Context

The wiki-subdir-ux requirement addresses a disconnect between where the app
thinks a graph's content lives (configured repo-root + wiki-subdir) and where
content actually lives on disk. The mismatch arises in two scenarios:

1. **Git-backed graphs** — `GraphManager.detectGitRoot()` (lines 990-1026) uses
   upward-only `.git` traversal, which yields an empty `wikiSubdir` when the
   graph path *is* the clone root (the normal post-clone case). The user must
   discover and type the subfolder manually via Git Setup → Step 2.

2. **SAF-mounted graphs** — `detectGitRoot()` bails out outright for
   `saf://`/`content://` paths (lines 998-1001), so no candidate detection
   exists for Android SAF users at all.

Once misconfigured, the app renders an empty graph (0 pages) with no indication
of why, and any writes during the misconfiguration window land at the wrong
location and become silently orphaned.

## 2. Existing Codebase Analysis

### 2.1 Candidate Detection (GraphDiagnostics)

`GraphDiagnostics.kt:81-114` (`appendDisk()` → `appendDiskCandidate()`) already
computes the mismatch signal the feature needs:

- Lists root subdirectories and files via `FileSystem.listDirectories()` /
  `listFiles()`
- Checks `pages/` and `journals/` at the configured root
- Performs a one-level downward probe (`MAX_NESTED_PROBES` cap) into
  subdirectories, flagging those with `pages/` or `journals/` as
  `NESTED GRAPH CANDIDATE`

This logic is **export-time only** — it produces a text string in the
diagnostics dump but does not surface as observable state. Phase 3 must extract
this scan into a reusable function callable both from
`GraphDiagnosticsCollector` (export path) and a new warm-reconcile hook (proactive
path).

### 2.2 Banner System (GraphContentMainArea)

`GraphContentMainArea.kt:95-174` hosts a pluggable banner system with three
existing conditions:

1. `GitDetectionBanner` — fires when `detectedRepoRoot != null && gitConfig == null`
   but **goes silent permanently once *any* config is saved**, even a wrong one
2. `BrowserOnlySyncBanner` — for web-only sync flows
3. `HostReconnectBanner` — for host-directory permission issues

The new mismatch banner should extend this system. Unlike the git-detection
banner (which is a setup-state check), the mismatch banner must be a
*warm-reconcile-time* check that fires even when git config exists. The
`BannerVisibility` data class and `computeBannerVisibility()` function are the
extension points.

### 2.3 Move/Merge Dialog Pattern

`StorageMoveChoiceDialog.kt` (commonMain) provides the existing UI pattern:
an `AlertDialog` with stacked full-width `OutlinedButton`s using
`describeForHumans()` to prevent leaking platform-specific path URIs into copy.
However, it is currently only a two-way choice (Relocate vs. Link). Requirements.md
§C.16 calls for a Move/Merge/Leave decision — this needs a sibling composable
following the same shape, not a parameterization of the existing dialog.

`DiskConflictDialog.kt` / `DiskConflictBlockMatcher.kt` handle per-file conflict
resolution during external-change reconciliation, which can be reused for
same-filename collisions in the move/merge flow.

### 2.4 File Operations (FileSystem Interface)

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt` provides:

- `listFiles(path)` / `listDirectories(path)` — single-level listing
- `listFilesRecursiveWithModTimes(path)` — recursive, SAF-safe via
  `listFiles`/`listDirectories` abstraction
- `renameFile(from, to): Boolean` — single-file move with:
  - **JVM**: `JvmFileSystemBase.renameFile` + `copyTo(overwrite=true)` + delete fallback
  - **Android**: dual-path with `safRenameFile()` using `DocumentsContract.moveDocument`
    (API 24+, all available at minSdk 26) + `genericCopyThenDelete` stream-copy fallback
  - **Wasm**: cache-level rename + async `HostDirectorySync.renameHostFile`
- `createDirectory(path)` — recursive creation support varies by platform

**Gap**: There is **no directory-level recursive move** primitive. A directory
move must be assembled by walking `listFilesRecursiveWithModTimes()` and calling
`renameFile` per file. Additionally, no `FileSystem.copyFile()` method exists —
only `renameFile()` (destructive). The Merge case and the SAF move-unreliable
fallback need a copy primitive (Phase 3 design question).

### 2.5 Atomic Git Commit

`GitSyncService.commitLocalChanges(graphId)` (lines 440-475) stages the wiki
subdirectory (`gitRepository.stageSubdir(config)`) and makes a single commit
(`gitRepository.commit(config, ...)`) with a descriptive message. Returns
`Either<DomainError.GitError, String?>` (commit SHA or null if nothing changed).

On Wasm, `WasmGitWriteService.commit()` (line 185, with `commitGitHub`/`commitGitLab`)
provides an equivalent primitive. The reconciliation flow must dispatch to whichever
commit primitive is live for the current graph's git backend.

### 2.6 DatabaseWriteActor

`DatabaseWriteActor.kt` serializes all database writes through a single coroutine,
eliminating SQLite write-lock contention. Two-channel priority queue (HIGH for
user-initiated, LOW for bulk). Provides `execute { }` for arbitrary write ops and
has `close()` for lifecycle management. The `WriteRequest.Execute` arm already
supports opaque write operations via `CompletableDeferred` — suitable for batching
a directory-scan-triggered reconciliation.

### 2.7 GraphInfo Model

`GraphInfo.kt` carries `detectedRepoRoot: String?` and
`detectedWikiSubdir: String?` (detection-only fields, separate from user-configured
`GitConfig`). These are updated via `updateGraphInfoDetection()` in
`GraphManager.kt:1042-1043`. The mismatch banner needs to compare configured
(`GitConfig.wikiSubdir`) against the *actual* on-disk candidate.

## 3. Integration Points & Data Flow

### 3.1 Proactive Detection Signal

```
GraphLoader.warmReconcile
  → calls GraphDirectoryScanResult.surveyContentRoot(configuredRoot)
    → listDirectories + listFiles at configured root
    → downward probe (1-2 levels) for nested pageDir/journalDir
    → produces: MismatchSignal(configuredRootIsEmpty, bestCandidatePath, fileCounts)
  → emits via StateFlow on StelekitViewModel
  → GraphContentMainArea.computeBannerVisibility() checks MismatchSignal
  → GitDetectionBanner (extended) or new MismatchBanner composable fires
```

The signal must be **debounced and cached** — re-scanning on every warm
reconcile tick is potentially expensive on SAF (each `listFiles` = Binder IPC).
A lightweight heuristic gate (only scan when page count is below a threshold) is
recommended per requirements.md §Scope, Q.101.

### 3.2 Move/Merge Decision Flow

```
User clicks "Fix" on the mismatch banner
  → WikiSubdirFixDialog (new composable, StorageMoveChoiceDialog sibling)
    → shows: dry-run manifest ("2 files at old root, 0 at new root")
    → three buttons: Move | Merge | Leave-as-is
  → On Move/Merge selection:
    → DatabaseWriteActor.execute { reconcileSubdirMove(config, strategy) }
      → stage file operations (move old→new, handle conflicts)
      → verify-after-fix re-scan
      → commit graph registry update
    → GitSyncService.commitLocalChanges(graphId)   // single atomic commit
    → emit success to UI
```

### 3.3 Consistency Boundaries

| Layer | Write Mechanism | Error Handling | Testability |
|-------|----------------|----------------|-------------|
| Database | `DatabaseWriteActor` (serialized) | `Either<DomainError, Unit>` | Unit-testable via `UnconfinedTestDispatcher` |
| Filesystem | `FileSystem.renameFile` per-file | `Boolean` return | Platform-specific; use `WikiSubdirUriGuardTest` pattern |
| Git | `GitSyncService.commitLocalChanges` | `Either<GitError, String?>` | Mockable via `GitConfigRepository` |

The reconciliation must be **crash-safe**: a mid-operation interruption requires
resumption. The requirements call for a marker file
(`.stelekit/move-in-progress.json`) serialized with `kotlinx-serialization-json`
(already a dependency) to record progress, checked on next launch.

### 3.4 Git-Sync Race Condition

Requirements.md Rabbit Hole §6.6 and §10.6 call out the race between a
reconciliation move and an in-flight auto-sync cycle. The existing
`GitSyncService` uses a `coordinatorMutex` (`GraphManager.kt:983`) to serialize
graph-id-keyed git operations, but file moves during reconciliation are
filesystem-level (outside the DatabaseWriteActor) while git sync stages/commits
through `gitRepository`. The sequencing constraint:

1. File moves must complete before `stageSubdir` is called
2. `DatabaseWriteActor` must flush any pending writes (journal entries created
   during misconfiguration) before the filesystem move begins
3. The git commit must not happen until both the filesystem move AND the DB
   stabilization are confirmed

The `DatabaseWriteActor.hasPendingWrites` flag (line 252-253) provides the
necessary synchronization signal.

## 4. Platform-Specific Considerations

| Platform | Scan Cost | Move Semantics | Special Concerns |
|----------|-----------|----------------|------------------|
| JVM/Desktop | Local syscalls, cheap | `renameTo` or copy+delete | Cross-volume fallback needed |
| Android (git) | Local syscalls if `hasAllFilesAccess()` | `renameFile` via `PlatformFileSystem` | Same as JVM |
| Android (SAF) | Each `listFiles` = IPC round-trip | `moveDocument` (API 24+, reliable on most OEMs) or `genericCopyThenDelete` stream fallback | Per-OEM `FLAG_SUPPORTS_MOVE` variability — existing `safRenameFile()` handles this |
| Web/Wasm | OPFS (local) or `HostDirectorySync` | `HostDirectorySync.renameHostFile` | Limited to app-owned or linked host directory |

No API-level gating is needed — `minSdk = 26` exceeds the API 24 requirement for
`DocumentsContract.moveDocument`/`copyDocument`. The only variability is
per-OEM-provider `FLAG_SUPPORTS_MOVE` support, already handled by the existing
`safRenameFile()` fallback pattern.

## 5. Architectural Disposition

This is a **REFACTOR-FIRST** requirement. Every capability the feature needs
already exists in primitive form in the codebase:

- Candidate scanning: `GraphDiagnostics.appendDiskCandidate()` (lines 96-102)
- Banner system: `GraphContentMainArea` + `GitDetectionBanner`
- Dialog pattern: `StorageMoveChoiceDialog` (AlertDialog + OutlinedButton)
- File move: `FileSystem.renameFile` + `listFilesRecursiveWithModTimes`
- Atomic git commit: `GitSyncService.commitLocalChanges`
- Serialized DB writes: `DatabaseWriteActor`
- Error handling discipline: Arrow `Either<DomainError, T>` throughout

The work is **composition and extension**, not invention: connect the existing
detection signal to a live UI banner, extend the existing dialog pattern to a
three-way choice, assemble per-file moves from existing primitives into a
directory-level operation, and wire the whole thing through the existing atomic
commit + DB-write-actor infrastructure for consistency.

The dominant integration risk is not architectural (no new patterns needed) but
**sequencing/safety**: ensuring file moves, DB stabilization, and git staging
happen in the correct order without racing, and that a mid-operation crash leaves
the graph in a resumable state rather than a corrupted one. The requirements'
safety guardrails (dry-run manifest, soft-delete trash, resumable marker file,
verify-after-fix) directly address this.

## 6. Key Design Questions for Phase 3

1. **Directory-level move primitive**: Assemble walk `listFilesRecursiveWithModTimes()`
   + `renameFile` per-file, OR add a new `FileSystem.moveDirectory()` method with
   platform-specific `actual` implementations backing it?
2. **Copy primitive**: Does the Merge case (files at both old and new locations with
   different content) need a `FileSystem.copyFile()` interface addition, or can it
   be built from `readFile` + `writeFile` + `deleteFile`?
3. **SAF copy reliability**: Is `DocumentsContract.copyDocument` reliable enough to use
   directly, or must the `genericCopyThenDelete` stream-copy pattern be extended?
4. **Warm-reconcile scan gating**: Should the periodic candidate re-scan be gated by
   a heuristic (e.g., only when total page count < N) to bound SAF IPC cost?
5. **Soft-delete retention policy**: Time-based, count-based, or "until next
   successful git sync"? Phase 3 planning decision.
