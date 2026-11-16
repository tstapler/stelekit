# Cross-artifact consistency review: sync-lock-and-image-annotator

> **Scope (triad repair 2).** This review covers the 89-task baseline plan (before triad repair 1). The 18 split tasks and the new tasks added since (107 tasks, 31 stories now) are covered by the plan's Repair Log and `validation.md` only; this file is intentionally not rewritten.

Artifacts: requirements.md, implementation/plan.md, design/ux.md, decisions/ADR-001..007. Read-only review; script checks run 2026-11-14.

Summary: 12 findings (1 BLOCKER, 5 CONCERN, 6 NITPICK). Highest: two-finger gesture is specified both as a disarm path and as "tool stays armed".

## Mechanical checks (all passed)
- 89 task headings, 31 stories, 4 epics, 42 glossary rows, 50 UX criteria, F1-F15 in both ux.md and plan.md: all counts match the plan's claims (per-epic E1 29 / E2 19 / E3 26 / E4 15).
- Effort table: all 31 story rows match task count and raw k-tokens recomputed from size letters (total 7,155k).
- Every task id referenced anywhere (requirements, ux, ADRs, plan) exists as a heading; all 7 ADR links resolve; no dead markdown links.
- Every task appears in exactly one wave (ranges like 1.1a-d expanded; 1.2b/1.2c in W4 are only "needs" mentions). No task depends (by text) on a task in the same or later wave. Lane orders hold: Service 1.3d(W3)->1.4a(W5)->1.4b-d(W6)->1.5(W7)->1.7c(W7); VM 2.2b(W3)->2.3b(W4)->3.4b(W5)->3.7a(W6)->3.6c(W7); Screen 2.3e/2.4c(W5)->2.5a/b(W6)->2.6a(W7)->3.6d(W8); Sidecar 3.2c(W3)->4.5a(W4)->3.9c(W6); Camera 4.4a(W4)->4.5b(W5)->4.4c(W6). 1.4e (W2) defines SyncOrigin/SyncInProgress before 1.4a (W5) uses them.
- Numbers agree across artifacts: 48 dp drag-commit (ADR-007, plan glossary, ux), 0.25 floor (ux, ADR-006, plan), FIT_SCALE = 1.0, N tiers (ADR-003 SCALE any N, LOO at N>=3; GRADIENT N>=5; GRADIENT_AND_K N>=6; PER_AXIS N>=4; plan open question identical), two paused retries at about 30 s and 2 min (ADR-001 d14, plan, ux), 60 s legacy rule appears only as the status quo being removed (ADR-001 L9, Task 1.2d, Task 1.1a test).
- ADR-003 renamed tier: GRADIENT used everywhere live; ux.md never says TILT (says "tilt tier" in prose only).

## BLOCKER
1. **Two-finger gesture: disarm vs stays armed.** ux.md L155, L173, UX-11 and ADR-005 decision 7 list "a two-finger gesture" as a disarm path (back to Select); ux.md L175, UX-13 and ADR-007 decision 4 say a second finger cancels the in-flight placement and "the tool stays armed". Plan Story 2.3 AC (L529) papers over it with "SELECT (chip tap) or unchanged per path". Resolution: pick one (ADR-007 version: second finger only discards the in-flight/pending point, tool stays armed), delete "two-finger gesture" from ux.md L155/L173/UX-11 and ADR-005 d7, and make the plan AC state the tool stays armed.

## CONCERN
1. **Requirements vs ADR-001 on the "joined" signal.** requirements.md L101 asks for "a distinct `SyncState`/log line for joined running sync"; ADR-001 d5 and the plan forbid touching `SyncState` on a coalesce (log line, `inFlight`, and an `AlreadySyncing` UI event only). Resolution: amend the requirement to "log line plus UI event, no SyncState change" or note the deliberate deviation in the plan.
2. **One concept, three names.** "tool lock" (requirements L69, plan L3/L460), "Keep tool" (ux.md, ADR-005 d3, plan 2.6a), `ToolStickiness.LOCKED` and Settings key `annotator_sticky_tools` / "sticky" (plan L132/L151, ADR-005 d2); the Settings row is titled "Keep tools active". Resolution: add a glossary note mapping UI label "Keep tool" = `ToolStickiness.LOCKED` = setting `annotator_sticky_tools`, and stop saying "sticky"/"tool lock" in prose.
3. **Stale Repair Log rows contradict the current design.** Plan L1189 (V-B5: "PER_AXIS/TILT write a CONSTRAINT_FIT plane") and L1225 (V-E3c: "mandatory gate (Task 3.9a); EXIF-only focal makes TILT ineligible; Story 3.9 split into mandatory and optional") describe iteration-1 behaviour; Story 3.9 and Task 3.9a now say the opposite (nothing in base tiers needs a focal; 3.9 is optional). Plan L885 also refers to the old "required for TILT" gate. Resolution: mark those rows "superseded by C8 (iteration 2)".
4. **Scope drift (stories with no requirement line).** (a) Task 1.2e `SyncMain` CLI claim (ADR-001 d13) - requirements never mention the CLI; (b) Task 3.9b lens-id input to profile matching borders on the requirements Out of Scope item "per-camera factory distortion profile databases"; (c) Settings rows (Task 2.2c) are justified only by Risk Control prose. Resolution: add a one-line requirement or an explicit "added by research/review" note for each.
5. **Success Metric 3 N is unresolved.** requirements says N is "set in research"; research did not set it and the plan defers N and tier thresholds to Story 3.8 (owner photos, unresolved question L148). Acceptance of the metric therefore depends on an owner-run study; plan states this but requirements does not. Resolution: add the dependency to requirements Success Metric 3 or Open Question 5.

## NITPICK
1. Task 2.3d has no `Files:` line (only the lane note), so the same-file lane rule for AnnotationGestureLayer.kt cannot be audited from the task.
2. ADR-007 Consequences L27 says "`PlacementGesture.Drag(start, end)` from ADR-005 is replaced"; ADR-005 no longer mentions `PlacementGesture` at all.
3. UX-50 (animations respect "remove animations") is built only for the lens crossfade (plan L985); the auto-return pulse and zoom-to-Fit animations (ux.md L228) have no task or AC.
4. Plan never cites UX-NN ids except UX-03, UX-24, UX-36; all other 47 criteria map by prose only. Sampled 20 (UX-02, 21, 23, 27, 29, 34, 36, 41, 43, 44, 45, 46...) and found tasks for each; no id-to-task trace table exists.
5. ux.md uses "tilt tier" prose (L308, L352) while ADR-003/plan use `GRADIENT`; add "(GRADIENT)" once for ubiquitous language.
6. All seven ADRs are still `Status: Proposed` while plan.md says "Ready for implementation"; and Task 1.8b makes the gate-asserting fake the default fixture only in W8, after 1.4a's tests (W5) run on the non-asserting fake (acceptable, but note it in 1.4a).
