# Cross-artifact consistency review: excalidraw-sketch-engine

Date: 2026-11-14. Phase 4 step 3. Inputs: `requirements.md`, `implementation/plan.md`, `design/ux.md`, `decisions/ADR-001..007`.
Severity per the prompt: BLOCKER = contradictions and coverage gaps, CONCERN = scope drift and terminology, NITPICK = UX-to-plan alignment.

**Totals: 4 BLOCKER, 16 CONCERN, 11 NITPICK.**

## Phase 4 repair status (documents edited; NOT re-reviewed)
Findings below are the original review text. These were addressed by editing plan, ADRs, ux.md and research/features.md; a fresh review has not been run, so nothing here is claimed to pass.
| Finding | Repair | Where |
|---|---|---|
| B1 | Circle = `EllipseElement` with equal radii; `PointElement` and `ArcElement` added as technical-geometry kinds; owner decision OD-8 (what is dropped if declined is spelled out) | plan glossary, Stories 1.2.1, 1.2.2, 1.3.3b, 2.1.1a, 3.2.4, 4.1.2b, 4.2.1b, 5.1.2a, 5.1.3; ADR-003 item 5; `research/features.md` |
| B2 | ADR-001 Decision 2 now says `sheets/<uuid>.v2.json` | ADR-001 |
| B3 | Constrain flyout for 1+ selected elements with per-item disabled reasons | plan Task 4.2.3b; ux.md sections 1, 2, 9, UX-30, UX-37 |
| B4 | `Not now` = per-sheet `Declined`, re-offered from the sheet menu | ux.md 14.2, UX-64; plan Stories 1.4.3, 1.4.6 |
| C5 | `ConversionReadyConsistencyTest` list includes 1.1.5 and technical-data, codec, dispatch and write-path test classes | plan Story 1.4.3, glossary |
| C6 | All caps (incl. the 10,000-char text limit) in `SceneLimits`; `ImportLimits.kt` removed; import cap unified at 20 MB | plan Story 1.2.4, 3.4.1, 3.4.3, 5.1.1; ADR-002 |
| N1 | First-write confirmation (and banner, failure dialog, flag-flip notice) is its own Story 1.4.6, wired to OD-1 | plan Story 1.4.6 |
Not touched in this repair (still open): C1-C4, C7-C16, N2-N4 (partly: UX-53, UX-57, UX-58 now have tasks), N7-N11. N5 (capacity UX) and N6 (UX-36, UX-47, toasts) are covered by Stories 2.2.4, 4.1.4b, 3.6.6, 4.2.2b.

## Recounted numbers (all VERIFIED by script over the files)
| Claim | Claimed | Recount | Result |
|---|---|---|---|
| plan epics / stories / tasks | 19 / 78 / 167 | 19 / 78 / 167 (`grep -c` on `### Epic`, `#### Story`, `##### Task`) | match |
| per-epic story/task counts and XS/S/M mix in the effort table (19 rows) | as table | all 19 rows match (first `(XS\|S\|M)` tag per task) | match |
| raw tokens / CU / x1.75 | 14.16M / 21.0M / ~36.7M | 14,160k / 20.96M / 36.7M | match |
| glossary terms | 43 | 43 rows | match |
| UX acceptance criteria | 68 | 68 distinct `UX-nn` | match |
| requirements success metrics in traceability | 7 | 7 | match |
| story references in plan/UX to undefined stories | - | none (only `2.5.7` = WCAG); all `Task N.N.Na` references resolve | clean |

(Counts above are as of review time. After the Phase 4 repair, recounted by script: 19 epics / 83 stories / 188 tasks, 46 glossary terms, 68 UX criteria, raw 15.11M tokens, 22.36M CU, ~39.1M CU with the x1.75 multiplier.)

## BLOCKER

