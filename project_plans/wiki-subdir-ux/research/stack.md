# Phase 2 Research: Stack

## Bottom line

No new dependencies are needed. Every capability this feature requires — directory scanning,
file move/rename (including SAF), a two/three-way choice dialog pattern, and an atomic git
commit wrapper — already exists as a reusable primitive somewhere in `kmp/src/commonMain` or a
platform `actual`. The work is composition and extension of those primitives (directory-level
move/merge, trash, resumable marker, three-way dialog, proactive banner wiring), not new library
integration. Pinned versions (Kotlin 2.4.10, Compose Multiplatform 1.10.3, coroutines 1.10.2,
AGP 8.13.2, minSdk 26/compileSdk 36) are current for this repo as of 2026-10-01 and impose no
constraint on the design — in particular, minSdk 26 means every `DocumentsContract` SAF API this
feature could use (`copyDocument`/`moveDocument`/`renameDocument`, all added API 24) is
unconditionally available; no API-level gating is required anywhere in this project.

## Versions in use (`kmp/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`)

| Dependency | Version | Source |
|---|---|---|
| Kotlin (multiplatform plugin) | 2.4.10 | `settings.gradle.kts:9` |
| Compose Multiplatform plugin | 1.10.3 | `settings.gradle.kts:17` |
| Android Gradle Plugin (`com.android.{application,library,test}`) | 8.13.2 | `settings.gradle.kts:14-16` |
| `kotlinx-coroutines-core` / `-android` / `-swing` / `-test` | 1.10.2 | `kmp/build.gradle.kts:94,169,263,152` etc. |
| `kotlinx-serialization-json` | 1.10.0 | `kmp/build.gradle.kts:96` |
| Arrow (`arrow-fx-coroutines`) | 2.2.1.1 | `kmp/build.gradle.kts:90` |
| SQLDelight (`coroutines-extensions`) | 2.3.2 | `kmp/build.gradle.kts:101` |
| `compileSdk` | 36 | `kmp/build.gradle.kts:1458` |
| `minSdk` | 26 | `kmp/build.gradle.kts:1462` |

No `build.gradle.kts` change is anticipated for this feature — it needs no new Maven/Gradle
dependency (no new file-move library, no new diff library beyond the already-present
`kotlin-multiplatform-diff:1.3.0` used by `DiskConflictDialog`, no new coroutine/serialization
primitive).

## (a) Proactive UI banner/badge for config mismatch

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/git/GitDetectionBanner.kt` and
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphContentMainArea.kt` are the named
  existing banner host/system (per requirements.md §A) — confirmed these files exist; banner
  composable + host site to extend, not build from scratch.
- The actual mismatch *signal* this banner would react to already exists server-side:
  `GraphDiagnosticsCollector.appendDisk()` in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/diagnostics/GraphDiagnostics.kt:81-114`
  already computes exactly the "near-zero content at configured root + nested candidate exists"
  condition today (the `NESTED GRAPH CANDIDATE: $root/$dir (pages=$hasPages journals=$hasJournals)`
  line, lines 96-102) — but only as a one-shot export-time string, not a queryable/observable
  state. Phase 3 needs to decide whether to extract this scan into a reusable function callable
  both from `GraphDiagnosticsCollector` and a new warm-reconcile hook (per requirements.md §B's
  "periodic re-scan on warm reconcile, not just setup time").

## (b) Directory-scan candidate detection across platforms

- **JVM**: plain `java.io.File` listing via `PlatformFileSystem.kt` (jvmMain) — cheap, local syscalls.
- **Android plain filesystem**: same `java.io.File` path as JVM when `hasAllFilesAccess()` is true.
- **Android SAF**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
  implements `listFiles`/`listDirectories` over SAF today via
  `DocumentsContract.buildChildDocumentsUriUsingTree` + a cursor query (lines ~244-260) — each
  directory listing is a `ContentResolver` query, i.e. a Binder IPC round-trip, not a local stat.
  This directly substantiates the "Rabbit Hole" in requirements.md about SAF scan cost: the common
  `FileSystem.listFilesRecursiveWithModTimes()` default (commonMain `FileSystem.kt:88-106`) already
  recurses using `listFiles`/`listDirectories`/`getLastModifiedTime`, so it's SAF-safe by
  construction, but each level of the "scan 1-2 levels down" candidate probe is a real IPC call on
  SAF, not a cheap syscall — Phase 3 should benchmark this (per the "Feasibility Risks" /
  "Rabbit Holes" sections) rather than assume parity with JVM/plain-Android cost, and should bound
  depth explicitly (the existing diagnostics scan already caps itself via `MAX_NESTED_PROBES`,
  `GraphDiagnostics.kt:96` — reuse that same cap rather than inventing a new one).
- **Web/Wasm**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
  backs `listFiles`/`listDirectories` with OPFS (app-owned storage, `supportsAppOwnedStorage = true`
  unconditionally per `FileSystem.kt:625`) and, when a host directory is linked
  (`supportsHostDirectoryLink`, gated on File System Access API / `showDirectoryPicker` support),
  via `HostDirectorySync`. Candidate scanning on web reuses the same common `FileSystem` interface
  methods — no Wasm-specific new API surface needed.
- No platform needs a new scanning library; the existing `FileSystem` interface
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`) already exposes
  `listFiles`, `listDirectories`, `directoryExists`, and `listFilesRecursiveWithModTimes` as the
  complete expect/actual-free toolkit for this.

