# ADR-005: Phase 3 Edit Session Guard for Race Condition Fix

**Status**: Accepted  
**Date**: 2026-04-30  
**Context**: Android SAF Performance — Story 1, race condition between Phase 3 re-indexing and active edit sessions

---

## Context

Live session logs show: `applyContentChange: block not found — content update dropped`.

When `indexRemainingPages` processes a page that the user is currently editing, it calls `parseAndSavePage`, which deletes all existing blocks for that page and inserts new ones with new UUIDs. `BlockStateManager` holds references to the old UUIDs. Subsequent calls from `BlockStateManager` (e.g. `applyContentChange`, `moveBlock`) fail with "block not found" because those UUIDs no longer exist in the database.

This is a classic time-of-check/time-of-use (TOCTOU) race: Phase 3 checks whether a page needs indexing, then indexes it, while `BlockStateManager` is concurrently modifying the same page's blocks.

---

## Decision

Expose a `activePageUuids: StateFlow<Set<String>>` (or a snapshot-safe equivalent) from `BlockStateManager` that reflects the set of pages with an active editing session. `GraphLoader.indexRemainingPages` reads this set before calling `parseAndSavePage` for each page and skips any page whose UUID is in the set.

Skipped pages are **not lost** — they will be re-indexed after the user saves. When `GraphWriter` completes a save, it triggers a re-index of that page via the existing `externalFileChanges` watch path (or a dedicated post-save re-index trigger).

---

## Rationale

### Why skip-in-indexer, not lock-in-BlockStateManager

The alternative is to hold a write lock in `BlockStateManager` that Phase 3 must acquire before re-indexing a page. This is rejected because:

1. `BlockStateManager` is a Compose-layer class. Adding lock contention to it risks introducing frame jank on the UI thread (Android Choreographer runs at 16ms intervals; any lock wait > 4ms misses a frame).
2. The lock would need to be a coroutine-compatible `Mutex`, which means Phase 3 suspends on it. With 1000+ pages, if even one page has an active edit session open for 30 seconds, Phase 3's entire run is delayed at that point.
3. The skip-in-indexer approach is asymmetric: Phase 3 is the one with low priority and tolerates skipping; `BlockStateManager` is the one with high priority and must never be blocked.

### Why StateFlow<Set<String>> and not a Boolean flag

`BlockStateManager` manages one page at a time (the currently open page) but multiple `BlockStateManager` instances can exist in a Compose session (e.g. nested pages or multi-pane layouts). A `Set<String>` of page UUIDs correctly handles this. The `StateFlow` wrapper ensures `indexRemainingPages` reads a consistent snapshot via `value` (no suspension needed).

### Post-save re-index trigger

When Phase 3 skips a page because it is being edited, that page must eventually be fully indexed (for backlink resolution, search accuracy, etc.). The trigger for re-indexing is the debounced save in `GraphWriter`. After `savePageInternal` completes, `GraphLoader.markFileWrittenByUs` is already called, which prevents the watcher from treating the save as an external change. A dedicated post-save call to `parseAndSavePage` (with `priority = LOW`) can be added at the same callsite, or the existing `externalFileChanges` path can be reused by not suppressing the post-save notification for FULL re-indexing.

### Alternatives Rejected

**Block UUID stability**: Make Phase 3 preserve existing block UUIDs during re-index by matching on content hash. This is more complex and partially implemented via `sidecarMap`. It does not prevent the race — it reduces its visible impact. The explicit session guard is a cleaner fix.

**Optimistic locking on blocks**: Add a version field check to `applyContentChange`. If the expected version does not match, re-fetch the block and retry. This is correct but adds a retry loop to every user keystroke. The session guard is O(1) and zero-cost during normal editing.

---

## Consequences

- `BlockStateManager` must expose `activePageUuids: StateFlow<Set<String>>`. The set is populated when a page is opened for editing and cleared when the editor is closed or the page saves.
- `GraphLoader.indexRemainingPages` must accept (or read via injection) the active page UUID set. This requires passing a reference from `StelekitViewModel` or `RepositorySet` to `GraphLoader`. The cleanest injection point is `GraphLoader`'s constructor (nullable `activePageUuids: StateFlow<Set<String>>? = null` parameter).
- Pages skipped by the guard will be re-indexed on next app start (warm reconcile path) if no post-save trigger is added. A post-save trigger is recommended for correctness but not required for the initial fix.
- The `applyContentChange: block not found` error will be eliminated for the common case (Phase 3 re-indexing during active edit). The error may still occur during rapid external file changes; that is a separate issue.