**B1. Constraint geometry has no scene element (requirements metric 3 vs plan vs ADR-003).**
Requirements metric 3 requires tangent and radius/diameter constraints. Plan Task 4.1.2b (circle/arc tangent, radius/diameter) and Task 4.2.1b ("only point/line/circle/arc/rect/polyline accept constraints") assume circle, arc and point geometry. The `SceneElement` hierarchy (glossary, Story 1.2.1b) has Rect, Diamond, Ellipse, Line, Arrow, Freedraw, Text, Image, Frame only, and ADR-003 item 5 puts "ellipse/conics" Out. Nothing says an `EllipseElement` with rx=ry is a circle, and no element can be an arc or a standalone point.
Resolution: add a rule (Story 4.2.1b, ADR-003) mapping circle = Ellipse with equal radii, and either add `ArcElement`/`PointElement` stories (model, hit-test, render, codec, importer extras) or drop arc/point/tangent-line-arc from the v1 constraint set and from metric 3.

**B2. ADR-001 Decision 2 contradicts ADR-001 4a, ADR-005 and the plan on the v2 sheet path.**
ADR-001 Decision 2: "A v2 sheet is a `sheets/<uuid>.json` with `schema: 2`". ADR-001 4a, ADR-005 item 4, Story 1.1.3 `SheetPaths` and Story 1.4.2 all require `sheets/<uuid>.v2.json` (the v1 file must stay hash-valid until the model flip). The path rule is data-compat critical, so the decisive ADR must not state the superseded path.
Resolution: edit ADR-001 Decision 2 to `sheets/<uuid>.v2.json` (schema 2) and point to `SheetPaths`.

**B3. The Constrain flyout is gated to 2+ selected elements, but required constraints apply to one element.**
ux.md section 1 ("Constraints tab ... or 2+ elements selected"), section 9 ("visible only when 2+ elements selected") and plan Task 4.2.3b say the same. Horizontal, Vertical, Fixed, Radius, Diameter and a one-line Distance apply to a single element, and plan Story 4.1.4 itself tests "a Horizontal constraint on L1". As written the only UI route to a single-element constraint is auto-constrain at draw time (Story 4.2.2), so metric 3 and UX-30 cannot be met for those types.
Resolution: show the flyout for 1+ selected elements (with per-item disabled reasons, UX-37) and amend Task 4.2.3b and ux.md section 9.

**B4. "Not now" behaviour: ux.md 14.2 vs plan Story 1.4.3.**
ux.md 14.2: "[Not now] re-asks next open", decision "remembered per graph ... per project". Plan Story 1.4.3: `Declined` is "remembered per sheet in `Settings`, offered again from the sheet menu" (no re-ask at open). Different trigger and different scope (project vs sheet), on the OD-1 safeguard flow.
Resolution: pick one (recommend per-sheet `Declined`, re-offer from the sheet menu) and edit ux.md 14.2 and UX-64 to match.

## CONCERN

**C1. Metric 4 weakened by a spike fallback.** Requirements metric 4 wants identical rendering within pixel tolerance on JVM, Android and wasmJs. Spike 0.1.3e and Task 2.1.3c allow Android parity to degrade to draw-list equality plus an owner on-device check. Resolution: record this as an explicit, owner-accepted requirements deviation if Spike 0.1.3e fails.

**C2. Desktop 16 ms p95 is not enforced anywhere.** Requirements NFR: p95 frame <= 16 ms desktop, <= 24 ms Pixel_Fold. Plan (Story 2.1.3, 6.2.2) enforces only "<= 1.5x empty-canvas p95" in CI, with 24 ms as an owner-run advisory. The requirements do say "relative-to-empty-canvas form", but the absolute desktop ceiling is dropped. Resolution: state in the plan that 16 ms is advisory or add it to `sketch-engine-budgets.json`.

**C3. Backup scope differs.** Requirements Risk Control: "backup of the sidecar bundle before first v2 write". ADR-001 4 / plan back up one sheet (`backup/<uuid>.v1.json`) per conversion. Resolution: amend the requirement to per-sheet backup or back up the model too.

**C4. Old-build rollback.** Requirements Risk Control offers "v1-compatible projection ... or read-only in v1". ADR-001 chose fail-closed for the whole project in old builds (ADR-001 and ADR-005 remain Proposed, gated on OD-1), which is neither. Resolution: record the decision as a requirements deviation once OD-1 is answered.

