# ADR-003: Bulk Delete via Confirmation Dialog + Disk File Removal

**Status**: Accepted
**Date**: 2026-04-16
**Feature**: All Pages View

## Context

Multi-select bulk delete must remove pages from the SQLite database AND delete the corresponding `.md` files from disk. The existing `pageRepository.deletePage(uuid)` only removes the DB row (blocks cascade-delete via FK). No disk deletion exists yet. An accidental bulk delete of 50 pages is catastrophic and irreversible.

## Decision

1. Wire bulk delete through `StelekitViewModel.bulkDeletePages(uuids: List<String>)`. The method:
   a. Collects `filePath` for each page before deletion.
   b. Calls `pageRepository.deletePage(uuid)` for each (cascade deletes blocks + properties).
   c. Calls `fileSystem.deleteFile(filePath)` for each non-null `filePath`.
   d. Calls `loadMoreRegularPages(reset = true)` to refresh `AppState.regularPages`.
2. Guard with a mandatory `AlertDialog` confirmation: "Delete N pages? This will permanently remove their files from disk. This cannot be undone."
3. No undo for bulk delete (disk deletion is irreversible; implementing a Trash bin is out of scope for this feature).

## Rationale

- **Safety**: two-step confirmation prevents accidental deletion.
- **Consistency**: files deleted from disk match DB state — no orphan `.md` files.
- **Simplicity**: no new DB schema needed; `PlatformFileSystem.deleteFile()` already exists.

## Consequences

- Positive: clean state after delete; no orphan files.
- Negative: no undo for bulk delete.
- Risk: `fileSystem.deleteFile` failing silently on a read-only mount. Mitigated by collecting errors and showing a notification: "Deleted N pages; M files could not be removed from disk."
