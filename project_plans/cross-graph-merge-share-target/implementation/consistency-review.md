# Cross-artifact consistency review: cross-graph-merge-share-target

**Date**: 2026-10-07
**Inputs**: `requirements.md`, `implementation/plan.md`, `design/ux.md`, `decisions/ADR-001..003`
**Method**: `sdd/skills/4-validate/cross-artifact-consistency-prompt.md` (coverage gaps, scope drift, UX-plan misalignment, terminology drift, direct contradictions). Severity per that prompt: BLOCKER = contradictions and coverage gaps; CONCERN = scope drift and terminology; NITPICK = UX alignment. Where a finding was moved off its default tier, the reason is stated.

**Summary: 17 findings (2 blockers, 7 concerns, 8 nitpicks).** Highest severity: B1 (what happens when a copy targets a graph that cannot be written off-graph).

---

## BLOCKER

### B1. Unwritable copy targets: UX says disabled, never queued, never needs a switch; plan and ADR-001 say apply-on-activate or inbox
- **Artifacts**: `design/ux.md` S3, UX-04, UX-31, UX-41 vs `implementation/plan.md` Story 4.5.1 (apply-on-activate), Story 2.3.1 "Refusals" AC, Story 2.3.3, ADR-001.
- **Conflict**:
  - UX S3: a graph that cannot be written (encrypted, SAF grant lost, platform cannot address the path) is shown disabled with a reason, and "Copies never use the share inbox." UX-04: no step of the copy flow requires switching the active graph. UX-31: such destinations are "disabled with a reason, never hidden".
  - Plan Story 4.5.1: on `PlatformUnsupported` (iOS/Web) the copy is staged and applied when the user opens B, with the UI text "Will be copied when you open B" (Task 4.5.1b renders it in the picker). That is a deferred, queue-like copy and it requires switching to B, so it contradicts UX-04 and UX S3 for exactly the platforms UX-41 says have the flow.
  - Plan Story 2.3.1 "Refusals" AC: encrypted/SAF-no-grant returns `Left(FileSystemError)` "so the caller uses the inbox", and ADR-001 says encrypted/SAF-without-grant targets "use the inbox path" without scoping to share. ADR-001 earlier says "inbox for share; failed page kept in staging for merge". The ADR contradicts itself.
  - Plan Story 2.3.3 says all capability reasons are rendered by the picker as disabled-with-reason (matches UX) yet 4.5.1 makes one of those reasons selectable. The plan never states what `PageMergeService` does for a merge to a target with reason `Encrypted`, `NoGrant` or `SafInboxOnly` that slips past the picker (for example a grant lost between S3 and S4/S5).
- **Resolution**: Decide once. Either (a) copies to unwritable targets are disabled everywhere including iOS/Web (UX as written; drop the 4.5.1 apply-on-activate path or restrict it to the interrupted-copy "Resume" case and add it to UX), or (b) keep apply-on-activate for `PlatformUnsupported`, amend UX S3/UX-04/UX-31/UX-41 and add a "will be copied when you open B" state plus pending indicator to the UX. Then fix the Story 2.3.1 refusal AC and the ADR-001 sentence so merge never refers to the inbox, and add a plan task for "grant lost mid-run" (what the router returns to `apply()`).

### B2. `merge_force_inbox` scope: UX says shares only, plan wires it into a capability that gates copies
- **Artifacts**: `design/ux.md` S16 vs `implementation/plan.md` Story 2.3.3, Risk Control, Task 2.3.2b.
- **Conflict**: UX S16 states "The setting affects shares only; copies (S2-S6) are unaffected, and the helper text says so." Plan Story 2.3.3 adds `ForcedInbox` as a reason returned by `TargetWriterCapabilities.canWriteOffGraph`, and Risk Control says the toggle "is consumed solely by `TargetWriterCapabilities`". The same capability object feeds the one `TargetWriterRouter` (used by merge and share) and the picker's disabled-with-reason logic (Story 3.1.1c, 2.3.3), so turning the toggle on would disable or reroute copy destinations. A copy has no inbox (B1).
- **Resolution**: Scope `ForcedInbox` to the share caller (for example check it in `InboxFallbackAppender`/`JournalAppender`, not in `canWriteOffGraph`), or make the toggle's effect on copies explicit in UX S16 and define what copy does. Also rename the key (it is `merge_`-prefixed but share-only), see C3.

---

## CONCERN

