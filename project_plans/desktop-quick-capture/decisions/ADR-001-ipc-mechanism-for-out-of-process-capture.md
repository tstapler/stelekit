# ADR-001: Pending-Captures Directory + Unix-Domain-Socket Fast Path as the Out-of-Process IPC Mechanism

**Status**: Proposed
**Date**: 2026-09-05
**Project**: desktop-quick-capture

## Context

Three of the four v1-scoped desktop capture triggers run as separate OS processes with no in-process access to the running SteleKit JVM: a macOS Services-menu handler, a Linux Nautilus script, and a Windows registry context-menu handler (the fourth, the global-hotkey popup, runs in-process and has no IPC problem at all — see requirements.md's "Key architectural fact"). These out-of-process triggers need some hand-off mechanism to reach a running SteleKit instance, plus a defined behavior when no instance is running (cold start).

The requirements doc explicitly treats the local REST/automation API (#232) as separate, out-of-scope work and warns against this feature becoming "a thin client of #232" by default — so whatever mechanism is chosen here must be a narrower, purpose-built channel, not a preview of #232.

Four options were evaluated (research/architecture.md §2, research/stack.md §2, research/build-vs-buy.md §3):

- **(a) Unix domain socket (POSIX) / named pipe (Windows)** — a tiny bespoke protocol, zero new dependency given this project's JDK 21 toolchain (JEP-380 UDS support stable since JDK 16).
- **(b) Minimal embedded HTTP listener** (`ktor-server-*` or JDK's built-in `com.sun.net.httpserver.HttpServer`) bound to loopback only.
- **(c) Lock file + short-lived second-JVM relaunch** (argv hand-off), modeled on Electron's `requestSingleInstanceLock()`.
- **(d) Pending-captures directory + poll**, reusing the poll-and-reconcile *pattern* (not the class) from `GraphFileWatcher`.

## Decision

Adopt **(d) as the unconditional cold-start path** (every option needs some fallback when SteleKit isn't running, and (d) already solves it with zero extra "is an instance running" detection code) **plus (a) as the live-instance fast path** to avoid (d)'s up-to-5s poll latency when SteleKit is already running. Both paths write into or read from the same `PendingCaptureFile` message shape via a single `CaptureSocketClient` (research/architecture.md's recommendation, "one entry point regardless of which path delivered the payload").

## Alternatives Considered

- **(b) Embedded HTTP listener**: Rejected. Any HTTP listener — even `127.0.0.1`-only, even the JDK's zero-dependency `HttpServer` — is harder to keep visibly distinct from the explicitly out-of-scope local REST API (#232) than a raw socket is; the requirements doc treats that line as one not to blur. Using `ktor-server-*` specifically would also add a new dependency class (server, not client) this project has never carried. HTTP's one real advantage — trivial `curl`-ability from shell scripts — doesn't outweigh this risk given the out-of-process triggers (Nautilus script, Windows handler) are already going to shell out to the `stelekit` binary itself (Epic 2.3/3.2 of the implementation plan), not to a bare HTTP endpoint.
- **(c) Lock file + second-JVM relaunch**: Rejected as a *standalone* mechanism. Detecting "is an instance running" via a lock file still needs *some* channel to hand off the actual payload once an instance is found — it reduces to (a) or (d) underneath, so building it adds a JVM-cold-start detection step (hundreds of ms to a few seconds, wasted purely to check a lock file) without replacing anything (a)/(d) don't already do more cheaply. `logseq#6185` (research/build-vs-buy.md §2) is a concrete precedent for the failure mode this path invites: cold-start and already-running argv-parsing paths drifting apart.

## Rationale

(d) alone would have an up-to-5s latency for the case where SteleKit is already running — acceptable for a genuinely fire-and-forget capture (no user is waiting on a dialog), but avoidable at low cost. (a) alone still needs a distinct cold-start story, since nothing is listening if SteleKit isn't running — every option needs *some* fallback, and (d) already provides one with zero extra "liveness detection" code (a stale/dead lock file's failure mode, per research/pitfalls.md §2, simply doesn't apply to plain file writes). Combining them, with (a) writing into the same message shape (d) reads, means the "append block to today's journal" replay logic (`CaptureWriter`, `CaptureController.writeCaptureDirect`) has exactly one entry point regardless of which path delivered the payload — matching this repo's existing "one capture surface, one write path" principle (ADR-003, `android-features-integration`).

## Consequences

**Positive:**
- Zero new dependency for the socket path (JEP-380, already-current JDK toolchain) and zero new dependency for the pending-captures path (plain file I/O + the already-on-classpath `kotlinx-serialization-json`).
- Cold-start is a non-event: SteleKit's normal startup just gains one poller (`PendingCapturePoller`), not a bespoke "was I invoked with a hand-off argv" code path.
- The mechanism stays visibly narrower than #232 — one fixed-shape message (`{captureId, text, capturedAt}`), no general query/read API, no route beyond "append this text."
- Replay/retry idempotency: every message (pending-capture file or socket) carries a stable `captureId` that becomes the resulting `Block`'s uuid, so re-delivering the same capture (crash before the file is deleted, or a lost socket ack followed by a file-write fallback) is an `INSERT OR REPLACE` no-op (`insertBlock`, `SteleDatabase.sq:335-337`), not a duplicate journal block — see plan.md Story 1.1.1, 2.2.1, and 3.2.1.

**Negative / Risks:**
- The socket path is POSIX-only as implemented (`StandardProtocolFamily.UNIX`); a genuine Windows named pipe would need a separate code path or a JNA-based library (`scalacenter/ipcsocket`) not adopted here. Since Windows is not part of this plan's v1 scope (see ADR-002 — Phase 2's Windows registry handler will shell out to the CLI capture mode, falling straight to the pending-captures file with no socket fast path on Windows until a follow-up adds one), this gap is acceptable for now but should be revisited before Phase 2's Windows work ships.
- A stale socket file from a prior crash must be actively cleaned up on bind failure (implementation plan Task 3.1.1d) — untested this, and it silently fails open (hotkey/UI capture keeps working; only the fast path degrades to the 5s poll).
- Payload size must be capped (implementation plan Task 3.1.1b, 64KB) to avoid a malicious/buggy local sender OOMing the listener thread, per research/pitfalls.md §2.

## Related

- Requirements: `project_plans/desktop-quick-capture/requirements.md` (Open Question 3, Acceptance Criterion 3)
- Research: `project_plans/desktop-quick-capture/research/architecture.md` §2, `research/stack.md` §2, `research/build-vs-buy.md` §3, `research/pitfalls.md` §2
- Plan: `project_plans/desktop-quick-capture/implementation/plan.md` (Phase 2 Epic 2.1/2.2, Phase 3 Epic 3.1/3.2)
