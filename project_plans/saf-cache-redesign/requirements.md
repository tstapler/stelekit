# Requirements: SAF Cache Redesign

## Problem Statement

SteleKit's `loadFullPage` uses a filesystem mtime-based guard to avoid re-reading pages that
are already in memory. This guard is unreliable in two scenarios that matter in practice:

1. **FAT/exFAT filesystems** — timestamps have 2-second granularity. A write and a subsequent
   read within the same 2-second window produce identical mtimes, so the guard incorrectly
   treats the page as up-to-date.
2. **Android SAF (Storage Access Framework)** — some SAF providers return 0, a stale value, or
   a provider-internal timestamp for `getLastModifiedTime`. The guard cannot distinguish "file
   not modified" from "mtime unavailable".

The consequence: after a page is edited externally (Logseq desktop, another device, a sync
service like Syncthing or iCloud) and the user navigates to that page in SteleKit, they see
the old content.

## Architecture Context

- **`DatabaseWriteActor`** serialises all DB writes. All block/page mutations go through it.
- **`GraphLoader`** reads markdown files from disk, parses them, and writes the result to the
  DB via the actor. The mtime guard lives here in `loadFullPage`.
- **`GraphWriter`** converts in-memory blocks back to markdown and writes to disk. It is the
  only path for SteleKit-originated writes.
- **File watcher** (`externalFileChanges` SharedFlow) detects filesystem changes and emits
  them. Currently used to show a conflict dialog; NOT currently used to auto-reload pages.
- **`BlockStateManager`** holds the editing state. A page should not be force-reloaded while
  the user is actively editing it.

The intended architecture: in-memory DB is the fast primary store; markdown files on disk are
the canonical/persistent store for interoperability. Reads from disk are expensive (SAF I/O is
slow on Android); the mtime guard was added to avoid redundant re-reads.

## Goals

1. **Eliminate stale reads caused by external edits** — after any external process modifies a
   markdown file, the next navigation to that page must reflect the current file content.
2. **No false positives on FAT/exFAT/SAF** — the solution must not depend on mtime accuracy.
3. **Do not degrade write performance** — `GraphWriter` writes must remain non-blocking for the UI.
4. **Do not reload pages that are actively being edited** — a reload during an edit session
   would destroy unsaved work.
5. **Keep navigation fast** — the time between tapping a link and seeing content should not
   increase materially (< 100 ms added latency on typical hardware).
6. **Survive no file-watcher** (iOS/WASM) — the file watcher is only available on JVM/Android.
   The solution must degrade gracefully when no watcher is present.

## Non-Goals

- Real-time collaborative editing (conflicts are already handled by the `DiskConflictDialog`).
- Eliminating all disk I/O on navigation (some re-read on first navigation is acceptable).
- Supporting write-back to disk from multiple processes simultaneously.

## Key Constraints

- **Platform**: JVM (Desktop), Android (SAF), iOS, WASM/JS.
- **Filesystem**: APFS, ext4, NTFS, FAT32/exFAT (via SAF on Android), cloud SAF providers.
- **Kotlin Multiplatform** — solution must live in `commonMain` with platform-specific overrides
  only where strictly necessary.
- **No new external dependencies** unless strongly justified.

## Open Design Questions (to be resolved by research)

1. What is the state of the art for cache invalidation without mtime? (content hashing,
   write-epoch, event-driven dirty sets, CRDT-style vector clocks)
2. How do well-known KMP/Android note-taking apps (Obsidian, Notesnook, Standard Notes) handle
   this problem?
3. What is the overhead of content hashing (SHA-256 or xxHash) on a typical Logseq page
   (1–200 blocks, 1–50 KB)?
4. Does Android SAF provide any reliable "has this file changed?" signal other than mtime?
   (DocumentsContract.Document.COLUMN_LAST_MODIFIED vs COLUMN_FLAGS)
5. What is the correct interaction between a dirty-write flag and the existing conflict
   detection (`DiskConflictDialog`)?

## Success Criteria

- A page edited externally is shown with current content on next navigation (no manual
  refresh required).
- On FAT/exFAT, the guard does not produce false "already up to date" results after an
  external edit within a 2-second window.
- Existing tests pass; new tests cover the stale-read and false-positive scenarios.
- No regression in navigation latency on the desktop demo benchmark.
