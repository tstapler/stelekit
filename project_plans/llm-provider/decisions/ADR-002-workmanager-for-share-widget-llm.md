# ADR-002: WorkManager for Share Widget LLM Calls

**Date**: 2026-06-13
**Status**: Accepted

## Context

The Android share widget captures a note via `Intent.ACTION_SEND` then optionally calls the
LLM tag provider. Network I/O cannot be performed directly in an Activity's `onCreate` or in
a Compose `LaunchedEffect` when the host Activity may be immediately finished after inserting
the note — Android's background restriction kills the process before the coroutine completes.

## Decision

The LLM portion of the share-widget tag suggestion is dispatched as a `OneTimeWorkRequest` via
`WorkManager` (already a project dependency via `WorkManagerSyncScheduler`). The `CoroutineWorker`
calls `LlmTagProvider.suggestTags()`, appends accepted tags to the saved block via
`blockStateManager.insertTextAtCursor`, and posts a notification on completion.

The local PageNameIndex scan (offline, instantaneous) is performed synchronously inside the
Activity before finishing — it does not need WorkManager since it involves no network I/O.

## Consequences

Positive:
- Survives process death; Android guarantees at-least-once execution.
- Consistent with `GitSyncWorker` pattern already in the codebase.
- No foreground service or notification channel needed for the network call itself.

Negative / Trade-offs:
- WorkManager minimum constraint: work runs when network is available, which may be immediate
  or deferred minutes later. Tags therefore appear asynchronously (notification on completion)
  rather than inline in the share flow.
- The note is saved before tags are appended — the block exists in the graph without tags
  briefly, which is acceptable (matches the "offline-first" requirement).
- MVP scope: the share widget's LLM-only path (no PageNameIndex since the graph may not be
  loaded) means the worker must construct a minimal `LlmTagProvider` directly rather than
  going through the in-process `TagSuggestionEngine`.

## Alternatives Rejected

- **Foreground Service**: heavier, requires a visible notification for the duration of the call.
  WorkManager handles retries and lifecycle automatically.
- **Fire-and-forget coroutine in Activity**: killed by Android on Activity finish before
  network response arrives.
