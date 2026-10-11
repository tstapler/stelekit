# ADR-005: In-process interpreter is primary; `sh` is optional, gated, desktop-only

**Status**: Accepted | **Date**: 2026-10-10 | **Amended**: 2026-10-10 (Phase 3 repair)

## Context
Android `Runtime.exec` runs as the app uid with toybox PATH, cannot see SAF trees, cannot exec from writable dirs (W^X), has no `git`. Termux/BusyBox bundling and Kotlin scripting are unavailable or JVM-only.

## Decision
- All first-class families (`sql git fs settings logs graph diag`) are Kotlin commands against repo abstractions (SAF-aware `FileSystem`, `GitRepository`). Tokenizer only: quotes and backslash, no globbing or pipes in v1.
- `sh` is a `ProcessRunner` command (argv list, never string concatenation into `sh -c` except the explicit user `sh` text on Desktop), registered last, `Unsupported` on wasm/iOS. **Amendment: `sh` is desktop-only in v1** (Desktop: `$SHELL -c`). Android, iOS and wasm return `Unsupported` ("shell bypasses the file deny-list; desktop only"). Reason: `sh` runs as the app user and can read the app-private directory (`shared_prefs`, vault files), so it bypasses the `fs` deny-list and defeats pattern redaction; enabling it on Android would void the credential posture. It sits behind its own second confirm that states the bypass. Minimal environment (no tokens), cwd = graph root or app files dir, default timeout, stdout/stderr merged, `ProcessHandle.descendants()` destroyed on cancel. Second confirm once per session, "shell armed" chip with Disarm.

## Alternatives rejected
Bundled busybox; Rhino/QuickJS scripting (bridge cost, wasm unverified; viable later); sh-only console.

## Consequences
Answers requirements Open Question 6 (both, interpreter first; process exec desktop only). No new dependencies. Redaction of `sh` output stays best-effort (ADR-002 / Story 4.3).
