# ADR-002: `FileSystemObserver` as primary external-change detection, poll as permanent fallback

**Status**: Accepted
**Date**: 2026-07-17
**Project**: web-local-folder-livesync

## Context

`requirements.md`'s Rabbit Holes section, written before Phase 2 research, assumed "no native
filesystem-watch API in browsers" and expected detection to be polling-only. `research/stack.md`
§2 found this assumption outdated: `FileSystemObserver` shipped to **stable** Chrome/Edge 133
(Jan 29, 2025) — not an origin trial, not flagged — and works against both the picked local
directory handle and OPFS handles via `observer.observe(handle, { recursive: true })`, delivering
scoped `FileSystemChangeRecord`s (`appeared`/`disappeared`/`modified`/`moved`/`errored`) instead of
requiring a full-tree walk per check.

However, `research/pitfalls.md` §4 (written independently) is more cautious, citing MDN's "not
recommended for production" framing (accurate as of when that source's snapshot was written,
stale relative to stack.md's more recent verification) and treating the API as origin-trial-only.
The two research documents disagree on how load-bearing this API should be, and this ADR resolves
that disagreement explicitly rather than letting the implementation phase pick one framing
ad hoc — this is exactly the kind of "non-standard/young technology choice" this project's planning
phase is required to flag.

Compounding factors specific to this feature:
- The browser support ceiling for the *entire* File System Access API family (per
  `requirements.md`'s own constraint) is already Chromium 133+ for the features this project needs
  — `FileSystemObserver`'s Chrome-133 floor does not narrow that ceiling further.
- `FileSystemObserver`'s `"errored"` record type and Windows cross-directory-move quirk
  (reports as separate disappear/appear rather than "moved") are real, documented rough edges in a
  young API (`stack.md` §2, open question #2).
- The existing `FileRegistry`/`GraphFileWatcher` polling pipeline (`db/FileRegistry.kt`,
  `db/GraphFileWatcher.kt`) is battle-tested on JVM/Android and must keep working as the detection
  mechanism regardless of what happens with `FileSystemObserver` — desktop/Android have no
  equivalent of `FileSystemObserver` and never will.

## Decision

Use `FileSystemObserver` as a **fast-path accelerator**, not a replacement, for external-change
detection:

- The permanent, always-present detection mechanism is the async `HostDirectoryPoller`
  (`platform/PlatformFileSystem.kt`, new) feeding `hostModTimes`/`cache`, which the existing
  `FileRegistry.detectChanges()` → `GraphFileWatcher` → `GraphLoader.externalFileChanges` pipeline
  already consumes unmodified (Phase 5 of `implementation/plan.md`). This is what actually makes
  changes visible to the UI — it must work with `FileSystemObserver` fully absent or broken.
- Where `'FileSystemObserver' in self` feature-detects true, `HostChangeObserver`
  (`platform/PlatformFileSystem.kt`, new) additionally observes the retained
  `HostDirectoryHandle` and, on receiving change records, immediately triggers
  `pollHostDirectoryOnce()` for the affected paths rather than waiting for the next timer tick —
  the same "fast path feeds the same generic pipeline" shape `GraphFileWatcher` already uses for
  Android's `ContentObserver` (`FileSystem.startExternalChangeDetection`).
- An `"errored"` change record, or the API being entirely unavailable, degrades to exactly the
  timer-poll-only behavior — never a crash, never a silently-stopped detection loop.
- The `visibilitychange`-triggered immediate recheck (belt-and-suspenders, `stack.md` §4) runs
  regardless of `FileSystemObserver` availability, covering both "observer missed something" and
  "observer isn't available at all."

## Rationale

- **Resolves the two research documents' disagreement without re-litigating either.** Both are
  right about different things: `stack.md` is right that the API is shipped and stable enough to
  use; `pitfalls.md` is right that a young API's edge cases (errored records, platform-specific
  move semantics) shouldn't be trusted as the *only* mechanism. Treating it as an accelerator on
  top of a permanent poll-based baseline is consistent with both findings.
- **Zero risk to the correctness-critical path.** Because the poll-based pipeline is mandatory and
  sufficient on its own, a `FileSystemObserver` bug, browser regression, or removal cannot cause a
  missed external change — worst case is added latency (falls back to the next poll tick), not
  silent data loss.
- **Matches the codebase's existing fast-path/fallback pattern exactly.** `GraphFileWatcher`
  already has this two-tier shape for Android (`ContentObserver` fast path + 5s poll fallback,
  `db/GraphFileWatcher.kt:134-153`) — this ADR extends the same architecture to web rather than
  introducing a new one.

## Consequences

- `HostChangeObserver` is pure latency optimization — its test coverage
  (`PlatformFileSystemHostChangeObserverTest`) may assert "detected faster than one poll interval,"
  never "detected at all" (that guarantee belongs to the poll-based test).
- If Chrome ever deprecates or materially changes `FileSystemObserver`'s behavior, only
  `HostChangeObserver` needs to change — `HostDirectoryPoller` and the entire
  `FileRegistry`/`GraphFileWatcher` pipeline downstream of it are unaffected.
- The `"errored"` record recovery spike flagged as Unresolved Question #1 in `implementation/plan.md`
  is scoped to *how quickly* recovery happens (fall back to next poll tick vs. force an immediate
  extra poll), not *whether* recovery happens — the poll baseline means "do nothing beyond falling
  back to the timer" is already a safe, if suboptimal, default.

## Alternatives Considered

| Option | Rejected because |
|---|---|
| `FileSystemObserver` as the sole/primary detection mechanism, poll as best-effort backup only | Makes the correctness-critical path depend on a ~1.5-year-old API's edge-case behavior (errored records, Windows move quirk) — too risky for a feature whose entire point is trustworthy sync |
| Poll-only, ignore `FileSystemObserver` entirely | Leaves a real, already-shipped latency improvement on the table for no safety benefit — the accelerator can be added with zero risk to the mandatory path, so declining it costs UX (slower detection) for no upside |