## (c) Three-way move/merge/leave dialog pattern

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveChoiceDialog.kt`
  exists exactly as named in requirements.md, but **it is currently a two-way choice**
  ("Relocate" vs. "Link" — `onRelocateChoose`/`onLinkChoose`, `AlertDialog` with a
  stacked-full-width-`OutlinedButton` layout, `describeForHumans()` extension on
  `StorageLocation` for human-readable source/destination text). Requirements.md's phrasing
  ("modeled on `StorageMoveChoiceDialog.kt`'s existing pattern") is accurate as a *pattern*
  reference (same `AlertDialog` shape, same neutral-weight `OutlinedButton` treatment so no
  option reads as pre-selected, same `describeForHumans()` idiom for never leaking a raw
  `content://`/OPFS path into UI copy) — it is not an existing three-way component to extend
  in place. Phase 3 should treat the new Move/Merge/Leave dialog as a sibling composable
  following this same shape, not a parameterization of `StorageMoveChoiceDialog` itself (a third
  `OutlinedButton` could fit mechanically, but "Leave as-is" is semantically a dismiss/decline
  action, not a third peer destination — worth a UX pass in Phase 3, not just a code pass).
- `DiskConflictDialog.kt` / `DiskConflictBlockMatcher` (same `ui/components` package) are the
  named reuse target for per-file conflict resolution and diff preview — exist, confirmed present
  alongside `StorageMoveChoiceDialog.kt`.

## (d) File move/copy operations across platforms

- **Single-file move already exists as a cross-platform primitive**: `FileSystem.renameFile(from, to): Boolean`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt:123`, default `false`),
  implemented on:
  - **JVM** (`jvmMain/.../PlatformFileSystem.kt:55`): delegates to `JvmFileSystemBase.renameFile`,
    with `oldFile.copyTo(newFile, overwrite = true)` + delete as the cross-volume fallback when a
    plain `renameTo` fails.
  - **Android** (`androidMain/.../PlatformFileSystem.kt:622-719`): dual-path — plain-filesystem
    rename with copy+delete fallback (lines ~640-667), and a dedicated `safRenameFile()`
    (lines 677-719) that uses `DocumentsContract.renameDocument` for a same-directory rename and
    `DocumentsContract.moveDocument` (doc comment at line 672 explicitly notes "API 24+, always
    available at this app's minSdk 26") for a cross-directory move, with a `genericCopyThenDelete`
    stream-copy fallback when a provider doesn't support `FLAG_SUPPORTS_MOVE` (returns
    null/throws) — this *is* the "conservative copy-then-verify-then-delete fallback" the
    requirements.md Feasibility Risks section anticipates needing; it already exists for the
    single-file case.
  - **Wasm** (`wasmJsMain/.../PlatformFileSystem.kt:607-615`): cache-level rename plus an async
    `HostDirectorySync.renameHostFile` fire-and-forget when a host directory is linked.
- **What does NOT exist**: a directory-level/recursive move or a `copyDocument`-based copy
  primitive. Grep of `kmp/src/androidMain` confirms `DocumentsContract.copyDocument` has zero call
  sites today (only `createDocument`, `renameDocument`, `moveDocument`, `deleteDocument`,
  `getTreeDocumentId`, `buildChildDocumentsUriUsingTree`/`buildDocumentUriUsingTree` are used).
  The Merge case (C) and the SAF-provider-unreliable-move fallback both plausibly need a real
  `copyDocument`-based (or stream-copy) path distinct from `renameFile`'s move-only contract —
  Phase 3 should scope whether to add `FileSystem.copyFile()` as a new interface method (mirroring
  `renameFile`'s shape) or build directory reconciliation purely out of existing
  `readFile`/`writeFile`/`listFilesRecursiveWithModTimes` + per-file `renameFile`.
- Directory-level move/merge will therefore be assembled in this feature's own code by walking
  `listFilesRecursiveWithModTimes()` and calling `renameFile` (or read+write+delete) per file —
  there is no existing "move a whole tree" call to reuse, which is consistent with
  requirements.md's Rabbit Hole flagging SAF move semantics as something Phase 3 must design, not
  assume.

## (e) Soft-delete / trash holding area

- No existing trash/soft-delete mechanism was found anywhere in `kmp/src/commonMain` or
  platform actuals (searched for "trash"/".stelekit/trash" patterns — none exist). This is new
  code for Phase 3 to design, per requirements.md's own Feasibility Risk #2 ("Soft-delete holding
  area adds a new on-disk artifact... that itself needs lifecycle management"). No library gap
  here — it's plain file moves into a `.stelekit/trash/<timestamp>/` directory using the same
  `FileSystem` primitives above; the open question (time-based vs. count-based vs.
  until-next-sync retention) is a Phase 3 product decision, not a stack question.

## (f) Crash-safe resumable-move marker file

- No existing marker-file/resumable-operation pattern was found in this codebase (no existing
  "pending operation" or "WAL"-style file for any other feature). `kotlinx-serialization-json`
  1.10.0 (already a dependency, used extensively elsewhere in `commonMain`) is the natural choice
  to serialize a small marker struct (old root, new root, strategy, per-file progress) to a
  well-known path (e.g. `.stelekit/move-in-progress.json`) and resume/replay it on next launch —
  no new serialization or persistence library is needed.

## (g) Single atomic git commit wrapping the reconciliation

- `GitSyncService.commitLocalChanges(graphId)` in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt:450-475` is an exact,
  ready-to-call primitive: it stages the whole wiki subdir (`gitRepository.stageSubdir`) and makes
  one commit (`gitRepository.commit`), returning `Either<DomainError.GitError, String?>` (the
  commit SHA, or `null`/right if nothing changed). The reconciliation flow can call this once after
  all file moves/merges complete, giving the single-atomic-commit requirement "for free" — the
  design work is sequencing (per requirements.md's Rabbit Hole on racing with
  `DatabaseWriteActor`/an in-flight auto-sync cycle), not building new git plumbing.
