# Carry-over audit (Story 6.0)

Audit date: 2026-11-09. Base: `main` at `9db1a8d58e`. Scope: Epic A (A.4, A.5), Epic C (C.1-C.10), Epic D (D.11) of `project_plans/annotation-memory-and-nav-ia/implementation/plan.md`. Only commit hashes, PR numbers and file names appear here.

## Method

1. Planning docs for the carry-over lane landed in #561 (`111a9c2f73`). Everything that shipped for it afterwards is `git log --no-merges 111a9c2f73..origin/main`, minus bot commits (`chore(bench)`, Homebrew). Command output (3 commits):

   | Commit | PR | Subject |
   |---|---|---|
   | `b30e08413c` | #563 | chore(main): release 0.107.0 |
   | `641a546909` | #562 | feat(annotate): diagnostics-first PR-0 |
   | `2dc338ec60` | #560 | fix(annotate): loupe centre now shows the point under the finger |

2. Each task's signature artifact (type, file, column, test class named in the plan) was searched in `kmp/src` with `grep -rI` on this branch's tree. A miss means the task's deliverable is absent from `main`.
3. Open PRs: `gh pr diff <n> --name-only` for #564 and #565.

## Result per task

Status: SHIPPED, PARTIAL, NOT SHIPPED, IN OPEN PR (not on main).

| Task | Status | Evidence |
|---|---|---|
| A.0 (PR-0 diagnostics, context for A.4/A.5) | SHIPPED | `641a546909` (#562): `ExitRecord.kt`, `EditorDiagnosticsSampler.kt`, `PostKillBanner.kt`, `EditorErrorBanner.kt`, `LogRedactor.kt`; released in 0.107.0 (#563) |
| A.4 (a: `LoupeOverlay.kt` extract + deferred `loupeOffset`; b: label cache; c: one `ImageLoader`; d, e: probe checks) | NOT SHIPPED | No `LoupeOverlay.kt`, `EditorRecompositionTest`, `MeasurementLabelCacheTest` or `ImageLoaderConstructionCountTest` under `kmp/src`; `LoupeOverlay` is still an inline composable in `AnnotationEditorScreen.kt`. #562 touched `SteleKitAssetFetcher.kt` (+3 lines, diagnostics only). Probe-based A.4d/A.4e need the harness in #564 (open) |
| A.5 (a: spike, b: strategy, c: degradation) | NOT SHIPPED | No `LoupeStrategy`, `LoupeSource` or `LoupeDegradation` anywhere in `kmp/src`. `#556`/`#560` are alignment fixes and are not A.5 deliverables |
| C.1 (a-g calibration basis) | NOT SHIPPED | `calibration_basis_version`, `NativeImageSize`, `ImageBoundsReader` all absent from `kmp/src` (this confirms the planning-time research claim) |
| C.2 (a: `clampLoupeOffset`; b: use in overlay) | NOT SHIPPED | `clampLoupeOffset` absent. `AnnotationEditorScreen.kt:1630-1632` still uses `coerceIn(marginPx, canvasSize - loupeSize - marginPx)`, which throws when the canvas is narrower than loupe plus margins (INFERRED from reading, not reproduced) |
| C.3 (a, b: `UndoHistory`) | NOT SHIPPED | `UndoHistory` absent (`UndoEntry` only in the unrelated `BlockUndoManager.kt`) |
| C.5 (a-e: serialized persistence, actor routing) | NOT SHIPPED | No queue type; `SqlDelightImageAnnotationRepository` still documents "calls steleDatabaseQueries directly" and uses `@DirectRepositoryWrite`. Not routed through `DatabaseWriteActor` |
| C.6 (a, b: honest unsaved-changes) | NOT SHIPPED | `showUnsavedChangesDialog` / `hasUnsavedChanges` flags are the pre-plan implementation (`AnnotationEditorScreen.kt:192-194`); no "Saving..." wait or retry banner |
| C.7 (a-c: export correctness) | NOT SHIPPED | No commit since #561 touches export; no EXIF-orientation handling found under `ui/annotate` |
| C.8 (a-i: a11y, haptics, non-drag placement) | NOT SHIPPED | `AnnotationSemanticNode.kt` exists since `69f95b1874` (May, #113) but is referenced by no other file (unwired). `onHapticFeedback` is a ViewModel constructor parameter (default no-op); the screen only documents how to supply it. No `PlacementController`, `NudgeStep` |
| C.9 (a-m: loupe edges, single decode, minors) | NOT SHIPPED | Only adjacent loupe fixes: #556 (`0be6a65081`, clamp inner image), #558 (`2edea3e2f0`), #560 (`2dc338ec60`). None implements a C.9 task (C.9a edge/placeholder/handedness has no test or type) |
| C.10 (a: pointer-cancel + AREA tolerance; b: camera smoke) | NOT SHIPPED | No commit since #561 adds either test (log above) |
| D.11 (a: `Screen.Library` + aliases; b: retire old screens) | IN OPEN PR | #565 (draft, 67 files) adds `ui/library/*`, `MoreSheet`, nav tests. On main `GalleryScreen`/`AssetBrowserScreen` still exist (`ui/gallery/`, `ui/assets/`, `ScreenRouter.kt`); `LibraryScreen` absent |
| A.4/A.5 harness dependency (Epic B) | IN OPEN PR | #564 (draft, 49 files): `performance/probe/*` (not on main: `ResourceProbe` absent) |

## Rewrites to later stories

- 6.2a-6.6 keep their full task lists: nothing in C.1-C.10 shipped, so the remainder is the whole of each task. Do not shrink them.
- 6.2a (C.1 calibration basis schema) also cannot assume any earlier C.1 task (C.1a-C.1d) exists; the plan already scopes 6.2a to the `AddColumn` form.
- 6.3 (C.2): the crash path in `AnnotationEditorScreen.kt:1630-1632` is still live; implement `clampLoupeOffset` as planned.
- 6.4b (C.5) stays blocked on 2.2 and 6.1 as declared in plan Section 10.
- 6.5a: `AnnotationSemanticNode.kt` and `onHapticFeedback` exist but are unwired; C.8a/C.8b reduce to wiring tasks rather than new types (small reduction, not a rewrite of the story).
- 6.7, 6.8, 6.10: still blocked on #565; 6.9, 6.11: still blocked on #564 (ADR-009 decision 3). Neither PR has merged as of this audit.
- A.4/A.5 (not in 6.2a-6.6) remain in the carry-over lane and unchanged.

## Gaps

- Only presence of artifacts and commits was checked. No C.* behaviour was run on a device, so "NOT SHIPPED" means the deliverable is absent, not that the underlying bug reproduces (C.2's crash is INFERRED).
- A.4/A.5/C.* work could exist on unmerged local branches of the owner; only `origin` branches and PRs #564-#566 were checked.