### C1. Desktop Esc and Android Back behavior (known open item): UX decides, plan is silent or says otherwise
- **Artifacts**: `design/ux.md` S2, S10, S11 and Open questions vs `implementation/plan.md` Stories 3.2.1, 4.2.1, 4.3.1.
- **Detail**:
  - UX S11: desktop quick capture Esc with typed text asks "Discard this note?" before closing and saves nothing. UX S10: Android Back with unsaved text auto-saves. UX itself marks this an unconfirmed assumption (Open questions). Plan Story 4.3.1 specifies Alt+G, failure-keeps-popup-open and the toast, but no Esc/discard behavior; Story 4.2.1 only references the existing auto-save indirectly (Task 4.2.1e).
  - Plan Story 3.2.1 AC says "Esc ... cancels"; UX S2 says Esc cancels but asks "Discard selection of N pages?" when selection > 0. No plan task builds that confirmation.
  - Because the two platforms intentionally differ (desktop asks, Android saves), a data-loss decision exists that the plan never records. Rated CONCERN rather than BLOCKER because UX flags it as an open question for the user; it must be resolved before Task 4.3.1c.
- **Resolution**: Get the user's answer, then add one acceptance criterion each to Stories 4.3.1 and 3.2.1 (Esc with text/selection) and one to 4.2.1 (Back auto-save, failure goes to inbox per S10).

### C2. Terminology drift: new / created / imported, and merged / combined
- **Artifacts**: `requirements.md` vs `plan.md` vs `design/ux.md`.
- **Detail**: Requirements success metric says "new / merged / unchanged". Plan `MergeOutcome.Merged` and `DryRunSummary` use "combined", Story 3.3.1's result AC uses "imported 3, combined 5", UX S4 says "new", UX S6 says "created", and UX design principle 1 says "combined, never merged". Plan Story 2.4.2 lists `conflicts` as a separate counter next to `combined` while UX S4 says "conflicts are inside combined" for the button count; the plan never states whether `Copy N pages` = new + combined includes conflicts.
- **Resolution**: Adopt one glossary: `new` (UI: "new - will be created"), `combined`, `unchanged`, `conflicts` (subset of combined). Use it in `DryRunSummary`, `MergeResult`, the result dialog AC and log lines (S15 already uses `new=` and `combined=`). State in requirements that "merged" in the metric means "combined".

### C3. Terminology drift: share queue vocabulary and setting name
- **Artifacts**: `plan.md`, `design/ux.md`, ADR-001.
- **Detail**: Plan/ADR use `ShareInbox`, "inbox", "queued", `Queued(reason)`. UX uses "Save for later", "Saved for later", "Waiting for Work graph", "N shares waiting", "queued icon". The key `merge_force_inbox` is share-only (B2) but is prefixed `merge_`. UX S14/S16 call it "Force queued shares".
- **Resolution**: Pick one user-facing noun ("saved for later") and one internal name (`ShareInbox`), record the mapping in the Domain Glossary, and rename the key to something like `capture_force_inbox`.

### C4. Scope drift: stories and surfaces with no requirement
- **Artifacts**: `plan.md` and `design/ux.md` vs `requirements.md` In Scope.
- **Items**:
  - Story 4.2.2 Direct Share shortcuts and UX S14: not in requirements (plan already marks it OPTIONAL/CUTTABLE; UX S14 is likewise optional).
  - `merge_force_inbox` and UX S16: not in requirements (marked optional).
  - Apply-on-activate staging for iOS/Web (Story 4.5.1, Task 2.4.2g, `PendingApplyOnActivate.kt`): derived from the requirements open question on non-active writes, but it is a new user-visible mode that requirements neither list nor forbid (see B1).
  - Interrupted-copy Resume (S9) and Android application-scope host (Task 3.3.1c): derived from the 8 000-page NFR, acceptable.
  - Pending-shares chip/panel (S13) and conflict review screen (3.3.2): derived from Observability and "true conflicts flagged"; acceptable but not named.
- **Resolution**: Add a one-line "derived scope" note to requirements for Resume, conflict review and the pending-shares indicator. Keep 4.2.2 and S16 explicitly out of the appetite (already stated in plan; also state it in UX). Resolve apply-on-activate with B1.

### C5. Success metric "one step" vs inbox fallback frequency
- **Artifacts**: `requirements.md` Success Metrics and Constraints vs `plan.md` Unresolved Questions (RoundTripGuard) and ADR-001.
- **Detail**: Requirement: a share lands in the chosen graph "in one step ... on Android and desktop". ADR-001 rejects the queue as primary because it breaks that metric, but the plan routes every `NotRoundTrippable`, encrypted, and ungranted-SAF target to the inbox, and Unresolved Questions admits "if low, most off-graph writes route to the inbox". The metric has no measurable acceptance criterion tied to the guard pass-rate.
- **Resolution**: Add an AC to Story 1.1.4/4.1.3 stating a minimum fixture pass-rate for `RoundTripGuard` (or the relaxed "structure-stable" rule) before the share target is declared meeting the metric; report the measured rate.

### C6. ADR-002 decision 3 still states the superseded derivation
- **Artifacts**: ADR-002 decision 3 (first paragraph) vs ADR-002 Revision 2/3 and plan Story 1.2.1.
- **Detail**: The opening of decision 3 specifies `UuidGenerator.generateDeterministic("merge:" + ...)` and "keep the source UUID when free" is withdrawn in Revision 2, which says not to use `generateDeterministic`. Readers of the ADR top-down get the rejected design. Same file, contradictory.
- **Resolution**: Rewrite decision 3 to the final SHA-256 always-remap rule and move the history into a "Revisions" note.

