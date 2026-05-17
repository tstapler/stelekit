# ADR-001: Use PlatformDispatcher.IO for SAF ContentResolver Calls

**Status**: Accepted  
**Date**: 2026-05-16

## Context

`GraphWriter.savePageInternal` performs three serial Storage Access Framework (SAF) Binder IPC calls — `fileExists`, `readFile` (safety check), and `writeFile` — when persisting a page on Android. These calls were dispatched on `Dispatchers.Default`, the CPU-bounded thread pool.

SAF operations cross process boundaries via Android Binder IPC, which involves blocking kernel syscalls. On `Dispatchers.Default`, blocking calls consume CPU worker threads, starving coroutines that perform actual computation and causing 1–2 second lag on block insert operations. The lag is proportional to how long the Binder call blocks a Default thread before it can be preempted.

The codebase already enforces a `PlatformDispatcher` abstraction (see `CLAUDE.md` dispatcher matrix) to ensure correct dispatch behavior across JVM, Android, iOS, and WASM/JS targets. Bypassing this abstraction with hardcoded `Dispatchers.IO` would be incorrect on iOS and WASM where those dispatchers map differently.

## Decision

All SAF `ContentResolver` calls in `GraphWriter` (and any future file-IO paths on Android) must be wrapped in `withContext(PlatformDispatcher.IO)`.

`PlatformDispatcher.IO` must **not** be replaced with:
- `Dispatchers.Default` — CPU pool; blocking Binder IPC starves CPU work
- Hardcoded `Dispatchers.IO` — bypasses platform abstraction; wrong on iOS/WASM

The platform mapping for `PlatformDispatcher.IO` is:

| Platform | Resolved dispatcher | Rationale |
|---|---|---|
| JVM (desktop) | `Dispatchers.IO` | Elastic IO thread pool; safe for blocking |
| Android | `Dispatchers.IO` | Same; Binder IPC is IO-bound |
| iOS | `Dispatchers.Default` | Native driver; no Binder; GCD handles threading |
| WASM/JS | `Dispatchers.Default` | Single-threaded runtime; no blocking IO |

## Consequences

**Positive**:
- Eliminates 1–2 second lag on Android block insert by moving Binder IPC off the CPU pool.
- Consistent with the existing `PlatformDispatcher` abstraction — no special-casing per platform at the call site.
- Correct behavior on all targets without conditional compilation.

**Negative/Risks**:
- Adds a `withContext` boundary around SAF calls, introducing a small coroutine suspension overhead (~microseconds) on every write.
- Developers unfamiliar with the abstraction might revert to `Dispatchers.IO` directly during future refactors.

**Mitigation**:
- The `CLAUDE.md` dispatcher matrix explicitly documents the rule; CI detekt rules can be added to flag direct `Dispatchers.IO` usage in commonMain/androidMain file IO paths.
- Code review checklist item: any new `ContentResolver` or file-IO call must use `PlatformDispatcher.IO`.
