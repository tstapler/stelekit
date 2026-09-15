# ADR-002: v1 Ships Only the In-Process Hotkey Popup; OS-Native Extension Surfaces Deferred to Phase 2

**Status**: Proposed
**Date**: 2026-09-05
**Project**: desktop-quick-capture

## Context

requirements.md's "Suggested scope" lists three v1 surfaces as if roughly equal effort: a macOS Share Extension/Services-menu entry, a Linux Nautilus script *or* global-hotkey popup, and a Windows context-menu registry handler. requirements.md's own Open Question 1 asks directly whether v1 should ship *only* the in-process global-hotkey popup and treat the other two as v2, given the item's own "whichever is lowest-effort first" instruction for the Linux choice.

Research surfaced that the three surfaces are not comparable in cost or risk:

- The **hotkey popup** runs in-process, in the already-running JVM, calling the exact same `JournalService`/`DatabaseWriteActor`/`GraphWriter` chain Android's `CaptureActivity` already validated — no IPC, no cold-start problem, no native tooling gap (research/architecture.md's "Key architectural fact").
- A true **macOS Share Extension** requires a native Swift `.appex` target inside the app bundle's `Contents/PlugIns/`, App Sandbox entitlements, and in practice notarization — none of which exist in this repo's build today (research/pitfalls.md §4, research/build-vs-buy.md §2). The much lower-effort **Services-menu** entry is a realistic v1-adjacent target instead, but still needs the out-of-process IPC/cold-start machinery from ADR-001.
- The **Linux hotkey path** itself (as an alternative to the Nautilus script) depends on JNativeHook/JKeymaster, both of which fail silently or require a portal grant dialog under Wayland/GNOME (research/build-vs-buy.md §1, research/pitfalls.md §1) — a real reliability gap independent of the Nautilus-vs-hotkey choice.
- The **Windows registry handler** needs WiX fragment authoring merged into the jpackage MSI build (research/stack.md §4, research/build-vs-buy.md §4) — no code exists in this repo to build on, and it depends on the same IPC/cold-start machinery as macOS's Services-menu path.
- research/ux.md §5 independently ranks the hotkey popup #1 by "JTBD value ÷ implementation cost," ahead of the macOS and Windows surfaces, precisely because it has zero IPC/cold-start cost.

## Decision

**v1 ships**: the in-process global-hotkey capture popup (macOS/Linux/Windows, via Compose Desktop, since Compose Desktop is already cross-platform) **plus** the pending-captures-directory file format and poller (ADR-001), so that out-of-process surfaces built later have a stable, already-tested format and CLI contract (`stelekit --capture-text`) to write into from day one of Phase 2.

**Deferred to Phase 2** (a separate, later project, explicitly sequenced after this plan, depending on but not blocking it): the macOS Services-menu entry, the Linux Nautilus "Send to" script, and the Windows registry context-menu handler. None of these are tasked in `project_plans/desktop-quick-capture/implementation/plan.md`.

This directly answers requirements.md's Open Question 1: yes, ship only the in-process popup in v1.

## Alternatives Considered

- **Build all three v1 surfaces in one pass** (requirements.md's literal "Suggested scope" list): Rejected. Bundling forces the lowest-risk, highest-value piece (the hotkey popup, ready today with zero IPC work) to wait on the two highest-uncertainty pieces (native Swift/Xcode tooling this repo's Gradle/Bazel build doesn't have; WiX/registry authoring). There is no technical dependency forcing this sequencing — it was purely an artifact of requirements.md listing them together.
- **Wait for #232 (local REST API) before building anything**: Rejected. #232 doesn't exist in this codebase (confirmed by code search, research/architecture.md §2) and requirements.md explicitly scopes it as separate work. Waiting blocks the in-process hotkey popup for no reason, since it never needed #232 in the first place.

## Rationale

"Lowest-effort first" is requirements.md's own stated tiebreaker for the Linux choice (Nautilus script vs. hotkey popup) — the same logic generalizes across all three platforms once the underlying cost asymmetry (in-process vs. requires-new-native-tooling) is made explicit. Shipping the hotkey popup now, plus the pending-captures format the other two surfaces will eventually consume, means Phase 2 starts from a validated write path and message format rather than designing both the OS integration *and* the IPC contract at once.

## Consequences

**Positive:**
- v1 ships something usable on all three desktop OSes (subject to the Wayland/GNOME hotkey caveat below) with a bounded, well-understood implementation (Phases 1-3 of the plan).
- Phase 2 (macOS/Linux/Windows OS-native surfaces) starts with a stable target to integrate against — the `stelekit --capture-text` CLI contract and the `PendingCaptureFile` format — rather than needing its own IPC design pass.
- No dependency on #232, which doesn't exist yet.

**Negative / Risks:**
- Requirements.md's literal ask ("all three surfaces in v1") is not fully delivered in this pass — mitigated by recording the cut explicitly here rather than silently shipping less than the backlog item describes.
- Linux users on Wayland/GNOME (a meaningful fraction of the user base per research/build-vs-buy.md §1) will find the hotkey non-functional or gated behind a portal permission dialog they don't expect; their fallback in v1 is none, since the Nautilus script (their natural fallback per research/build-vs-buy.md's own recommendation) is exactly the piece deferred to Phase 2. This is a known, accepted gap for v1, not an oversight — flagged here so it isn't rediscovered as a "bug" later.
- macOS Quick Note (a first-party OS capture surface, research/ux.md §1) already covers a similar mental model; this ADR does not attempt to compete with it — the hotkey popup's macOS value is additive (captures directly into SteleKit, not generic Notes.app), consistent with research/ux.md's own recommendation.
- Closing the main window disables capture entirely until relaunch. `Main.kt`'s `onCloseRequest` calls `exitApplication()`, which terminates the whole JVM process — including `JKeymasterHotkeyListener` (Epic 1.3) and `CaptureSocketListener` (Epic 3.1). This directly undercuts the feature's core promise ("capture without switching windows") for as long as the window stays closed. **Decision: accept this as an explicit v1 limitation (option (b)), not build `Tray`-based background residency (option (a))** — a tray icon needs per-OS assets, a "Quit" menu item, and rewiring `onCloseRequest` to hide-not-exit, which is exactly the kind of infra-heavy addition this ADR already defers elsewhere (the OS-native surfaces). Users who want capture available while the window is "closed" should minimize it instead; Task 1.5.1d's code carries a comment pointing back to this paragraph so the tradeoff isn't rediscovered as a bug. Revisit if/when Phase 2's OS-native surfaces make idle-process residency valuable for more than just the hotkey.

## Related

- Requirements: `project_plans/desktop-quick-capture/requirements.md` (Open Question 1)
- Research: `project_plans/desktop-quick-capture/research/ux.md` §5, `research/pitfalls.md` §1/§4/§5, `research/build-vs-buy.md` §1/§2/§4, `research/stack.md` §1/§3/§4
- Plan: `project_plans/desktop-quick-capture/implementation/plan.md` (Phases 1-3, "Deferred" section)
- Related ADR: ADR-001 (IPC mechanism the deferred surfaces will use once built)
