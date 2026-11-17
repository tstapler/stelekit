# A1 go/no-go gate (Task 6.2.4e)

## Result: NOT RUN. The gate is owner-only and has no recorded result

- Precedence of requirements.md "A1 go/no-go gate": UNMEASURED, then PIVOT, then ADJUST, then PROCEED. No outcome of the four is claimed here.
- Task 0.1.7c (the pre-check) was WAIVED by the owner on 2026-11-16 (`baselines.md`): no list of 5 diagrams, platforms or project kinds was recorded. There is therefore no recorded reachable item to draw, and the gate diagram, its platform and its project kind are UNRECORDED.
- The paired timing (in-app run, then the same diagram in excalidraw.com on the same device; finished, ratio, blocker, would-continue) has not been done. In-app time, excalidraw.com time and the ratio are UNMEASURED.
- Effect: this file releases nothing of the held set (Stories 4.2.2, 4.2.3, 4.2.5, 4.2.6b, 4.2.7b, 4.2.7d, 5.1.5, 5.2.3 and Task 6.2.5c). The gate stays open until the owner fills in the record below.

## What an agent built, and what remains owner-only

| Item | State |
|---|---|
| Rendered slice (Task 6.2.4g, `RenderedSliceScenarioTest`) | merged in W9 |
| Decision rule (`A1Gate` in `sketch/v2/diagnostics/A1Gate.kt`, `A1GateTest`): UNMEASURED, PIVOT, ADJUST, PROCEED; ratio bar `A1Gate.RATIO_BAR` = 2.0 (INFERRED, tunable) | written and tested with fixture inputs, not owner data |
| Choosing a reachable diagram, drawing it twice, timing both, naming blockers, answering would-continue | OWNER-ONLY, not done |
| Recording the result below | OWNER-ONLY, not done |

## Record (owner fills in; every line is UNMEASURED or UNRECORDED until then)

- Diagram: UNRECORDED
- Platform, browser, project kind: UNRECORDED
- Order (in-app first): UNRECORDED
- In-app time: UNMEASURED
- excalidraw.com time: UNMEASURED
- Ratio (bar: at most 2.0): UNMEASURED
- Finished: UNRECORDED
- Would continue: UNRECORDED
- Named blockers (must-fix defects): UNRECORDED
- Outcome: NOT RECORDED

## Owner waiver of the A1 gate (2026-11-17)
- The owner chose "Waive it, build them" when asked whether to hold Stories 4.2.2, 4.2.3, 4.2.5, 4.2.6b, 4.2.7b, 4.2.7d, 5.1.5, 5.2.3 and Task 6.2.5c until the gate is run.
- Effect: the held set is released and built. The gate result stays **UNMEASURED**: no paired run was timed, no ratio exists, and nothing in the product, docs or release notes may say the A1 gate passed.
- The A1 gate harness (`A1Gate`) and this record remain, so the owner can still run the paired run later and fill in the fields above.
