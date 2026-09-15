# Verification Report — desktop-quick-capture

**Date**: 2026-09-11/12

## Technology Surface

| Technology | Files | Review approach |
|---|---|---|
| Kotlin Multiplatform (Compose Desktop) | all of `kmp/src/{commonMain,jvmMain,jvmTest}/kotlin/dev/stapler/stelekit/{capture,desktop,ui}/` | `code-kmp` skill + repo `CLAUDE.md` conventions |

## Layer 1 — Idioms (code-kmp skill)

| Finding | Severity | Action taken |
|---|---|---|
| `FirstRunHotkeyNotice` never requested focus, so its documented Escape-dismiss AC silently never fired in practice | MUST FIX | Fixed — added `FocusRequester` + `LaunchedEffect` mirroring `CapturePopupWindow`'s pattern (commit `f6db3649ef`) |
| `CaptureController.stop()` didn't cancel its own scope, unlike its sibling surfaces' `stop()` | SUGGEST | Fixed inline (commit `f6db3649ef`) |
| `StelekitAppCaptureDeps.hotkeyComboLabel`'s default value duplicates `GlobalHotkeyListener.DEFAULT_COMBO_LABEL` (can't share directly: commonMain can't see jvmMain's constant) | SUGGEST | Left as-is — already explicitly documented as a deliberate tradeoff in the class's own doc comment; every real caller (`Main.kt`) passes the constant explicitly, so the default is dead code, not a live drift risk |
| Fully-qualified `HotkeyRegistrationFailure` reference instead of an import in `App.kt` | NITPICK | Fixed (commit `f6db3649ef`) |

Verified clean: write-path source-audit enforcement, coroutine scope ownership on all three background surfaces, dispatcher usage, no `@DirectSqlWrite`.

## Layer 2 — Architecture

**kibitzer mechanical pre-pass**: 1 advisory (`CapturePopupWindow.kt` — 41-line function, 1 over the 40-line threshold; not fixed, purely cosmetic).

| Finding | Severity | Action taken |
|---|---|---|
| `CapturePopupState.Shown`'s independently-settable `saveState`/`captureResult` fields let a stale `captureResult` from a prior failure survive into a Retry's `Saving` state, rendering the wrong placeholder (e.g. "Vault is locked") instead of the saving spinner — flagged in the original 2026-09-05 architecture-review and never resolved until now | CONCERN (real, traced bug) | Fixed — `performSave()` now clears `captureResult` when transitioning to `Saving`; added regression test `save_should_ClearStaleCaptureResult_When_RetryingAfterAPriorFailure` (commit `f6db3649ef`) |
| Socket path (`~/.stelekit/stelekit.sock`) copy-pasted as a literal default in 3 files — a future edit to one without the others would silently downgrade every capture to the slow poll path, no error | Refactor finding (highest risk) | Fixed — added `CaptureSocketPath` single source of truth (commit `f6db3649ef`) |
| Replay logic (decode → write → branch on `Saved`) duplicated between `CaptureSocketListener` and `PendingCapturePoller` | Refactor finding | Noted as follow-up — not fixed (moderate effort, no correctness impact today) |
| `graphManager` + `CoroutineScope` construction boilerplate tripled across 3 classes | Refactor finding | Noted as follow-up — not fixed |
| `CaptureSocketClient.readResponse` decodes bytes one-at-a-time (`.toInt().toChar()`), which would mis-decode non-ASCII; harmless today since only `"OK\n"`/`"ERR\n"` are ever sent | Refactor finding | Noted as follow-up — not fixed |
| `CaptureController.hide()` vs `dismiss()` naming doesn't convey the precondition | Refactor finding (nitpick) | Noted as follow-up — not fixed |

No BLOCKERs. All of the *prior* architecture-review's blockers (illegal-state AC unsatisfiability, Poller/SocketListener's CaptureController dependency, silent window-close kill) were confirmed resolved by direct code inspection, not just by prose claim.

## Layer 3 — Correctness & Tests

All 7 backlog acceptance criteria verified against the diff (see `get_backlog_item` progress notes for exact evidence/commits per criterion). 6 of 7 marked `pass` with test-execution proof; AC7 (keyboard-only popup) is source-verified by three independent reviews reading the same code but marked `in_progress` since its Compose UI tests cannot execute in this sandbox (see Layer 4).

**Tests**: 49 automated tests in the `capture`/`desktop` packages. 38 pass; 11 fail, all with the identical `java.awt.AWTError`/`NoClassDefFoundError` at `X11GraphicsEnvironment` — this sandbox has no X11/Wayland display and no `Xvfb` installed (`sudo -n true` confirms no passwordless sudo to install it). This repo's own CI runs `ciCheck` under `xvfb-run --auto-servernum` specifically for this case (`CLAUDE.md`), so these 11 are expected to pass there.

**Security**: dispatched a scoped security review against the socket listener, pending-capture writer/poller, and CLI capture path (locally-reachable Unix-domain-socket accepting untrusted JSON that reaches the DB and disk — a legitimate security-sensitive surface). It surfaced 2 candidate findings; both were run through an adversarial false-positive filter and scored 2/10 and 3/10 (below the ≥8 reporting bar). Root cause of both: a same-OS-user local process could theoretically abuse the socket, but that same process already has direct read/write access to the graph's markdown files (block UUIDs are visible there), so the socket grants no capability the attacker doesn't already have more directly — not a cross-privilege-boundary vulnerability for a single-user personal desktop app's threat model. No fix applied; no security blockers.

**Error handling**: per-file (`PendingCapturePoller`) and per-connection (`CaptureSocketListener`) try/catch isolation confirmed present so one malformed input can't kill the loop for subsequent ones. All three background surfaces (`CaptureController`, `PendingCapturePoller`, `CaptureSocketListener`) have `CoroutineExceptionHandler`s.

**Observability**: matches plan.md's Observability Plan — `Logger` calls at every surface's entry/error points; no metrics added, as explicitly planned (solo-maintainer desktop app, no telemetry infra).

## Layer 4 — UX & Behavioral

**Skipped/deferred**: `project_plans/desktop-quick-capture/design/ux.md` exists, so this layer would normally run `quality:does-it-work` + a golden-path browser/UI walkthrough. This sandbox has no display (no X11/Wayland/Xvfb, no sudo), so the Compose Desktop app cannot be launched or interacted with here. Deferred to a manual smoke test on a real desktop session, or to whoever reviews the PR with a display available. All 13 UX-relevant Compose tests are written and compile-clean (see Layer 3).

## Verdict

**PASS with one documented, environment-caused verification gap** (AC7 / Layer 4) — ready for `/backlog/review`, with the gap disclosed explicitly rather than silently claimed as verified.