**C5. `ConversionReady` test list is inconsistent.** Glossary: true only while "bundle, technical-data, codec, fusion and preview test classes" are registered. Risk Control names Stories 1.1.3, 1.1.4, 1.1.5, 1.4.5. Story 1.4.3 names 1.1.3, 1.1.4, 1.4.5 (no 1.1.5) and its `ConversionReadyConsistencyTest` list (`BundleV2PassthroughTest`, `ReconcilerV2GoldenTest`, `SidecarWriterRaceTest`, `MixedProjectFusionGoldenTest`, `SketchPreviewV2Test`) contains no technical-data test (`TechnicalDataTest`/`ConversionPolicyTest`) and no codec test (`SheetCodecV2Test`). Resolution: add those classes to the list and 1.1.5 to the AC.

**C6. Caps outside `SceneLimits`, and a phantom plan cap in the UX.** Story 1.2.4 says all bounds use `SceneLimits` and "a test greps that no other numeric cap exists". But Story 5.1.1 has `XCAL/ImportLimits.kt` (a 25 MB test file; ux.md says a 20 MB cap), Story 3.4.3 has 60 MB / 16 MP image caps, ux.md has a 200-item library cap and a 10,000-character text cap, ADR-006 has 64 layers. ux.md section 6 says the 10,000-char limit "match[es] the plan's size sanity", but plan.md has no such limit. Resolution: add these caps to `SceneLimits` (or exempt `ImportLimits` explicitly) and add the text limit to the plan.

**C7. Terminology: "Pen mode" vs "Finger drawing" (inverted names for one switch).** Plan Story 3.1.2: "Pen mode off makes touch draw". ux.md section 3 names the cluster button "finger draw" while the text calls the toggle "Pen mode" (OFF = finger draws), and UX-07 says "Finger drawing". Resolution: one name and one polarity ("Finger drawing", on = touch draws).

**C8. Terminology: style fields.** ux.md style panel uses "Sloppiness [Clean][Sketchy]", "Edges [Sharp][Round]", "Fill style [Solid][Hatch][Cross]"; plan and ADR-004 use `roughness` (numeric, 0 = Clean), `roundness`, `fillStyle` hachure/cross-hatch/zigzag/solid. The UX omits zigzag and cannot express roughness values between 0 and Sketchy. Resolution: align labels to the model and add zigzag.

**C9. Terminology: nudge unit.** ux.md section 10 steps "1 px / 10 px / 1%"; plan Stories 3.1.2/3.6.1/3.6.3 and UX-40 use scene "units" (Shift = 10 units; 1 unit = 1/16 in). Resolution: say "units" or define the px-to-unit rule at zoom.

**C10. ADR-004 vs plan on font fallback.** ADR-004: "no system fallback for diagram text". Plan Story 0.1.3 option (b) and Story 2.1.2 use a platform fallback font for CJK and require "never shows tofu without a fallback". The plan says ADR-004 will be amended by the spike; until then the ADR contradicts a plan branch. Resolution: reword ADR-004 to "no system fallback for Latin; CJK per Spike 0.1.3b".