- Web/Wasm git writes go through `WasmGitWriteService`
  (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`), which has its own
  `commit()` (line 185) plus host-specific commit builders (`commitGitHub` line 385,
  `commitGitLab` line 795) — confirms a parallel but structurally equivalent atomic-commit
  primitive exists on the Wasm path too; the reconciliation flow will need to dispatch to whichever
  commit primitive is live for the current graph's git backend, not assume `GitSyncService` covers
  Wasm.

## SAF API-level gating — resolved, no gating needed

`minSdk = 26` (`kmp/build.gradle.kts:1462`). `DocumentsContract.moveDocument` and
`DocumentsContract.copyDocument` were both added in API 24 (Android 7.0); `renameDocument` and
`createDocument`/`deleteDocument` are older still. Since the app's floor is API 26, every
`DocumentsContract` move/copy API this feature could use is available on 100% of supported
devices — this is already noted inline in the existing code's doc comment
(`androidMain/.../PlatformFileSystem.kt:672`: "API 24+, always available at this app's minSdk
26"). No `Build.VERSION.SDK_INT` branching is needed anywhere in this feature's SAF code; the only
real-world variability is **per-OEM-provider** support for `FLAG_SUPPORTS_MOVE` (some document
providers don't advertise it and `moveDocument` returns null/throws), which the existing
`safRenameFile()` already handles via its `genericCopyThenDelete` fallback — the same fallback
shape should be reused for whatever new copy/merge primitive Phase 3 designs.

## Candidate detection — `GraphManager.detectGitRoot()` SAF gap (context for §B)

`GraphManager.kt:990-1001` (`detectGitRoot`) explicitly bails out for any `saf://`/`content://`
path with a logged INFO line ("skipping SAF/content path, git-repo auto-detection unsupported"),
by design — a SAF grant is scoped to exactly the picked folder, so there's no `.git` to walk
upward to find even in principle. This confirms requirements.md's Problem Statement claim
precisely and clarifies scope for Phase 3: the SAF-side "candidate detection" (§B) is necessarily
a *downward* scan from the picked folder (using the SAF `listDirectories`/`listFiles` primitives
above), never an upward git-root walk — there is no SAF equivalent to fix in `detectGitRoot`
itself, only a net-new downward probe to add at SAF folder-pick time.

## Summary of Phase 3 inputs

1. No new Gradle dependencies.
2. Directory scanning: reuse `FileSystem.listFiles`/`listDirectories`/`listFilesRecursiveWithModTimes` (commonMain) — already SAF-safe; bound depth using the same cap idiom as `GraphDiagnostics.kt`'s `MAX_NESTED_PROBES`; benchmark SAF listing cost before fixing a default depth (open question, not yet measured).
3. Single-file move: reuse `FileSystem.renameFile` (all platforms); directory-level move/merge is new orchestration code built on top, not an existing primitive.
4. Possible new interface method: `FileSystem.copyFile()`/SAF `copyDocument` wrapper — does not exist today; needed for Merge and for the SAF move-unreliable fallback.
5. Dialog: new three-way (or two-plus-dismiss) composable following `StorageMoveChoiceDialog.kt`'s `AlertDialog`/`OutlinedButton`/`describeForHumans()` shape; reuse `DiskConflictDialog`/`DiskConflictBlockMatcher` for per-file conflicts as named in requirements.md.
6. Trash and resumable-marker: new code, built on existing `FileSystem` I/O + `kotlinx-serialization-json` (already a dependency) — no new library.
7. Atomic commit: reuse `GitSyncService.commitLocalChanges` (JVM/Android) and `WasmGitWriteService.commit`/`commitGitHub`/`commitGitLab` (Wasm) as-is; sequencing against concurrent auto-sync is the design work, not the primitive itself.
8. SAF API levels: no gating required anywhere (minSdk 26 > API 24 requirement for every relevant `DocumentsContract` call); only per-provider `FLAG_SUPPORTS_MOVE` variability matters, already handled by an existing fallback pattern to replicate.
