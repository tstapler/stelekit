# ADR-001: Use Per-Subdirectory FileObserver Instances Instead of a Single Graph-Root Observer

## Status
Accepted

## Context

Android's `FileObserver` wraps a single `inotify_add_watch` call on one directory inode. inotify watches are not recursive: a watch placed on the graph root (`$graphPath/`) delivers `IN_CREATE` / `IN_MODIFY` / etc. only for files directly inside that directory, not for files inside its subdirectories (`pages/`, `journals/`). This is a fundamental OS-level limitation — there is no recursive inotify variant available in the Android NDK or through any public API.

SteleKit organises markdown files in two named subdirectories:
- `$graphPath/pages/` — per-page files
- `$graphPath/journals/` — per-date journal files

A root-level `FileObserver` placed on `$graphPath` therefore never delivers events for changes in either of these directories, making the fast-path file watcher functionally inert for all user content.

Android API 29 introduced a multi-path constructor `FileObserver(List<File>, mask)` that accepts multiple directories in a single object. Internally it still creates one inotify watch descriptor per path — the multi-path form is syntactic sugar, not a recursive watch.

## Decision

`SafChangeDetector.startFileObserver` creates two separate `FileObserver` instances:
- one watching `File(graphPath, "pages")`
- one watching `File(graphPath, "journals")`

Both use the same event mask: `CREATE | DELETE | MODIFY | MOVED_FROM | MOVED_TO`. Both observers call the same `onExternalChange()` callback on any matched event.

The `start()` method filters the list to directories that exist before calling `startWatching()`, so the watcher starts cleanly on graphs where only one subdirectory has been created yet, or on SAF-only paths where real filesystem paths are unavailable.

The `stop()` method iterates the list and calls `stopWatching()` on each instance.

## Alternatives Considered

**Single root-level FileObserver**: Watches `$graphPath` only. Does not deliver events for `pages/` or `journals/` subdirectory changes. This was the original (broken) implementation.

**Multi-path API 29+ constructor (`FileObserver(List<File>, mask)`)**: Functionally equivalent to two separate instances — same inotify watch count, same event delivery behaviour, same non-recursive semantics. Rejected in favour of two explicit instances for clarity (each instance's watched path is immediately visible at the call site) and for compatibility with API < 29 (the two-instance form does not require the newer constructor).

**Recursive inotify via native code**: No `inotify_add_watch` with `IN_RECURSIVE` flag exists. The Linux kernel has no native recursive inotify, and the Android NDK does not expose any equivalent. Not viable.

## Consequences

- `SafChangeDetector` holds a `List<FileObserver>` (two entries) instead of a single reference.
- `start()` must call `startWatching()` on each non-null instance after filtering for directory existence.
- `stop()` must iterate the list and call `stopWatching()` on each instance.
- Two inotify watch descriptors are consumed per open graph (vs. one previously). With Android's default limit of 8 192 watches shared across all processes, this remains safe for single-graph use.
- If `pages/` or `journals/` is deleted and recreated (e.g., by a sync client doing an atomic directory replace), the existing `FileObserver` becomes a dangling watch on the old inode. Recovery depends on the 30-second SAF polling fallback and the `ProcessLifecycleOwner.onStart` scan. Adding `DELETE_SELF` to the mask and rearming on receipt is a future improvement.