**C11. Stale dependency.** Story-level edge table: 4.2.1 needs "1.4.2 (codec fields)". `SheetCodecV2` is Story 1.1.5 (the plan says so in Story 1.4.2's Files line). Resolution: change to 1.1.5.

**C12. ux.md 14.6 is stale.** It says in-app restore "is NOT provided (documented manual procedure)"; plan Story 6.2.6 provides `SketchBackupRestore` and a settings action (U4 says "RESOLVED in plan" but 14.6 text was not updated). Resolution: rewrite 14.6 to link the in-app action.

**C13. Observability coverage is thin.** Observability Plan lists seven log lines (`sketch.tool.activated`, `sketch.solve.failed`, `sketch.import.failed/summary`, `sketch.migrate.outcome`, `sketch.persist.failed`, `sketch.sheet.rejected`) and requirements ask for tool activation and migration outcome logging. Only Story 6.2.3 (solve log, no-user-text test) covers any; UX-71 ("every user-visible error maps to one log line") and UX-73 (no per-pointer-move logging) have no story or test. Resolution: add acceptance criteria to 6.2.3 for each line and a table-driven UX-71 check.

**C14. Scope drift (stories without a requirement).** Story 5.1.4 (paste Excalidraw clipboard JSON) and Task 3.5.6b (`.excalidrawlib` import/export) are not named in requirements In Scope (nearest: ".excalidraw JSON import and export", "library of reusable shapes"). Resolution: add one line to requirements In Scope, or mark both as accepted extensions.

**C15. "Phase 0 spikes may start now" vs OD-3.** Plan Status line and wave W1 ("6 parallel") conflict with Story 0.1.2's precondition and Unresolved Questions ("Decide OD-3 BEFORE any Task 0.1.2a code"). Resolution: say Spike 0.1.2 starts only after OD-3.

**C16. Solver gating text differs.** Status line: solver production code "4.1.2+" waits on OD-3 and Spike 0.1.2; edge table: "4.1.1-4.1.3 ... needs 0.1.2, OD-3, ADR-003 Accepted"; Epic 4.1 header: gated by 0.1.2 and ADR-003. Resolution: use the edge-table wording everywhere.

## NITPICK (UX surface or criterion with no story or task)

**N1. Migration and shell surfaces have no UI task.** ux.md section 1 read-only banner, lease-conflict "Switch to it", open-failure panel with "Open backup copy", 14.1 settings toggle copy, 14.2 first-write confirmation (the OD-1 safeguard; Task 1.4.3b only says it is "owned by the router/editor"), 14.3 post-conversion banner, 14.4 conversion-failure dialog, 14.5 "Update SteleKit" panel ("Check for updates", "Export last backup"). Recommend promoting the 14.2 dialog to its own story.

**N2. Library UI (section 13).** Story 3.5.6 has no `UI2` file: no Library tab, item thumbnails, undoable delete, or the 200-item cap message.

**N3. Export dialog (section 12).** Story 5.2.3 covers frame and selection scope only; "Style: As drawn / Clean", the PNG pixel cap (16k px) with "Use 1x", OOM retry, blocked-download handling, CSV option and the `.excalidraw` layers-flattened notice (UX-58) have no task.

**N4. Import options (section 11, UX-53).** "Import first 5,000" and "Import into a new sketch" have no story (Story 5.1.1 only rejects or sanitises; there is no new-sketch import path); canvas drag-and-drop of a file and the "newer Excalidraw schema" notice are also unowned.

**N5. Capacity UX (section 15, UX-73).** Soft thresholds 4,000 elements / 160 constraints and hard-cap actions ("Delete some", "Split into a new sketch", "Re-enable effects") are absent from the plan; Task 2.2.3b specifies only a 4,900-element banner. The Android "Low memory" message has no task.

**N6. Solver and motion UX.** UX-36 (solve > 100 ms indicator with Cancel), UX-47 (reduced motion), the "Added Coincident"/"Removed N constraints" toasts, and the "Nothing drawn" status have no story (Story 4.1.4 has a wall-time cap but no cancel or progress).

**N7. Layers panel extras (section 8).** "Move elements to Base then delete", "Move selection here", "Isolate", active-layer concept, the overflow-menu route to the first extra layer, and the rename-collision message are not in Story 3.5.2 or the `LayerCommands` tasks.

**N8. Line finish.** ux.md section 5: Enter finishes a line; plan Story 3.2.1 specifies double-click only.

**N9. Forward and gate references.** ADR-007 links `research/input-spike.md`, which does not exist yet (Spike 0.1.4 creates it). ADR-001 flips to Accepted when Stories 0.1.1, 1.1.1 and 1.1.3 are green; the plan header lists only 0.1.1 and 1.1.1.

**N10. Naming overlaps.** ADR-006 names the z-key field `index`, plan uses `ZKey`/`zKey` (the import mapping `index` to `ZKey` is Excalidraw-side, but the ADR does not say so). ADR-001 and ADR-002 refer to "ADR-005/002/003 of home-project-sketching", which collide with this project's ADR-005/002/003. ux.md uses "sketch" for project, sheet and embed interchangeably while the plan keeps project/sheet distinct.

**N11. Requirements In Scope still lists iOS input** ("Desktop, Android, iOS, Web") although the OQ5 decision makes iOS compile-only; update the scope line.