### C7. Requirement for iOS/Web share-target choice has no implementing task
- **Artifacts**: `requirements.md` In Scope ("share-target graph choice where a share/capture entry point exists") and `design/ux.md` S12/UX-41 vs `plan.md` Story 4.5.1.
- **Detail**: Story 4.5.1 AC says the "in-app quick-add" honors the default capture graph on iOS/Web, and UX S12 shows the Settings section with an "Used by quick add" note, but no task wires an iOS/Web quick-add entry point to `CaptureTargetResolver`/`JournalAppender`, and the plan never verifies whether such an entry point exists. Rated CONCERN (not BLOCKER) because the requirement is conditional on the entry point existing.
- **Resolution**: Add a task to confirm whether iOS/Web have a capture/quick-add entry point; if yes, wire it to `JournalAppender`; if no, drop the Story 4.5.1 AC and the UX S12 quick-add wording.

---

## NITPICK (UX surfaces or behaviors with no plan story/task)

### N1. Pending-shares panel actions (UX S13, UX-29)
Plan Story 4.4.1 builds `ShareInbox`, the drain and a pending-count indicator (Task 4.4.1c). No task for the panel, per-item Discard (with 80-char confirm), Copy text, Retry now, "Open <graph>", or the "couldn't be added" drain-failure state.

### N2. Conflict badge and Remove-copy undo (UX S8, UX-20)
Plan Story 3.3.2 covers list, Keep both and Remove copy only. No task renders a text "Conflict" badge on the block on the page itself, and none provides the 10-second snackbar Undo for "Remove copy". Row-level error/Retry states are also unplanned.

### N3. Gaps UX already listed but plan was not updated for
Undo failure, partial and expired messages (S7); "Can't resume" states for swept staging or removed graph (S9); deferred "Skipped for now (N)" result group (S6); picker "Re-grant access" action (S3). UX's own "Gaps found against the plan" table lists these and Story 2.5.1, 3.3.1 and 2.3.2 still do not cover them.

### N4. Last-used copy destination (UX S3)
UX preselects the last-used copy destination. Plan has `CaptureTargetSettings` for capture only; no setting or task persists a copy destination.

### N5. Where the >200 linked-page confirmation lives
UX S2 requires inline confirmation in the picker (repeated as a line in S4). Plan Task 3.3.1a puts "confirm >200" in `DryRunDialog` and Task 3.2.1b only shows the live delta.

### N6. Share overlay states (UX S10)
Plan Story 4.2.1 lists "Save to <fallback>" and "Retry" for an unavailable target; UX also requires "Save for later", the "no graphs configured" placeholder that saves to the inbox on Close (the inbox is keyed per `GraphId`, so with no graph there is no key), and the "Already added" state for redelivery.

### N7. Pending-shares chip host (UX Open questions)
UX notes the plan names no host for the chip; Task 4.4.1c produces state only. Decide the host (near graph switcher on desktop, Settings > Capture on Android) before building.

### N8. Plan hygiene
Status line says "Ready for implementation" while ADR-001..003 are still "Proposed" and ADR-001 is gated by open spikes; Story 4.5.1 lists Task 4.5.1d before 4.5.1c. Align statuses and ordering.

---

## Coverage check (requirements In Scope -> plan stories)

| Requirement | Plan coverage |
|---|---|
| Block-level merge (UUID, content, props, conflicts, additive) | Stories 1.1.1, 1.1.2, 1.1.4, 1.2.1 |
| Selection UI, filters, search | Stories 2.4.1, 3.1.1, 3.2.1 |
| Linked pages and assets | Story 2.4.3 |
| Dry-run summary | Stories 2.4.2, 3.3.1 |
| Share target default + override (Android) | Stories 4.1.1, 4.2.1 |
| Desktop quick capture graph choice | Story 4.3.1 |
| Android/Desktop/Web/iOS copy UI | Stories 3.x, 4.5.1 (see B1, C7) |
| Write to non-active graph | ADR-001, Epic 2.1, 2.3 |
| Observability (logs, snackbar, visible failure) | Observability Plan, Stories 3.3.1, 4.4.1 |
| Risk control (additive, dry-run gate, undo evaluated) | Epic 2.5, ADR-003 |

No requirement lacks a story outright; gaps are partial (C5, C7).

## Known open items reviewed

- Desktop Esc asks before discard vs Android Back auto-saves: UX-only decision, plan silent -> C1.
- Copies never use the share inbox: consistent with UX S3, but contradicted by plan Story 2.3.1 refusal text, ADR-001 and the plan's apply-on-activate path, and indirectly by `ForcedInbox` -> B1, B2.
