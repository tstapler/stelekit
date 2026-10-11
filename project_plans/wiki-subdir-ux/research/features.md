# Research: Wiki Subdir UX Feature Landscape

## Scope & Focus

Analysis of how wiki-subfolder mismatches occur (git-clone and SAF paths), how
the codebase already detects them (GraphDiagnostics' nested-candidate scan) but
never surfaces them, what reusable UI patterns exist, and what edge cases and
unstated needs the design must handle.

## 1. How the Problem Manifests

### 1.1 Git-clone mismatch (the original bug)

`GraphManager.detectGitRoot()` (`GraphManager.kt:990-1026`) walks **upward**
from the graph path to find a `.git` directory. On a fresh clone the graph
path *is* the clone root, so:

```
<clone-root>/            ← graph path == detectedRepoRoot
  .git/
  logseq/
    pages/...
    journals/...
```

`detectGitRoot` returns `repoRoot = <clone-root>`, `wikiSubdir = ""` (empty
string — there's no subfolder to infer by going up). The "Notes subfolder
(optional)" field in Git Setup Step 2 (`GitSetupStep2RepoPath.kt:204-220`)
is **blank by construction** after a clone, not merely overlooked.

**Consequence**: `GraphLoader` looks in `<clone-root>/pages` and `<clone-root>/journals`
— finds nothing — graph appears empty. Content lives in `logseq/pages/`.

### 1.2 SAF mismatch (the same problem without git)

`GraphManager.kt:998-1001` **explicitly bails out** for `saf://`/`content://`
paths:

```kotlin
if (graphPath.startsWith("saf:///") || graphPath.startsWith("content://")) {
    logger.info("detectGitRoot: skipping SAF/content path, git-repo auto-detection unsupported ($graphPath)")
    return null
}
```

The same "configured root ≠ where content actually lives" problem applies to
SAF-mounted graphs, but there's **no detection at all** — the SAF path is
just skipped entirely.

### 1.3 Config-saved-but-wrong (the silent-suppression gap)

`GitDetectionBanner` (`GraphContentMainArea.kt:105-107`) only fires when:

```kotlin
activeGraphInfo?.detectedRepoRoot != null &&
    inputs.appState.gitConfig == null &&
    activeGraphInfo.gitDetectionDismissed == false
```

Once **any** git config is saved (even with a wrong/blank `wikiSubdir`), the
banner goes silent forever. Re-running warm reconcile with the **correct**
subfolder doesn't trigger the banner because `gitConfig != null`. No other
live surface exists for "content exists but at a different path than you're
configured for."

## 2. What the Codebase Already Computes (But Doesn't Surface)

### 2.1 The nested-candidate scan exists — it's just diagnostic-only

`GraphDiagnosticsCollector.appendDisk()` (`GraphDiagnostics.kt:81-114`),
lines 96-102:

```kotlin
for (dir in rootDirs.map { it.substringAfterLast('/') }.take(MAX_NESTED_PROBES)) {
    val hasPages = fileSystem.directoryExists("$root/$dir/pages")
    val hasJournals = fileSystem.directoryExists("$root/$dir/journals")
    if (hasPages || hasJournals) {
        appendLine("NESTED GRAPH CANDIDATE: $root/$dir (pages=$hasPages journals=$hasJournals)")
    }
}
```

**The detection logic already exists and is correct** — it scans up to
`MAX_NESTED_PROBES = 40` sibling directories at the configured root, checks
each for `pages/` + `journals/` subdirectories, and reports matches. This is
exactly the "candidate" detection brainstorm idea B.12 describes.

**The gap**: this only runs inside `GraphDiagnosticsCollector.collect()`,
which is invoked on-demand from the Logs screen export. It never runs during
warm reconcile (`GraphLoader`), never writes a banner condition, and never
informs `GitConfigRepository` or `GitSyncCoordinator`.

### 2.2 False-positive candidate filtering is partially done

The diagnostics scan in `appendDisk()` includes `.stelekit` as a candidate
(the requirements doc notes the actual diagnostics export flagged
`.stelekit` as `pages=true journals=false` alongside the real `logseq/`
candidate). The scan does **not** filter app-internal folders — that filtering
(brainstorm B.13) is a feature gap, not an existing safeguard.

### 2.3 GitSyncService already has resync machinery

`GitSyncService` (`GitSyncService.kt:46-69`) owns `GitSyncBusyCounter`
(`kt:65-68`) — an injectable shared instance explicitly documented as
"callers that need to observe busy-ness externally (e.g. a quiesce strategy)
should inject a shared instance instead." This is **exactly** the
synchronization primitive needed for the git-commit-atomicity rabbit hole
(brainstorm D.22 / requirements §Rabbit Holes): a move/merge operation can
await `gitSyncBusyCounter` to reach zero before staging its own commit,
preventing races with an in-flight auto-sync tick.

## 3. Reusable UI Patterns

### 3.1 StorageMoveChoiceDialog.kt:45-111 (the two-option pattern)

- **Structure**: Two `OutlinedButton` actions (deliberately even-handed — no
  primary button emphasis), with a safety copy: "The old copy stays until
  you confirm it's safe to remove."
- **Reuse for C**: Extend from two options (Relocate/Link) to three
  (Move/Merge/Leave-as-is). The dialog shape, the even-handed button styling,
  and the safety copy all transfer.
- **Constraint**: This dialog is currently **Android-only**
  (`ui/components/android/storage/`). The wiki-subdir move/merge flow must
  cover JVM/Desktop, Android (git + SAF), and Web/Wasm per requirements §Scope.
  Either port to commonMain or create a parallel common dialog.

### 3.2 DiskConflictDialog.kt (per-file conflict resolution)

- Already supports a **three-way + manual + view-full** choice for individual
  file conflicts.
- `DiskConflictBlockMatcher` provides the content-diff matching logic.
- **Reuse for C.3**: When the same filename exists at old and new locations
  with different content, hand off to this existing machinery instead of
  building a new merge resolver (brainstorm C.3 explicitly names this).

### 3.3 GitDetectionBanner.kt + BrowserOnlySyncBanner.kt

- Stacked banner pattern in `GraphContentMainArea.kt:130-174`.
- **Reuse for A.1**: Add a second banner condition alongside the existing
  `showGitBanner` — one that fires when gitConfig exists but warm reconcile
  finds a mismatch between configured root and on-disk content.

## 4. Edge Cases & Failure Modes

### 4.1 Race conditions

**Git sync during relocate (brainstorm D.22 rabbit hole).** A user changes
`wikiSubdir` and triggers a move. Simultaneously, `GitSyncService.autoSync`
ticks and commits the *old* layout. The move's subsequent commit then races
against or conflicts with the auto-sync commit.

**Mitigation**: Await `GitSyncBusyCounter` quiescence before staging the
reconciliation commit. The counter's design comment (`GitSyncService.kt:65-68`)
explicitly anticipates this caller.

**DB write during directory move.** Moving files that are still being read by
a `DatabaseWriteActor` or `GraphLoader` coroutine — files vanish mid-read.

**Mitigation**: This codebase already uses `EditLock` (`GraphManager.kt`,
passed to `GitSyncService` at `kt:50`) in the git-sync path. The move flow
must acquire the same lock, or the existing actor's `CompletableDeferred`
pattern (`DatabaseWriteActor.kt`) must be used to await in-flight writes.

### 4.2 SAF move/copy semantics (brainstorm D.22 / requirements §Rabbit Holes)

`DocumentsContract` has **no atomic move** on all API levels or all OEM
document providers. A SAF "move" may require: copy entire tree → verify
copy → delete source. On providers that don't support `DocumentsContract.copyDocument`
reliably, this degrades to manual per-file copy.

**Implication for soft-delete (C.26)**: On SAF, you can't easily move files
to `.stelekit/trash/` — you'd need to copy *again*. Soft-delete on SAF may
need to be "mark for deletion in metadata + require explicit user confirmation
before delete" rather than physical staging.

### 4.3 Partial migration / crash safety (C.23, D.23)

If the app is killed mid-move (Android process death is common):
- Some files relocated, some not.
- No record of which files need moving.
- User reopens → app must detect "half-migrated state" and offer to resume.

**Mitigation**: Write a marker file `.stelekit/reconfigure-marker.json` with
`{oldRoot, newRoot, strategy, completedFiles: [...]}`. On warm reconcile, if
the marker exists, surface a "resume incomplete move" prompt. This is
brainstorm C.23's "resumable/idempotent move."

### 4.4 Partial-migration edge: what's "the move"?

The three-way choice (Move/Merge/Leave) must define its scope precisely:

| Choice | What happens to files at OLD root? | What happens to files at NEW root? |
|--------|-------------------------------------|--------------------------------------|
| Move | Moved to NEW root | — |
| Merge | Moved to NEW root (conflicts resolved) | Existing files kept, conflicts merged per-file |
| Leave | Stays at OLD root | — (graph stays empty) |

"Leave as-is" is the dangerous one: the graph stays misconfigured. Brainstorm
D.27 requires it be **explicitly logged** as "left in place," not a silent
no-op.

### 4.5 Empty vs. nonexistent vs. conflicting target (D.25)

Three-way path validation on save:

1. **Path doesn't exist** — likely a typo. Different icon (warning triangle).
   "This path doesn't exist. Creating it will start with empty pages/journals."
2. **Path exists and is empty** — probably fine. Different icon (info).
   "Path exists but has no pages/ or journals/ folders yet."
3. **Path exists with content that conflicts** — needs the move/merge flow.
   Different icon (alert). "Found 12 pages and 13 journals here. Change will
   require moving or merging these."

## 5. What's Not Yet Implemented (Feature Gaps)

### 5.1 No "effective path" abstraction

`GitConfig` (`GitConfig.kt:26`) has `wikiRoot` but `GraphInfo` (`GraphInfo.kt`)
has no `effectivePath` / `effectiveRepoRoot` field. The detection result
(`detectedRepoRoot`, `detectedWikiSubdir` on `GraphInfo.kt`) is **computed
separately** from the operational config in `GitConfigRepository`. There's no
single source of truth that says "this graph reads from `<repoRoot>/<wikiSubdir>`
and detects it *might* be wrong because content is at `<repoRoot>/logseq/`."

### 5.2 No mismatch state model

`GraphInfo` has `detectedRepoRoot: String?` and `detectedWikiSubdir: String?`
but no `contentMismatchDetected: Boolean` or `candidateSubdir: String?`
field. The diagnostics scan computes candidates but throws the result away
after building a text report.

### 5.3 No candidate cache / no warm-reconcile re-scan

The nested-candidate scan lives **only** in `GraphDiagnosticsCollector`.
There's no equivalent scan in `GraphLoader`'s warm reconcile path
(`GraphManager.kt` warm-reconcile section, `GraphLoader.kt`). The diagnostics
scan doesn't run at all except on manual export.

### 5.4 No soft-delete infrastructure

There's no `.stelekit/trash/` or equivalent anywhere in the codebase. A
`grep -rn "stelekit/trash\|stelekitTrash\|softDelete\|SoftDelete"` returns
zero hits. This would be entirely new infrastructure (brainstorm C.26/C.27).

### 5.5 No reconfiguration history

`GraphInfo` has no history of past `repoRoot`/`wikiSubdir` values. The
diagnostics export has no "reconfiguration history" section (E.29).
Auditability is nonexistent.

### 5.6 No move/merge marker file

Same as 5.4 — no `.stelekit/reconfigure-marker.json` or any similar
crash-safety mechanism exists anywhere in the repo.

## 6. Industry Comparison

### 6.1 Git-backed note apps

- **Obsidian**: Git-synced vaults + nested content. Does **not** auto-scan
  for nested folders — if you clone into the wrong place, the vault appears
  empty until you re-open the vault at the correct subfolder. No proactive
  banner; relies on user noticing empty vault.
- **Logseq**: The exact source of the `logseq/` convention. Logseq **does**
  auto-detect `logseq/` — the `logseq/pages` and `logseq/journals` convention
  is its default. SteleKit's `detectGitRoot()` upward-only scan misses this.
- **Zettlr/Zotero**: Both require manual folder selection; no auto-detection.

### 6.2 File-relocation UX patterns

- **Dropbox/Drive desktop**: When moving a folder that's already synced, shows
  a conflict resolution dialog: "This file already exists at the destination.
  Keep both / Replace / Skip." Uses even-handed buttons (not "Delete" styled
  as primary). This is the exact pattern `StorageMoveChoiceDialog` mirrors.
- **rsync**: Three flags — `--remove-source-files` (delete after), `--backup`
  (rename old), default (overwrite). The three-way choice (Move/Merge/Leave)
  maps to these but with safety defaults.
- **Git**: A subfolder restructure is just `git mv` — atomic in a single commit.
  SteleKit can't use raw `git mv` because the move may cross the git working
  tree boundary *and* the move must be sequenced against auto-sync (§4.1).

## 7. Unstated User Needs (Inferred)

1. **"Don't make me think in path syntax."** The wikiSubdir field is a raw
   text input. Users will type `./logseq` or `/logseq/` or `logseq\` and
   wonder why it doesn't work. They want path normalization (strip leading
  `./`, normalize `\`, suggest `logseq` with a click).

2. **"Trust but verify."** After fixing the config, users don't trust that
   it actually worked. They want the "verify-after-fix re-scan" (C.20) to
   show "12 pages, 13 journals now found" immediately.

3. **"I don't want to lose files I didn't know existed."** The
   pre-existing journal entries that land at the wrong root (mentioned in
   requirements §Problem Statement: "11 DB journal entries with no
   corresponding file") — users don't know these exist until they're orphaned
   by the fix. They need the dry-run manifest (C.17) *and* a "these DB-only
   journals will become visible" note.

4. **"Let me undo if I picked wrong."** Even with soft-delete, users on
   non-SAF platforms want an explicit "undo" action for the last
   reconfiguration, not just "it's in trash, go dig it out." The audit trail
   (E.29) enables this.

5. **"Don't surprise me with 40 filesystem round-trips."** On a local FS
   this is cheap, but on SAF each `listDirectories` is a content-provider IPC
   (§Rabbit Holes). Users on Android with large repos will notice if the scan
   is unbatched or unbounded.

## 8. Platform-Specific Constraints

### 8.1 JVM/Desktop (Linux + macOS)

- `PlatformFileSystem` overrides `listFiles`/`listDirectories` with native
  calls. The candidate scan is fast (local syscalls).
- `detectGitRoot()` works — walks upward to find `.git`.
- Soft-delete via `.stelekit/trash/` is a normal directory move.
- File watching exists (used by web-sync) — candidate re-scan could hook into
  the existing `externalFileChanges` flow (`GraphLoader.externalFileChanges`).

### 8.2 Android (git + SAF)

- Two distinct paths: git-backed (local filesystem, `detectGitRoot` works)
  and SAF (document-tree, `detectGitRoot` bails out, §1.2).
- SAF move/copy is the #1 rabbit hole (§4.2).
- Process death is common — crash-safety (§4.3) is critical on Android
  specifically, not just "nice to have."
- `WikiSubdirUriGuardTest` (`WikiSubdirUriGuardTest.kt`) already exists —
  regression tests for move/merge must follow this pattern (D.26).

### 8.3 Web/Wasm

- `WasmGitWriteService.kt` exists — git-write-on-wasm is supported.
- No raw filesystem access — only the virtual FS provided by the browser.
  Candidate scan via `listDirectories` works but is in-memory.
- SAF doesn't exist — the SAF-specific paths are Android-only.

### 8.4 iOS (out of scope, per requirements §Scope)

- No `GitRepository` implementation on iOS. Nothing to extend.

## 9. Key Integration Points

### 9.1 GraphManager.addGraph → detectGitRoot (background)

When a graph is added (line 408-414), git detection runs fire-and-forget on
`PlatformDispatcher.IO`:

```kotlin
coroutineScope.launch(PlatformDispatcher.IO) {
    val detected = detectGitRoot(expandedPath)
    if (detected != null) {
        updateGraphInfoDetection(graphId, detected.first, detected.second)
    }
}
```

**For subdir detection**: After clone, `cloneAndAdd` (line 446+) should run
the downward candidate scan and either pre-fill `wikiSubdir` or surface a
banner.

### 9.2 GitSyncCoordinator → uiState

`GitSyncCoordinator` (`GitSyncCoordinator.kt:86-93`) builds `syncState` and
`gitLastSyncAt` StateFlows. It dispatches dialog visibility via:

```kotlin
uiState.update { it.copy(conflictResolutionVisible = true) }
uiState.update { it.copy(journalMergeReviewVisible = true) }
```

**For mismatch banner**: A new `AppState` field like
`contentMismatchVisible: Boolean` or a per-graph `GraphInfo`-level flag
would drive the banner in `GraphContentMainArea.computeBannerVisibility()`.

### 9.3 DatabaseWriteActor (write serialization)

All DB writes (including `updateGraphInfoDetection` at line 1043) serialize
through `DatabaseWriteActor` (priority queue: high for user writes, low for
bulk). A "resume incomplete move" marker write must go through this actor
to avoid races with in-flight page/block saves.

### 9.4 GitSyncService.autoSync (sync cycle)

`GitSyncService` runs periodic auto-sync. `GitSyncBusyCounter` (injected,
`GitSyncService.kt:65-68`) tracks in-flight sync. The reconciliation move
must await this counter at zero before committing its own staging change.

## 10. Research Summary: 3 Bullets

1. **The detection logic already exists** (`GraphDiagnosticsCollector.appendDisk()`,
   `GraphDiagnostics.kt:96-102`) but is diagnostic-only — it runs on manual
   diagnostics export and never surfaces results to the UI. The fix is
   structural reuse: extract the nested-candidate scan into a shared utility
   and run it during warm reconcile (`GraphLoader`), then drive a new banner
   condition in `GraphContentMainArea.computeBannerVisibility()` that fires
   when gitConfig exists but content is mismatched — closing the silent-suppression
   gap where today's banner goes dark once any config is saved.

2. **Three reusable UI patterns already exist in-tree**:
   `StorageMoveChoiceDialog` (two-option, even-handed buttons, safety copy),
   `DiskConflictDialog` + `DiskConflictBlockMatcher` (per-file three-way
   conflict resolution with diff preview), and the stacked-banner pattern in
   `GraphContentBanners` (extendable to a mismatch banner). The move/merge
   three-way choice extends `StorageMoveChoiceDialog` to three options, and
   same-filename conflicts should wire through `DiskConflictDialog` rather than
   building new merge logic — per requirements §Constraints, these existing
   flows must not regress.

3. **The critical unsolved safety infrastructure** is crash-resumable moves:
   there is no marker-file, no soft-delete trash area, and no reconfiguration
   audit trail anywhere in the codebase (all-new per brainstorm C.23/C.26/D.29).
   However, `GitSyncService` already exposes `GitSyncBusyCounter` (documented
   as intended for "a quiesce strategy") — that's the synchronization primitive
   for sequencing reconciliation commits against auto-sync ticks. On SAF,
   true soft-delete is infeasible (copy-to-trash costs another full copy), so
   the SAF path needs a metadata-marked-for-deletion approach instead.
