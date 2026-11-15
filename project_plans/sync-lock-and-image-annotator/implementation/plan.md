# Implementation Plan: sync-lock-and-image-annotator

**Feature**: Four independently shippable items: (E1) git sync single-flight and safe lock recovery, (E2) annotator one-shot tools, tool lock and a larger canvas, (E3) measurement value editing and multi-distance calibration, (E4) in-app camera lens switching with lens metadata.
**Date**: 2026-11-14 (revised twice after architecture and adversarial review, then patched once in Phase 4 after the pre-mortem, consistency, adversarial, validation and architecture re-reviews; see the Repair Log at the end, "Triad repair iteration 2" last)
**Status**: Ready for implementation (Phase 4 patch and triad repair iterations 1 and 2 applied; ADRs are "Accepted pending owner review" and the consolidated "Owner confirmations" table below is what the owner confirms; the Phase 4 gate re-reads this plan once more before Phase 5)
**ADRs**: [ADR-001](../decisions/ADR-001-sync-single-flight-gate-and-lock-recovery.md), [ADR-002](../decisions/ADR-002-measurement-value-override-vs-calibration-constraint.md), [ADR-003](../decisions/ADR-003-constraint-calibration-model.md), [ADR-004](../decisions/ADR-004-camera-session-and-lens-catalog.md), [ADR-005](../decisions/ADR-005-tool-state-policy-in-viewmodel.md), [ADR-006](../decisions/ADR-006-pasteboard-and-point-clamp-policy.md), [ADR-007](../decisions/ADR-007-annotator-gesture-semantics.md)

Path aliases (all under the repo root `/home/tstapler/Programming/stelekit`): `CM` = `kmp/src/commonMain/kotlin/dev/stapler/stelekit`, `AM` = `kmp/src/androidMain/kotlin/dev/stapler/stelekit`, `AP` = `androidApp/src/main/kotlin/dev/stapler/stelekit` (the Android `Application` lives here, not under `AM`), `JM` = `kmp/src/jvmMain/kotlin/dev/stapler/stelekit`, `JC` = `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit` (compiled for both JVM and Android), `CT` = `kmp/src/commonTest/kotlin/dev/stapler/stelekit`, `BT` = `kmp/src/businessTest/kotlin/dev/stapler/stelekit`, `JT` = `kmp/src/jvmTest/kotlin/dev/stapler/stelekit`, `AU` = `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit`. Files marked **(new)** do not exist today. Each epic lands as its own draft PR (or commit series) and is revertable alone. Labels: VERIFIED (read/run this pass), INFERRED, UNVERIFIED (must be resolved by the named task).

**Registration rule (shared files).** Workers never edit `AllBusinessTests.kt`, `AllJvmTests.kt`, `AllAndroidUnitTests.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `wiring-catalog.txt`, `wiring-allowlist.txt` or the test-baseline files. Each task lists `REGISTER: <file> <class>` lines in its handoff and one **registration integrator** applies a wave's lines at the end of that wave. The integrator is an owned, costed step (triad repair 2): one XS step per wave named `INT-W1`..`INT-W9` in the wave placement table, run by a dedicated worker after the wave's last task, editing only the registries named above; it is not a task (the 107-task count excludes it) but its 9 XS steps are counted in the effort table. Same rule for the lane files below.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `RepoOpGate` | Process-wide per-repo mutual-exclusion registry (successor of `NamedLocks`), keyed by `RepoOpGate.keyFor(gitDir)`: a hash of the **canonical path** of the resolved `.git` directory (`File.canonicalFile`), never of the remote URL | `tryAcquire`, `isHeld`, `heldBy`, `awaitFree`, `acquire` (suspending, quiesce only), `releaseOrHandOff` (non-suspending `fun`); required ctor param everywhere, never defaulted in production; key resolution failure is `Unreadable`, never a different key (ADR-001 decisions 2 and 4) |
| `ActiveRunRegistry` | In-process set of canonical record paths with a run in progress | `beginOwnedOperation` fills it, `endOwnedOperation` empties it; `ClearedOwn` is reachable only when no entry exists, so it is only ever a corpse (pre-mortem P1-1) |
| `OwnerHeartbeat` | A touch of the owner record's mtime every 5 s while a run holds the lease | The record's mtime is the upper bound for deleting a dead owner's locks (ADR-001 decision 12 step 5) |
| `MergeUnitOutcome` | Sealed `Continue(remoteCommitsMerged, autoMergedPaths)` / `Exit(Either.Left)` returned by the extracted `mergeUnit()` | Replaces a `withContext(NonCancellable)` wrapper whose `return@withContext` would rebind (pre-mortem P1-4) |
| Keep tool | UI label of the control for `ToolStickiness.LOCKED`, persisted by Settings key `annotator_sticky_tools` ("Keep tools active" row) | One concept, three spellings: prose says "Keep tool" for the control and `LOCKED` for the state; requirements.md's "tool lock" and "sticky" are the same thing (ADR-005 decision 8) |
| `GateLease` | Token proving the gate is held; released in `finally` after the blocking git call returns | Closeable, not a coroutine-scoped resource |
| `resolveGateKey` | Read-only config read returning `Resolved(key)` or `Unreadable`; no state write, no retry cancel | Unreadable fails closed: no git call under any other key (ADR-001 decision 4) |
| `SyncTrigger` | EXISTING enum `Manual` / `Automatic` (`CM/git/FirstSyncConfirmation.kt:42`); untouched | Consent and guard semantics |
| `SyncOrigin` | NEW enum UiPress / FollowUp / Scheduled / Worker / Cold / Resume / RateLimitRetry / Resolve / Repair / Deepen / Setup / Clone / Cli / Quiesce | Logging, `SyncRunInfo`, rerun; `toTrigger()` maps the five sync-calling origins |
| `SyncDecision` | Enum Started / Coalesced / Skipped | One log line per trigger |
| `SyncRunInfo` | `(origin, startedAtMs, graphId, followUpQueued)` of the run in flight | Exposed as `inFlight: StateFlow<SyncRunInfo?>` |
| `GitError.SyncInProgress` | Typed result for a coalesced trigger | A non-failure everywhere; never `SyncState.Error` |
| `RerunRequest` | One-bit flag set inside the gate lock: a UiPress arrived mid-run; one follow-up after release | Not a queue; never carries `liftMassChangeGuard` |
| `LockOwnerRecord` | `.git/stelekit-sync.owner`: instanceId, pid, processStartTicks, pidNs, host, bootId, op, origin, startedAtMs | Not a lock; published atomically (temp file + `createLink`); empty/torn/unparseable = `OwnerUnknown`; its mtime is the heartbeat |
| `InstanceId` | Random id per process start | Newtype over String |
| `LockRecovery` | Claim protocol: read record, decide, (deleting verdicts only) take the recovery lock and re-decide, act, then write ours | Pure `decide` in `CM`; `LockFiles` port implemented in `JC` |
| `RecoveryLock` | Kernel-held `FileChannel.tryLock` on the permanent `.git/stelekit-sync.recover`; only a holder may delete a dead owner's locks or record | Released by the kernel on process death; never deleted (ADR-001 decision 12) |
| `QuiesceHold` | The gate lease plus the worktree mutex taken together by `quiesce` and released together by `release` or on any exception | Task 1.3c |
| `LockVerdict` | Sealed: NoLock / ClearedOwn / ClearedDeadOwner / OwnerAlive / OwnerUnknown(reason: TornRecord, ForeignLockSuspected, RecoveryLockUnsupported, ...) / NoRecord | Exhaustive handling |
| `ProcessLiveness` | Port returning Alive / Dead / Unknown for (pid, startTicks, pidNs, host) | Unknown fails closed; Linux/Android compare raw `/proc/<pid>/stat` field 22 ticks exactly, macOS/Windows compare `startInstant` within 2 s; an absent pid in a different or unreadable pid namespace is Unknown |
| `PausedRetryPolicy` | Two automatic retries (about 30 s, about 2 min) after `OwnerUnknown`/`NoRecord`, then Failed | Published as `pausedRetry` |
| `ToolStickiness` | Enum ONE_SHOT / LOCKED on `AnnotationEditorState` | Not undoable; reset to ONE_SHOT on leaving the editor |
| `nextToolAfterCommit` | Pure function `(tool, stickiness, autoReturnEnabled) -> AnnotationTool` | Called once from `commitAnnotation` |
| `PlacementGesture` | Sealed output of `classifyPlacement`: `AimPlace(point)` / `DragMeasure(start, end)` | ADR-007; `DragMeasure` only at `viewScale >= FIT_SCALE`; layer carries no tool policy |
| `DRAG_COMMIT_DP` | 48 dp release distance above which a DISTANCE drag with no pending point is a measurement | Evaluated on release |
| `FIT_SCALE` | 1.0, the fitted view; the default transform and `isFit` use it | Decoupled from the floor |
| `MIN_VIEW_SCALE` | 0.25 (of fit), the zoom-out floor | ADR-006 |
| `Pasteboard` | View-only neutral canvas area around the image, with a frame | Never exported or persisted |
| `MeasurementValue` | Sealed domain type `Computed(m)` / `Overridden(m, originalM)` | Mapped to `ValueSource` + `originalValueMeters` columns at the repository boundary |
| `ValueSource` | Enum COMPUTED / USER_OVERRIDE (column) | Additive column + sidecar key |
| `CalibrationConstraint` | One known distance: two normalised points, `distanceMeters`, source, optional measurement uuid | `GeometryCalibration.constraints` |
| `ConstraintFitRecord` | `{tier, n, residualMeanPct, looPct, worstId, focalSource, priorBasis}` | `GeometryCalibration.fit`; `priorBasis` restores the replaced calibration |
| `PlaneKind` | Enum PAPER_REFERENCE / CONSTRAINT_FIT on `PlaneCalibration` (the fit's `method` is the existing `MANUAL_REFERENCE`) | Discriminator (not `referenceName`) |
| `ConstraintFitApplier` | Pure code that writes a fit into `Calibration`/`GeometryCalibration` per the ADR-003 table | The only writer of fit-derived fields |
| `ConstraintCalibrator` | Pure solver that fits a model tier to a constraint list | Typed failures, no number on degenerate input |
| `ModelTier` | Enum SCALE / GRADIENT / GRADIENT_AND_K, plus optional PER_AXIS (off by default) | P = 1 / 3 / 4 / 2; eligibility N - P >= 2 plus identifiability gates; GRADIENT is the focal-free first-order tilt tier, gated by a Student-t test for N - P dof with a leave-one-out veto, a tilt validity bound and a crop refusal, and **ships default-off** until the Story 3.8a real-photo study |
| `FocalSource` | Enum USER_CONFIRMED / SIDECAR_LENS_RECORD / PROFILE / EXIF_ONLY | Optional refinement only (adds `1/cos(tilt)`); never required for any tier; EXIF_ONLY and PROFILE never enable it |
| `FitReport` | Tier, N, per-constraint residual %, leave-one-out error, worst offender, mixed-surface flag | Logged and shown |
| `CalibrationFailure` | Sealed typed failures of the solver | See ADR-003 |
| `CalibrationHistory` | Named collaborator holding undo snapshots of constraints + fit | Extracted before Task 3.7a; keeps the ViewModel from growing |
| `CameraSession` | androidMain owner of camera binding and the lens-switch state machine | Single binding owner; never calls `unbindAll()` |
| `CameraLensCatalog` | commonMain port listing usable back lenses | Fake in tests |
| `CameraLens` | `(id, facing, focalLengthMm?, focal35mmEq?, zoomRatio?, isLogicalMulti, kind)` | `kind` = ULTRA_WIDE/WIDE/TELE/UNKNOWN |
| `LensKind` | Enum from the classifier | Pure function over `CameraLensInfo` fixtures |
| `CaptureLensRecord` | Camera id, physical id, focal mm, zoom ratio, `source` (PREVIEW_RESULT_AT_SHUTTER / SELECTED_LENS), `exifFocalMm` | Sidecar only |
| `CapabilityProbe` | One-time screen/log dump of camera ids, characteristics and physical-bind results | Output via `Logger` |
| `PlacementCounters` | Three persisted log counters per editor session: `placement gesture=drag-measure`, `placement gesture=aim-place`, `placement undo-after-drag-measure` (an undo within 5 s of a drag-measure commit) | Lets Task 2.7a tune `DRAG_COMMIT_DP`; log lines only, no analytics |
| `PanPad` | Four 48 dp arrows in their own pan row (never the status strip) that move a view by 25 percent of the viewport per press (WCAG 2.5.7 alternative to one-finger pan) | Available exactly when `viewScale >= FIT_SCALE` and `[Pan]` is on; same moves as TalkBack custom actions (triad repair 2: one visibility rule) |
| `RetryNow` | Enabled action in the paused sync sheet: runs one extra attempt immediately through the gate | Does not consume the automatic budget (try number unchanged, pending try re-armed on failure); never adds a retry; coalesces like a second press; "Sync now" from Failed restarts the budget |
| `StripPriority` | Pure function choosing the one status-strip message: error, tool prompt or pending state, first-three-times hint, status text, voice hint, idle | ux.md 3.5; never queues, a lower message returns when a higher one clears |
| `EditorBackPolicy` | Pure function ordering Back: modal or nudge session, then disarm (with the existing discard dialog for two or more unfinished points), then the existing leave check | ux.md 3.5; reuses `backOutcome` (`LeavePolicy.kt:24-27`) |

Total: 51 terms (counted by script: rows of this table; 42 before the Phase 4 patch, +4 in it: `ActiveRunRegistry`, `OwnerHeartbeat`, `MergeUnitOutcome`, Keep tool; +3 in triad repair 1: `PlacementCounters`, `PanPad`, `RetryNow`; +2 in triad repair 2: `StripPriority`, `EditorBackPolicy`).

---

## Pattern Decisions

Step 0.5 creative pass, per epic (strength / weakness; chosen in bold).

- E1: (a) **Layered gate + owner-record claim**: covers cold and live paths, kills, and the CLI; costs one extra file write. (b) Lock-file protocol as the mutex: cross-process but polling and pid reuse. (c) Serialize in `GitSyncService` only: smallest, misses the cold worker, quiesce and CLI.
- E2: (a) **Pure policy in the ViewModel with a policy-free gesture layer and a pure placement classifier**: testable; one more state field. (b) Sticky tools plus Done button: simple, an extra tap per measurement. (c) Policy in the gesture layer: no new state, untestable without Compose.
- E3: (a) **Fit result into existing scale/homography representations by one pure applier with a precedence table and a provenance record, with a focal-free scale-gradient tilt tier**: old builds keep measuring (existing `MANUAL_REFERENCE` method); works on existing photos; solver must stay small. (b) New calibration table: queryable, migration risk. (c) Full intrinsics via OpenCV: strongest in theory, under-determined from taps.
- E4: (a) **`CameraSession` + `CameraLensCatalog` seam on CameraX 1.4.1 (physical-id APIs already present, bind-tested per device)**: one binding owner; OEM-hidden lenses need zoom-chip fallback. (b) Raw Camera2: full control, rewrites preview/lifecycle. (c) Bump to CameraX 1.7 RC: not needed for physical binding.

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| `RepoOpGate` | Registry of mutual-exclusion tokens, try-acquire with atomic hand-off | PoEAA Pessimistic Offline Lock (Fowler), coroutines `Mutex` | Decorator lock on `GitRepository` | Wrong grain: pipelines interleave between calls |
| `SyncSingleFlight` over `GitSyncService` | Facade/Decorator at public-method grain, edits at four named pipeline seams | GoF Decorator | Refactor `sync()` body | #676/#662 guard regression risk |
| `LockRecovery` | Strategy with `ProcessLiveness` and `LockFiles` ports, JVM/Android adapters; deletions serialised by a kernel-held `RecoveryLock` | GoF Strategy, Hexagonal | Age/mtime tests in each repo; `CREATE_NEW` claim token; tombstone rename | Age is not liveness (root cause); a token or rename is not compare-and-delete |
| `LockVerdict` | Sealed interface | type-driven-design | Boolean "isStale" | Exhaustive handling, fail closed |
| `InstanceId`, `SyncRunInfo`, `SyncOrigin` | Newtype / value object / separate enum | type-driven-design | Extending `SyncTrigger` | Existing enum has 12 call sites with `== Automatic` semantics |
| Coalescing | One-bit `RerunRequest` inside the gate lock | Syncthing-style trigger coalescing (INFERRED) | Queue of triggers; bit outside the lock | Lost press race; pointless duplicate syncs |
| `ToolPolicy` | Pure function / policy object | PoEAA Transaction Script (small rule) | State machine class | One decision point, no history |
| `classifyPlacement` / `PlacementGesture` | Pure classifier + sealed event | type-driven-design | Callback per tool | Layer stays policy-free; testable |
| `ConstraintCalibrator` | Pure domain service with Either-style typed result | PoEAA Domain Service; Arrow `Either` per CLAUDE.md | Throwing exceptions | Typed failures per requirement |
| `ConstraintFitApplier` | Pure function over a precedence table | Functional core | Writes scattered through the ViewModel | One place decides which field wins |
| `ModelTier` | Enum ladder + selection by leave-one-out + identifiability gates | Occam, model selection | Always fit richest model | Over-fit with 3-4 taps |
| `MeasurementValue` | Sealed type mapped to two columns | type-driven-design | Enum + nullable pair | Illegal pairs unrepresentable |
| `CameraSession` | State machine + Adapter over CameraX | GoF Adapter | Four binders each choosing a selector | Duplicated `DEFAULT_BACK_CAMERA` |
| `LensKind` classifier | Pure function over fixtures | Functional core / imperative shell | Logic inside CameraX glue | Not testable without device |
| `SidecarJsonMerge` | Overlay of unknown keys on save | Tolerant Reader (Fowler) | Drop unknown keys | Forward-compat loss |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `CM/git/GitSyncService.kt` (806 lines; `sync()` ~240 lines) | God object, no exclusion | Isolate via seam | Add `RepoOpGate` + `SyncSingleFlight` at public-method grain (Tasks 1.4a1 and 1.4a2); pipeline body edited only at four named seams (ADR-001 decision 9) to protect PR #676/#662 guards |
| `AndroidGitRepository.removeStaleLockFile` (L663-686), `JvmGitRepository.kt:568-585`, `GitOperationSupport.deleteLockFileIfStaleForRetry` (L287) | Same age/mtime liveness logic duplicated three times; root cause of live-lock deletion | Refactor-first | Story 1.2 lands one `LockRecovery` claim protocol before the gate is built on it; `GitRepository` gains defaulted methods (strangler) |
| `NamedLocks` in `CM/git/ColdSyncRunner.kt:24`, private `ColdSyncLocks` in `AM/git/WorkManagerSyncScheduler.kt:325` | Lock registry private to the cold path; no `tryAcquire` | Extend as-is | Rename/move to `RepoOpGate`, add `tryAcquire`/`isHeld`/`awaitFree`, required ctor param, hoist instance to composition roots and `StelekitAppDependencies` |
| `CM/ui/annotate/AnnotationEditorViewModel.kt` (1510 lines) | God class: tools, placement, 3 calibration sessions, persistence, undo | Isolate via seam | New pure collaborators `ToolPolicy.kt`, `MeasurementValueEdit.kt`, `PlacementClassifier.kt`, `CalibrationHistory.kt`, `ConstraintFitApplier`, `ConstraintCalibrator`; the ViewModel gets one-line delegations only. Task 3.7a replaces the single-level snapshot **by calling `CalibrationHistory`, not by growing the ViewModel**; extraction of the three calibration sessions is a named follow-up outside this plan |
| `CM/ui/annotate/AnnotationViewTransform.kt` (171 lines), `AnnotationGestureLayer.kt` | Scale floor and Fit conflated in four places | Extend as-is | Small pure file with tests; `FIT_SCALE` introduced before the floor moves; no policy added to the layer |
| `CM/model/{GeometryCalibration,LensCalibration}.kt`, `CM/db/sidecar/ImageSidecarSchema.kt`, codecs | Single-scale model | Extend as-is | Additive JSON with `ignoreUnknownKeys`; `constraints`/`fit` also ride in the `calibration_geometry_json` DB column |
| `CM/model/MeasurementAnnotation.kt` (282 lines) | Mixes model and helpers | Extend as-is | One sealed `MeasurementValue` added; no new instance of the same violation |
| `AM/platform/sensor/AndroidCameraProvider.kt` (224 lines), `AM/ui/components/CameraViewfinderDialog.android.kt`, `AM/platform/sensor/AndroidCameraPreviewBinder.kt`, `AM/platform/sensor/AndroidCameraFrameSource.kt` | `DEFAULT_BACK_CAMERA` hard-coded 4 times; four binding owners; `unbindAll()` in two of them | Isolate via seam | `CameraSession` + `CameraLensCatalog`; preview binder's and frame source's no-`unbindAll` contract preserved |
| `expect CameraViewfinderDialog` (`CM/ui/components/CameraViewfinderDialog.kt`) | Signature carries no lens data | Extend as-is | Results ride on `PlatformImageFile` |

---

## Migration Plan

Only E3 Story 3.2 changes schema; E1, E2, E4 have none (calibration constraints and lens fields are JSON/sidecar-only; `constraints` also ride in the existing `calibration_geometry_json` column, VERIFIED `GeometryCalibration.kt:39`).

- **Migration**: `measurement_annotations` gains `value_source TEXT NOT NULL DEFAULT 'COMPUTED'` and `value_original_meters REAL`. Both the `.sq` `CREATE TABLE` (fresh databases) and `SchemaOp.AddColumn` entries in `CM/db/MigrationRunner.kt` (existing databases; same pattern as the `calibration_lens_json` columns at ~L943-953) carry them; edit `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` (table at L1063). The existing `insertMeasurementAnnotation` write stub in `CM/db/RestrictedDatabaseQueries.kt` (~L620-630, `INSERT OR REPLACE` with an explicit column list) changes **signature** and every caller/test follows; update `CM/repository/SqlDelightMeasurementAnnotationRepository.kt`. Regenerate and commit SQLDelight sources: `./gradlew :kmp:generateCommonMainSteleDatabase` then `rsync -a kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/ kmp/src/generated/sqldelight/`. No new table, so `MigrationRunnerSchemaSyncTest` is unaffected; add a column-presence test that runs on **both** a fresh and an upgraded database (that test covers tables only).
- **Sidecar**: `SidecarMeasurement` gains `valueSource`, `originalValueMeters`; `SidecarImageAnnotation.calibrationGeometry` nests `constraints` and `fit`; capture lens fields live in a new nested `SidecarLensRecord` (new file) so E3 and E4 touch `ImageSidecarSchema.kt` once each, serialized (Sidecar lane). `schemaVersion` is written as 2 only when v2 data are present, via one `SidecarVersion.forContent` helper (owned by Task 3.2c1); the reader keeps `ignoreUnknownKeys` (VERIFIED `CM/db/sidecar/ImageSidecarManager.kt:35`, no version check found by grep).
- **Reversibility**: columns are additive with defaults; rollback leaves old rows valid. SQLite column drop is not performed (irreversible by design, harmless).
- **Zero-downtime**: expand-only; nothing reads the new columns until the UI ships; old rows read as COMPUTED.
- **Rollback and downgrade (precise)**: revert the E3 PR; leftover columns are ignored by the older build. A downgrade to v0.113.3 **reads** new data without error and measures with the stored `pixelsPerMeter`/`plane` (the fitted plane shows as "Known distances"). It loses editability: any re-save drops `valueSource`, `constraints`, `fit` and `PlaneCalibration.kind`, and any calibration change there recomputes every measurement by the old rules, so overrides are lost. "Measurements remain correct" is true only until such a re-save. A frozen-copy decode test asserts this (Task 3.2c2).

## Observability Plan

- **Logs** (via `Logger`, persisted sink; owner reads the in-app Logs screen):
  - E1: `sync origin=<ui|followup|scheduled|worker|cold|resume|retry|...> decision=<started|coalesced|skipped> graph=<id>`; `lock verdict=<ClearedOwn|ClearedDeadOwner|OwnerAlive|OwnerUnknown|NoRecord> file=<name> owner=<instanceId> reason=<...>`; `gate acquire|release key=<hash> heldMs=<n>`; `rerun requested|started`; `paused retry n=<1|2> inMs=<n>`. A coalesced trigger is never logged at error level.
  - E2: `tool commit tool=<x> next=<y> stickiness=<s>`; `placement gesture=<aim|drag> tool=<x>`; low-zoom clamp events not logged.
  - E3: `calibration fit tier=<t> n=<N> residualMean=<%> loo=<%> worst=<id> focal=<source>`; `value override measurement=<uuid>`.
  - E4: `camera lens list=[id:focal:kind...]`, `camera bind lens=<id> result=<ok|fail:...> ms=<n>`, `capture lens id=<..> physical=<..> focal=<..> zoom=<..> source=<..>`; `CapabilityProbe` dump including per-physical-id bind results.
- **Metrics**: none new beyond logs (no alerting infrastructure per requirements). Operations over 100 ms that log duration: sync gate held time (`heldMs`), lens switch time, ConstraintCalibrator fit time.
- **Alerts**: no new alerts required.

## Risk Control

- **Feature flags**: E1 none (fail closed, guarded by test matrix). E2 Settings `annotator_auto_return_to_select` (default true), `annotator_sticky_tools` (default false), each with a Settings row (Task 2.2c) because the toggle is the rollback for the default flip. E3 Settings `calibration_gradient_tier` (default **off**, `ConstraintTuning.GRADIENT_DEFAULT_ON = false` until the Story 3.8a real-photo study; SCALE-with-LOO is the shipped default meanwhile); the edit flow is otherwise explicit per action. E4 none; falls back to the current single-lens behaviour when the catalog returns one lens.
- **Rollback**: each epic is its own draft PR; revert the PR. Previous release v0.113.3 stays installable.
- **Series that must not be split (triad repair 2).** Tasks 1.3d1 to 1.3d4 land in ONE PR and are never bisected, cherry-picked or released between 1.3d1 and 1.3d2: after 1.3d1 the nullable `repoOpGate` is null in the roots and `buildGitSyncService` returns null (no fallback), so no `GitSyncService` can be built in a running app until 1.3d2 supplies the gate, and 1.3d3 is needed for the test sources to compile. `git bisect` over E1 must skip commits strictly between 1.3d1 and 1.3d4 (mark them untestable).
- **PR merge order across epics.** E4 Task 4.5a1 depends on E3 sidecar Task 3.2c2 (Sidecar lane), so E4's PR merges after the E3 PR (or after E3's sidecar commit series); E1 and E2 are independent of both.
- **Staged rollout**: full rollout on merge; the owner decides when to merge or release (requirements). PRs open as drafts.
- **Fail closed (E1)**: unknown lock owner or unreadable liveness means "another sync may be running" and nothing is deleted.
- **Done means CI-green vs owner-verified.** Stories are closed "CI-green" by tests. These close "owner-verified" only after Tyler's device run and are tracked as PR checklists, not as blockers for the CI-green merge of the data-safety fix: 1.8c (force-stop, `/proc`), 2.7a (12 MP jank on the phone), 3.8a (owner photos), 4.1b/4.7b (camera). E1 may ship after CI-green with 1.8c as a follow-up: **1.8c stays an open, unticked PR-checklist item after the CI-green merge** (it is not removed or marked done by the merge), and until it is ticked every unreadable liveness probe stays `Unknown` (fail closed), so the unverified Android `/proc` behaviour can only produce amber, never a delete on a guess (pre-mortem #13).

## Scope additions traced to requirements (consistency CONCERN 4)

| Item | Why it is in scope | Requirement line |
|------|--------------------|------------------|
| Task 1.2e `SyncMain` CLI claim | VERIFIED an ungated cross-process writer: `SyncMain.kt:59` builds its own `JvmGitRepository` and runs stage/commit/fetch/merge/push with no gate and no record, so it can run alongside the desktop app and break Success Metric 1 ("no run leaves a lock", "second press produces zero new index.lock") and the "index.lock handling" rabbit hole. In scope as a **data-safety fix**, not a feature; the adversarial review shows the cross-process case is the one the in-process gate cannot cover. | requirements.md Success Metric 1, Rabbit Holes (index.lock), Scope item 1 |
| Task 3.9b lens-id profile matching | **Kept as an optional refinement**, tied to "record lens/focal length in the photo's metadata/annotation **for calibration use**" (Scope item 4). It reuses the existing `CameraProfile` set (no new profile database, so it does not touch the Out of Scope "per-camera factory distortion profile databases"), is a no-op without a `CaptureLensRecord`, and never auto-selects from a `SELECTED_LENS` record (ADR-004 decision 5). Droppable alone without touching any other task. | requirements.md Scope item 4 |
| Task 2.2c Settings rows | The two toggles are the rollback path for the default flip. | requirements.md Risk Control ("auto-return-to-Select default on; sticky-tool lock opt-in" behind a per-feature Settings toggle) |
| `calibration_gradient_tier` setting | Lets the owner try the default-off GRADIENT tier on their own photos for the Story 3.8 study. | requirements.md Success Metric 3 (verification photo set), Risk Control (additive schema) |

## Owner confirmations (consolidated)

All seven ADRs stay "Accepted pending owner review"; the owner accepts them by confirming or overriding the rows below. Every row has a default that the plan builds, none blocks CI-green work, and each "if no" column names the smallest change. OC ids are referenced from `design/ux.md`.

| OC | Decision (ADR) | Default (what is built) | If the owner says no |
|----|----------------|-------------------------|----------------------|
| OC-1 | ADRs 001-007 status | Accepted pending owner review; confirming this table accepts them | Reopen the named ADR only; the others stand |
| OC-2 | A lock that postdates the dead owner's last heartbeat takes the amber path (retry, then manual confirm) instead of auto-clearing (ADR-001 decision 12 step 5) | Amber; fail closed | Auto-clear after a longer quiet window: re-adds the foreign-lock delete risk; change step 5 bound and `ForeignLockNewerThanDeadRecordTest` |
| OC-3 | Paused sync: two automatic retries (about 30 s, 2 min), then red; plus enabled Retry now (ADR-001 decision 14) | As stated; Retry now is an extra attempt that does not consume the automatic budget, and "Sync now" from Failed restarts the budget (triad repair 2; supersedes "counts as the current try") | Change the `PausedRetryPolicy` constants; make Retry now consume a try by one line in `PausedRetryPolicy`; remove Retry now by deleting one row in `SyncStatusSheetModel` |
| OC-4 | Auto-return to Select on by default; Keep tool off, under Advanced (ADR-005) | On / off | Flip the two Settings defaults (constants in Task 2.2c) |
| OC-5 | Drag-measure only at or above Fit; aim-place below (ADR-007 decision 1) | Gated by `viewScale >= FIT_SCALE` | Allow at every zoom: drop the gate in `classifyPlacement` and one property test |
| OC-6 | `DRAG_COMMIT_DP = 48`, a PROPOSED threshold tuned from `PlacementCounters` on the owner's thumb (Task 2.7a) | 48 dp | One constant |
| OC-7 | Canvas laid out inside the system-gesture inset on Android (ADR-006 decision 6); costs about 2 x 24-32 dp at Fit | Applied | Full-bleed canvas: the edge pixel then needs zoom-out; drop the inset layout in Task 2.4c |
| OC-8 | A second finger keeps an already-placed first point and the tool stays armed (ADR-007 decision 4) | Keep A, stay armed | Discard A: one branch in `AnnotationGestureLayer` |
| OC-9 | Off-canvas or back-gesture release cancels only the press and keeps the tool armed (ADR-007 decision 4; supersedes the earlier default that returned to Select) | Stay armed; only tap-active-chip, Select, Back/Esc disarm | Return to Select on that one row: the `disarmOnOffCanvas` constant and one test |
| OC-10 | Value editor wording "Use as known distance" / "Change label only" and the one preselection rule (ux 6.1, ADR-002) | As stated | Wording and rule live in `ux.md` 6.1 and Task 3.5a only |
| OC-11 | GRADIENT tier ships default-off until the Story 3.8a study; PER_AXIS off (ADR-003) | Off; SCALE with leave-one-out is the default | Default-on: flip `ConstraintTuning.GRADIENT_DEFAULT_ON`; reduces protection against a worse-but-trusted fit |
| OC-12 | App-level mic FAB hidden while the annotation editor is open, so voice capture is unavailable there (Task 2.5c); the idle strip says "Voice notes: go Back to use the mic" the first three times (UX-70) | Hidden, with the hint | Keep the FAB: Task 2.5c becomes an inset rework of the second toolbar row and the hint is dropped |
| OC-13 | SM3b accuracy tolerance: held-out error at most 2 percent of the true length (0.5 cm floor under 25 cm), PROPOSED | 2 percent / 0.5 cm | Change the threshold in `AccuracyStudyVerdictTest` and requirements; no code |
| OC-14 | SM2 and SM4 thresholds: image at most 25 percent of viewport width at the floor; lens switch median at most 1,000 ms and worst case at most 2,000 ms; p95 frame time at most 16.7 ms with jank under 5 percent, PROPOSED | As stated | Change numbers in requirements and the owner checklists |
| OC-15 | Camera: zoom-chip-first; physical-id lenses only when bind-tested; `SELECTED_LENS` records never auto-select a profile (ADR-004) | As stated | Physical-id first would trust an unprobed OEM behaviour; not recommended |
| OC-16 | Usage scenario and frequency in requirements (5 to 15 measurements per photo, several sessions a week) | INFERRED text | Edit the scenario; sizing does not change |
| OC-17 | Accessibility additions: Pan pad, crosshair custom action for TalkBack, second-row wrap at 360 dp / 200 percent font / RTL (UX-53, UX-54, UX-62) | Built | Dropping any one fails WCAG 2.5.7 or leaves TalkBack unable to place a point; not recommended |
| OC-18 | Sticky-tool Settings row advanced-only (existing product call) | Advanced | Move the row; no logic change |
| OC-19 | Value editor scope (triad repair 2, ux.md 6.0, ADR-002 decision 6a): opens for Distance only; tapping an Area or Angle value selects it with "Only distance values can be edited"; the editor's unit chip sets that entry's unit only, never the global display unit | Distance only; entry-unit chip | Extend to Area and Angle (same "Change label only" choice, plus "known area" for Area): adds a value kind to `MeasurementValue` and one editor variant per kind (about one M task each) |

**PR merge order (triad repair 2).** E1, E2 and E3 are independent PRs. E4's PR merges after the E3 PR that contains sidecar Task 3.2c2, because E4 Task 4.5a1 extends the same `ImageSidecarSchema.kt` (Sidecar lane); whichever lands second rebases.

## Unresolved Questions

- [ ] Does forced cancel of a blocking JGit call leave a lock? Resolved by Task 1.1b (real-JGit test) before Task 1.5a. Owner: implementer. (JVM only; the Android shadow path is covered by the owner checklist 1.8c.)
- [ ] `/proc/sys/kernel/random/boot_id` on the owner's Android (AOSP made it world-readable in 2017, Bug 69294418, so API 28+ is expected; Android 8.x may deny): if unreadable the record uses `Settings.Global.BOOT_COUNT`, else null (falls through to the pid/start-tick probe). Resolved by the Task 1.8c device check. Owner: Tyler.
- [ ] `/proc/<pid>` readability for a dead same-uid process on the owner's Android version (API 33+): ENOENT must mean Dead, EACCES must mean Unknown. Resolved by on-device check in the E1 owner checklist (Task 1.8c); until then fail closed (every force-stop yields amber then, after two retries, a manual confirm). On JVM, `ProcessHandle.info().startInstant()` may be empty (containers, restricted `/proc`), which also yields Unknown; named in the desktop AC. Owner: Tyler (device).
- [x] WorkManager worker vs foreground process: RESOLVED. No `android:process` in `androidApp/src/main/AndroidManifest.xml` or `kmp/src/androidMain/AndroidManifest.xml` (grep, 0 hits); one process. If a library manifest merged in adds one, Task 1.1c re-checks the merged manifest. The JVM CLI (`SyncMain`) IS a separate process and is covered by ADR-001 decision 13.
- [ ] Does anything construct `GitSyncService` twice per graph (research H5)? Resolved by Task 1.1d before Story 1.4 (`GitSyncServiceRegistry` may be an `object`; the task says so). Owner: implementer.
- [ ] Which existing tests assert or depend on a `DISTANCE` default tool, or commit several measurements after one `selectTool`? Resolved by Task 2.1a (starting list in ADR-005 Consequences). Owner: implementer.
- [x] Hit-test radius is screen px or image px at low scale? RESOLVED: screen px, dp-derived (`AnnotationHitTest.kt:19-30`, gesture layer L119-120); no code change, property test only (ADR-006 decision 5).
- [ ] Which Compose host draws the app-level mic FAB and what flag can hide it inside the editor? Resolved by Task 2.1a, applied by Task 2.5c. Owner: implementer.
- [ ] Number N of known distances for Success Metric 3 and tier thresholds (LOO margin, direction/position/radial spread gates, noise-floor). Provisional: SCALE with LOO needs N >= 3 non-collinear, GRADIENT (P = 3, focal-free) N >= 5, GRADIENT_AND_K (P = 4) N >= 6, optional PER_AXIS (P = 2) N >= 4 (N - P >= 2) plus the ADR-003 position/radial/direction spread and significance gates. These N are **provisional per tier** (the requirements said "set in research"; research did not set it) and are finalised only by the owner-run Story 3.8a study; requirements.md Success Metric 3 now states that dependency. Blocks Story 3.8 acceptance only. Owner: Tyler (photos + ground truth).
- [x] Does `PlaneCalibration.homography` map undistorted native pixels to metres so a fitted homography can reuse it? RESOLVED (VERIFIED `LensMeasurement.kt:42-68`). Remaining: how the height offset composes with a plane (Task 3.2a reads `HeightOffset` and pins it with a test).
- [ ] Which lenses/ids does the owner's phone expose; does `setPhysicalCameraId` bind per physical id; does the session capture callback deliver `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`; does EXIF `FocalLength` reflect the selected lens? Resolved by Story 4.1 probe run by Tyler (Task 4.1b). **It does not block Task 4.3b**: 4.3b (W3) is zoom-chip-first (zoom-ratio stops from the device zoom range plus Camera2 characteristics) and lists a physical-id lens only if its `setPhysicalCameraId` bind succeeds at runtime on that device, so the physical-id path is UNVERIFIED until the probe run and is never assumed. 4.1b gates 4.5b's record source, the 4.2 threshold tightening and 4.7 acceptance (the W5/W6 tasks), and is acceptance-only for 4.3b.
- [ ] Is a global Settings key for sticky tools wanted prominently, or advanced-only? Product call, default advanced-only (the row exists either way). Owner: Tyler; does not block.

## Dependency Visualization

```
                        (all four epics independent; each is its own PR)

E1 Sync safety (FIRST)               E2 Annotator interaction          E4 Camera lenses             E3 Measurements
 1.1 repro tests                      2.1 audit                          4.1 probe (device) --+        3.1 harness+golden
 1.2 LockRecovery claim --+           2.2 ToolPolicy + VM (VM lane)      4.2 classifier ------+        3.2 model+migration (sidecar lane)
 1.3 RepoOpGate+quiesce --+--> 1.4    2.3 gesture classifier + layer     4.3 CameraSession ---+--> 4.4 UI   3.3 solver + applier
 1.4e1-3 SyncInProgress --+     |      2.4 view transform (FIT_SCALE)     4.5 metadata+EXIF --+ 4.6 binders  3.4 override --> 3.5 editor sheet
        1.4a1..d2 service gate --+> 1.5 cancel + 1.6 callers               4.7 wiring+owner                    3.6 pill+sheet --> 3.6d screen hooks
        1.7 UI (needs 1.4b)  --> 1.8 guard                               2.5 layout --> 2.6 a11y --> 2.7     3.7 history/undo (VM lane) --> 3.8 study (owner)
                                                                                                              3.9a-c optional focal refinement (gates nothing)

Cross-epic edges:
  E4.5a lens record (sidecar) --optional input--> E3.9b (lens-id profile match) and E3.9c FocalSource SIDECAR_LENS_RECORD (E3 and every tier work without it; the focal refinement is off by default)
  E3.2c sidecar schema --file order--> E4.5a --> E3.9c (Sidecar lane: all edit ImageSidecarSchema.kt/ImageSidecarManager.kt/ImageAnnotation.kt/MeasurementAnnotation.kt; whichever PR lands second rebases)
  E2.5 (controls leave image area) --UI space--> E3.5/E3.6 sheets
Lanes (same-file serialization, one worker per lane, in this order; recomputed after the triad repair 1 splits):
  VM lane        AnnotationEditorViewModel.kt : 2.2b1 -> 2.3b -> 3.4b -> 3.7a -> 3.6c   (2.2b2/2.2b3 edit test files only and are not in the lane)
  Screen lane    AnnotationEditorScreen.kt (+ AnnotationToolbar.kt): 2.3e -> 2.4c -> 2.5a -> 2.5b -> 2.6a -> 2.6c -> 3.6d   (2.6c edits only the shortcut map in AnnotationToolbar.kt, W8, after 2.6a; 3.6d edits only the Screen)
  Service lane   GitSyncService.kt            : 1.3d1 -> 1.4a1 -> 1.4a2 -> 1.4b -> 1.4c -> 1.5a -> 1.5b -> 1.7c   (1.3d2/1.3d3/1.3d4 and 1.4d1/1.4d2 do not edit GitSyncService.kt; 1.3d2-4 run after 1.3d1 inside W3, 1.4d1 -> 1.4d2 run after 1.4c inside W6)
  Claim lane     LockRecovery.kt              : 1.2a2 -> 1.2c1 -> 1.2c2   (1.2a2 W2, 1.2c1 and 1.2c2 W3 in order)
  Calibrator lane ConstraintCalibrator.kt, ConstraintTuning.kt : 3.3a -> 3.3b1 -> 3.3b2 -> 3.3b3   (W3, W4, W5, W6; 3.3c edits only the applier file)
  Sidecar lane   ImageSidecarSchema.kt, ImageSidecarManager.kt, ImageAnnotation.kt, MeasurementAnnotation.kt : 3.2a -> 3.2c1 -> 3.2c2 -> 4.5a1 -> 4.5a2 -> 3.9c   (E3 before E4 is a hard Phase-4 gate: 3.2c2 W3 -> 4.5a1/4.5a2 W4 -> 3.9c W6; whichever PR lands second rebases)
  Gesture lane   AnnotationGestureLayer.kt, AnnotationNonDragMode.kt, AnnotationPlacement.kt : 2.3b -> 2.3d (2.3d may not edit the Screen or ViewModel: if the handle-tap entry point lives there it hands that one-line edit to the Screen-lane or VM-lane worker as a REGISTER-style note)
  Repo lane      GitRepository.kt + implementers : 1.2d1 -> 1.2d2 (both before 1.4a1)
  Cold/launcher lane  ColdSyncRunner.kt, WorkManagerSyncScheduler.kt, JvmGitCloneWorkerLauncher.kt : 1.3b1 -> 1.3b2 -> 1.4d2
  Quiesce lane   GraphMoveQuiesceStrategy.android.kt, GitShadowFlushActor.kt : 1.3c only (1.3b2 threads construction at the roots, after 1.3c)
  Camera lane    CameraViewfinderDialog.android.kt / CameraSession.kt : 4.1a -> 4.4a -> 4.5b -> 4.4c -> 4.6a
  Registry       all test registries and catalogs : integrator per wave (see Registration rule)

Counting rule for "3-5 files per task": hand-written source and test files named in the task's Files line; generated output (the SQLDelight rsync tree), test resource fixtures and a file edited only by a scripted codemod are not counted. Task 1.5a lists exactly five hand-written files (the cap). The one task above five hand-written files is Task 1.3d3 (a scripted `sg` codemod over 24 test files, justified there with its verification method); the per-task Files lists were re-counted by script after the triad repair 1 splits (the script is described in the Repair Log).

Agent waves (what runs concurrently; tasks in the same wave touch disjoint files except where a lane is named, in which case lane tasks run one after another inside the wave range). Rule: a task never uses a type or method that a task in the same or a later wave introduces, unless the two are in one named lane and ordered inside the wave; where two tasks in one wave are coupled they are ordered inside the wave or split across waves.
- **W1 (4 parallel streams)**: 1.1a, 1.1b, 1.1c, 1.1d, 2.1a, 3.1a, 4.1a. 4.1a builds the probe so the owner can run it while later waves proceed.
- **W2**: 1.2a1 -> 1.2a2 (Claim lane; 1.2a1 defines the `LockFiles`/`ProcessLiveness` ports, 1.2a2 `LockVerdict` and `decide`), 1.3a, 1.4e1; 2.2a, 2.3a, 2.4a; 3.1b, 3.2a, 3.4a; 4.2a, 4.3a. 4.1b (owner probe run) can happen any time after W1; it gates acceptance, the W5 task 4.5b's record source and the 4.2 threshold tightening, not 4.3b.
- **W3**: 1.2b (adapters, after 1.2a1), 1.2c1 -> 1.2c2 (Claim lane: claim logic against the port with a fake `LockFiles` in `CT`; 1.2c1 holds a fail-closed placeholder for the foreign-lock step until 1.2c2); 1.3c (quiesce lane), 1.3d1 -> 1.3d2, 1.3d3, 1.3d4 (Service lane first; 1.3d1 adds the constructor parameter and nullable root field, 1.3d2 builds the gate in the roots, 1.3d3 is the scripted codemod, 1.3d4 creates `GateAssertingGitRepository`, the no-fallback audit and the wired fixtures; 1.8b only makes the fake the default fixture), 1.4e2, 1.4e3 (after 1.4e1, disjoint files); 2.2b1 (VM lane) -> 2.2b2, 2.2b3 (test-file repair, after 2.2b1), 2.4b; 3.2b, 3.2c1 -> 3.2c2 (Sidecar lane), 3.3a (defines every `CalibrationFailure` variant), 3.6a, 3.7c, 3.9a; 4.2b, 4.3b (zoom-chip first), 4.5c.
- **W4**: 1.2d1 -> 1.2d2 (Repo lane), 1.2f (real-directory claim tests via `LockRecovery.claim`, needs 1.2b, 1.2c1, 1.2c2, all W3), 1.3b1 -> 1.3b2 (after 1.3c and 1.3d4; roots, scheduler factory, quiesce construction); 2.2c, 2.3b (VM lane and Gesture lane); 3.3b1 (Calibrator lane, after 3.3a), 3.3c (after the 3.2a pinning test and the 3.3a failure variants), 3.3d, 3.5a; 4.4a, 4.5a1 -> 4.5a2 (Sidecar lane, after 3.2c2).
- **W5**: 1.2e (`SyncMain`, after 1.2d2), 1.2g (repository-level claim tests, after 1.2d1/1.2d2), 1.4a1 -> 1.4a2 (Service lane, after 1.3d1); 2.3c, 2.3d (Gesture lane, after 2.3b), 2.3e and 2.4c (Screen lane); 3.3b2 (Calibrator lane), 3.4b (VM lane), 3.5c, 3.6b; 4.4b, 4.5b (Camera dialog lane, after 4.4a and the 4.1b result).
- **W6**: 1.4b -> 1.4c (Service lane), 1.4d1 -> 1.4d2, 1.7a; 2.5a -> 2.5b (Screen lane), 2.5c; 3.3b3 (Calibrator lane), 3.5b, 3.7a (VM lane), 3.9c (Sidecar lane, after 4.5a2); 4.4c, 4.6a, 4.7a.
- **W7**: 1.5a -> 1.5b (Service lane), 1.6a, 1.6b, 1.7b, 1.7c; 2.6a (Screen lane); 3.6c (VM lane), 3.7b, 3.8a (owner photos), 3.9b; 4.7b (owner).
- **W8**: 1.8a, 1.8b; 2.6b, 2.6c; 3.6d (Screen lane), 3.6e.
- **W9**: 1.8c (owner), 2.7a, then each epic's verification gate.

**Wave placement table (machine-checkable, triad repair 2).** One row per task: each of the 107 tasks appears in exactly one row and one wave (the prose above is explanatory; this table is the placement of record, and a script compares its ids with the task headings and checks that no dependency sits in a later wave). `depends-on` lists lane predecessors and the explicit "after" and "needs" edges; two tasks of one wave are ordered by it. The nine `INT-Wn` rows are the owned registration integrator steps (XS each, not tasks; counted in the effort table).

| task id | wave | lane | depends-on |
|---|---|---|---|
| 1.1a | W1 | - | - |
| 1.1b | W1 | - | - |
| 1.1c | W1 | - | - |
| 1.1d | W1 | - | - |
| 1.2a1 | W2 | - | - |
| 1.2a2 | W2 | Claim | 1.2a1 |
| 1.2b | W3 | - | 1.2a1 |
| 1.2c1 | W3 | Claim | 1.2a2 |
| 1.2c2 | W3 | Claim | 1.2c1 |
| 1.2d1 | W4 | Repo | - |
| 1.2d2 | W4 | Repo | 1.2d1 |
| 1.2e | W5 | - | 1.2d2 |
| 1.2f | W4 | - | 1.2b, 1.2c2 |
| 1.2g | W5 | - | 1.2d1, 1.2d2 |
| 1.3a | W2 | - | - |
| 1.3b1 | W4 | Cold | 1.3c, 1.3d4 |
| 1.3b2 | W4 | Cold | 1.3b1 |
| 1.3c | W3 | Quiesce | - |
| 1.3d1 | W3 | Service | - |
| 1.3d2 | W3 | - | 1.3d1 |
| 1.3d3 | W3 | - | 1.3d1 |
| 1.3d4 | W3 | - | 1.3d1 |
| 1.4a1 | W5 | Service | 1.2d2, 1.3d1, 1.3d3, 1.4e1 |
| 1.4a2 | W5 | Service | 1.4a1 |
| 1.4b | W6 | Service | 1.4a2 |
| 1.4c | W6 | Service | 1.4b |
| 1.4d1 | W6 | - | 1.4c |
| 1.4d2 | W6 | Cold | 1.3b2, 1.4d1 |
| 1.4e1 | W2 | - | - |
| 1.4e2 | W3 | - | 1.4e1 |
| 1.4e3 | W3 | - | 1.4e1 |
| 1.5a | W7 | Service | 1.1b, 1.4c |
| 1.5b | W7 | Service | 1.5a |
| 1.6a | W7 | - | 1.4a2, 1.4e3 |
| 1.6b | W7 | - | 1.4a2, 1.4e2 |
| 1.7a | W6 | - | 1.4b |
| 1.7b | W7 | - | 1.7a |
| 1.7c | W7 | Service | 1.5b |
| 1.8a | W8 | - | 1.4d2 |
| 1.8b | W8 | - | 1.3d4, 1.4a1 |
| 1.8c | W9 | - | 1.8b |
| 2.1a | W1 | - | - |
| 2.2a | W2 | - | - |
| 2.2b1 | W3 | VM | - |
| 2.2b2 | W3 | - | 2.2b1 |
| 2.2b3 | W3 | - | 2.2b1 |
| 2.2c | W4 | - | 2.2b1 |
| 2.3a | W2 | - | - |
| 2.3b | W4 | Gesture | 2.2b1, 2.3a |
| 2.3c | W5 | - | 2.3b |
| 2.3d | W5 | Gesture | 2.3b |
| 2.3e | W5 | Screen | 2.3a, 2.3b |
| 2.4a | W2 | - | - |
| 2.4b | W3 | - | 2.4a |
| 2.4c | W5 | Screen | 2.3e, 2.4a |
| 2.5a | W6 | Screen | 2.4c |
| 2.5b | W6 | Screen | 2.5a |
| 2.5c | W6 | - | 2.1a |
| 2.6a | W7 | Screen | 2.5b |
| 2.6b | W8 | - | 2.6a |
| 2.6c | W8 | Screen | 2.6a |
| 2.7a | W9 | - | 2.4c |
| 3.1a | W1 | - | - |
| 3.1b | W2 | - | 3.1a |
| 3.2a | W2 | Sidecar | - |
| 3.2b | W3 | - | 3.2a |
| 3.2c1 | W3 | Sidecar | 3.2a |
| 3.2c2 | W3 | Sidecar | 3.2c1 |
| 3.3a | W3 | Calibrator | - |
| 3.3b1 | W4 | Calibrator | 3.3a |
| 3.3b2 | W5 | Calibrator | 3.3b1 |
| 3.3b3 | W6 | Calibrator | 3.3b2 |
| 3.3c | W4 | - | 3.2a, 3.3a |
| 3.3d | W4 | - | 3.3a |
| 3.4a | W2 | - | - |
| 3.4b | W5 | VM | 2.3b, 3.4a |
| 3.5a | W4 | - | 3.4a |
| 3.5b | W6 | - | 3.5a |
| 3.5c | W5 | - | 3.5a |
| 3.6a | W3 | - | - |
| 3.6b | W5 | - | 3.6a |
| 3.6c | W7 | VM | 3.7a |
| 3.6d | W8 | Screen | 2.6c |
| 3.6e | W8 | - | 3.6b |
| 3.7a | W6 | VM | 3.3c, 3.4b, 3.7c |
| 3.7b | W7 | - | 3.7a |
| 3.7c | W3 | - | - |
| 3.8a | W7 | - | 3.1b, 3.3b3 |
| 3.9a | W3 | - | - |
| 3.9b | W7 | - | 3.9a |
| 3.9c | W6 | Sidecar | 4.5a2 |
| 4.1a | W1 | Camera | - |
| 4.1b | W2 | - | 4.1a |
| 4.2a | W2 | - | - |
| 4.2b | W3 | - | 4.2a |
| 4.3a | W2 | - | - |
| 4.3b | W3 | - | 4.3a |
| 4.4a | W4 | Camera | 4.1a |
| 4.4b | W5 | - | 4.4a |
| 4.4c | W6 | Camera | 4.5b |
| 4.5a1 | W4 | Sidecar | 3.2c2 |
| 4.5a2 | W4 | Sidecar | 4.5a1 |
| 4.5b | W5 | Camera | 4.1b, 4.4a |
| 4.5c | W3 | - | - |
| 4.6a | W6 | Camera | 4.3b, 4.4c |
| 4.7a | W6 | - | 4.4c |
| 4.7b | W7 | - | 4.7a |
| INT-W1 | W1 | Registry | last task of W1 |
| INT-W2 | W2 | Registry | last task of W2 |
| INT-W3 | W3 | Registry | last task of W3 |
| INT-W4 | W4 | Registry | last task of W4 |
| INT-W5 | W5 | Registry | last task of W5 |
| INT-W6 | W6 | Registry | last task of W6 |
| INT-W7 | W7 | Registry | last task of W7 |
| INT-W8 | W8 | Registry | last task of W8 |
| INT-W9 | W9 | Registry | last task of W9 |

Phase-4 gate (architecture R6): Task 3.2a (reads how `HeightOffset` composes with a plane and pins it with a test) is W2 and 3.3c is W4, so the dependency holds; the validator must keep it as an explicit gate. Triad repair 1 gate: the `CroppedOrZoomedFrame` and `TiltTooLarge` variants are defined in Task 3.3a (W3), a wave before 3.3c (W4) refuses results carrying them and before 3.3b2/3.3b3 produce them.
Critical path in waves: E1 is 9 waves deep (1.1 -> 1.2a1/a2 -> 1.2c1/c2 -> 1.2d1/d2 -> 1.4a1/a2 -> 1.4b..d2 -> 1.5a/1.6/1.7 -> 1.8); E2's Screen lane is 8 (2.3e -> ... -> 2.6a -> 3.6d); the other epics are 6 to 8. Wall-clock blockers set the calendar (see Effort estimate).

---

## Phase 1: Epic 1 — Sync single-flight and safe lock recovery (data safety; do first)

### Epic 1.1: One gate, one recovery, no cancellation-driven overlap
**Goal**: A second sync trigger never cancels, restarts, or errors the running sync; no lock is deleted unless it is ours or its owner is provably dead; unknown fails closed.

#### Story 1.1: Reproduce on today's code (tests first, plus open questions)
**As a** maintainer, **I want** failing regression tests and the unresolved facts answered, **so that** the fix is proven against the real defect.
**Acceptance Criteria**:
- Overlap test fails on today's code and later passes (Success Metric 1).
  - *Given* a `FakeGitRepository` whose `commit` suspends on a `CompletableDeferred` and holds a real `index.lock` file for the test duration, *When* `sync(Manual)` is started and a second `sync(Manual)` is called 0.3 s of virtual time later, *Then* the second returns `Left(GitError.SyncInProgress)`, `removeStaleLockFile`/`beginOwnedOperation` is called 0 times for the second call, `syncState` never becomes `Error`, and max concurrent repository calls is 1.
- Coalesce during a state-writing phase (adversarial finding: a press reaching step 3a2).
  - *Given* the first sync is in `Merging` (repository mid-merge, so `guards.repositoryState` would refuse) and separately a run is in `Fetching` while the network monitor flips to offline and the vault locks, *When* a second `sync(Manual)` arrives, *Then* it returns `SyncInProgress` and `syncState` is unchanged (not `Error(RepairNeeded)`, `Error(Offline)` or `CredentialVaultLocked`).
- Live lock older than 60 s is not deleted.
  - *Given* a `.git/index.lock` last modified 90 s ago held by a fake in-flight run, *When* a second trigger arrives, *Then* the file still exists.
- Forced cancel (open question 2).
  - *Given* a real `JvmGitRepository` on a temp repo with 2,000 staged files, *When* the coroutine running `add`+`commit` is cancelled mid-call and joined, *Then* the test records whether `index.lock` remains after the call returns (expected: absent) and the gate-held-until-return assertion in 1.5a is parameterised by that result.
**Files**: `BT/git/SyncSingleFlightRegressionTest.kt` (new), `BT/git/GitSyncServiceTestFixtures.kt`, `JT/git/JgitCancelLockTest.kt` (new)

##### Task 1.1a: Overlap, coalesce-during-state-write and >60 s live-lock tests against a gate-aware fake (S)
- Extend `GitSyncServiceTestFixtures.kt` with `FakeGitRepository` that records overlapping calls, can block `commit`/`fetch`/`merge` on a deferred, and creates/removes a real lock file in a temp dir. Write the tests above; confirm they fail on current `main` (run output pasted into the PR).
- Files: `BT/git/GitSyncServiceTestFixtures.kt`, `BT/git/SyncSingleFlightRegressionTest.kt`. REGISTER: `AllBusinessTests.kt`.

##### Task 1.1b: Real-JGit forced-cancel test (S)
- In `JT/git/JgitCancelLockTest.kt` drive `JvmGitRepository` against a temp repo (2,000 small files), cancel the job mid-`commit`, `join`, assert no `*.lock` under `.git` (record the actual result as the answer to Open Question 1). No `Thread.sleep`; gate on a `CountDownLatch`/progress hook. Add `JgitCancelSweepTest` (I-E1-04): the cancel is swept across at least 30 add/commit/merge/fetch progress points; after each, `endOwnedOperation` plus a fresh claim must return `NoLock`/`ClearedOwn` and `git status` must be healthy (the claim half runs once Tasks 1.2d1/1.2d2 land: the sweep is registered here and gains its claim assertions in Task 1.2g). REGISTER: `AllJvmTests.kt`.
- Files: `JT/git/JgitCancelLockTest.kt`, `JT/git/JgitCancelSweepTest.kt` (new)

##### Task 1.1c: Re-verify the process question on the merged manifest (XS)
- Build the debug merged manifest (`./gradlew :androidApp:processDebugMainManifest`, wrapped in `timeout 30m`) and grep for `android:process`; paste result into the PR. Expected: 0 hits, so the in-process gate covers the WorkManager worker.
- Files: none (evidence only)

##### Task 1.1d: Count `GitSyncService` constructions per graph across `switchGraph` (S)
- Test over `GitSyncServiceRegistry` (`CM/git/GitSyncServiceRegistry.kt`; likely an `object`, confirm): construct/register twice for one graph, assert a second `PeriodicSyncDriver` is not left running (research H5). If it can happen, fix by stopping the replaced service's driver in `register`. Also pin that two services for one repo share one gate key (relocate: key change while a lease is held does not strand the lease).
- Files: `BT/git/GitSyncServiceRegistryTest.kt` (new), `CM/git/GitSyncServiceRegistry.kt` (only if H5 is real)

#### Story 1.2: One `LockRecovery` claim protocol replaces three age/mtime implementations (Refactor-first)
**As a** user, **I want** a lock deleted only when it is ours or its owner is dead, **so that** a live index is never corrupted.
**Acceptance Criteria**:
- Decision table is total and fails closed (Success Metric 1, forced-crash matrix).
  - *Given* `.git/index.lock` plus a record `(instanceId=A, pid=4242, startTicks=900, host=h)` and a fake `ProcessLiveness` returning Dead, *When* the claim runs in instance B, *Then* verdict `ClearedDeadOwner`, the lock and the dead record are removed, our record is then written, and one log line names owner A.
  - *Given* the same files and liveness Alive, *Then* `OwnerAlive`, lock and the other owner's record untouched (our record is NOT written over it).
  - *Given* liveness Unknown (EACCES, empty `startInstant`, or a **different hostname** on a shared folder), *Then* `OwnerUnknown`, nothing touched.
  - *Given* a record with the same host and a **different `bootId`** (battery death, reboot), *Then* `ClearedDeadOwner` without consulting the pid probe (adversarial C1); *Given* either `bootId` null, *Then* the pid/start-tick probe decides; fixture tests for `boot_id` and `Settings.Global.BOOT_COUNT` sources.
  - *Given* `index.lock` with no record, *Then* `NoRecord`, nothing deleted; the UI action is the confirm-gated `forceRemoveLock`.
  - *Given* a record with the same pid but a different `processStartTicks` (pid reuse), *Then* `ClearedDeadOwner`.
  - *Given* a record whose `instanceId` equals this process while the gate is held **and no run is registered for that canonical record path**, *Then* `ClearedOwn` (it can only be our own corpse); *Given* the same record while the `ActiveRunRegistry` has an entry for that canonical path (a second spelling of one repo, `ActiveRunNotClearedOwnTest`), *Then* `Left(SyncInProgress)`, the live `index.lock` and the live record are untouched.
  - *Given* an empty, half-written or unparseable record (a reader between create and write, `TornRecordIsUnknownTest`), *Then* `OwnerUnknown(TornRecord)`, nothing deleted; *Given* the record is published through temp file + `createLink`, *Then* no reader ever observes a partial record on a filesystem with hard links.
  - *Given* a record whose pid is absent but whose `pidNs` differs from ours or is unreadable (`PidNamespaceAbsentPidTest`), *Then* `OwnerUnknown`, not `Dead`; *Given* two JVMs whose `ProcessHandle.startInstant` differs by a simulated wall-clock step while the raw `/proc` ticks are equal (`ClockStepStartTimeTest`), *Then* the owner is not `Dead` (Linux/Android compare ticks exactly; macOS/Windows `Instant`s within 2 s).
- **Ordering (adversarial B1).** *Given* instance B holds the gate, instance A (live, second `instanceId`, liveness stub Alive) holds a lock and a record, *When* B runs the real `beginOwnedOperation` (not a hand-built record), *Then* the lock and A's record survive and B receives `OwnerAlive`; *and given* a leftover record from B's own earlier run, *Then* it is read before being overwritten.
- Exclusive claim over **no record**. *Given* two claimers racing `CREATE_NEW` on an empty directory, *Then* exactly one wins and the loser re-reads and gets `OwnerAlive`.
- **Exclusive recovery over a PRE-EXISTING dead record (adversarial B1).**
  - *Given* a dead record R and an `index.lock`, and two claimers P1 and P2 (separate `InstanceId`s, real `JavaIoLockFiles` on a temp directory, a deterministic barrier so both decide `ClearedDeadOwner` before either deletes), *When* both run `beginOwnedOperation`, *Then* exactly one returns success and writes its record, the other returns `SyncInProgress`/`OwnerAlive`, **no live lock or record written by the winner is ever deleted** (a hook creates a simulated live `index.lock` right after the winner's record write and asserts it survives the loser's whole run), and the number of overlapping "owners" observed is 1.
  - *Given* the same setup with **three** claimers, *Then* exactly one wins and both others lose without deleting anything of the winner's; run 200 seeded interleavings of the barrier points.
  - *Given* a claimer that acquires the recovery lock after the winner already finished (late), *Then* it re-reads under the lock, finds the winner's live record and returns `OwnerAlive` without deleting.
  - *Given* `LockFiles.tryLockRecovery()` throws `IOException` (simulated unsupported filesystem), *Then* `OwnerUnknown(RecoveryLockUnsupported)` and nothing is deleted; *Given* the holder process dies while holding the lock (second JVM killed), *Then* the next claimer acquires it.
  - **Foreign-lock rule (ADR-001 decision 12 step 5; pre-mortem P1-3).** *Given* a dead record and an `index.lock` whose mtime is older than the record's `startedAtMs` minus 2 s, *Then* nothing is deleted and the verdict is `OwnerUnknown(ForeignLockSuspected)`; *Given* a dead record whose mtime (last heartbeat) is a week old and an `index.lock` modified today (a terminal `git`, `ForeignLockNewerThanDeadRecordTest`), *Then* nothing is deleted and the verdict is `OwnerUnknown(ForeignLockSuspected)`; *Given* a lock modified within the last 5 s of the verdict, *Then* `OwnerUnknown`; *Given* a lock whose mtime lies in `[startedAtMs - 2 s, heartbeatMtime + 10 s]` and at least 5 s old (a real corpse), *Then* it is deleted. If any allow-listed lock fails a bound, no lock and not the record is deleted.
  - **Heartbeat.** *Given* a run holding the lease for 12 s of virtual time, *Then* the record's mtime advanced at least twice (`OwnerHeartbeatTest`); a starved heartbeat degrades only to `OwnerUnknown`, never to a delete.
  - **`forceRemoveLock` is gated (P1-3).** *Given* the user confirms "Remove lock file..." while a `SyncMain` or desktop run starts and writes a live record and `index.lock`, *When* `forceRemoveLock` runs (gate, `tryLockRecovery`, re-read, re-decide), *Then* it refuses on `OwnerAlive` and the live lock survives (`ForceRemoveRacesLiveRunTest`); with a genuinely dead or absent owner it deletes only allow-listed locks.
  - **In-process claimers.** *Given* two coroutines or threads of one JVM racing `beginOwnedOperation` for one repo, *Then* the in-process mutex (keyed by the canonical recovery-file path) excludes them before `FileChannel.tryLock`, and an `OverlappingFileLockException` is also handled as `SyncInProgress(heldBy = null)` (`RecoveryLockInProcessMutexTest`).
- Allow-list only.
  - *Given* `.git/objects/pack/tmp_pack_x.lock` and `.git/refs/heads/main.lock` (dead owner), *When* recover runs, *Then* only the `refs/**` lock is removed.
- Android SAF: *Given* a SAF graph, *Then* locks and the record are read and written under `shadow.resolveForJGit(repoRoot)`.
- CLI participation. *Given* `SyncMain` running with a live record, *When* the desktop app syncs the same repo, *Then* the app gets `OwnerAlive` and coalesces; *Given* the app's live record, *Then* the CLI exits with the "another sync is running" code.
**Files**: `CM/git/LockRecovery.kt` (new, pure `decide` + types), `CM/git/LockOwnerRecord.kt` (new), `CM/git/ProcessLiveness.kt` (new), `CM/git/LockFiles.kt` (new port, incl. `tryLockRecovery`), `JC/git/JavaIoLockFiles.kt` (new), `JM/git/JvmProcessLiveness.kt` (new; `ProcessHandle` is Java 9, Android minSdk is 26), `AM/git/AndroidProcessLiveness.kt` (new), `CM/git/GitRepository.kt`, `AM/git/AndroidGitRepository.kt`, `JM/git/JvmGitRepository.kt`, `JC/git/GitOperationSupport.kt`, `JM/cli/SyncMain.kt`

##### Task 1.2a1: Domain types, liveness policy and ports (S)
- (Triad repair 1: the former Task 1.2a listed nine files and is split in two; the Phase 4 patch had already raised it from S to M.) `LockOwnerRecord` (incl. `host`, `bootId` and `pidNs`; a decode of an empty/partial/unparseable file yields `TornRecord`; JSON codec with `ignoreUnknownKeys`), `InstanceId`, `ProcessLiveness` (Alive/Dead/Unknown), the liveness comparison policy as a pure function (`LivenessPolicy`: Linux/Android ticks equal, macOS/Windows `Instant` within 2 s, an absent pid is `Dead` only with the same non-null `pidNs`), and the `LockFiles` port (`listAllowListedLocks` with mtimes, `readRecord` returning content plus the record's mtime, `createRecordExclusive` (atomic publish), `touchRecord` (heartbeat), `delete`, and `tryLockRecovery(): RecoveryLockHandle?` which throws or returns null when contended).
  Test: `ClockStepStartTimeTest` (U-E1-07; property over simulated `btime` skew against the pure policy).
- Files: `CM/git/LockOwnerRecord.kt`, `CM/git/ProcessLiveness.kt`, `CM/git/LivenessPolicy.kt` (new), `CM/git/LockFiles.kt`, `CT/git/ClockStepStartTimeTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 1.2a2: `LockVerdict` and the pure `decide` function (M) (Claim lane, after 1.2a1)
- `LockVerdict` (sealed: NoLock / ClearedOwn / ClearedDeadOwner / OwnerAlive / OwnerUnknown(reason) / NoRecord) and `decide(existingRecord, lockFiles, liveness, self): LockVerdict` as a pure function over the **existing** record only (our record does not exist yet); rule order: same host and differing non-null `bootId` is Dead, then the pid/start-tick probe, a different host is Unknown; a torn record maps to `OwnerUnknown(TornRecord)`, never `Dead`/`NoRecord`. Kotest-property: for any combination of {record present/absent, owner alive/dead/unknown, self/other, allow-listed or not}, no file is ever deleted unless the verdict is ClearedOwn or ClearedDeadOwner and the file is allow-listed.
  Tests (validation ids in parentheses): `TornRecordIsUnknownTest` (U-E1-06), `PidNamespaceAbsentPidTest` (U-E1-43), plus the existing decision-table tests (`LockRecoveryDecisionTest`).
- Files: `CM/git/LockRecovery.kt`, `CT/git/LockRecoveryDecisionTest.kt` (new), `CT/git/TornRecordIsUnknownTest.kt` (new), `CT/git/PidNamespaceAbsentPidTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 1.2b: Liveness adapters and file adapter (M)
- JVM (`JM`): **on Linux read the raw `/proc/<pid>/stat` field 22 start ticks exactly as Android does** (the JDK's `startInstant()` adds ticks to a `/proc/stat` `btime` read once per JVM, so a wall-clock step makes two JVMs disagree; INFERRED from the JDK implementation, not run); `ProcessHandle.of(pid)` + `info().startInstant()` only on macOS and Windows, compared within 2 s (empty -> Unknown). `pidNs` = `readlink /proc/self/ns/pid` (VERIFIED readable on the dev machine this pass), null where unreadable. Android (`AM`): `/proc/<pid>/stat` field 22 (start ticks), ENOENT = Dead (only with the same non-null `pidNs`), EACCES/parse failure = Unknown; self ticks from `/proc/self/stat`; `host` = constant, `bootId` = `/proc/sys/kernel/random/boot_id` else `Settings.Global.BOOT_COUNT` else null (JVM: Linux `boot_id`, else null). `JavaIoLockFiles` in `JC` (shared by JVM and Android): list allow-listed locks (with mtimes), read record (empty/partial = torn), **atomic exclusive publish** (write temp, flush, `Files.createLink`, delete temp; fallback `CREATE_NEW` then write where hard links are unsupported), `touchRecord` (mtime heartbeat), delete, and `tryLockRecovery` (`FileChannel.tryLock()` on the permanent `.git/stelekit-sync.recover` opened `CREATE`+`WRITE` so the exclusive lock is legal; channel opened only for the call and closed in `finally`; a `.git` that is a file is resolved through its `gitdir:` line or reported unsupported). Fixture-driven parser test for `/proc/<pid>/stat` lines with spaces/parentheses in the comm field.
- Files: `JM/git/JvmProcessLiveness.kt`, `AM/git/AndroidProcessLiveness.kt`, `JC/git/JavaIoLockFiles.kt`, `CT/git/ProcStatParserTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 1.2c1: The claim protocol over the port: registry, mutex, recovery lock, re-decide (M) (Claim lane, after 1.2a2)
- (Triad repair 1: the former Task 1.2c, rated L-risk, is split; the foreign-lock window, heartbeat and `forceRemoveLock` moved to 1.2c2.) `LockRecovery.claim(...)` per ADR-001 decision 12, steps (0)-(4) and (6): (0) **if the in-process `ActiveRunRegistry` already has an entry for this canonical record path, return `Left(SyncInProgress)` before anything else (so `ClearedOwn` is reachable only for a corpse; pre-mortem P1-1)**, (1) read the existing record, (2) `decide`, (3) on `ClearedOwn`/`ClearedDeadOwner` only: take the in-process mutex keyed by the canonical recovery-file path, then `tryLockRecovery` (contended or `OverlappingFileLockException`: `SyncInProgress(heldBy = null)`; unsupported: `OwnerUnknown(RecoveryLockUnsupported)`), (4) re-read and re-decide under the lock, **re-running the liveness probe** (changed: release, return the new verdict), (6) publish ours atomically, register it in the `ActiveRunRegistry`, re-read, release the locks in `finally`; end = delete our record only if `instanceId` and `startedAtMs` match and unregister, best-effort. **Step (5) is a fail-closed placeholder in this task**: it returns `OwnerUnknown(ForeignLockSuspected)` and deletes nothing until Task 1.2c2 supplies the window rule, so the intermediate state can never delete a lock. Write failure is log-and-continue. Unit tests in `CT` over a fake `LockFiles` with an injectable barrier: `LockRecoveryClaimTest`, `ActiveRunNotClearedOwnTest` (U-E1-44), `RecoveryLockInProcessMutexTest` (U-E1-48).
- Files: `CM/git/LockRecovery.kt`, `CM/git/ActiveRunRegistry.kt` (new), `CT/git/LockRecoveryClaimTest.kt` (new), `CT/git/ActiveRunNotClearedOwnTest.kt` (new), `CT/git/RecoveryLockInProcessMutexTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 1.2c2: Foreign-lock window, heartbeat and gated `forceRemoveLock` (M) (Claim lane, after 1.2c1)
- Step (5) of the claim: apply the **foreign-lock rule**: every allow-listed lock must satisfy `startedAtMs - 2 s <= lock.mtime <= record.mtime + 10 s` and `lock.mtime <= now - 5 s`, else delete nothing and return `OwnerUnknown(ForeignLockSuspected)`; when all pass delete the locks, then the dead record. `OwnerHeartbeat` (new file): touches the record's mtime every 5 s while a run holds the lease (`touchOwnedOperation`; a starved heartbeat degrades only to `OwnerUnknown`, never to a delete). `forceRemoveLock` is `claim`'s deleting path with `OwnerAlive` and a live registry entry as refusals (gate first, then recovery lock, re-decide; ADR-001 decision 12a). Unit tests in `CT` over the fake `LockFiles`: `ForeignLockNewerThanDeadRecordTest` (U-E1-39), `ForceRemoveRacesLiveRunTest` (U-E1-40), `OwnerHeartbeatTest` (U-E1-45). The real-directory integration tests are Task 1.2f, after the adapters exist.
- Files: `CM/git/LockRecovery.kt`, `CM/git/OwnerHeartbeat.kt` (new), `CT/git/ForeignLockNewerThanDeadRecordTest.kt` (new), `CT/git/ForceRemoveRacesLiveRunTest.kt` (new), `CT/git/OwnerHeartbeatTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 1.2f: Real-directory claim tests: two- and three-claimer recovery races, two-JVM race (M)
- (Triad repair 1: this task now drives `LockRecovery.claim` over the real `JavaIoLockFiles` and `JvmProcessLiveness`, both available in W3 from Tasks 1.2b, 1.2c1 and 1.2c2; it no longer calls `GitRepository.beginOwnedOperation`, which Task 1.2d1/1.2d2 (same wave W4) add. The repository-level assertions moved to Task 1.2g, W5.) With the real adapters on temp directories and two or three `InstanceId`s: the Story 1.2 ordering AC at claim level (a leftover record from the claimer's own earlier run is read before being overwritten; a live second instance's lock and record survive and the claimer receives `OwnerAlive`), the no-record `CREATE_NEW` race, the **pre-existing dead record two-claimer and three-claimer tests** (barrier hooks via a `LockFiles` decorator, 200 seeded interleavings, simulated live `index.lock` surviving), late claimer, unsupported-lock fail-closed, mtime filter, SAF-resolved path, and a cross-process variant that spawns a second JVM (`ProcessBuilder`) holding the recovery lock then is killed (releases). **Genuinely cross-process test (adversarial C6, since the in-JVM races lean on the JVM's own `FileLockTable`, not the kernel):** `TwoJvmRecoveryRaceTest` (I-E1-06; two spawned JVMs x 40 rounds racing `LockRecovery.claim` over a pre-existing dead record: exactly one winner each round and a simulated live `index.lock` survives), which also exercises the real `/proc` liveness path (U-E1-07, real half). The recovery file is opened `CREATE`+`WRITE`; a `.git`-is-a-file layout is asserted `OwnerUnknown`. No `Thread.sleep`; latches only.
- Files: `JT/git/LockClaimIntegrationTest.kt` (new), `JT/git/TwoJvmRecoveryRaceTest.kt` (new), `JT/git/RecoveryRaceChildMain.kt` (new; the spawned JVM's entry point). REGISTER: `AllJvmTests.kt`.

##### Task 1.2d1: `GitRepository` strangler methods, JVM implementation and shared support (M) (Repo lane, first)
- (Triad repair 1: the former Task 1.2d listed six files and is split by platform.) Add to `GitRepository` with default implementations (iOS, wasm, `StubGitRepository`, `GitSyncServiceStepRoutingTest.kt:43`, `GraphManagerCloneTest.kt:97`, `GitSyncServiceStuckStateTest` fakes keep compiling): `beginOwnedOperation(config, info): Either<GitError, LockVerdict>`, `endOwnedOperation(config)`, `touchOwnedOperation(config)` (the 5 s heartbeat), `forceRemoveLock(config)` (**gate-then-recovery-lock-then-re-decide, refuses on `OwnerAlive`; ADR-001 decision 12a; a bypass of the claim protocol is a defect**), each delegating to `LockRecovery.claim`. `JvmGitRepository` overrides them. `removeStaleLockFile` loses its 60 s age rule (returns Right when no lock, `Left(StaleLockFile(verdict))` otherwise, never deletes) in `JvmGitRepository.kt:568-585`, and `GitOperationSupport.deleteLockFileIfStaleForRetry` (L287) is routed to the claim. After a deletion call `status` and `repositoryHealth`; a corrupt index returns the existing repair error, never an auto-reset. Update `GitOperationSupportHelpersTest`.
- **Default implementations are FAIL-CLOSED (triad repair 2).** The defaults on `GitRepository` (`GitRepository.kt:20` is the interface; implementers without an override are iOS, wasm and `StubGitRepository` at `CT/git/testsupport/StubGitRepository.kt`) never claim there is no lock: `beginOwnedOperation` returns `Right(LockVerdict.OwnerUnknown(RecoveryLockUnsupported))` (never `NoLock`, `ClearedOwn` or `ClearedDeadOwner`), `forceRemoveLock` returns a `Left` refusal and deletes nothing, and `touchOwnedOperation`/`endOwnedOperation` are no-ops (they hold no state). A silent `Right`/"no lock" default would let an unported platform skip the claim. Test `GitRepositoryDefaultsFailClosedTest` (a bare anonymous implementer must get `OwnerUnknown` and a refusal). Test doubles that need `NoLock` get an explicit test-only override (Task 1.3d4: `GateAssertingGitRepository` takes an injectable verdict defaulting to `NoLock`).
- Files: `CM/git/GitRepository.kt`, `JM/git/JvmGitRepository.kt`, `JC/git/GitOperationSupport.kt`, `JT/git/GitOperationSupportHelpersTest.kt` (path as found), `CT/git/GitRepositoryDefaultsFailClosedTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 1.2d2: Android implementation of the strangler methods (S) (Repo lane, after 1.2d1)
- `AndroidGitRepository` overrides `beginOwnedOperation`/`endOwnedOperation`/`touchOwnedOperation`/`forceRemoveLock` over `shadow.resolveForJGit(repoRoot)` (SAF graphs: locks and the record are read and written there), and routes `removeStaleLockFile`/`deleteStaleLockFile` (L663-686) and the `deleteLockFileIfStaleForRetry` call sites (L217/241/424) to the claim; same no-auto-reset rule. Update `AndroidGitRepositoryShadowWorktreeTest` and add `AndroidGitRepositoryClaimTest` (Robolectric/JVM-hosted, fake `ProcessLiveness`).
- Files: `AM/git/AndroidGitRepository.kt`, `AU/git/AndroidGitRepositoryShadowWorktreeTest.kt`, `AU/git/AndroidGitRepositoryClaimTest.kt` (new). REGISTER: `AllAndroidUnitTests.kt`.

##### Task 1.2g: Repository-level claim tests: real `beginOwnedOperation` ordering, process-kill orphan lock, cancel sweep claim half (S)
- Needs Tasks 1.2d1/1.2d2 (W4), so it runs in W5. `BeginOwnedOperationOrderingTest` (the Story 1.2 ordering AC through the real repository method, not a hand-built record: instance B holds the gate, live instance A holds a lock and a record, B's `beginOwnedOperation` leaves them and returns `OwnerAlive`); `ProcessKillOrphanLockTest` (I-E1-08/09: a child JVM running a real `JvmGitRepository.commit` is `destroyForcibly()`-ed mid-call, then the parent runs `beginOwnedOperation` and a real commit at repository level: `ClearedDeadOwner`, healthy index via `repositoryHealth`, no auto-reset; variants: killed while holding the recovery lock, pid reuse simulated by differing start ticks); the claim assertions added to `JgitCancelSweepTest` (I-E1-04: after each of at least 30 cancel points, `endOwnedOperation` plus a fresh claim returns `NoLock`/`ClearedOwn`). The service-level two-JVM test `CrossProcessSyncOverDeadRecordTest` (I-E1-07) needs `GitSyncService` gating (Task 1.4a1, W5) and lives in Task 1.8b.
- Files: `JT/git/BeginOwnedOperationOrderingTest.kt` (new), `JT/git/ProcessKillOrphanLockTest.kt` (new), `JT/git/ProcessKillChildMain.kt` (new), `JT/git/JgitCancelSweepTest.kt` (extend). REGISTER: `AllJvmTests.kt`.

##### Task 1.2e: `SyncMain` claims through the same protocol (S)
- **Scope: a data-safety fix, not a feature** (see "Scope additions traced to requirements": `SyncMain.kt:59` builds its own `JvmGitRepository` and is an ungated cross-process writer, which the in-process gate cannot cover). `JM/cli/SyncMain.kt` (a composition root: it is added to `WiringRoots`) builds a process-local `RepoOpGate` keyed by the canonical `.git` path, and wraps its stage/commit/fetch/merge/push sequence in `beginOwnedOperation`/`endOwnedOperation` (origin `Cli`) with the 5 s heartbeat; `OwnerAlive` exits with a new documented exit code. Test with a second `InstanceId`.
- Files: `JM/cli/SyncMain.kt`, `JT/cli/SyncMainClaimTest.kt` (new). REGISTER: `AllJvmTests.kt`.

#### Story 1.3: `RepoOpGate`, composition-root wiring and gate-first quiesce
**As a** maintainer, **I want** one gate shared by live, cold and quiesce paths with a single lock order, **so that** exclusion holds across all triggers and the relocate path cannot deadlock a sync.
**Acceptance Criteria**:
- Cold and live paths collide.
  - *Given* a `RepoOpGate` and `ColdSyncRunner` holding key `RepoOpGate.keyFor("/data/.../gitshadow")`, *When* `GitSyncService.sync(Manual)` for the same repo starts, *Then* it returns `SyncInProgress` without calling the repository; and the reverse: *Given* a live sync holding the gate, *When* `ColdSyncRunner.run` is called, *Then* it returns `Skipped(Busy)` immediately (no wait, no blocked worker).
- Keys are canonical-path based (pre-mortem P1-1; `GateKeyAliasTest`, U-E1-10). *Given* one repository reached through a trailing `/`, a `..` segment, a symlinked second path and a second graph registration, *Then* all spellings yield the same key (`File.canonicalFile` of the resolved `.git` directory, hashed), and `/a-b` and `/a/b` yield different keys. *Given* canonicalisation throws, *Then* key resolution is `Unreadable` and no other key is used. *Given* two registrations of one repo and a run holding the gate through spelling 1, *When* spelling 2 starts, *Then* it returns `SyncInProgress` and `beginOwnedOperation` is not reached.
- `isHeld` drives the periodic driver (advisory only).
  - *Given* the gate held, *When* `PeriodicSyncDriver` ticks, *Then* it records `SkipReason.Busy` and never queues.
- Required, shared instance.
  - *Given* the Android and Desktop composition roots, *When* the entry-point tests run, *Then* the gate instance used by `GitSyncService`, `ColdSyncRunner`, the scheduler factory and the quiesce strategy is the **same object** (identity assertion), and no constructor has a default for it.
- Quiesce lock order (architecture B2).
  - *Given* a fake sync holding the gate and needing the shadow worktree lock inside `fetch`, *When* `quiesce` runs, *Then* it waits for the gate (never holding the worktree lock while waiting), completes after the sync, and does not time out; *Given* quiesce holding the gate and worktree lock, *When* a new trigger arrives, *Then* it coalesces; `release` frees both.
- **Real flush does not self-deadlock (architecture R1).**
  - *Given* a real `GitShadowFlushActor` over a temp shadow worktree with a non-empty `GitWriteBackQueue`, *When* `quiesce` runs, *Then* it drains through `flushLocked` and completes in well under the 30 s timeout with the queue empty; *Given* cancellation or an exception after the gate lease is acquired but while waiting for the worktree mutex, or a drain error, *Then* both the lease and the mutex are released and a fresh `sync` acquires.
- **A press that loses to a quiesce lease is not queued (architecture R5).**
  - *Given* quiesce holds the gate (`origin = Quiesce`), *When* `UiPress` arrives, *Then* it returns `SyncInProgress(heldBy = Quiesce)`, the rerun bit stays unset, no follow-up runs at release, and the UI event reads "Moving your graph storage. Sync resumes afterwards."
- Keys are shared: *Given* the quiesce strategy's `gateKeyFor` and the sync's `resolveGateKey` for one graph, *Then* they return the same key.
**Files**: `CM/git/RepoOpGate.kt` (new; successor of `ColdSyncRunner.kt:24`'s `NamedLocks`), `CM/git/ColdSyncRunner.kt`, `AM/git/WorkManagerSyncScheduler.kt`, `AM/db/GraphMoveQuiesceStrategy.android.kt`, `AM/git/GitShadowFlushActor.kt`, `CM/git/GitSyncService.kt` (constructor only, Task 1.3d1), `CM/ui/StelekitAppDependencies.kt`, `CM/ui/GraphContentGitSyncSetup.kt`, `AP/SteleKitApplication.kt`, `JM/desktop/Main.kt`

##### Task 1.3a: `RepoOpGate` (S)
- `tryAcquire(key, info): GateLease?`, `isHeld`, `heldBy`, `awaitFree`, `acquire` (suspending, quiesce only), `releaseOrHandOff(lease): FollowUp?` (a plain non-suspending `fun`; rerun bit checked and cleared under the same internal lock; the bit is never set while the holder's origin is `Quiesce`), `keyFor(gitDir: File)` = hash of `gitDir.canonicalFile.path` (**no longer a wrapper of `GitWriteLockNaming.lockNameFor`**, which is a remote-URL normaliser that cannot alias symlinks or `..`; pre-mortem P1-1) with a `keyForOrNull` that returns null on `IOException`; every caller (service, cold runner, scheduler, quiesce, clone, `SyncMain`) uses this one function. Property test: any interleaving of tryAcquire/press/release never yields two live leases for one key and never loses a set rerun bit (model-based interleaving test, deterministic; no sleeps).
- Files: `CM/git/RepoOpGate.kt` (new; `NamedLocks` stays in `ColdSyncRunner.kt` until Task 1.3b1 migrates its only user), `CT/git/RepoOpGateTest.kt` (new), `CT/git/GateKeyAliasTest.kt` (new; symlink, `..`, trailing slash, second registration, `/a-b` vs `/a/b`, canonicalisation failure), `CT/git/RepoOpGateReleaseStructureTest.kt` (new: `releaseOrHandOff` is not `suspend`, a throwing `endOwnedOperation` still frees the gate). REGISTER: `CT` BUILD entry.

##### Task 1.3b1: Cold runner and scheduler factory on the shared gate (M) (W4, after 1.3c and 1.3d4)
- `ColdSyncRunner` replaces `NamedLocks` (`ColdSyncRunner.kt:46`, waiting `withLock` at L51) with the required `gate: RepoOpGate` and `tryAcquire`/`Skipped(Busy)`; remove private `ColdSyncLocks` (`WorkManagerSyncScheduler.kt:325`) and construct the scheduler factory with the **same** gate instance the roots already hold (Task 1.3d2), as a required, non-defaulted parameter. Update `ColdSyncRunnerTest`.
- Files: `CM/git/ColdSyncRunner.kt`, `AM/git/WorkManagerSyncScheduler.kt`, `AU/git/ColdSyncRunnerTest.kt` or `BT/git/ColdSyncRunnerTest.kt` (as found)

##### Task 1.3b2: Quiesce and clone-launcher construction, shared-gate entry-point test (S) (W4, after 1.3b1)
- Construct, for the first time in production, `createAndroidGraphMoveQuiesceStrategy` (with the `gateKeyFor` lambda from `GitConfigRepository` and `RepoOpGate.keyFor`) and the clone worker launchers with the same gate instance, as required non-defaulted parameters. Add `RepoOpGate  RepoGateSharedEntryPointTest  effect:repo-gate-shared` to `wiring-catalog.txt`; the entry-point test (`WiredAppFixture`/`DesktopWiredAppFixture`) asserts the gate used by `GitSyncService`, `ColdSyncRunner`, the scheduler factory and the quiesce strategy is the same object.
- Files: `AP/MainActivity.kt` (the quiesce strategy has no production construction today, grep this pass: this task creates it, so no line reference), `JM/git/JvmGitCloneWorkerLauncher.kt`, `AU/wiring/RepoGateSharedEntryPointTest.kt` (new). REGISTER: `wiring-catalog.txt`, `AllAndroidUnitTests.kt`.

##### Task 1.3c: Quiesce takes the gate first, drains without re-entering the worktree lock (M) (W3)
- `AndroidGraphMoveQuiesceStrategy` (`GraphMoveQuiesceStrategy.android.kt:66-109`) gains the required `gate: RepoOpGate` and `gateKeyFor: suspend (StorageMoveOperation) -> String?` parameters (null: no git config or unresolvable `.git`, no gate; **it is built from the same canonical `RepoOpGate.keyFor(resolvedGitDir)` resolver the service uses, so quiesce and sync share one key by construction, pre-mortem P2-7**; `createAndroidGraphMoveQuiesceStrategy` passes them through; while a `Quiesce` lease is held every `UiPress` is refused with `SyncInProgress(heldBy = Quiesce)` and never sets the rerun bit, so no follow-up can fire against relocated storage). `quiesce`: `gate.acquire(key, info(Quiesce))`, then `GitWorktreeLocks.lockFor(...).lock()` inside `try/catch (Throwable)` that releases the lease on failure, store one `QuiesceHold(lease, lock)` in `heldLocks` (replacing the `Mutex` map value), `awaitIdle` (immediate), then drain; any exception or timeout cancellation after the hold exists releases both before propagating; `release` frees both and `resumeBackgroundSync` as today. **R1:** add `GitShadowFlushActor.flushLocked()` (the current `flush` body, `internal`, caller holds the worktree mutex; `flush() = lockFor(...).withLock { flushLocked() }`), add `flushWhileLocked` to `ShadowWorktreeQuiesceTarget`, and make `drainWriteBack` use it (the production wiring passes `actor::flushLocked`). `GateBeforeWorktreeOrderTest` source audit: no `GitWorktreeLocks` scope calls gate APIs; no code holding the worktree lock calls `GitShadowFlushActor.flush`; flush paths `GitShadowFlushActor.kt:48` and `PlatformFileSystem.kt:1005` take only the worktree lock. Tests: update `AndroidGraphMoveQuiesceStrategyTest` and `SafGrantReleaseTest`; new `QuiesceRealFlushTest` (real actor, non-empty queue, must complete) and the cancellation-leak test.
- Files: `AM/db/GraphMoveQuiesceStrategy.android.kt`, `AM/git/GitShadowFlushActor.kt`, `AU/db/AndroidGraphMoveQuiesceStrategyTest.kt`, `AU/db/QuiesceRealFlushTest.kt` (new), `BT/git/GateBeforeWorktreeOrderTest.kt` (new). REGISTER: `AllBusinessTests.kt`, `AllAndroidUnitTests.kt`.

##### Task 1.3d1: Required `gate` parameter and nullable root field (S) (W3, Service lane, first)
- Add `internal val gate: RepoOpGate` as a required, non-defaulted constructor parameter of `GitSyncService` (stored, unused until Task 1.4a1), so no later wave threads a parameter an earlier wave has not added. Add `StelekitAppPlatformIntegrations.repoOpGate: RepoOpGate? = null` (`StelekitAppDependencies.kt:87`, a nullable field like its neighbours); `buildGitSyncService` (`GraphContentGitSyncSetup.kt:437-470`) passes it and returns null when it is null instead of creating a private gate (**no fallback**, unlike the busy-counter line it sits beside). The one production construction site is `GraphContentGitSyncSetup.kt:448`. Until Task 1.3d2 supplies the gate in the roots, the null field means no sync service is built in a running app; the two tasks are ordered inside W3 and ship in the same PR, so no released state exists in between. **The 1.3d1 to 1.3d4 series must land in ONE PR and must not be bisected, cherry-picked or released between 1.3d1 and 1.3d2** (a null `repoOpGate` means no `GitSyncService` can be built until 1.3d2; see Risk Control).
- Files: `CM/git/GitSyncService.kt` (constructor only), `CM/ui/StelekitAppDependencies.kt`, `CM/ui/GraphContentGitSyncSetup.kt`

##### Task 1.3d2: Construct the gate once in each composition root (S) (W3, Service lane, after 1.3d1)
- The gate is constructed once in `AP/SteleKitApplication.kt` (reached from `AP/MainActivity.kt:552`) and `JM/desktop/Main.kt:243` (and `JM/desktop/ui/App.kt:29` if it passes a `gitRepository`) and assigned to `repoOpGate`; `wasmJsMain` passes nothing (no git service there). `RepoOpGate()` appears in main source sets only in these roots (`WiringRoots`).
- Files: `AP/SteleKitApplication.kt`, `AP/MainActivity.kt`, `JM/desktop/Main.kt`, `JM/desktop/ui/App.kt`

##### Task 1.3d3: Scripted `sg` codemod: `gate = RepoOpGate()` at every test construction site (M) (mechanical, scripted; W3, after 1.3d1)
- (Triad repair 1: recounted by command this pass: **24 test files, 27 construction sites**, plus the 1 production site above; the earlier "24 sites (15/5/4)" counted files, and the 5 jvmTest files that call `buildTestGitSyncService` need no edit because the fixture builder takes the gate.) One `sg` rule rewrites `GitSyncService($$$ARGS)` to add `gate = RepoOpGate()` in each file; `GitSyncServiceTestFixtures.kt` and `GitSyncServiceJvmFixtures.kt` also gain an optional `gate` builder argument for tests that share one. Per-file sites: androidUnitTest (4 files, 5 sites): `git/GitSyncBusyCounterSharedWiringTest.kt` 1, `git/GitSyncWorkerGateAndRecordTest.kt` 1, `git/WorkManagerSyncSchedulerRetryOwnerTest.kt` 2, `git/GitSyncWorkerStaleGateTest.kt` 1. businessTest (15 files, 17 sites): `ui/GitSyncCoordinatorTest.kt` 1, `git/GitSyncBusyCounterFetchOnlyTest.kt` 1, `git/GitSyncServiceScheduledSyncTest.kt` 3, `git/GitSyncServiceChangeBackTest.kt` 1, `git/GitSyncServiceChangeGuardsTest.kt` 1, `git/GitSyncServiceTestFixtures.kt` 1, `git/GitSyncServicePeriodicTest.kt` 1, `git/GitSyncServicePublishFailureTest.kt` 1, `projectbridge/PostSyncProjectJobEntryPointTest.kt` 1, `git/GitSyncServiceLfsGuardTest.kt` 1, `git/GitSyncServiceConflictMarkerGuardTest.kt` 1, `git/GitSyncServiceFirstSyncGateTest.kt` 1, `git/GitSyncServiceRateLimitRetryTest.kt` 1, `git/GitSyncServiceAutoCommitTest.kt` 1, `git/ScheduledSyncStackE2ETest.kt` 1. jvmTest (5 files, 5 sites): `git/GitSyncAttributeMergeE2ETest.kt`, `git/GitSyncConflictReloadE2ETest.kt` (line 93), `git/GitAbortRecoveryJvmTest.kt`, `git/GitSyncServiceJvmFixtures.kt` (line 33), `git/GitSyncServiceLfsJvmTest.kt`, 1 each. No test assertion changes.
- **How the codemod is verified** (a scripted rewrite, so the worker reads none of the 24 files in full): (1) before and after, the recount command `grep -rlE "(^|[^a-zA-Z.])GitSyncService\(" kmp/src | grep '\.kt$'` returns the same files, and `sg --pattern 'GitSyncService($$$A)'` finds 28 sites in total of which 28 also match `sg --pattern 'GitSyncService($$$B, gate = $G)'` (the two counts must be equal); (2) `git diff --stat` shows only the files above, and every changed hunk contains `gate =`; (3) the three test source sets compile (`./gradlew :kmp:compileTestKotlinJvm :kmp:compileDebugUnitTestKotlinAndroid`, wrapped in `timeout 30m`); (4) `NoGateFallbackAuditTest` (Task 1.3d4) passes. The counts are re-run by command at implementation time because tests land continuously.
- Files: the 24 test files above (scripted; per-file sites listed). Lane: Service lane (no `GitSyncService.kt` edit).

##### Task 1.3d4: `GateAssertingGitRepository`, no-fallback audit and wired fixtures (S) (W3, after 1.3d1)
- **Creates `GateAssertingGitRepository` (pre-mortem P2-8, adversarial C7: it used to be created by Task 1.8b in W8, after Task 1.4a1 (W5) needed it)**: `BT/git/GateAssertingGitRepository.kt` (new), a delegating `GitRepository` that throws `AssertionError` when a mutating method is called without the gate key held (`RepoOpGate.isHeld(key)`); Task 1.4a1's tests use it from day one, and Task 1.8b only makes it the default in every sync fixture. New `NoGateFallbackAuditTest` (source audit): `RepoOpGate()` appears in main source sets only in `WiringRoots`. It takes an injectable `LockVerdict` for `beginOwnedOperation` defaulting to `NoLock` (the fail-closed production defaults of Task 1.2d1 would otherwise pause every fixture). Sets the gate in `AU/wiring/WiredAppFixture.kt:46` and `JT/wiring/DesktopWiredAppFixture.kt:109`.
- Files: `BT/git/GateAssertingGitRepository.kt` (new), `BT/wiring/NoGateFallbackAuditTest.kt` (new), `AU/wiring/WiredAppFixture.kt`, `JT/wiring/DesktopWiredAppFixture.kt`. REGISTER: `AllBusinessTests.kt`.

#### Story 1.4: Single flight at the service (Isolate via seam)
**As a** user, **I want** a second Sync press to do nothing harmful, **so that** my sync is not cancelled and no lock error appears.
**Acceptance Criteria**:
- Second manual press joins (Success Metric 1).
  - *Given* a Manual sync in `Committing`, *When* the user presses Sync again, *Then* the second call returns `Left(GitError.SyncInProgress)`, `inFlight.value` is unchanged (`origin=UiPress, startedAtMs=T0`), `syncState` stays `Committing`, and a log line `decision=coalesced` is written; a record/lock-write failure in the first run does not leave the gate held (acquire is inside the try/finally structure; test throws from `beginOwnedOperation`).
- One follow-up only, and it is not lost.
  - *Given* a running sync and three further `UiPress` calls, *When* the run finishes, *Then* exactly one additional run starts (`rerun started` logged once) and a Scheduled trigger in the same window produces `decision=skipped`, 0 runs; *Given* a press that loses `tryAcquire` at the exact moment the holder is releasing (model-based interleaving test), *Then* the press either coalesces into the follow-up or acquires; it is never dropped.
- Lifted guard is refused, not queued.
  - *Given* a running sync, *When* a `liftMassChangeGuard = true` press arrives, *Then* it returns `SyncInProgress` with the "Wait for the running sync to finish, then confirm again" copy, the rerun bit stays unset, and no follow-up carries the flag.
- Every mutating entry point holds the gate, before any state write.
  - *Given* `GateAssertingGitRepository` (created in Task 1.3d4, so it exists before this story's tests) that throws if the gate is not held, *When* each of sync, fetchOnly, commitLocalChanges, resolveConflicts, applyJournalMerge, abortActiveMerge, deepen, repairBranch, changeBackBranch runs, *Then* none throws; and *Given* any of them called while a sync is in `Fetching`, *Then* `syncState` is unchanged.
- Confirm before the gate.
  - *Given* `abortActiveMerge` with a `confirm` suspended on a dialog, *When* a scheduled tick fires, *Then* the tick runs normally (the gate is not held across the dialog); *When* confirm returns true, *Then* the gate is acquired and the repository state is re-checked.
- Rate-limit retry ordering and survival.
  - *Given* a scheduled rate-limit retry pending and a Manual press that coalesces, *When* the press returns, *Then* the retry is still scheduled; *Given* the press acquires the gate, *Then* the retry is cancelled after acquire; *Given* `RateLimited(retryAfterSeconds = 0)` published while the failing run still holds the lease, *Then* the retry waits for `awaitFree` and then runs (not dropped).
- Existing data-safety guards intact, and the merge unit.
  - *Given* the PR #676 unapplied-disk-content fixtures, *When* run through the gated `sync`, *Then* `GitSyncConflictReloadE2ETest` and `DiskAppliedStateTest` pass with assertions and fixtures unchanged (the only edit to those files is the one `gate = RepoOpGate()` constructor argument added by the Task 1.3d3 codemod; `GitSyncConflictReloadE2ETest.kt:93` constructs the service directly).
- Release is unconditional (adversarial C2; the PR #662 never-stuck class).
  - *Given* a run whose `endOwnedOperation` throws `CancellationException` or `RuntimeException` in its `finally`, *And given* a run cancelled mid-`commit`, *When* each finishes, *Then* the gate is free (`isHeld == false`) and a fresh `sync` acquires and runs; `releaseOrHandOff` is a non-`suspend` function (reflection test) and every `tryAcquire`/`acquire` result is released in a `finally` ending in `releaseOrHandOff` (source audit).
- Unreadable config fails closed (architecture R3).
  - *Given* run A holds `RepoOpGate.keyFor(resolvedGitDir)` (canonical path) and the config read for run B throws or returns `Left` (twice), *When* B starts, *Then* B returns `SyncInProgress` (its service's `inFlight` is set) or, if idle, only the config-error path runs and **no repository method is called**; `syncState` is not overwritten while A is running. Clone key: `RepoOpGate.keyFor(File(destinationPath, ".git"))` equals the key later derived from the resolved config for the same directory, with and without a trailing slash and through a symlink. `ResolveGateKeyFailureTest` (U-E1-13): a config read that throws, a `.git` that cannot be resolved and a canonicalisation `IOException` each yield `Unreadable` while another run holds the repo key; the run coalesces or runs only the config-error path, and **no repository method is called under any other key**.
- Origin/trigger mapping.
  - *Given* every `SyncOrigin` that calls `sync()`, *Then* `toTrigger()` is `Manual` for UiPress/FollowUp and `Automatic` for Scheduled/Worker/Resume/RateLimitRetry, and `FirstSyncConfirmation.kt` is unchanged.
**Files**: `CM/git/GitSyncService.kt`, `CM/git/SyncSingleFlight.kt` (new), `CM/git/SyncOrigin.kt` (new), `CM/git/SyncRunInfo.kt` (new), `CM/error/DomainError.kt`, `CM/git/RateLimitRetry.kt`, `CM/git/PeriodicSyncDriver.kt`, `CM/git/BranchRepairService.kt`, `CM/ui/screens/git/GitSetupScreenSaveLogic.kt`

##### Task 1.4e1: `GitError.SyncInProgress`, `SyncOrigin` enum, message mapping and copy (S) (W2)
- Add `SyncInProgress(heldBy: SyncOrigin?)` to `GitError` (and the plain enum `CM/git/SyncOrigin.kt` it refers to, moved here from Task 1.4a2 so this W2 task compiles; `toTrigger()` stays in 1.4a2) and handle it in `DomainError.toUiMessage` (~L382) and `toSyncErrorMessage` (L446), both exhaustive `when`s without `else`; update `DomainErrorTest.kt` lists at L41, L133, L261. `StaleLockFile` gains an optional `verdict: LockVerdict? = null` (no `when` change) and its copy at `DomainError.kt:390` and `:450` ("another process may be using the repository" / "Stale git lock file - tap to retry") is replaced with the Story 1.7 copy. `GitCloneWorkerData.encodeFailure` has an `else` (safe).
- Files: `CM/error/DomainError.kt`, `CT/error/DomainErrorTest.kt`, `CM/git/SyncOrigin.kt` (new, enum only)

##### Task 1.4e2: `SyncInProgress` consumers in the runners and tick mapping (S) (W3, after 1.4e1)
- `ScheduledSyncRunner.runFull` (L107-118: explicit `SyncInProgress -> TickOutcome.Skip(SkipReason.Busy)`, no `consecutiveFailures++`, no backoff) and its `fetch(...).toTickOutcome()` path, whose definition is `CM/git/PeriodicSyncDriver.kt:52-55` (`internal fun Either<GitError, FetchResult>.toTickOutcome()` maps everything but `Offline` to `Failure`; it gains `SyncInProgress -> Skip(SkipReason.Busy)`); `GitSyncTask.liveTick`; `TaskOutcomeMapping` (`CM/git/TaskOutcomeMapping.kt`; it renders `SkipReason.Busy` as "busy" already and must stay out of `jobs/schedule`). Tests: a lost `tryAcquire` race never records a failed tick or starts backoff, with **one test per path** (`runFull` and the `fetch` path through `toTickOutcome`) so a copy-paste of only one is caught.
- Files: `CM/git/ScheduledSyncRunner.kt`, `CM/git/PeriodicSyncDriver.kt`, `CM/git/GitSyncTask.kt`, `CM/git/TaskOutcomeMapping.kt`, `BT/git/SyncInProgressConsumersTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

##### Task 1.4e3: `SyncInProgress` consumers in the UI-side coordinators (S) (W3, after 1.4e1)
- `GitSyncCoordinator`, `SyncSafetyActions` and `BranchRepairService` map `SyncInProgress` to a non-failure (no `SyncState.Error`, no red); each consumer is covered by a test.
- Files: `CM/ui/GitSyncCoordinator.kt`, `CM/ui/SyncSafetyActions.kt`, `CM/git/BranchRepairService.kt`, `BT/git/SyncInProgressUiConsumersTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

##### Task 1.4a1: `resolveGateKey`, `SyncSingleFlight` wrapper and the acquire/release structure (M) (W5, Service lane, after 1.3d3)
- (Triad repair 1: the former Task 1.4a, rated L-risk across the gate, atomic rerun, follow-up launch and origin mapping, is split; rerun, heartbeat and origin mapping moved to 1.4a2.) Read-only `resolveGateKey(graphId)` first (catches `Throwable`, rethrows `CancellationException`, retries once, returns `Resolved(RepoOpGate.keyFor(resolvedGitDir))` with the **canonical-path key** or `Unreadable`, including when canonicalisation fails; `Unreadable` never selects another key: coalesce if the service's own `inFlight != null`, otherwise run only the config-error path), gate acquired immediately after and before any `_syncState` write, `rateLimitRetry.cancel()` or `gitSyncBusyCounter.begin()`. Wrap each public mutating method as `singleFlight(origin) { ...Locked() }`; inner methods call each other only via the `...Locked` variants (no reentrant acquire). Structure (ADR-001 decision 4): `lease = tryAcquire ?: return coalesced`, then `try { beginOwnedOperation (verdict -> OwnerAlive: SyncInProgress; Unknown/NoRecord: StaleLockFile(verdict) published, nothing deleted); counter.begin(); body } finally { try { withContext(NonCancellable) { counter.end(); endOwnedOperation } } finally { releaseOrHandOff(lease) } }`. Pipeline body changes **only at the four named seams** (ADR-001 decision 9a-d except the NonCancellable wrapper and the `ensureActive()` placements, which are Task 1.5a): step 3b replaced by the claim, `rateLimitRetry.cancel()` moved after acquire. Coalesced returns do not call `counter.begin()`. The constructor parameter already exists (Task 1.3d1) and tests use `GateAssertingGitRepository` from Task 1.3d4.
- Files: `CM/git/GitSyncService.kt`, `CM/git/SyncSingleFlight.kt` (new), `CM/git/SyncRunInfo.kt` (new), `BT/git/GitSyncServiceSingleFlightTest.kt` (new), `BT/git/ResolveGateKeyFailureTest.kt` (new, U-E1-13). REGISTER: `AllBusinessTests.kt`.

##### Task 1.4a2: Atomic rerun and follow-up, owner heartbeat start, origin mapping (S) (W5, Service lane, after 1.4a1)
- `SyncOrigin.toTrigger()`; the atomic one-bit rerun: `releaseOrHandOff(lease)?.let(launch FollowUp on the service's supervised scope)`; a press with `liftMassChangeGuard = true` that loses is refused and never sets the bit; a press that loses to a `Quiesce` holder never sets the bit. The 5 s owner heartbeat (`touchOwnedOperation`) starts after a successful claim on the service's scope and is cancelled in the unconditional `finally` before the record end.
- Files: `CM/git/GitSyncService.kt`, `CM/git/SyncOrigin.kt` (adds `toTrigger()`; the enum exists since Task 1.4e1), `BT/git/SyncOriginTriggerMappingTest.kt` (new), `BT/git/SyncSingleFlightModelTest.kt` (new, U-E1-15), `BT/git/GateKeyResolutionTest.kt` (new; holds the quiesce-key-equals-sync-key test U-E1-14). REGISTER: `AllBusinessTests.kt`.

##### Task 1.4b: `inFlight`, driver and `isBusy` wiring, retry survival (S)
- `inFlight: StateFlow<SyncRunInfo?>` (incl. `followUpQueued`) written only by the gate holder; replace the driver's `isBusy` lambda (`GitSyncService.kt:~229`) with `gate.isHeld`; keep `gitSyncBusyCounter` only as the quiesce secondary signal. `RateLimitRetry.schedule` job calls `gate.awaitFree(key)` before the retried operation; `cancel()` calls (L296, L542) now sit after a successful acquire.
- Files: `CM/git/GitSyncService.kt`, `CM/git/PeriodicSyncDriver.kt`, `CM/git/RateLimitRetry.kt`

##### Task 1.4c: Conflict-pending semantics and confirm-before-gate (S)
- Gate is released at `ConflictPending`; a merge-in-progress repo state blocks a new sync via existing `guards.repositoryState` (L336). `resolveConflicts`/`applyJournalMerge` re-acquire. `abortActiveMerge(graphId, confirm)` awaits `confirm` before acquiring and re-checks the repo state after. Test: tick during pending merge is skipped with the guard message; resolve then succeeds; dialog open does not block ticks.
- Files: `CM/git/GitSyncService.kt`, `BT/git/GitSyncServiceConflictResolutionTest.kt` (extend)

##### Task 1.4d1: Setup screen, branch repair and graph-manager callers (S)
- Gate (or allow-list with a reason in `git-gate-allowlist.txt`) the callers the plan had missed (setup and clone keys come through the one `RepoOpGate.keyFor(String)` normaliser, never a hand-built string): `GitSetupScreenSaveLogic.kt:98` (direct `gitRepository.fetch(config)` on a form-built config: take the gate keyed by that config's `repoRoot`), `BranchRepairService.kt:58` (probe fetch, reached through `GitSyncService.changeBackBranch()` L138-143: gated by the service wrapper; edit only if a direct call remains), `GraphManager.kt:530`. Tests for each.
- Files: `CM/ui/screens/git/GitSetupScreenSaveLogic.kt`, `CM/git/BranchRepairService.kt`, `CM/db/GraphManager.kt`, `BT/git/EntryPointGateTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

##### Task 1.4d2: Clone launchers and workers (S) (after 1.4d1)
- `JvmGitCloneWorkerLauncher.kt:50` and `AndroidGitCloneWorker`/`GitCloneWorker.kt:132` (clone: no `GitConfig` exists yet, so the key is derived from the clone's destination `.git` path via the canonical `RepoOpGate.keyFor(File)`; the clone worker takes the gate, `Skipped(Busy)` if held); `SyncMain` is Task 1.2e. Tests for each path, including the key equality `RepoOpGate.keyFor(File(destinationPath, ".git"))` with and without a trailing slash and through a symlink.
- Files: `JM/git/JvmGitCloneWorkerLauncher.kt`, `AM/git/AndroidGitCloneWorker.kt`, `CM/git/GitCloneWorker.kt` (paths as found), `BT/git/CloneEntryPointGateTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

#### Story 1.5: Cooperative cancel; gate held until the blocking call returns
**As a** user, **I want** Cancel to be safe, **so that** cancel followed by Sync never overlaps a still-running git call and a merge is never left unreloaded.
**Acceptance Criteria**:
- Cancel then immediate re-press does not overlap.
  - *Given* a `FakeGitRepository.commit` blocked on a thread latch inside `withContext(IO)`, *When* the UI Cancel is pressed and Sync is pressed 50 ms later, *Then* the second returns `SyncInProgress`, the gate is released only after the latch opens, and `SyncState` is not `Idle` while the call is still running.
- Commit phase is non-cancellable.
  - *Given* cancel requested during `commit`, *When* the call returns, *Then* the commit completed (HEAD advanced) and the pipeline stops before `fetch`.
- **Cancel during fetch does not merge (adversarial C4).**
  - *Given* cancellation requested while `fetch` is in flight and the fetch then returns `hasRemoteChanges = true`, *When* the pipeline resumes, *Then* `ensureActive()` at the unit entry throws before `_syncState = Merging`, `merge()` is never called, the working tree is untouched and the gate is released.
- **A conflict or commit failure in the unit never reaches push (pre-mortem P1-4, adversarial C5).**
  - *Given* `merge` returns conflicts (and separately the unit's commit step fails), *When* the extracted `mergeUnit()` returns `MergeUnitOutcome.Exit`, *Then* the pipeline returns that `Left`, `push` is **never called** (asserted on the recording fake, in `GitSyncServiceConflictResolutionTest` and `GitSyncCancelBoundaryTest`), and `NonCancellableReturnAuditTest` (source audit) finds no `return@withContext` inside any `withContext(NonCancellable)` block of `GitSyncService.kt`.
- **Merge unit keeps the #676 invariant.**
  - *Given* cancel requested immediately after `merge()` returns (fake `merge` completes, the next suspension point is cancelled), *When* the unit finishes, *Then* `reloadMergedFiles` still ran for every changed path (the DB reflects the merged files), `endGitMerge` ran, and only then does the pipeline stop.
- **The unit contains DB failure and is observable (adversarial C3).**
  - *Given* the database is closed (as `GraphManager.switchGraph`/`shutdown` does) while `reloadFiles` runs inside the unit, *Then* no exception escapes (a recording default uncaught-exception handler sees none), `reload aborted reason=...` is logged, `reloadIncomplete` is set, the gate lease is released, and re-opening the graph with a real `GraphLoader` picks up the merged files from disk; *Given* a reload of 1,000 paths, *Then* progress is logged per 200-path chunk and a hold above 30 s logs a `heldMs` warning; no timeout exists inside the unit.
**Files**: `CM/git/GitSyncService.kt`, `CM/git/SyncRunGuard.kt`, `CM/ui/GitSyncCoordinator.kt`

##### Task 1.5a: `NonCancellable` merge unit and step-boundary cancellation (L)
- **Class L, deliberately not split (triad repair 1):** it is one extraction inside a single 806-line hot file (`GitSyncService.kt`) with three `return@withContext` rebinds (VERIFIED at `GitSyncService.kt:429`, `:456`, `:464`) and a `SyncRunGuard` contract change; splitting would leave an intermediate state in which the merge region is half-extracted and the #676 reload invariant unprotected. It carries exactly 5 hand-written files (two production, three test; the cap) and a hard prerequisite (the Task 1.1b JGit result). The L class rests on the single-file extraction risk above, not on the file count; with five files it is also the largest task by files, which is a second reason not to add any scope to it.
- **Extract, do not wrap (pre-mortem P1-4).** Move `merge` + `beginGitMerge` + auto-resolve + the existing `try/finally { reloadMergedFiles }` into `private suspend fun mergeUnit(...): MergeUnitOutcome` (sealed `Continue(remoteCommitsMerged, autoMergedPaths)` / `Exit(Either.Left)`) executed under `withContext(NonCancellable)` **inside that function**, with a body that contains no `return@withContext` (the three exits at `GitSyncService.kt:429`, `:456` and `:464` become `return MergeUnitOutcome.Exit(...)`), and translate it at exactly one call site in the pipeline; **no `ensureActive()` between `merge()` returning and the end of the reload**. `ensureActive()` before `commit`, before `fetch`, before `push`, after the merge unit, and **once immediately before the unit (after `fetchResult` is known, before `_syncState = Merging`; outside `NonCancellable`, strictly before `merge()` is called)**; commit and push-ref-update are also `NonCancellable`. State in the PR exactly which pipeline lines changed (the four named seams) and that the reload-in-`finally` and #662 never-stuck guards are untouched. `SyncRunGuard.cancelled()` must not publish `Idle` while a non-cancellable phase runs (keep the running state until return). The Cancel button shows "Stopping..." and is disabled until the call returns (UX 2.2 step 7). Uses the result of Task 1.1b. Adds `BT/audit/NonCancellableReturnAuditTest.kt` (U-E1-46) and extends `GitSyncServiceConflictResolutionTest` with "push is never called after a conflict in the unit".
- Files: `CM/git/GitSyncService.kt`, `CM/git/SyncRunGuard.kt`, `BT/git/GitSyncCancelBoundaryTest.kt` (new), `BT/audit/NonCancellableReturnAuditTest.kt` (new), `BT/git/GitSyncServiceConflictResolutionTest.kt` (extend). REGISTER: `AllBusinessTests.kt`.

##### Task 1.5b: Contain and observe the merge unit (reload chunking, closed-DB containment, progress and `heldMs` log) (S) (Service lane, after 1.5a)
- In `reloadMergedFiles` (`GitSyncService.kt:255-264`): reload in chunks of at most 200 paths with a `reload progress done/total` log line; catch every non-cancellation `Throwable` from `beginGitMerge`/`reloadFiles` inside the unit, log `reload aborted reason=<class>`, set `reloadIncomplete`, keep the existing `finally { withContext(NonCancellable) { endGitMerge() } }`; `GitSyncService.shutdown()` never cancels the unit; `gate heldMs` warning above 30 s; quiesce timeout message "a sync is still finishing". Tests: closed-DB during the unit (recording uncaught handler), chunk progress, reopen reconciles from disk with a real `GraphLoader`, `GraphManagerDatabaseLifecycleTest` extended with a merge in flight.
- Files: `CM/git/GitSyncService.kt`, `BT/git/GitSyncMergeUnitContainmentTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

#### Story 1.6: Every trigger goes through the gate; runners never acquire
**As a** user, **I want** scheduled, worker, retry and UI triggers to coalesce the same way, **so that** background work never fights a manual sync.
**Acceptance Criteria**:
- Worker vs foreground in one process (Success Metric 1, entry points).
  - *Given* `GitSyncWorker` fires while a foreground Manual sync is in `Fetching`, *When* the worker runs via `GitSyncTask.liveTick`, *Then* the outcome is `Skipped(Busy)`, the worker returns without error, and no second `fetch` is issued.
- Runner does not self-coalesce (adversarial B3).
  - *Given* an idle repo and `ScheduledSyncRunner.runFull`, *When* a tick runs, *Then* the sync actually executes (the runner holds no lease; the service wrapper acquires), and *Given* the service returns `SyncInProgress`, *Then* the tick outcome is `Skip(Busy)`, `consecutiveFailures` is unchanged and no backoff starts.
- Cold worker vs live service, both directions.
  - *Given* `ColdSyncRunner` mid-fetch holding the gate, *When* the service is created and the user presses Sync, *Then* the press coalesces and the cold fetch is not interrupted; *Given* a live sync holding the gate, *When* the cold worker runs, *Then* it returns `Skipped(Busy)` without waiting.
- Skipped trigger does not hide a prior failure.
  - *Given* run 1 ended in `Error(FetchFailed)`, *When* a Scheduled tick then coalesces into nothing because a Manual run is active, *Then* the UI still shows run 1's failure until run 2 publishes its own result.
- Second-press feedback channel (F9).
  - *Given* a coalesced `UiPress`, *Then* `GitSyncCoordinator` emits one `AlreadySyncing` UI event (collected as a polite live-region announcement), with no snackbar, no toast and no `syncState` change.
**Files**: `CM/git/ScheduledSyncRunner.kt`, `CM/git/GitSyncTask.kt`, `CM/git/ColdSyncRunner.kt`, `CM/ui/GitSyncCoordinator.kt`, `CM/ui/SyncSafetyActions.kt`, `AM/git/WorkManagerSyncScheduler.kt`

##### Task 1.6a: UI and safety-action callers (S)
- `triggerSync`/`triggerFetchOnly` map `SyncInProgress` to the `AlreadySyncing` event and no state change; `SyncSafetyActions.kt:101` (`liftMassChangeGuard = true`) is **refused** while a run is in flight with "Wait for the running sync to finish, then confirm again" (never carried through a coalesce). Test both.
- Files: `CM/ui/GitSyncCoordinator.kt`, `CM/ui/SyncSafetyActions.kt`, `BT/ui/GitSyncCoordinatorCoalesceTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

##### Task 1.6b: Scheduled/worker/cold callers and `OneShotConflict` audit (S)
- Runners hold no lease (they call the service and read `isHeld` advisory only); `ColdSyncRunner` on the shared gate with `tryAcquire`/`Skipped(Busy)`; `PeriodicSyncDriver` busy check is advisory. Grep every `runOnce(...)` for git-sync passing `OneShotConflict.Replace` and change to KEEP at the git call sites only; nothing in `jobs.schedule` may import `RepoOpGate`, `GitSyncService` or any `git` type (`JobsScheduleImportDirectionTest`). Verified there is no `runOnce(... Replace)` for git-sync in androidMain today (only `DepthModelDownloader`), so this is likely an assertion test. Extend `GitSyncWorkerGateAndRecordTest`, `ColdSyncRunnerTest`, `PeriodicSyncDriverTest`.
- Files: `CM/git/ScheduledSyncRunner.kt`, `CM/git/ColdSyncRunner.kt`, `AM/git/WorkManagerSyncScheduler.kt`, the three tests

#### Story 1.7: UI states and copy (own run is never an error; amber is not a dead end)
**As a** user, **I want** calm messages that resolve themselves, **so that** a running or paused sync does not look like data loss.
**Acceptance Criteria**:
- Running is neutral.
  - *Given* `inFlight != null`, *When* the status sheet renders, *Then* the Sync button shows "Syncing..." with `enabled=false`, `syncNowDisabledReason="A sync is already running"` and a `stateDescription` for TalkBack; no red element.
- Follow-up shown (F8).
  - *Given* a UiPress mid-run set the rerun bit, *When* the row/sheet renders, *Then* it shows "One more sync will run after this"; with no queued follow-up the text is absent; exactly one follow-up runs.
- Unsure is amber, then escalates (F7).
  - *Given* verdict `OwnerUnknown`, *When* the badge renders, *Then* text is "Sync paused - retrying automatically", color is the new warning token, row height >= 48 dp, tapping opens Details (not a retry), Details shows "try 1 of 2" and a countdown; *Given* the two automatic retries (about 30 s and 2 min, `PausedRetryPolicy`) both fail, *Then* the row reads "Couldn't sync. Your notes are saved on this device." in the error color with "Sync now" and "Details" (each >= 48 dp; "Try again" is not used for sync); the retry never runs while the gate is held.
- Amber token (F15).
  - *Given* light and dark themes, *When* the warning token pair is checked, *Then* text/background contrast is >= 4.5:1 in both; no `Color(0xFFF59E0B)` literal remains in the sync UI files touched.
- Manual removal is confirm-gated.
  - *Given* verdict `NoRecord`, *When* the user taps "Remove lock file..." in Details, *Then* a confirm dialog states "Only if no other sync or git app is running" with initial focus on Cancel, and nothing is deleted until confirmed; the one-tap row never shows file jargon (`index.lock`, "stale").
- Notes-are-safe sentence only when the #676 guard confirms local content is intact; otherwise omitted.
- **Retry now (triad repair 1, UX-51).**
  - *Given* a paused state with a retry pending, *When* the sheet renders, *Then* an enabled "Retry now" (>= 48 dp) is shown (not a disabled Sync button); *When* it is pressed, *Then* the pending timer is cancelled and exactly one extra `sync` attempt runs through the gate with origin `RateLimitRetry`-class automatic semantics, **the "try n of 2" number does not move and the automatic budget is not consumed** (triad repair 2; if the attempt ends in an unknown owner the pending automatic try is re-armed with its original delay); *Given* a run already holds the gate, *Then* the press coalesces (no error, `AlreadySyncing` announcement, no extra retry scheduled); *Given* five presses in a row, *Then* at most one attempt runs at a time, the tries shown stay at their value and the state never reaches Failed from presses alone; *Given* Failed, *When* "Sync now" is pressed and ends in an unknown owner, *Then* a fresh two-try amber cycle starts (budget restarted).
- **Live region on Paused and Failed (UX-04, triad repair 2).**
  - *Given* the state changes to Paused, *Then* one polite live-region announcement "Sync paused. Retrying automatically." is made (once per entry, not on each recomposition or each retry tick); *Given* Failed, *Then* the Failed text is announced once.
- **Action vocabulary (UX-69).**
  - *Given* any sync state, *Then* the only start label is "Sync now" and "Retry now" appears only in Paused; the string "Try again" appears in no sync state.
- **Copy asserts no cause (triad repair 1).**
  - *Given* verdict `OwnerUnknown`, *Then* the sheet body reads "We couldn't confirm that nothing else is syncing, so we paused to protect your notes. We'll retry automatically." and contains neither "another git operation" nor "running"; Details shows "lock owner unknown ... try 1 of 2".
- **Durability (triad repair 1, UX-52).**
  - *Given* a paused retry scheduled on the service scope (an in-process coroutine, `RateLimitRetry.kt:22` pattern) and the service is rebuilt (simulated process death), *Then* no paused state is restored, the next `sync` claims again (a provably dead owner clears silently, otherwise a fresh two-try cycle starts); *Given* the scheduled retry time has passed while the app was backgrounded, *When* `pausedRetry` is first collected again, *Then* the overdue retry runs once; *Given* the WorkManager periodic task (`WorkManagerTaskScheduler.kt:158,185`) fires while the state is paused, *Then* it goes through the gate and claim and never bypasses the retry budget.
**Files**: `CM/ui/components/SyncStatusBadge.kt`, `CM/ui/components/SyncStatusSheet.kt`, `CM/git/SyncStatusSheetModel.kt`, `CM/git/PausedRetryPolicy.kt` (new), `CM/ui/theme/` warning token file (path as found, new if absent), `AU/ui/components/SyncStatusBadgeTest.kt`

##### Task 1.7a: Model + presentation mapping (pure) (S)
- Extend `SyncStatusSheetModel` to derive Running / FollowUpQueued / Paused / Failed from `(syncState, inFlight, lastVerdict, pausedRetry)`; defines the `PausedRetryInfo` data class (new file `CM/git/PausedRetryInfo.kt`, with `nextRetryAtMs` and `tryNumber`) that Task 1.7c populates, and the `retryNow` action (a function type on the model, enabled iff a retry is pending) that 1.7b binds and 1.7c implements, and the one-announcement-per-entry rule for Paused and Failed; pure tests in `BT` for every transition, including the escalation after two retries.
- Files: `CM/git/SyncStatusSheetModel.kt`, `BT/git/SyncStatusSheetModelTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

##### Task 1.7b: Compose changes, warning token and Robolectric tests (M)
- `heightIn(min = 48.dp)` on the Error/Paused row; **the row, sheet and buttons satisfy UX-68 at 360 dp, 200 percent font and RTL (text wraps, the primary action stays reachable, no horizontal scroll; test `SyncStatusTextScaleTest` in `AU/ui/components`, X-68b)**; the Failed action is labelled "Sync now" (UX-69); the Paused live-region announcement (UX-04); enabled "Retry now" action in the paused sheet (binds the `retryNow` action declared on `SyncStatusSheetModel` by Task 1.7a; Task 1.7c implements it in the service, so 1.7b uses no symbol introduced in its own wave) and the cause-neutral paused copy; amber token pair `warning`/`onWarning` with 4.5:1 contrast in both themes (replacing the hard-coded `Color(0xFFF59E0B)` at `SyncStatusBadge.kt:165,203,255,348` and `SyncStatusSheet.kt:125` where touched); confirm dialog; announcements via live region (`AlreadySyncing`, Running/Paused/Failed transitions); "One more sync will run after this" line; "Stopping..." Cancel state. Robolectric tests in `androidUnitTest` per CLAUDE.md. REGISTER: `AllAndroidUnitTests.kt`.
- Files: `CM/ui/components/SyncStatusBadge.kt`, `CM/ui/components/SyncStatusSheet.kt`, theme token file, `AU/ui/components/SyncStatusBadgeTest.kt`, `AU/ui/components/SyncStatusTextScaleTest.kt` (new)

##### Task 1.7c: Automatic paused retry, Retry now, durability and escalation (S)
- `PausedRetryPolicy` (two retries at about 30 s and 2 min) scheduled by the service after `StaleLockFile(OwnerUnknown/NoRecord)` on a service-owned scope, published as `pausedRetry: StateFlow<PausedRetryInfo?>` (`nextRetryAtMs`, `tryNumber`), using `awaitFree`; after the second failure state becomes the red Failed with "Sync now". `retryNow()` cancels the pending timer and runs one extra attempt through the normal gate (coalescing-safe); it does **not** advance `tryNumber` or consume the budget, and if the attempt ends in an unknown owner the pending automatic try is re-armed with its original delay (repeated calls cannot add retries or reach Failed; triad repair 2). Failed's "Sync now" is a fresh run that restarts the budget. **Durability (verified mechanism, not a new one):** the timer is an in-process coroutine like `RateLimitRetry.kt:22`, so it is lost with the process by design and nothing is persisted; the durable re-evaluation is the existing WorkManager periodic task (`WorkManagerTaskScheduler.kt:158,185`, which reaches `sync` through the gate and claim); an overdue retry (`nextRetryAtMs < now` on first collection) runs once. Tests with virtual time, including a simulated process death (rebuilt service has no paused state and re-claims) and five rapid `retryNow` calls.
- Files: `CM/git/PausedRetryPolicy.kt`, `CM/git/GitSyncService.kt` (Service lane, after 1.5a), `BT/git/PausedRetryPolicyTest.kt` (new), `BT/git/PausedRetryNowAndDurabilityTest.kt` (new; also asserts Retry now leaves `tryNumber` unchanged and Failed "Sync now" restarts the budget). REGISTER: `AllBusinessTests.kt`.

#### Story 1.8: Bypass-proof guard, observability, owner checklist
**As a** maintainer, **I want** a structural test, **so that** a new mutating entry point cannot skip the gate.
**Acceptance Criteria**:
- New entry point cannot bypass.
  - *Given* a source audit listing the `GitRepository` mutating methods (`add`, `commit`, `merge`, `fetch`, `push`, `checkout`, `reset`, `stageSubdir`, `removeStaleLockFile`, `forceRemoveLock`, `clone`, ...), *When* a call site in `CM/git/**`, `CM/ui/**`, `AM/git/**` or `JM/**` outside an allow-listed gated scope is added, *Then* `GitGateAuditTest` fails naming file and line; the allow-list contains the entries from Tasks 1.4d1 and 1.4d2 with reasons.
- Runtime guard over all entry points.
  - *Given* `GateAssertingGitRepository` used by every `GitSyncService*Test` fixture, *When* any test calls a mutating method without the lease, *Then* it throws `AssertionError`.
- Log lines present.
  - *Given* a coalesced trigger, *When* the Logs sink is read, *Then* it contains `sync origin=ui decision=coalesced`, and each `LockVerdict` has a `lock verdict=` line.
**Files**: `BT/git/GitGateAuditTest.kt` (new), `BT/git/GitSyncServiceTestFixtures.kt`, `kmp/src/businessTest/resources/git-gate-allowlist.txt` (new)

##### Task 1.8a: `GitGateAuditTest` and the project-wide source audits (M)
- (Phase 4 patch: S to M.) Model on `WiringCompletenessTest`: read source resources (add a `filegroup` in `//kmp/src/businessTest/kotlin:wiring_*_as_resources` for `git/`, `ui/` and `jvmMain` dirs if not bundled), regex call sites of mutating repository methods, assert each lies inside a function reachable only through `singleFlight`/`withLease` (allow-list file with reasons). Also from validation.md: `NewCodeAuditTest` (U-E1-37/X-48: files added by this project import no `java.net`, `okhttp` or `android.util.Log`, and new status/error text is never toast-only) and `ScopeEscapeAuditTest` (U-E2-16: no `rememberCoroutineScope()` value flows into a constructor stored by `remember { }` in files added by this project), and (triad repair 1, UX-47) `ExitPathAuditTest` (X-47): for every presentation-model state value (`SyncStatusSheetModel`, the camera dialog state, the value-editor error set, the known-distances presenter, the tool-strip state) iterate the enum entries or sealed subclasses and assert a non-empty `exits`; a new state without exits fails the test. REGISTER: `AllBusinessTests.kt`, `kmp/src/businessTest/kotlin/BUILD.bazel`.
- Files: `BT/git/GitGateAuditTest.kt`, `BT/audit/NewCodeAuditTest.kt` (new), `BT/audit/ScopeEscapeAuditTest.kt` (new), `BT/ux/ExitPathAuditTest.kt` (new), `kmp/src/businessTest/resources/git-gate-allowlist.txt`

##### Task 1.8b: Gate-asserting fake as the default in all sync fixtures, log assertions, service-level two-JVM test (S)
- `GateAssertingGitRepository` already exists (Task 1.3d4); make it the default in `GitSyncServiceTestFixtures.kt`; add log-line assertions for each `SyncDecision` and each `LockVerdict` (`SyncObservabilityLogTest`); add `CrossProcessSyncOverDeadRecordTest` (I-E1-07: two JVMs run `GitSyncService.sync`, and `SyncMain` vs the desktop app, over a dead record and `index.lock` seeded by a killed third process; the dead owner is cleared once and both end synced or coalesced).
- Files: `BT/git/GitSyncServiceTestFixtures.kt`, `BT/git/SyncObservabilityLogTest.kt` (new), `JT/git/CrossProcessSyncOverDeadRecordTest.kt` (new). REGISTER: `AllBusinessTests.kt`, `AllJvmTests.kt`.

##### Task 1.8c: Owner device checklist for E1 (XS)
- Document in the PR (closes the story "owner-verified", not the CI-green merge): (1) tap Sync three times quickly during a slow sync; expect one run and one follow-up; (2) force-stop the app during a sync, relaunch: expect auto-clear or amber then red after two retries, never an unexplained red; (3) export logs: `lock verdict=` lines present; (4) on the owner's Android version record whether a dead same-uid pid reads ENOENT and whether `/proc/sys/kernel/random/boot_id` is readable (closes the two `/proc` unresolved questions); (5) reboot test: start a sync, force a power-off or reboot mid-run, relaunch: expect an automatic clear (`ClearedDeadOwner` by boot id), no amber.
- Files: PR description only

---

## Phase 2: Epic 2 — Annotator interaction (one-shot tools, tool lock, larger canvas)

### Epic 2.1: Tap a tool, drag once, back in Select, with room to reach the edges
**Goal**: Success Metric 2 end to end, with accessible alternatives and no overlay on the image.

#### Story 2.1: Audit before changing behavior
**As an** implementer, **I want** the facts that gate design confirmed, **so that** tests and defaults change deliberately.
**Acceptance Criteria**:
- Test audit recorded in the PR.
  - *Given* the whole annotator suite run with the default flipped to SELECT and auto-return on (a scratch branch run, not committed), *When* it finishes, *Then* the PR lists every failing test file:line and every test that commits more than one measurement after a single `selectTool`. The expected starting list (ADR-005 Consequences) is `AnnotationEditorCrashGuardTest.kt:147`, `UndoHistoryTest.kt:76`, `AnnotationGeometryTest`, `MeasurementInjectionTest`, `AnnotationEditorScreenKeyboardTest.kt:88-89`, `AnnotationToolbarShortcutTest`, `AnnotationEditorUnitTest.kt:84-191`, `AnnotationEditorHeightOffsetTest`; the earlier guess (`AnnotationEditorViewModelBranchTest`, `AnnotationEditorViewModelTest`) is not a hit list.
- Screen/export facts recorded.
  - *Given* `AnnotationEditorScreen.kt`, *When* read at ~L1687 and the export composable, *Then* the PR states where the depth status row renders, that export ignores the pasteboard, whether the 12 MP bitmap is drawn at full size when scaled down, which composable draws the app-level mic FAB and the flag that can hide it, and where the Settings screen hosts annotator prefs.
**Files**: read-only; findings in PR

##### Task 2.1a: Read-only audit (S)
- Run the suite on a scratch branch with the default flipped; record the failing set; read `AnnotationGestureLayer.kt:119-209`, `AnnotationPlacement.kt`, the depth row (~L1687), `AnnotationExporter` coordinate source, the render path (`graphicsLayer` vs canvas transform) for 12 MP, the FAB host and the Settings host. No code change.
- Files: none

#### Story 2.2: `ToolPolicy`, the ViewModel hook and Settings rows (ADR-005)
**As the** owner, **I want** the editor to return to Select after each measurement, **so that** pan and zoom work again with no extra tap, and I can switch it off.
**Acceptance Criteria**:
- Auto-return (Success Metric 2).
  - *Given* a fresh `AnnotationEditorViewModel`, *Then* `currentTool == SELECT` (was DISTANCE at `AnnotationEditorViewModel.kt:108`); *When* the user selects DISTANCE and `addPoint(0.2,0.3)` then `addPoint(0.6,0.7)` complete the measurement, *Then* `currentTool == SELECT`, the new measurement is selected, undo stack grew by exactly 1, and `inProgressPoints` is empty.
- Lock keeps the tool.
  - *Given* `stickiness = LOCKED` and DISTANCE active, *When* a measurement commits, *Then* `currentTool == DISTANCE`.
- Setting off keeps legacy behavior.
  - *Given* `annotator_auto_return_to_select = false`, *When* a measurement commits, *Then* the tool is unchanged.
- Multi-step sessions.
  - *Given* GRID_REF with `rulerSession` open and one tick placed, *When* a point commits but the session is unfinished, *Then* the tool stays GRID_REF; *When* `confirmRulerSession`/`confirmGridRefCalibration`/`confirmPlaneSession` runs, *Then* the tool becomes SELECT.
- Undo/redo do not re-arm and do not change the lock.
  - *Given* a committed measurement then undo then redo, *Then* the tool is SELECT; *Given* the user sets LOCKED and then undoes a measurement, *Then* `stickiness` is still LOCKED (`restoredOver` copies `stickiness` and `pendingKnownDistance` from the live state, as it does `currentTool`).
- Persistence failure.
  - *Given* `persistence.submit` fails after the optimistic append, *When* rollback runs, *Then* the measurement is removed and `nextToolAfterRollback` restores the previous tool only if the user has not chosen another tool; the strip shows "Couldn't save. Try again".
- Settings rows exist (rollback path for the default flip).
  - *Given* the Settings screen, *When* opened, *Then* "Return to Select after each measurement" (default on) and "Keep tools active" (default off, under Advanced) toggle the two keys, and the editor reads them on open.
**Files**: `CM/ui/annotate/ToolPolicy.kt` (new), `CM/ui/annotate/AnnotationEditorViewModel.kt`, `CM/ui/annotate/UndoHistory.kt`, the Settings screen file found in 2.1a, `CM/settings` keys file, `CT/ui/annotate/ToolPolicyTest.kt` (new), `BT/ui/annotate/AnnotationEditorViewModelAutoReturnTest.kt` (new)

##### Task 2.2a: Pure `ToolPolicy` with property test (S)
- `ToolStickiness`, `nextToolAfterCommit`, `nextToolAfterRollback`. kotest-property: for all tools/stickiness/settings combos, result is SELECT iff (autoReturn && ONE_SHOT && no multi-step session open); LOCKED never returns SELECT; idempotent for SELECT.
- Files: `CM/ui/annotate/ToolPolicy.kt`, `CT/ui/annotate/ToolPolicyTest.kt`. REGISTER: `CT` BUILD entry.

##### Task 2.2b1: ViewModel delegation and default tool (M) (VM lane, first)
- (Triad repair 1: the former Task 2.2b also repaired a nine-file test list; production and test repair are now separate runs, and the repair batches are bounded by the Task 2.1a output.) Add `stickiness` (and, for Story 3.6, `pendingKnownDistance: Boolean = false`) to `AnnotationEditorState`; call `nextToolAfterCommit` once after the optimistic append in `commitAnnotation` (L814), select the new measurement, return to Select on the three confirm methods, change the default to SELECT, read the two Settings keys via the existing prefs pattern, reset stickiness on editor exit, and have `restoredOver` copy `stickiness` and `pendingKnownDistance` from the live state. `AnnotationEditorViewModel.kt` gets one-line delegations only. Touches no Screen source.
- Files: `CM/ui/annotate/AnnotationEditorViewModel.kt`, `CM/ui/annotate/UndoHistory.kt`, `BT/ui/annotate/AnnotationEditorViewModelAutoReturnTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

##### Task 2.2b2: Repair batch A of tests that assume a DISTANCE default (S) (W3, after 2.2b1)
- Test-file-only edits: add `selectTool` before each commit, or construct with auto-return off where the test is about something else (never loosen an assertion without stating why it was red). **Bounded by Task 2.1a**: batch A takes the first four files of the 2.1a failing list in the order of the ADR-005 starting list; if 2.1a lists more than the starting eight, the 2.1a PR names additional batches (`2.2b4`, ... each at most five test files, same rules) and the integrator adds them to the wave table and counts; if it lists fewer, batch B shrinks.
- Files: `BT/ui/annotate/AnnotationEditorCrashGuardTest.kt` (L147), `BT/ui/annotate/UndoHistoryTest.kt` (L76), `CT/annotate/AnnotationGeometryTest.kt`, `BT/platform/measurement/MeasurementInjectionTest.kt`

##### Task 2.2b3: Repair batch B of tests that assume a DISTANCE default (S) (W3, after 2.2b1; disjoint from 2.2b2)
- Same rules as 2.2b2 for the second half of the starting list. The two `jvmTest` files keep their `// jvmTest-reason:` comments and run behind `scripts/jvm-display-check.sh`.
- Files: `BT/ui/annotate/AnnotationEditorUnitTest.kt` (L84-191), `BT/ui/annotate/AnnotationEditorHeightOffsetTest.kt`, `JT/ui/annotate/AnnotationToolbarShortcutTest.kt`, `JT/ui/annotate/AnnotationEditorScreenKeyboardTest.kt` (L88-89) (paths as found by Task 2.1a)

##### Task 2.2c: Settings rows (S)
- Two rows on the Settings screen found in 2.1a: "Return to Select after each measurement" and "Keep tools active" (Advanced), bound to `annotator_auto_return_to_select` and `annotator_sticky_tools`; Robolectric test that toggling updates the stored key. REGISTER: `AllAndroidUnitTests.kt`.
- Files: Settings screen file (path as found), `AU/ui/settings/AnnotatorSettingsRowsTest.kt` (new)

#### Story 2.3: Drag-measure, aim-place, tap-tap, disarm and nudge (ADR-007)
**As the** owner, **I want** to tap Distance, drag once from A to B and be back in Select, while keeping the loupe-precise path and a no-drag alternative, **so that** it works with one hand and with accessibility services.
**Acceptance Criteria**:
- One drag creates a measurement (at Fit or zoomed in).
  - *Given* DISTANCE armed with no pending point and a 1080x2400 viewport at Fit, *When* the user presses at (200,600), drags to (700,1500) (travel far above 48 dp) and lifts, *Then* one `DragMeasure(start,end)` is classified and one 2-point measurement is committed from the press position to the release position, and the tool is SELECT.
- Existing aim-place is unchanged (adversarial B4; `InProgressAnnotationCanvasGestureTest` stays green).
  - *Given* DISTANCE armed with no pending point, *When* the user presses, drags 30 dp to the crosshair and lifts, *Then* exactly one point is placed at the release position (`AimPlace`) and no measurement is made; *Given* a pending first point, *When* the user presses and drags any distance and lifts, *Then* the second point is placed at the release position and the measurement commits; *Given* a drag past 48 dp that returns within 48 dp before lifting, *Then* it is `AimPlace` and no ghost line remains.
- Drag-measure is gated by zoom (adversarial C10).
  - *Given* DISTANCE armed with no pending point and view scale 0.5 (below Fit), *When* the user presses and drags 200 dp and lifts, *Then* exactly one point is placed at the release position (`AimPlace`), no measurement is made, and the strip reads "Tap point A"; *Given* scale 1.0 or 3.0, *Then* the same drag is `DragMeasure`. A property test over `viewScale` in 0.25..8 asserts `DragMeasure` iff `viewScale >= FIT_SCALE` and the other ADR-007 conditions hold.
- A can be refined right after a drag-measure.
  - *Given* a drag-measure just committed, *When* the user presses on A's handle and drags, *Then* the loupe shows (`AnnotationGestureLayer.kt:264-271` path) and the move is one undo entry; the strip reads "Drag an end to fine-tune" (first three times).
- Other tools unchanged.
  - *Given* AREA, ANGLE, LABEL, GRID_REF or plane corners armed, *When* a long drag is made, *Then* it places one point at release (never a measurement).
- Feedback.
  - *Given* a drag past 48 dp while down, *Then* a marker at A and a ghost line A to finger are drawn (visible signal that release will commit); below 48 dp only the loupe shows.
- Counters for tuning the 48 dp threshold (triad repair 1).
  - *Given* a drag-measure commit, an aim-place, and an undo within 5 s of a drag-measure commit, *Then* the persisted log contains `placement gesture=drag-measure`, `placement gesture=aim-place` and `placement undo-after-drag-measure` respectively (one line each, no coordinates, no photo path); the lines are the `PlacementCounters` Task 2.7a reads.
- Second finger and cancel (one rule, one table: ux.md 3.3; triad repair 1, OC-9).
  - *Given* a drag in progress, *When* a second pointer goes down, *Then* only the in-flight placement is discarded (no point, no measurement), pinch/pan takes over, **the tool stays armed and an already-placed first point is kept** (the single behaviour; ADR-007 decision 4; consistency BLOCKER); *Given* a drag released off-canvas or outside the system-gesture inset rectangle (ADR-006 decision 6), or started in it (the press is then outside the canvas viewport and is not a placement), *Then* nothing is created, **the tool stays armed, an already-placed first point is kept**, and the strip shows "Cancelled" (every cancel keeps the tool armed; only tapping the active chip, tapping Select, or Back/Esc disarms); this cancel path is tested **with an injected non-zero inset and with a zero inset** (desktop, three-button navigation), so it is exercised in both (pre-mortem P3-12). The single constant `disarmOnOffCanvas = false` flips the off-canvas row if the owner answers OC-9 differently.
- Identical points.
  - *Given* tap-tap with both points within 2 image px, *Then* the second is rejected, the strip reads "Choose a second point away from the first" and the first point is kept.
- Disarm paths (F10).
  - *Given* a pending first point, *When* the user taps the active tool chip, taps Select, or presses Back/Esc, *Then* the point is discarded, no measurement exists and the tool is SELECT (chip tap, Select, and Back/Esc; the strip Cancel below keeps the tool armed); `discardFirstPoint()` is tested. *Given* a pending first point, *When* a second finger lands (pinch to zoom before placing B), *Then* the first point is **kept** and the tool stays armed: a second finger is not a disarm path.
- Back order (UX-64, triad repair 2; ux.md 3.5).
  - *Given* a sheet, dialog or nudge session open, *When* Back is pressed, *Then* only that closes; *Given* DISTANCE armed with a pending point A, *When* Back is pressed once, *Then* A is discarded, the tool is SELECT and the editor is still open; *Given* a multi-step tool with two or more unfinished points, *Then* the existing "Discard N unfinished points?" dialog (`backOutcome`, `LeavePolicy.kt:24-27`) shows first and Keep editing leaves everything unchanged; *Given* Select with nothing pending, *When* Back is pressed, *Then* the existing leave check runs (`viewModel.resolveBack()`, `AnnotationEditorScreen.kt:324-332`): Close, or the unsaved-changes or save-failed dialog; *Given* a predictive-back gesture, *Then* it commits the same step as the button and a gesture the finger abandons changes nothing.
- Strip Cancel and strip priority (UX-63, UX-65).
  - *Given* a pending point A, *Then* the strip shows "Point A placed. Tap point B" with a visible 48 dp "Cancel"; *When* it is pressed, *Then* A is discarded, the tool stays armed and the prompt returns; *Given* a pending point A and the view zoomed above Fit with `[Pan]` on, *Then* the strip prompt with Cancel and the pan row arrows are both on screen; *Given* an error and a prompt at once, *Then* the error shows and the prompt returns after it clears; the pure `StripPriority` order is error, prompt/pending, first-three-times hint, status text, voice hint, idle.
- Tool change after the gesture ends.
  - *Given* a drag that completes the measurement, *When* auto-return fires, *Then* the pointer-input restart happens after the iteration ends and a new press immediately after release is not lost.
- Crosshair mode and nudge reuse (F4).
  - *Given* the crosshair session active and a placement commits with `ONE_SHOT`, *Then* the session is reset with the tool (SELECT); *Given* `LOCKED`, *Then* the session stays; *Given* Select and a tap on an existing handle, *Then* the nudge pad opens in `moving` mode, arrows move the point by 1 px / 10 px / 1 percent, Apply commits one undo entry, an arrow at the image edge is disabled with state description "At image edge".
**Files**: `CM/ui/annotate/PlacementClassifier.kt` (new, pure `classifyPlacement` + `PlacementGesture`), `CM/ui/annotate/AnnotationGestureLayer.kt`, `CM/ui/annotate/AnnotationNonDragMode.kt`, `CM/ui/annotate/AnnotationPlacement.kt`, `CM/ui/annotate/AnnotationEditorViewModel.kt` (thin), `CT/ui/annotate/PlacementClassifierTest.kt` (new), `AU/ui/annotate/AnnotationGestureLayerPlacementTest.kt` (new)

##### Task 2.3a: Pure `classifyPlacement` with property test (S)
- (Triad repair 2: this task also owns two small pure functions, `StripPriority.resolve(...)` over the six-level order of ux.md 3.5 and `EditorBackPolicy.resolve(modalOpen, nudgeOpen, toolArmed, unfinishedPoints)` returning `DismissModal`, `CancelNudge`, `DisarmSilently`, `ConfirmDiscardThenDisarm` or `ResolveLeave`; both tested exhaustively in `EditorChromePolicyTest`, a property over every input combination.)
- `classifyPlacement(pressPx, releasePx, thresholdPx, pendingPoints, tool, viewScale): PlacementGesture`; kotest-property: DISTANCE-with-no-pending at `viewScale >= FIT_SCALE` is `DragMeasure` iff release distance > threshold, and below Fit it is always `AimPlace`; every other (tool, pending) combination is `AimPlace`; symmetric in direction; threshold boundary exact. Also pure helpers for "identical points" (< 2 image px) and "off-canvas/back-inset release".
- Files: `CM/ui/annotate/PlacementClassifier.kt`, `CM/ui/annotate/EditorChromePolicy.kt` (new, holds `StripPriority` and `EditorBackPolicy`), `CT/ui/annotate/PlacementClassifierTest.kt`, `CT/ui/annotate/EditorChromePolicyTest.kt` (new). REGISTER: `CT` BUILD entry (both tests).

##### Task 2.3b: Gesture layer rewrite and thin ViewModel methods (M) (VM lane)
- Read `placeGesture` (L186-209) first. Keep the loupe; measure pixels; call `classifyPlacement` with the live `viewScale` read through `rememberUpdatedState`; draw the ghost line/marker past 48 dp (state exposed to the layer, not tool policy); second pointer discards and calls `viewGesture`; key `pointerInput` on `Unit` with `rememberUpdatedState` for tool and handlers (research pitfall: restart keys). ViewModel gets `placeDrag(start,end)` (calls the existing add-point path twice, one commit), `discardFirstPoint()` and `disarm()` (one-line delegations). Strip messages for cancel and identical points. Off-canvas cancel keeps the tool armed (`disarmOnOffCanvas = false`, OC-9). Emits the three `PlacementCounters` log lines (the layer logs the gesture kind; the ViewModel's existing undo path logs `undo-after-drag-measure` when an undo follows a drag-measure commit within 5 s).
- Files: `CM/ui/annotate/AnnotationGestureLayer.kt`, `CM/ui/annotate/AnnotationEditorViewModel.kt` (thin delegation only)

##### Task 2.3c: Robolectric gesture tests (M)
- Compose tests in `androidUnitTest` using `performTouchInput` for drag-measure, aim-place, aim-then-return, tap-tap with pending point, second finger (tool stays armed, first point kept), off-canvas cancel (tool stays armed, first point kept) **with an injected non-zero system-gesture inset and again with a zero inset** (I-E2-03), the three `PlacementCounters` log lines, other tools, immediate re-press after auto-return, plus an unchanged `InProgressAnnotationCanvasGestureTest`. REGISTER: `AllAndroidUnitTests.kt`.
- Files: `AU/ui/annotate/AnnotationGestureLayerPlacementTest.kt`, `AU/ui/annotate/InProgressAnnotationCanvasGestureTest.kt`

##### Task 2.3d: Crosshair session with auto-return and handle-tap nudge entry (S)
- Verify (read `AnnotationPlacement.kt`, `AnnotationEditorViewModel.kt:453`) that a handle tap in Select opens the existing nudge pad in `moving` mode; add that entry point if absent (in the Gesture-lane files only, see Files); pin with tests that the session resets on auto-return and survives LOCKED; the edge-arrow disabled state; no new inspector UI (ADR-007 decision 6). If the missing entry point turns out to live in `AnnotationEditorViewModel.kt` or `AnnotationEditorScreen.kt`, this task does not edit them: it hands the one-line change to the VM-lane or Screen-lane worker (lanes in the Dependency Visualization).
- Files: `CM/ui/annotate/AnnotationNonDragMode.kt`, `CM/ui/annotate/AnnotationPlacement.kt`, `CM/ui/annotate/AnnotationGestureLayer.kt` (only if the handle-tap entry point is there), `BT/ui/annotate/AnnotationPlacementTest.kt` (extend). Lane: Gesture lane, after 2.3b (same-file audit: only these three source files).

##### Task 2.3e: Screen wiring of drag/disarm/strip (S) (Screen lane, first)
- Wire `onDrag`/`onTap`, strip text ("Distance: drag from point A to B", "Point A placed. Tap point B", "Cancelled", first-three-times hint stored in Settings), and the active-chip-disarm path into `AnnotationEditorScreen.kt` (L628 region). Strip is a polite live region. **Triad repair 2:** render the strip through `StripPriority` (one message, never queued); add the 48 dp "Cancel" beside "Point A placed. Tap point B" (calls `discardFirstPoint()`, tool stays armed); replace the always-enabled `PlatformBackHandler` body at `AnnotationEditorScreen.kt:324-332` with `EditorBackPolicy` steps (disarm first, then the existing `resolveBack()` leave path; the handler stays `enabled = true`, which is what predictive back relies on). Test `StatusStripTest` (priority, Cancel, strip and pan row together, Back order, predictive-back commit and abandon via the Robolectric back dispatcher).
- Files: `CM/ui/annotate/AnnotationEditorScreen.kt`, `CM/ui/annotate/AnnotationToolbar.kt`, `AU/ui/annotate/StatusStripTest.kt` (new). REGISTER: `AllAndroidUnitTests.kt`.

#### Story 2.4: Zoom-out floor, pasteboard, edge reach (ADR-006)
**As the** owner, **I want** to zoom out past fit and place a point on the outermost pixel, **so that** edge points are easy.
**Acceptance Criteria**:
- Default is Fit (regression) and `isFit` is symmetric (architecture R2).
  - *Given* `AnnotationViewTransform()`, *Then* `isFit` is true and `scale == FIT_SCALE (1.0)`, even though `MIN_VIEW_SCALE == 0.25`; *Given* scale 0.5 and 0.25, *Then* `isFit` is false and `clamped` returns the same scale (centred), never the identity; *Given* scale 1.0005, *Then* `isFit` is true; *Given* 200 pinch events of factor 0.9995 from Fit, *Then* the scale leaves Fit and ends near 0.905 (the snap is display-only, not sticky). `isFit` and the `clamped` short-circuit use `abs(scale - FIT_SCALE) <= FIT_EPSILON`.
- Zoom-out reaches 0.25 (Success Metric 2).
  - *Given* a 3000x4000 image at Fit in a 1080x2000 viewport, *When* pinch-zoom-out is applied repeatedly, *Then* `scale` reaches 0.25 and stops, the image's rendered width is at most 25 percent of the viewport width (area at most 6.25 percent of the viewport; SM2), the whole image is visible and centred, and the zoom-out button is disabled only at 0.25.
- Edge pixel reachable.
  - *Given* scale 0.5 with the image centred and the finger at 40 dp outside the image's left edge, *When* a point is placed, *Then* the stored normalized point is x=0.0 (clamped), y as touched, and the sidecar value is within 0 to 1.
- Clamp rule (one rule, ADR-006 decision 2).
  - *Given* scale below Fit, *Then* pan is a no-op on any axis whose content fits the viewport (centred); *Given* scale >= Fit and a pan of 10,000 px, *Then* the far edge stays `slack` (25 percent of the viewport dimension) short of the viewport edge so some of the image is always visible; *Given* a pinch centroid at or above Fit and unclamped, *Then* the image point under it stays fixed; below Fit zoom is about the viewport centre.
- Edge pixel reachable on gesture navigation (adversarial C11).
  - *Given* a system-gesture inset of 28 dp on each side (`WindowInsets.systemGestures` fake) at Fit, *When* the outermost image pixel is placed with a press, *Then* the press lies inside the canvas viewport (outside the inset), no system gesture is in play, and the stored point is x = 0.0; *Given* the inset is 0 (desktop, three-button navigation), the layout is unchanged.
- Fit still works.
  - *Given* any transform, *When* Fit is tapped or double-tapped to toggle, *Then* the transform equals `Fit` and `isFit` is true; double-tap from 0.5 returns to Fit.
- Hit targets constant in screen dp (property test only; no code change, ADR-006 decision 5).
  - *Given* scale 0.25 and 1.0, *When* hit-testing a handle with a 24 dp screen radius, *Then* both grab within the same screen radius, and a handle at the image edge is grabbable.
**Files**: `CM/ui/annotate/AnnotationViewTransform.kt`, `CM/ui/annotate/AnnotationHitTest.kt`, `CM/ui/annotate/LoupeGeometry.kt`, `CM/ui/annotate/AnnotationEditorScreen.kt` (pasteboard draw), `CT/ui/annotate/AnnotationViewTransformTest.kt`, `CT/ui/annotate/AnnotationHitTestScaleTest.kt` (new)

##### Task 2.4a: Transform change, test-first (M)
- Property tests first in `commonTest`: `toCanvas(toView(p)) == p` everywhere; pinch-centroid-fixed in the unclamped region only; every normalized point reachable inside the viewport at some legal transform at floor scale; default-is-Fit regression. Then introduce `FIT_SCALE = 1f` and make the default and `isFit` use it (all four places: default L57, `isFit` L61 as `abs(scale - FIT_SCALE) <= FIT_EPSILON`, `transformed` coerce, `clamped` short-circuit L111 with the same `abs` comparison), then lower `MIN_VIEW_SCALE` to 0.25, apply the one clamp rule, make `slack` proportional to viewport, and keep `toggledAt` semantics from any scale. **Snap is display-only (pre-mortem P2-9, adversarial C10):** `transformed()` derives `newScale = (scale * zoom)` from the current, already snapped scale (`AnnotationViewTransform.kt:78-79`) and `clamped()` re-snaps within `FIT_EPSILON` (L109-111), so a slow pinch-out at factors 0.997-0.999 per event would never leave Fit. The gesture state accumulates the **unsnapped** scale and the snap applies only to `isFit` and `toggledAt`; test `slowPinchOut_should_LeaveFit_When_200EventsOfFactor09995` (U-E2-09: 200 events of 0.9995 reach about 0.905).
- Files: `CM/ui/annotate/AnnotationViewTransform.kt`, `CT/ui/annotate/AnnotationViewTransformTest.kt`

##### Task 2.4b: Hit-test property, loupe edge clearance, point clamp (S)
- No radius change (already screen px; ADR-006 decision 5): add the same-screen-radius property test at 0.25 and 1.0 and the 24 dp minimum assertion (UX-24); verify `loupeZoomFactor` (floor 3x) and the loupe offset stay inside the viewport near edges; keep `[0,1]` clamps (`AnnotationPlacement.kt:39,48`, `AnnotationHitTest.kt:65-66`) and add a test for finger-in-pasteboard clamping; confirm hit-testing at the edge does not clamp the raw touch.
- Files: `CM/ui/annotate/LoupeGeometry.kt`, `CM/ui/annotate/AnnotationHitTest.kt` (tests only unless a bug is found), `CT/ui/annotate/AnnotationHitTestScaleTest.kt`. REGISTER: `CT` BUILD entry.

##### Task 2.4c: Pasteboard and frame rendering (S) (Screen lane)
- Lay the canvas viewport out inside `WindowInsets.systemGestures` left and right (ADR-006 decision 6; Android only, zero elsewhere) so Fit leaves the outermost pixel clear of the back-gesture zone, and treat a press or release outside that rectangle as off-canvas. Draw neutral surface and a thin image-bounds frame (3:1 non-text contrast) behind the image; image remains drawn through the same transform (no export change). If the audit (2.1a) shows the screen draws the 12 MP bitmap at full size when scaled down, switch to a bounded decode for scale < 1 (`ZoomedOutDecodeTest`, I-E2-07: 12 MP at 0.25 stays within the memory ceiling and never allocates the full bitmap twice). Reduced-motion: zoom-to-Fit animation disabled (UX-50).
- Files: `CM/ui/annotate/AnnotationEditorScreen.kt`

#### Story 2.5: Controls and status out of the image area; contrast; no FAB overlap
**As the** owner, **I want** the zoom buttons, depth status and mic button to stop covering the photo and toolbar, **so that** I can see and touch the image edges.
**Acceptance Criteria**:
- Depth status not over the image.
  - *Given* depth model ready and `onEstimate == null`, *When* the editor renders, *Then* no "estimation coming in a future update" text appears; *Given* a non-null status string, *Then* it renders in a layout slot above the canvas and the image's top 56 dp receives pointer events.
- Zoom controls leave the image and are 48 dp (F5, F14).
  - *Given* the editor at Fit on a 1080 px-wide (360 dp) viewport, *When* laid out, *Then* the -/percent/+/Fit row sits in the toolbar's second row, each target is >= 48 dp, the row bounds do not intersect the image bounds (asserted via `getUnclippedBoundsInRoot()`), and the percentage is exposed as "Zoom 40 percent"; *Given* a width >= 600 dp, *Then* the row is a vertical rail outside the photo.
- Select label contrast (F3).
  - *Given* Select is active, *When* the toolbar renders, *Then* the label is `Color.White` semibold on the dark toolbar with the filled indicator behind the icon, contrast >= 4.5:1 computed in the test against `TOOLBAR_BACKGROUND_COLOR` (0xEE1A1A1A); `onPrimaryContainer` is not used because the fill does not extend behind the label (VERIFIED in the owner screenshot); inactive/disabled label contrast >= 4.5:1.
- Mic FAB (F6).
  - *Given* the annotation editor is the active screen, *When* composed, *Then* the app-level mic FAB is not shown and the toolbar's second row has no overlapping element (layout-bounds assertion). Recorded consequence: voice capture is unavailable while the editor is open (OC-12). **Voice hint (UX-70):** *Given* the editor opens for the first, second and third time (Settings counter `annotator_voice_hint_count`) and nothing of higher priority is showing, *Then* the idle strip reads "Voice notes: go Back to use the mic"; *Given* the fourth open, *Then* it is never shown.
- Non-drag pan (WCAG 2.5.7; UX-53, triad repair 1).
  - *Given* scale 2.0 and the Pan toggle on, *When* the pan row renders (its own row between the canvas and the toolbar, never the status strip), *Then* four 48 dp arrows are shown outside the photo bounds; *When* the right arrow is pressed, *Then* the view moves 25 percent of the viewport width and the arrow is disabled with `stateDescription` "At image edge" when the clamp is reached; **one visibility rule (ux 4.1, UX-53, ADR-006 decision 2): the toggle and row are available exactly when `viewScale >= FIT_SCALE`** (slack-pan at Fit is real), so *Given* scale 1.0 (Fit) the toggle is enabled and the row shows when on, and *Given* scale strictly below Fit (0.5), *Then* the Pan toggle is disabled with reason "Nothing to pan" and the row is hidden; the canvas also exposes TalkBack custom actions "Pan left/right/up/down" that make the same moves.
- Second row at 360 dp, 200 percent font and RTL (UX-62, triad repair 1).
  - *Given* a 360 dp x 640 dp window, *Then* the **first row (the seven labelled chips Select, Distance, Area, Angle, Label, Reference, Calibrate) is also a wrapping flow layout (labels wrap to two lines, nothing clipped at 200 percent; UX-68)**, and Undo, Redo, Keep tool, the unit chip, `[-] % [+] [Fit] [Pan]` wrap onto at most two lines with every target >= 48 dp, none clipped and none overlapping (bounds assertions); *Given* font scale 2.0, *Then* text wraps instead of truncating, the row takes at most three lines and the toolbar (both rows and the pan row) is at most 40 percent of the window height (otherwise the zoom group and the pan row are the single "Zoom" popup button); *Given* an RTL locale, *Then* the control order mirrors and the photo and its stored coordinates do not (x = 0.0 is still the left edge of the photo).
**Files**: `CM/ui/annotate/AnnotationEditorScreen.kt` (~L1687), `CM/ui/annotate/AnnotationZoomControls.kt`, `CM/ui/annotate/AnnotationToolbar.kt`, the FAB host (found in 2.1a), `AU/ui/annotate/AnnotatorLayoutTest.kt` (new)

##### Task 2.5a: Depth status row to a layout slot / hidden when unavailable (S) (Screen lane)
- Move the row at `AnnotationEditorScreen.kt` ~L1687 out of the canvas overlay (status strip above the canvas, shared with the ADR-007 strip); hide the text when `onEstimate == null` (scope: only the misleading toast).
- Files: `CM/ui/annotate/AnnotationEditorScreen.kt`

##### Task 2.5b: Zoom row, 48 dp targets and Select label (S) (Screen lane)
- Place `AnnotationZoomControls` in the toolbar's second row (rail at >= 600 dp), 48 dp targets, percentage value semantics, `[-]` enabled until the floor, `[Fit]` enabled whenever not at Fit; the second row is a wrapping flow layout (never horizontal scroll) that satisfies the 360 dp / 200 percent / RTL criteria; add the `[Pan]` toggle (enabled exactly when `viewScale >= FIT_SCALE`) and the `AnnotationPanPad` in its own pan row (four 48 dp arrows, 25 percent of the viewport per press, disabled at the clamp edge, hidden below Fit; counts toward the 40 percent toolbar budget and folds into the "Zoom" popup with the zoom group when over it); make the first toolbar row a wrapping flow layout too (UX-68); fix the active Select label (white, semibold, fill indicator) and replace hard-coded `0xFFBBBBBB` with theme-derived tokens where touched (`AnnotationToolbar.kt` ~L275). Tests in `AnnotatorLayoutTest` (layout bounds at 360 dp, fontScale 2.0, RTL for both rows, pan pad rule at scale 0.5 / 1.0 / 2.0). **These are the first tests that measure the UNVERIFIED contrast and size claims of ux.md section 1**: `ToolbarContrastTest` (X-16), `ToolbarLayoutBoundsTest` (X-17), `ZoomControlsTest` (X-22) and `ThemeContrastTest` (X-49) run here, and the labels stay UNVERIFIED until they have run green.
- Files: `CM/ui/annotate/AnnotationZoomControls.kt`, `CM/ui/annotate/AnnotationPanPad.kt` (new), `CM/ui/annotate/AnnotationToolbar.kt`, `CM/ui/annotate/AnnotationEditorScreen.kt`, `AU/ui/annotate/AnnotatorLayoutTest.kt`

##### Task 2.5c: Hide the mic FAB inside the editor (S)
- Using the host and flag found in 2.1a, do not compose the app-level mic FAB while the annotation editor is the active screen; layout-bounds test. Not a Screen-lane file (FAB host lives in app routing). **Voice hint (UX-70):** the three-shows hint is a `StripPriority` level-5 message supplied through the strip state (Settings counter `annotator_voice_hint_count`); this task adds the counter and the string (Task 2.3e's strip already renders a level-5 message when one is supplied). Test `VoiceHintTest`.
- Files: FAB host file (path as found), `AU/ui/annotate/AnnotatorLayoutTest.kt`, `AU/ui/annotate/VoiceHintTest.kt` (new). REGISTER: `AllAndroidUnitTests.kt`.

#### Story 2.6: Toolbar accessibility, Keep tool and desktop keyboard
**As a** TalkBack user, a desktop keyboard user and the owner, **I want** tools announced with state, a visible Keep tool toggle and keyboard parity, **so that** modes are predictable.
**Acceptance Criteria**:
- Radio semantics.
  - *Given* DISTANCE active, *When* the semantics tree is read, *Then* the toolbar is a `selectableGroup`, Distance has `Role.RadioButton`, `selected = true`, and Select has `selected = false`; every chip is >= 48 dp in both dimensions.
- Keep tool toggle (F4; replaces the nested lock icon).
  - *Given* DISTANCE active and `ONE_SHOT`, *When* the user taps the 48 dp "Keep tool" toggle in the action row, *Then* `stickiness == LOCKED`, the chip shows a decorative lock badge and the toggle exposes `stateDescription = "locked"`; long-press and double-tap on the chip do the same; the toggle is absent while Select is active; tapping the already-active chip disarms (never locks).
- Announcements.
  - *Given* a measurement commit with auto-return, *When* it completes, *Then* a live-region announces "Distance 90 cm added, returned to Select".
- Disabled reason.
  - *Given* not calibrated, *When* Reference's semantics are read, *Then* it exposes disabled with description "Calibrate first", and a tap on it shows the strip hint "Calibrate first to use Reference".
- TalkBack custom actions and the placement path (UX-54, UX-55, triad repair 1).
  - *Given* TalkBack is on and a tool chip is focused, *Then* its custom actions list "Keep tool" (or "Release tool" when locked) and invoking it toggles `stickiness` exactly like the visible toggle; *Given* a TalkBack user with DISTANCE armed, *When* they invoke the canvas action "Place point with crosshair", *Then* the crosshair session starts, "Crosshair at 50 percent, 50 percent" is announced, each arrow nudge announces the new coordinate (and the live length once a first point exists), Place point announces "Point A placed", and the second Place point announces "Distance 90 cm added, returned to Select"; no step requires a drag or a double-tap-and-hold.
- Desktop keyboard.
  - *Given* a hardware keyboard, *When* the shortcut for Keep tool is pressed with a tool armed, *Then* `stickiness` toggles; tool shortcuts arm a tool and re-arming after each auto-return is one key press; a mouse drag past 48 dp creates a measurement (same classifier).
**Files**: `CM/ui/annotate/AnnotationToolbar.kt`, `CM/ui/annotate/AnnotationEditorScreen.kt`, `AU/ui/annotate/AnnotationToolbarSemanticsTest.kt` (new)

##### Task 2.6a: Semantics, Keep tool toggle, announcements (M) (Screen lane)
- `selectableGroup`, `Role.RadioButton`, `selected`, Keep tool 48 dp toggle (`Role.Switch`/ToggleButton) plus long-press/double-tap on the chip **and the TalkBack custom actions "Keep tool"/"Release tool" on each chip**, the canvas custom actions "Place point with crosshair" and "Pan left/right/up/down" (the pan moves call the Pan-pad handlers from Task 2.5b), live-region announcement, disabled reason for Reference, 48 dp for all chips. **UX-50:** the auto-return pulse on the chip (and the zoom-to-Fit animation of Task 2.4c) is skipped when the system "remove animations" setting is on; Robolectric test reads the animation scale and asserts no animation runs.
- Files: `CM/ui/annotate/AnnotationToolbar.kt`, `CM/ui/annotate/AnnotationEditorScreen.kt`

##### Task 2.6b: Robolectric semantics tests (S)
- Assert the criteria with `onNode(hasRole...)`, including the custom actions (UX-54, UX-55) and the Pan pad semantics (UX-53). REGISTER: `AllAndroidUnitTests.kt`.
- Files: `AU/ui/annotate/AnnotationToolbarSemanticsTest.kt`, `AU/ui/annotate/AnnotationA11yActionsTest.kt` (new)

##### Task 2.6c: Desktop keyboard shortcuts (S)
- Add a Keep tool shortcut beside the existing tool shortcuts; update `AnnotationToolbarShortcutTest` and `AnnotationEditorScreenKeyboardTest` (jvmTest file keeps its `// jvmTest-reason:` comment; run behind `scripts/jvm-display-check.sh`). REGISTER: `AllJvmTests.kt`.
- Files: `CM/ui/annotate/AnnotationToolbar.kt` (shortcut map only, after 2.6a), `JT/ui/annotate/AnnotationEditorScreenKeyboardTest.kt`

#### Story 2.7: Performance and regression gate
**As the** owner, **I want** the larger zoom range to stay smooth, **so that** the 60 fps SLO holds on a 12 MP photo.
**Acceptance Criteria**:
- Baseline then target recorded (closes the UNVERIFIED baseline; owner-verified on the phone, CI-green on the harness).
  - *Given* a 12 MP test image, *When* a scripted pinch from scale 1 to 0.25 and back runs under the existing jank harness (`kmp/TESTING_README.md`), *Then* frame-time p95 is recorded before and after Story 2.4 and after is within the same SLO bucket as before.
- Full existing annotator suite green.
  - *Given* `bazel test //kmp:business_tests` and `testDebugUnitTest`, *When* run, *Then* all pass; the `jvm_tests` UI run goes through `scripts/jvm-display-check.sh`.
**Files**: perf notes in PR; existing benchmark harness

##### Task 2.7a: Baseline/after perf capture, owner-device gesture pass and suite run (S)
- Capture p95 frame time and jank rate before (pre-2.4) and after with the existing harness on the same device and script (scripted pinch Fit to 0.25 and back on a 12 MP image; `scripts/gfxinfo-session.sh` on the phone), run the suites with `timeout` wrappers; paste outputs. The pre-change numbers close the UNVERIFIED baseline; the absolute thresholds (p95 at most 16.7 ms, jank under 5 percent) are PROPOSED (OC-14).
- **Owner-device pass criteria for the 48 dp drag-commit threshold (PROPOSED defaults, OC-6; triad repair 1).** With a debug build on the phone, at Fit and again at 2x zoom, the owner performs 20 deliberate drag-measures (A to B) and 20 deliberate aim-places (press, aim with the loupe, release near the press). Read the `PlacementCounters` log lines (Logs screen export). **Pass:** (1) at least 18 of the 20 drag-measures log `gesture=drag-measure` (a drag shorter than the threshold is not a measurement); (2) at most 1 of the 20 aim-places logs `gesture=drag-measure` (a drift past 48 dp must be rare); (3) `undo-after-drag-measure` appears for at most 3 of the 20 drag-measures (a high rate means the finger-occluded point A is wrong too often); (4) the owner reports no case of an unnoticed wrong measurement. **If (2) or (3) fails:** raise `DRAG_COMMIT_DP` (one constant, tuned in steps of 8 dp) or, if (1) fails, lower it, and repeat; if the occlusion rate stays high at every threshold, drag-measure is turned off at 2x zoom and above (OC-5 fallback) and the finding is recorded in the PR.
- Files: none (evidence in the PR; the threshold constant `DRAG_COMMIT_DP` in `CM/ui/annotate/PlacementClassifier.kt` is the only code that may change as a result)

## Phase 3: Epic 3 — Measurement editing and multi-distance calibration

### Epic 3.1: Edit a value; calibrate from several known distances
**Goal**: Success Metric 3: change any point-to-point value (as an override or an explicit known distance), and a fit from N known distances beats single-scale on held-out distances for the verification set. **Plan reading of SM3 (requirements.md untouched):** "in different directions" is read as distances in different places and directions; the first-order tilt information is the scale gradient across *positions*, so the tiers are SCALE-with-LOO (the shipped default) and the focal-free GRADIENT tier (works on existing photos, **default-off until the Story 3.8a real-photo study shows a held-out gain**), with PER_AXIS reported separately and off by default. **SM3 is not marked met by synthetic tests**: it needs the Story 3.8 real-photo hold-out, or an explicit "not met for distortion" outcome recorded and shown to the owner.

#### Story 3.1: Accuracy harness and golden scenes (spike; first)
**As the** owner, **I want** a measured baseline, **so that** "lower error" is a number, not a feeling.
**Acceptance Criteria**:
- Harness reproduces prior baselines.
  - *Given* the golden scene (4000x3000, f_px 3100, ruler along the bottom, object 18.000 cm, k = -0.01), *When* all corrections are off, *Then* uncorrected error equals the published measurement-accuracy figure within 0.01 cm (guards a wrong simulator).
- Held-out metric defined, including model-misspecification scenes (adversarial: synthetic truth equal to the solver's model class is circular).
  - *Given* 6 synthetic distances with tilt 5 degrees and 1 px tap noise (500 random scenes), *When* single-scale and each candidate tier are fit on all but one distance, *Then* the harness prints mean absolute error on the withheld distance per tier and per N; *and given* the **out-of-model scene families** (points a few percent of camera distance above the plane; constraints split across two planes, a door frame plus a floor tile; 1-2 px systematic tap bias; non-square pixels / no per-axis anisotropy; higher-order radial distortion (k2, k3) beyond the single-k model; a cropped or digitally zoomed frame with an off-centre principal point; a 90-degree rotation (portrait/landscape EXIF)), *Then* the same table is printed per family so a tier that only wins in-model is visible.
**Files**: `CT/calibration/GoldenScene.kt` (new), `CT/calibration/ConstraintAccuracyHarnessTest.kt` (new), `stapler-scripts/calibration/opencv_oracle.py` (offline fixtures only)

##### Task 3.1a: Locate or build the golden-scene generator (S)
- Check `CT/calibration/` for the measurement-accuracy epic's generator (not found by listing: only service/profile tests present, so INFERRED absent); build `GoldenScene` (pinhole, tilt, division k, noise, plus the out-of-model families) as pure commonTest code. Reproduce `LensDistortionSimulationTest` baselines.
- Files: `CT/calibration/GoldenScene.kt`, `CT/calibration/GoldenSceneBaselineTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 3.1b: Held-out evaluation harness (M)
- (Phase 4 patch: S to M.) Function `evaluate(scene, solver, n)` returning MAE on the withheld distance, per scene family, for the seven out-of-model scene families (a) heights above the plane, (b) two planes, (c) 1-2 px systematic tap bias, (d) k2/k3 radial, (e) non-square pixels, (f) cropped/digitally zoomed frame, (g) 90-degree rotation (generators and the printed per-family table here; the pass/fail test `OutOfModelRobustnessTest`, U-E3-02, needs the solver and lives in Task 3.3b3: pass per family is MAE(default) <= MAE(single-scale) * 1.02 with a 95 percent CI, and a failing family blocks the tier from shipping as default); table output used by Story 3.8. Optional OpenCV `uvx` oracle script for homography/k fixtures (offline, not a dependency).
- Files: `CT/calibration/ConstraintAccuracyHarnessTest.kt`, `stapler-scripts/calibration/opencv_oracle.py`

#### Story 3.2: Constraint model, override value type, persistence, migration
**As a** user, **I want** known distances and edited values saved with the photo, **so that** they survive reload and sync.
**Acceptance Criteria**:
- Old sidecar loads (backward compat).
  - *Given* the golden v0.113.3 sidecar fixture `sidecar-v0113.json` (no constraints, no valueSource), *When* loaded, *Then* constraints is empty, every measurement is `Computed`, and re-save produces identical existing fields.
- New sidecar read by the old reader, with the old-build claim made precise (ADR-003 decision 8).
  - *Given* the golden v0.113.3 sidecar `sidecar-v0113.json` with measurements and a calibration, *When* it is loaded, saved, loaded, and the height offset is set and cleared twice (the `setGeometry` predicate now being `merged != GeometryCalibration()`), *Then* every existing measurement's `distanceMeters` is **byte-identical** to the value before the round trip (`GoldenSidecarMeasurementIdentityTest`, U-E3-33, pre-mortem P2-9: the predicate change must not alter stored measurement values).
  - *Given* a sidecar containing `constraints`, `fit`, a `CONSTRAINT_FIT` plane and `valueSource=USER_OVERRIDE`, *When* decoded with a frozen copy of the 0.113.3 data classes (`ignoreUnknownKeys = true`), *Then* decoding succeeds, the stored `pixelsPerMeter` and plane homography are present, the plane has non-zero extents and no `checkResidualMm`; *and when* that old copy re-saves, *Then* the test asserts exactly what is dropped (`constraints`, `fit`, `kind`, `valueSource`, `originalValueMeters`).
- The same compat holds for the DB column.
  - *Given* a `calibration_geometry_json` value containing `constraints` and `fit`, *When* decoded by `GeometryCalibrationCodec` and by a frozen old codec, *Then* both decode (the old one to a geometry without them).
- Unknown keys preserved by the v2 reader.
  - *Given* a sidecar with an extra unknown key `futureThing` on an annotation, *When* loaded and saved by the new code, *Then* `futureThing` is still present.
- Override persistence and the sealed type.
  - *Given* a measurement `Overridden(0.90, original 0.8741)`, *When* saved to DB, reloaded, and a recalibration runs, *Then* `valueMeters` stays 0.90 and the flag stays; *Given* a row with COMPUTED and a non-null original, or OVERRIDE with a null original, *Then* it is normalised on read and logged once.
- Migration on fresh AND upgraded databases.
  - *Given* a DB at the previous schema, *When* `MigrationRunner.applyAll()` runs, *Then* `measurement_annotations` has `value_source` (default `'COMPUTED'`) and `value_original_meters`, existing rows read as COMPUTED; *Given* a brand-new database created from the `.sq`, *Then* the same columns exist; the changed `insertMeasurementAnnotation` stub round-trips both.
- Plane discriminator.
  - *Given* a paper plane saved by 0.113.3 (no `kind`), *When* loaded, *Then* `kind == PAPER_REFERENCE`.
- Old builds read a constraint fit as calibrated (adversarial C6).
  - *Given* a sidecar and a DB row written with a SCALE-tier fit (`method = MANUAL_REFERENCE`, `fit` present), *When* decoded by the frozen 0.113.3 loaders (`CalibrationMethod.valueOf(...)` with `getOrDefault(NONE)`, `ImageSidecarManager.kt:175`, `SqlDelightImageAnnotationRepository.kt:148`) and passed through the old `usableScale` rule, *Then* the calibration is usable and measures with the stored `pixelsPerMeter`; a source audit asserts no new `CalibrationMethod` value is added by this plan.
- `GeometryCalibration` keeps constraints and fit through a height-offset edit (adversarial C7).
  - *Given* a SCALE-tier fit (plane null) and no height offset, *When* the height offset is set and then cleared twice, *Then* `constraints` and `fit` survive both edits (`merged != GeometryCalibration()` is the only emptiness test, tested over every field by a reflection property so a future field cannot be dropped).
**Files**: `CM/model/CalibrationConstraint.kt` (new), `CM/model/GeometryCalibration.kt`, `CM/model/MeasurementAnnotation.kt`, `CM/db/sidecar/ImageSidecarSchema.kt`, `CM/db/sidecar/ImageSidecarManager.kt`, `CM/db/sidecar/SidecarJsonMerge.kt`, `CM/db/MigrationRunner.kt`, `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`, `CM/db/RestrictedDatabaseQueries.kt`, `CM/repository/SqlDelightMeasurementAnnotationRepository.kt`

##### Task 3.2a: Read how the plane and height offset compose, write the model types (M) (Sidecar lane)
- Read `CM/calibration/PlaneReference.kt`, `LensMeasurement.kt`, `HeightOffset*.kt`, `MeasurementAdvisor.kt`, `model/GeometryCalibration.kt` to confirm: the homography convention (already VERIFIED: undistorted native px to metres), how `(D-h)/D` composes with a plane (write the pinning test), and where `PlaneCalibration` fields are read. Add `CalibrationConstraint`, `ConstraintSource`, `GeometryCalibration.constraints = emptyList()` and `.fit: ConstraintFitRecord? = null` (and the one emptiness rule `isEmpty() = this == GeometryCalibration()`, used by `setGeometry` and `keepingGeometry` in Task 3.7a), `PlaneKind` + `PlaneCalibration.kind = PAPER_REFERENCE`, `FitReport` (serializable), `ValueSource`, sealed `MeasurementValue` with `MeasurementAnnotation.valueSource`/`originalValueMeters` as its column projection. Codec round-trip and unknown-key tests in `CT`.
- Files: `CM/model/CalibrationConstraint.kt`, `CM/model/GeometryCalibration.kt`, `CM/model/MeasurementAnnotation.kt`, `CT/model/CalibrationConstraintCodecTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 3.2b: Column migration, SQLDelight regeneration, repository (M)
- Add the columns in the `.sq` `CREATE TABLE` **and** `MigrationRunner.all` `AddColumn` entries; change the `insertMeasurementAnnotation` stub signature in `RestrictedDatabaseQueries` (`@DirectSqlWrite`, ~L620-630) and update every caller and test; repository maps `MeasurementValue` to the two columns; run `./gradlew :kmp:generateCommonMainSteleDatabase`, rsync and commit `kmp/src/generated/sqldelight/`. Column-presence test next to `MigrationRunnerSchemaSyncTest` for fresh and upgraded databases.
- Files: `.sq`, `CM/db/MigrationRunner.kt`, `CM/db/RestrictedDatabaseQueries.kt`, `CM/repository/SqlDelightMeasurementAnnotationRepository.kt`, `BT/db/MeasurementValueSourceMigrationTest.kt` (new) (5 hand-written files; `kmp/src/generated/sqldelight/**` is rsync output of the generator and is not counted, see the counting rule in the header). REGISTER: `AllBusinessTests.kt`.

##### Task 3.2c1: Sidecar fields, shared version helper and compat fixtures (M) (Sidecar lane)
- (Triad repair 1: the former Task 3.2c listed six files and is split.) `SidecarMeasurement.valueSource/originalValueMeters`, `calibrationGeometry.constraints/fit`; `SidecarVersion.forContent` helper (writes `schemaVersion = 2` only when v2 data present; E4's lens keys call it). Golden fixtures `sidecar-v0113.json`, `sidecar-v2.json`; tests for the sidecar criteria above. Extend `ImageAnnotationOfflineTest`/`ImageAnnotationModelTest`.
- Files: `CM/db/sidecar/ImageSidecarSchema.kt`, `CM/db/sidecar/ImageSidecarManager.kt`, `CM/db/sidecar/SidecarVersion.kt` (new), `BT/db/sidecar/SidecarCompatTest.kt` (new), test resources. REGISTER: `AllBusinessTests.kt`.

##### Task 3.2c2: Unknown-key overlay, golden measurement identity and frozen old-reader test (S) (Sidecar lane, after 3.2c1)
- `SidecarJsonMerge` overlays unknown keys from the raw `JsonObject` on save (one call added in `ImageSidecarManager.kt`); `GoldenSidecarMeasurementIdentityTest` (U-E3-33: existing `distanceMeters` byte-identical after the `setGeometry` predicate change) and a frozen copy of the 0.113.3 data classes in test sources (`FrozenOldReaderDecodeTest`: a SCALE fit decodes and measures; a re-save by the old reader drops the new keys as ADR-003 decision 8 states).
- Files: `CM/db/sidecar/SidecarJsonMerge.kt` (new), `CM/db/sidecar/ImageSidecarManager.kt` (one call), `BT/db/sidecar/GoldenSidecarMeasurementIdentityTest.kt` (new, U-E3-33), `BT/db/sidecar/FrozenOldReaderDecodeTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

#### Story 3.3: `ConstraintCalibrator` and `ConstraintFitApplier` (pure, ADR-003)
**As a** user, **I want** several known distances to produce a better scale or plane, **so that** measurements across the photo are more accurate.
**Acceptance Criteria**:
- Recovers synthetic truth in-model (property test).
  - *Given* a random plane with tilt in 0 to 10 degrees (square pixels), 6 known segments whose midpoints span at least 40 percent of the image diagonal, and 0.5 px endpoint noise (500 scenes), *When* `fit` runs, *Then* the selected tier's mean absolute error on a 7th held-out distance is at most the single-scale error in at least 95 percent of scenes.
- **Out-of-model robustness (anti-circularity).**
  - *Given* the Story 3.1 out-of-model families (heights above the plane, two planes, 1-2 px systematic bias, square pixels), *When* `fit` runs, *Then* the shipped default selection (tier chosen by LOO) is no worse than single-scale by more than the tap-noise floor in at least 90 percent of scenes per family, and a tier is never selected that raises held-out error on average in a family; the result is a printed table, and failing it blocks the default-tier choice.
- Identifiability gates (adversarial: angle spread alone is not enough; iteration 2 recomputed per tier).
  - *Given* 6 segments clustered in one image corner, *When* GRADIENT is requested, *Then* `IllConditioned(position spread)`; *Given* 6 segments whose midpoints lie on one line, *Then* `IllConditioned(position spread)` (a gradient needs spread in both axes); *Given* a true tilt of 0.5 degrees (|g| below the Student-t gate for N - P dof), *Then* `NoMeasurableTilt` and SCALE is kept; *Given* 6 segments with varied positions all near the principal point, *When* GRADIENT_AND_K is requested, *Then* `IllConditioned(radial spread)`; *Given* a k whose predicted effect on the longest span is under twice the tap error, *Then* GRADIENT_AND_K is ineligible with the noise-floor reason; *Given* any GRADIENT scene, *Then* the Jacobian over (w0, gx, gy) has rank 3 and a deliberately rank-deficient scene returns `IllConditioned` rather than a number. Direction spread is asserted **only** for PER_AXIS (a GRADIENT fit succeeds on 6 parallel segments spread over the frame).
  - *Given* the Story 3.1 golden scene at tilt 5 degrees and 0.3 percent tap noise, *Then* the recovered gradient SNR is above 10 (the INFERRED figure in ADR-003 decision 5 is about 23 for a 0.3 percent per-segment error; it falls to about 10 for 5 percent-of-frame segments at 1 px taps, so the test names its segment-length assumption), recorded in the test output.
- **GRADIENT is not trusted when it is worse (pre-mortem P1-5, adversarial C8/C9).**
  - *Given* 500 pure-noise scenes (no tilt) at N = 5 and again at N = 6, *When* GRADIENT is evaluated, *Then* at most 1 percent are accepted (`GradientNoiseFalsePositiveRateTest`, U-E3-07; the gate is `|g| > t_{0.995, N-P} * SE(g)`, not a fixed 3 SE which passes about 9.5 percent at 2 dof); *Given* a scene where the t-test passes but leave-one-out error is not better than SCALE's by the margin, *Then* GRADIENT is rejected (the LOO check vetoes; `GradientLooVetoTest`, U-E3-31).
  - *Given* a gradient whose implied tilt `atan(|g| * f_px_exif)` exceeds 10 degrees using the EXIF 35 mm-equivalent focal as an order-of-magnitude bound only, *Then* the result is "tilt too large for this model", SCALE is kept and no plane is written; *Given* no EXIF focal, *Then* the bound is reported as unchecked (`GradientSignificanceTest`, U-E3-08).
  - *Given* a photo whose sidecar lens record has `zoomRatio != 1`, whose EXIF focal and aspect indicate a crop, or whose aspect is not 4:3 or 3:2 within 2 percent, *When* GRADIENT is requested, *Then* `Left(CroppedOrZoomedFrame)` and SCALE is kept (`CroppedFrameRefusalTest`, U-E3-09), and `ConstraintFitApplier` never writes a `CONSTRAINT_FIT` plane for a result carrying that failure.
  - *Given* a fresh install, *Then* `ConstraintTuning.GRADIENT_DEFAULT_ON == false` and tier selection returns SCALE-with-LOO unless Settings `calibration_gradient_tier` is on (`GradientDefaultOffTest`, U-E3-32); the pill shows the tier and "estimated accuracy +/-R%" from the LOO figure, never a bare check. The default flips only through Task 3.8a's study outcome.
- Degenerate input returns typed failures.
  - *Given* three segments with identical direction, *When* PER_AXIS is requested, *Then* `Left(Collinear(angleSpreadDeg=0.0))` and the SCALE tier result is returned with a note; *Given* a zero-length pixel segment, *Then* `Left(ZeroLengthSegment(id))`; *Given* distance -0.5, *Then* `Left(NonPositiveDistance(id))`; *Given* 2 constraints with tier GRADIENT requested, *Then* `Left(TooFewConstraints(need=5, have=2))`.
- Exactly two constraints (F2).
  - *Given* N=2, *When* fit runs, *Then* tier SCALE with `loo = null` and `agreementPct = |s1-s2|/mean` is reported (no LOO, no tilt).
- Spare degrees of freedom (recomputed: SCALE P=1, PER_AXIS P=2, GRADIENT P=3, GRADIENT_AND_K P=4).
  - *Given* N=4 constraints, *When* tier GRADIENT (P=3) is evaluated, *Then* it is ineligible (N - P = 1 < 2) and the report says "needs 1 more distance"; *Given* N=5, GRADIENT is eligible and GRADIENT_AND_K needs 1 more.
- No focal length is needed for the tilt tier; focal refinement is optional.
  - *Given* a photo with only EXIF `FocalLength` (or none), *When* GRADIENT is requested on a tilted golden scene, *Then* it is eligible and its held-out error beats SCALE (`FocalSource = EXIF_ONLY` changes nothing); *Given* `USER_CONFIRMED` or a sidecar lens record at zoom 1, *When* the refinement flag is on, *Then* the exact `1/cos(tau)` factor is applied with `f_px = HeightOffset.focalPixels(f35, w, h)` and the result differs from the unrefined fit by at most 0.5 percent at 5 degrees; *Given* `PROFILE` or `EXIF_ONLY`, *Then* the refinement is never applied. The refinement ships off.
- Conflicting distance and mixed surfaces.
  - *Given* distances 0.90 m, 0.88 m and 2.54 m for similar pixel lengths (a unit typo), *Then* `FitReport.worst` names the 2.54 m constraint with its implied scale ratio; *Given* residuals that split into two groups of at least 2 beyond 3 percent, *Then* `FitReport.mixedSurfaces = true`.
- Leave-one-out chosen over training residual.
  - *Given* N=5 constraints where tier K lowers training residual but raises LOO error, *When* selecting, *Then* the simpler tier is returned.
- Legacy basis refused.
  - *Given* `Calibration.basisVersion = 0`, *When* fit runs, *Then* `Left(LegacyBasis)`.
- **Precedence table (ADR-003 decision 2), one test per row.**
  - *Given* a photo with a paper plane and 3 constraints, *When* the SCALE fit is applied, *Then* `plane == null`, `method == MANUAL_REFERENCE`, `pixelsPerMeter` is the fit and measurements use it (not silently ignored), and `priorBasis` holds the paper plane; *Given* a GRADIENT/PER_AXIS fit, *Then* `plane.kind == CONSTRAINT_FIT`, `method == MANUAL_REFERENCE`, extents are non-zero, `checkResidualMm == null`, and a frozen old `LensMeasurement` measures through the stored homography; *Given* a new ruler calibration, *Then* `constraints`, `fit` and `plane` are cleared; *Given* a height-offset edit, *Then* plane, constraints and fit are kept; *Given* deleting the last constraint, *Then* `priorBasis` is restored exactly.
**Files**: `CM/calibration/ConstraintCalibrator.kt` (new), `CM/calibration/ConstraintModels.kt` (new), `CM/calibration/ConstraintFitApplier.kt` (new), `CM/calibration/ConstraintTuning.kt` (new), `CM/calibration/MeasurementAdvisor.kt`, `CM/calibration/Homography.kt` (make `solve` internal), `CT/calibration/ConstraintCalibratorPropertyTest.kt` (new), `CT/calibration/ConstraintCalibratorFailureTest.kt` (new), `CT/calibration/ConstraintFitApplierTest.kt` (new)

##### Task 3.3a: Tiers SCALE and optional PER_AXIS, typed failures, FitReport (M)
- **Defines every `CalibrationFailure` variant up front, including `CroppedOrZoomedFrame` and `TiltTooLarge` (triad repair 1: Task 3.3c's applier refuses results carrying them, so the types must exist a wave before the tasks that produce them), in `ConstraintModels.kt`.** Normalised coordinates, taps undistorted with the current `k`, log-space median/LSQ scale, 2-param anisotropy (PER_AXIS, optional, off by default) by linear least squares on squared lengths, direction-spread and normal-matrix conditioning gate, residual and LOO per constraint, N=1 "approx" and N=2 agreement, mixed-surface flag. Make `HomographyFitter`'s private `solve` internal and reuse it; no new dependencies.
- Files: `CM/calibration/ConstraintCalibrator.kt`, `CM/calibration/ConstraintModels.kt`, `CM/calibration/Homography.kt`, `CT/calibration/ConstraintCalibratorFailureTest.kt`. REGISTER: `CT` BUILD entry.

##### Task 3.3b1: GRADIENT and GRADIENT_AND_K solver with rank and spread gates (M) (Calibrator lane, after 3.3a)
- (Triad repair 1: the former Task 3.3b, rated L-risk with nine files, is split in three along the solver, the statistical gates and the refusals.) Gauss-Newton/LM over `w0, gx, gy` (and `k`) with a numeric Jacobian on the pure-perspective homography `w0 * [[1,0,0],[0,1,0],[gx,gy,1]]` in normalised pixels, converted to undistorted native pixels for `PlaneCalibration.homography`; no focal length anywhere in the base tier. Rank check; position-spread, radial-spread and noise-floor gates; `IllConditioned` results (all failure variants already exist from Task 3.3a); optional `1/cos(tau)` refinement behind a flag, fed by `FocalSource` (Story 3.9) through `HeightOffset.focalPixels`. In-model property tests, including the SNR recording.
- Files: `CM/calibration/ConstraintCalibrator.kt`, `CM/calibration/ConstraintTuning.kt`, `CT/calibration/ConstraintCalibratorPropertyTest.kt`, `CT/calibration/GradientIdentifiabilityTest.kt` (new). REGISTER: `CT` BUILD entries.

##### Task 3.3b2: Significance test, leave-one-out veto, tilt validity bound and tier selection (M) (Calibrator lane, after 3.3b1)
- The Student-t significance test for N - P dof (`|g| > t_{0.995, N-P} * SE(g)`, ADR-003 decision 5) whose acceptance the leave-one-out comparison can veto; `NoMeasurableTilt`; the **EXIF tilt validity bound** (an optional `CaptureFrameInfo(widthPx, heightPx, exifFocal35mmEq?, zoomRatio?)` input; EXIF focal as an order-of-magnitude check only, falling back to SCALE with `TiltTooLarge` above 10 degrees); tier selection by LOO with a margin from `ConstraintTuning` (values finalised in Story 3.8). Tests: `GradientNoiseFalsePositiveRateTest` (U-E3-07), `GradientSignificanceTest` (U-E3-08), `GradientLooVetoTest` (U-E3-31).
- Files: `CM/calibration/ConstraintCalibrator.kt`, `CM/calibration/ConstraintTuning.kt`, `CT/calibration/GradientNoiseFalsePositiveRateTest.kt` (new), `CT/calibration/GradientSignificanceTest.kt` (new), `CT/calibration/GradientLooVetoTest.kt` (new). REGISTER: `CT` BUILD entries.

##### Task 3.3b3: Crop/zoom refusal, default-off gate and out-of-model robustness (S) (Calibrator lane, after 3.3b2)
- The **crop/zoom refusal** (`CroppedOrZoomedFrame`: sidecar `zoomRatio != 1`, EXIF crop indicators, aspect not 4:3 or 3:2 within 2 percent); GRADIENT selected only when `ConstraintTuning.GRADIENT_DEFAULT_ON` or Settings `calibration_gradient_tier` is on (default off); `OutOfModelRobustnessTest` (U-E3-02, all seven families, generators from Task 3.1b). Tests: `CroppedFrameRefusalTest` (U-E3-09), `GradientDefaultOffTest` (U-E3-32).
- Files: `CM/calibration/ConstraintCalibrator.kt`, `CT/calibration/OutOfModelRobustnessTest.kt` (new), `CT/calibration/CroppedFrameRefusalTest.kt` (new), `CT/calibration/GradientDefaultOffTest.kt` (new). REGISTER: `CT` BUILD entries.

##### Task 3.3c: `ConstraintFitApplier` and the precedence table (S)
- Uses the `CroppedOrZoomedFrame` and `TiltTooLarge` variants that Task 3.3a defines (W3), so it does not depend on Task 3.3b1/b2/b3. Pure function writing a fit into `Calibration`/`GeometryCalibration` per the ADR-003 table (SCALE clears the plane; GRADIENT/PER_AXIS writes the `CONSTRAINT_FIT` plane with metric-extent width/height; K writes `lens.k`; every tier writes `method = MANUAL_REFERENCE` and a `confidencePercent` derived like `MultiPointCalibration.kt:131` (read first); `priorBasis` snapshot and restore; other flows' clears; never writes a `CONSTRAINT_FIT` plane for a result carrying `CroppedOrZoomedFrame` or the "tilt too large" outcome). One test per table row.
- Files: `CM/calibration/ConstraintFitApplier.kt`, `CT/calibration/ConstraintFitApplierTest.kt`. REGISTER: `CT` BUILD entry.

##### Task 3.3d: `MeasurementAdvisor` handles constraint-fit planes (S)
- Branch on `PlaneKind`: `CONSTRAINT_FIT` prints "Scale from N known distances (tier T), +/-R%" and skips the centre check; `PAPER_REFERENCE` unchanged. Extend the advisor tests.
- Files: `CM/calibration/MeasurementAdvisor.kt`, `CT/calibration/MeasurementAdvisorTest.kt` (extend)

#### Story 3.4: Value override model and ViewModel path (Isolate via seam)
**As the** owner, **I want** to change a measurement's value, **so that** a known size shows as I typed it.
**Acceptance Criteria**:
- Edit is an override.
  - *Given* a calibrated photo with a DISTANCE of 0.8741 m, *When* the user submits "0.90" with choice "Change label only", *Then* the value is `Overridden(0.90, original 0.8741)`, geometry and calibration unchanged, and one undo entry exists.
- Recalibration skips overrides.
  - *Given* that override and 3 other measurements, *When* `updateCalibration` changes scale by 4 percent, *Then* the three others are recomputed and the override stays 0.90; the preview text says "1 measurement keeps your edited value".
- Unit parsing and validation.
  - *Given* display unit feet-inches, *When* the user enters `5'3"`, *Then* `valueMeters = 1.6002`; *When* the user enters `0`, `-2`, or an empty string, *Then* submit is disabled with inline helper text and nothing is stored.
- Dragging an override.
  - *Given* an override measurement, *When* the user drags an endpoint, *Then* a confirm "This value was edited by you. Clear it and use the measured length?" asks to clear the override; on decline the points revert.
**Files**: `CM/ui/annotate/MeasurementValueEdit.kt` (new), `CM/ui/annotate/AnnotationEditorViewModel.kt`, `BT/ui/annotate/AnnotationEditorViewModelValueEditTest.kt` (new)

##### Task 3.4a: Pure `MeasurementValueEdit` (S)
- Parse (reuse the reference-length parser from `HeightOffsetForm`/unit prefs), validate (>0, finite, plausible magnitude), produce the new `MeasurementAnnotation`; pure tests incl. property test on parse/format round trip (`5'3"`, fractions, mm/cm/m).
- Files: `CM/ui/annotate/MeasurementValueEdit.kt`, `CT/ui/annotate/MeasurementValueEditTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 3.4b: ViewModel delegation, recompute skip, drag-confirm, undo (M) (VM lane)
- Thin delegations: `submitValueEdit(uuid, text, intent)`, skip `Overridden` in the recompute loop (VM ~L897-903), optimistic+rollback pattern consistent with L877-889, drag confirm, one undo entry.
- Files: `CM/ui/annotate/AnnotationEditorViewModel.kt`, `BT/ui/annotate/AnnotationEditorViewModelValueEditTest.kt`. REGISTER: `AllBusinessTests.kt`.

#### Story 3.5: Inline value editor with the two-meaning choice and its error states (ADR-002)
**As the** owner, **I want** to tap a value and say what the edit means, **so that** I never rescale by accident.
**Acceptance Criteria**:
- Tap opens the editor.
  - *Given* a measurement label at Fit, *When* the user taps the label (hit area >= 48 dp), *Then* the editor shows the value, unit chip and two actions "Use as known distance" and "Change label only" (the former "Just relabel" is retired as jargon; UX-26) each with a one-line consequence; TalkBack exposes "Edit value".
- Scope and unit chip (UX-66, UX-67, OC-19, ux.md 6.0).
  - *Given* a Distance label, *When* tapped, *Then* the editor opens; *Given* an Area or Angle value, *When* tapped, *Then* the measurement is selected, no "Edit value" custom action is exposed, and the strip says "Only distance values can be edited"; *Given* a Label annotation, *Then* its existing text editing is unchanged; *Given* the editor's unit chip set to ft while the toolbar display unit is cm and "2.5" is typed, *Then* the entry is 2.5 ft (0.762 m), the toolbar unit chip is still cm, stored metres are 0.762 and the label shows in cm after Apply; *Given* `5'3"` typed with the chip on cm, *Then* the typed unit wins for that entry.
- Large text, narrow width and RTL (UX-68).
  - *Given* 360 dp, font scale 2.0 and an RTL locale, *Then* the field, unit chip, both radio rows and their consequence lines wrap without clipping, [Cancel] [Apply] stay visible (pinned), the sheet content scrolls vertically, and every target is >= 48 dp.
- Not calibrated defaults to known distance.
  - *Given* a "Not calibrated" photo, *When* the editor opens, *Then* "Use as known distance" is preselected; *Given* a calibrated photo and the **first edit of this editor session**, *Then* neither is preselected and Apply is disabled until one is chosen; *Given* a **later edit in the same session**, *Then* the last choice is preselected (still shown, still changeable) and is not persisted past leaving the editor (one rule, ux.md 6.1; resolves the earlier "remembered for the session" versus "neither preselected" conflict).
- Undo snackbar duration (UX-61).
  - *Given* touch exploration or a switch service is enabled, *When* Apply shows the Undo snackbar, *Then* its duration is indefinite with a dismiss action; otherwise `Long`; the toolbar Undo button is available in both cases.
- Preview before commit.
  - *Given* "Use as known distance" with 90 cm on a measurement and 3 others, *When* the user reviews, *Then* it shows "3 other measurements will change", scale before/after, and Apply; the snackbar offers Undo and one undo restores everything.
- Error states (F13).
  - *Given* a save failure, *Then* rollback, inline "Couldn't save the change. Try again", sheet open, input kept; *Given* a ratio above 3x or below 1/3x of the current scale in known-distance mode, *Then* a non-blocking warning "This is 4x the current scale. Check the unit." with "Apply anyway"; *Given* a zero-pixel-length segment, *Then* known-distance is disabled with "This line is too short to set a scale" and "Change label only" stays allowed; *Given* an edited value, *Then* a pencil glyph shows and the accessible name includes "edited"; *Given* a legacy-basis photo, *Then* known-distance is disabled with "Calibrated with an older method. Re-calibrate to add distances."; *Given* a solver typed failure, *Then* SCALE is used and one footer line says why.
**Files**: `CM/ui/annotate/MeasurementValueEditorSheet.kt` (new), `CM/ui/annotate/AnnotationSemanticNode.kt`, `AU/ui/annotate/MeasurementValueEditorTest.kt` (new)

##### Task 3.5a: Editor sheet UI and semantics (M)
- Bottom sheet in a **new file** (reuse the GRID_REF dialog fields `updateGridRefLengthText`), inline validation, 48 dp label hit area, custom accessibility action only on Distance nodes (Area/Angle expose none; the select-with-message path is in the sheet's host hook, Task 3.6d), entry-unit chip that never writes the global display unit (UX-66, UX-67). The single hook into `AnnotationEditorScreen.kt` is applied in Task 3.6d (Screen lane), not here.
- Files: `CM/ui/annotate/MeasurementValueEditorSheet.kt`, `CM/ui/annotate/AnnotationSemanticNode.kt`

##### Task 3.5b: Robolectric tests (S)
- Cover the three base criteria; register in `AllAndroidUnitTests`.
- Files: `AU/ui/annotate/MeasurementValueEditorTest.kt`. REGISTER: `AllAndroidUnitTests.kt`.

##### Task 3.5c: Editor error states and the "edited" marker (S)
- Implement and test the F13 criteria (save failure, implausible ratio, zero-length segment, legacy basis, pencil/"edited" semantics, solver-failure footer), the unit-chip criterion (X-67, entry unit only) and the 360 dp / 200 percent / RTL layout (X-68c, `ValueEditorTextScaleTest`).
- Files: `CM/ui/annotate/MeasurementValueEditorSheet.kt`, `AU/ui/annotate/MeasurementValueEditorErrorStatesTest.kt` (new), `AU/ui/annotate/ValueEditorTextScaleTest.kt` (new). REGISTER: `AllAndroidUnitTests.kt`.

#### Story 3.6: The calibration pill, known-distances sheet and the state ladder
**As the** owner, **I want** one place to add, see and remove known distances, **so that** I can improve accuracy and see how far to trust it.
**Acceptance Criteria**:
- The visible control is the entry (F1).
  - *Given* the editor, *When* it renders, *Then* the `CalibrationConfidenceBadge`/`CalibrationStatusBar` pill (VERIFIED `CalibrationConfidenceBadge.kt:87`, `AnnotationEditorScreen.kt:1316`) is a single target >= 48 dp tall that opens the Known distances sheet; `CalibrationNudgeBanner` (shown in some states, `AnnotationEditorScreen.kt:401`) opens the same sheet and the two never show contradictory text.
- Legacy methods are never "Not calibrated".
  - *Given* a photo calibrated by BLE, manual reference, ruler ticks, paper plane or EXIF estimate, *When* the pill renders, *Then* it keeps the existing method label ("+/-manual ref", "Scale uncertain") and never "Not calibrated"; "Not calibrated" shows only when calibration is null or method NONE; for `basisVersion = 0` the known-distances action is disabled with the reason and a link to Calibrate.
- State ladder (text plus icon, never colour only).
  - *Given* 0 distances and no calibration, *Then* "Not calibrated - add a known distance"; *Given* 1 constraint, *Then* "Scale from 1 distance (approx.)" with "Add another"; *Given* exactly 2 constraints (F2), *Then* "Scale from 2 distances, agree within X%"; *Given* 3 non-collinear with residual mean 1.2 percent, *Then* "Calibrated from 3 distances, +/-1.2%" with the confidence badge; *Given* a fit that fell back, *Then* "Scale from N distances (basic)" and the sheet explains why.
- Row detail and worst-offender flag.
  - *Given* a row whose residual is at least 3x the mean and over 3 percent, *When* shown, *Then* it is flagged "disagrees by 6% - re-measure or remove?" with Use anyway; *Given* `mixedSurfaces`, *Then* the hint is "These distances may be on different surfaces" instead; delete removes the row and re-fits (calibration warning dialog with the count of affected measurements, then a snackbar Undo restoring the previous list and fit).
- Direction nudge.
  - *Given* all constraint midpoints within 25 percent of the image diagonal of one another, *When* shown, *Then* the hint "Add one in a different part of the photo to check tilt" appears and does not block; *Given* the optional PER_AXIS tier is enabled and all directions are within 15 degrees, *Then* "Add one in a different direction to check stretching" appears instead.
- Known-distance round trip (F11).
  - *Given* the user taps "Draw a known distance", *When* the sheet closes, *Then* DISTANCE arms with `pendingKnownDistance = true` and the strip reads "Known distance: drag from A to B"; *When* the drag commits, *Then* the value editor opens automatically in known-distance mode for the new measurement (auto-return still happens); *When* Apply, *Then* the sheet reopens with the new row; *When* Cancel, *Then* the drawn measurement is removed and the sheet reopens with nothing added; in at most 4 steps (tap Draw, drag, type value, Apply).
- Empty state (UX-58, triad repair 1).
  - *Given* a photo with zero constraints, *When* the sheet opens, *Then* it shows the explanation "A known distance is a length you know, like a door width..." with "Draw a known distance" and "Use a card or sheet of paper", never an empty list; with a legacy calibration the heading is the legacy method label; the sheet has `[x]`, Back and scrim exits.
- Existing options retained.
  - *Given* the sheet, *When* opened, *Then* "Draw a known distance" and "Use a card or sheet of paper" are on top and the peer/BLE/ticks options are under "More ways". The words "lens distortion" are never a headline ("Improve accuracy").
- Duplicate and typo guards.
  - *Given* a segment within 2 px of an existing one, *Then* "This segment is already a known distance" "Replace value" or "Cancel"; all-collinear is informational only.
**Files**: `CM/ui/annotate/KnownDistancesSheet.kt` (new), `CM/ui/annotate/CalibrationStatePresenter.kt` (new), `CM/ui/annotate/CalibrationNudgeBanner.kt`, `CM/ui/annotate/CalibrationSheet.kt`, `CM/ui/annotate/CalibrationConfidenceBadge.kt`, `AU/ui/annotate/KnownDistancesSheetTest.kt` (new)

##### Task 3.6a: Presentation model (pure) (S)
- `CalibrationStatePresenter(calibration, constraints, fit) -> PillState + rows` covering: legacy-method labels, the exact-2 rung, 1 rung, 3+ rung, fell-back rung, the position-cluster tilt hint (shown only while the GRADIENT tier is enabled; and the 15-degree PER_AXIS hint when that tier is enabled), the tier and "estimated accuracy +/-R%" text (never a bare check), the mixed-surface hint, worst-offender threshold (3x mean and >3 percent), duplicate segment, legacy-basis disabled reason; pure tests for every rung.
- Files: `CM/ui/annotate/CalibrationStatePresenter.kt`, `CT/ui/annotate/CalibrationStatePresenterTest.kt` (new). REGISTER: `CT` BUILD entry.

##### Task 3.6b: Sheet, pill and banner components (M)
- New `KnownDistancesSheet` (including the zero-row empty state), pill/banner as single >= 48 dp targets (pill ~19 dp today), keep `CalibrationChangeWarningDialog`; delete-row Undo snackbar uses the UX-61 duration rule; components only, no edits to `AnnotationEditorScreen.kt` (hooks are Task 3.6d). Known distances rows satisfy UX-68: the 56 dp minimum grows, names wrap to two lines, value, residual text and the 48 dp delete stay visible at 360 dp, font scale 2.0 and RTL.
- Files: `CM/ui/annotate/KnownDistancesSheet.kt`, `CM/ui/annotate/CalibrationNudgeBanner.kt`, `CM/ui/annotate/CalibrationSheet.kt`, `CM/ui/annotate/CalibrationConfidenceBadge.kt`

##### Task 3.6c: `pendingKnownDistance` flow in the ViewModel (S) (VM lane, last)
- One-shot flag set by "Draw a known distance"; on a committed DISTANCE with the flag, emit the open-editor event and clear it; Cancel removes the drawn measurement without leaving an undo entry; `restoredOver` keeps the live flag. Tests for apply, cancel and undo.
- Files: `CM/ui/annotate/AnnotationEditorViewModel.kt`, `BT/ui/annotate/AnnotationEditorViewModelPendingKnownDistanceTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

##### Task 3.6d: Screen hooks for the value editor, pill and known-distances sheet (S) (Screen lane, last)
- One worker adds the three hooks (label tap -> editor sheet for Distance only, with the "Only distance values can be edited" strip message for Area/Angle; pill -> sheet, pending-known-distance event) to `AnnotationEditorScreen.kt` after 2.6a, so E3 never edits the Screen concurrently with E2.
- Files: `CM/ui/annotate/AnnotationEditorScreen.kt`

##### Task 3.6e: Robolectric tests for pill and sheet (S)
- Ladder rungs, legacy labels, 48 dp target, row flagging, delete + undo, hints, 4-step round trip with a fake ViewModel, and the text-scale rows test (X-68d: 360 dp, font scale 2.0, RTL, long row names). Also `ValueEditorScopeTest` (X-66; needs the Task 3.6d host hook, hence this task): Distance opens the editor, Area and Angle select with "Only distance values can be edited" and expose no "Edit value" action, Label keeps its text editing.
- Files: `AU/ui/annotate/KnownDistancesSheetTest.kt`, `AU/ui/annotate/ValueEditorScopeTest.kt` (new). REGISTER: `AllAndroidUnitTests.kt`.

#### Story 3.7: Re-fit integration, history and undo
**As the** owner, **I want** calibration edits to be undoable and consistent, **so that** a bad known distance is one tap to revert.
**Acceptance Criteria**:
- Adding a constraint re-fits.
  - *Given* a photo with 2 constraints, *When* a third is added via `confirmConstraintCalibration`, *Then* the applier runs once, a `FitReport` is stored, and the log contains `calibration fit tier=SCALE n=3`.
- Calibration history holds the constraint list and fit (via `CalibrationHistory`, not new ViewModel logic).
  - *Given* 3 constraints then a delete of one, *When* the user taps undo calibration, *Then* all 3 constraints, the previous fit and the replaced basis return (the single-level `calibrationHistory` is replaced by the list-aware snapshot in the named collaborator).
- Existing flows follow the precedence table.
  - *Given* a photo with constraints, *When* a new line/ruler calibration is confirmed through `keepingGeometry`, *Then* `constraints`, `fit` and `plane` are cleared (after the warning dialog); *When* `setGeometry` edits a height offset, *Then* plane, constraints and fit survive.
- Failure falls back safely.
  - *Given* `Left(Collinear)` for the requested tier, *When* applied, *Then* the SCALE tier result is used, the sheet says why, and measuring is never blocked.
- Overrides survive.
  - *Given* an override measurement, *When* a constraint is added, *Then* it is unchanged (Story 3.4 criterion holds under re-fit).
**Files**: `CM/ui/annotate/CalibrationHistory.kt` (new), `CM/ui/annotate/AnnotationEditorViewModel.kt` (`keepingGeometry` ~L1221, `setGeometry` ~L1204, `confirmConstraintCalibration` modelled on `confirmMultiPointCalibration` L657), `CM/calibration/CalibrationService.kt`, `BT/ui/annotate/AnnotationEditorViewModelConstraintTest.kt` (new), `CT/ui/annotate/CalibrationHistoryTest.kt` (new)

##### Task 3.7c: Extract `CalibrationHistory` (S)
- A named, pure collaborator holding undo snapshots of `(calibration, constraints, fit)`; tests; extracted **before** 3.7a so that 3.7a only delegates (architecture C8: the ViewModel must not grow).
- Files: `CM/ui/annotate/CalibrationHistory.kt`, `CT/ui/annotate/CalibrationHistoryTest.kt`. REGISTER: `CT` BUILD entry.

##### Task 3.7a: `confirmConstraintCalibration` and precedence-table wiring (M) (VM lane)
- Delegating `confirmConstraintCalibration`; `keepingGeometry` and `setGeometry` call `ConstraintFitApplier` rules (clear constraints/fit/plane on a new line/ruler/paper calibration; keep through height offset); `setGeometry`'s `takeIf { height > 0 || distance != null || plane != null }` (`AnnotationEditorViewModel.kt:~1213`) is replaced by `merged.takeIf { !it.isEmpty() }` (Task 3.2a's emptiness rule), and `keepingGeometry`'s `copy(plane = null)` also clears `constraints` and `fit`; `CalibrationHistory` replaces the single-level snapshot; log the fit. No new logic inline.
- Files: `CM/ui/annotate/AnnotationEditorViewModel.kt`, `CM/calibration/CalibrationService.kt`

##### Task 3.7b: ViewModel tests (S)
- Cover the five criteria with in-memory repositories; register in `AllBusinessTests`.
- Files: `BT/ui/annotate/AnnotationEditorViewModelConstraintTest.kt`. REGISTER: `AllBusinessTests.kt`.

#### Story 3.8: Accuracy study on real photos; set N and thresholds; decide Success Metric 3
**As the** owner, **I want** the model validated on my photos, **so that** the shipped tier thresholds are measured and SM3 is honestly met or not.
**Acceptance Criteria**:
- Verification set result (Success Metric 3, owner-verified).
  - *Given* the owner's door-frame photo plus at least 4 further photos with a tape-measured ground truth for at least 6 distances each (supplied by Tyler), *When* the harness fits tiers on N-1 distances and evaluates the withheld one, *Then* a table per photo, tier and scene family is committed to `project_plans/sync-lock-and-image-annotator/implementation/accuracy-study.md`, comparing against **both** single-scale and the best existing calibration (ticks/ruler/paper) on the same photo, and `ConstraintTuning` margins and spread gates are set from it.
- Outcome is explicit.
  - *Given* the study, *When* complete, *Then* `accuracy-study.md` ends with exactly one of: "SM3 met: shipped default tier has lower mean absolute error than single scale on the real hold-out" or "SM3 not met for distortion: default stays SCALE-with-LOO (a noise-averaging gain only); the owner was told", and the PR checklist item "SM3: met / not met" must be filled; the work item may not be marked done with it blank.
- **Honest design of the study (validation I-E3-12/13).**
  - *Given* the owner's photos split into **set A** (the door-frame photo plus at least 2 more, used to tune N and thresholds) and **set B** (at least 2 other photos never seen by tuning, which decide), *When* the harness runs, *Then* it reports MAE on the withheld distance by leave-one-out per photo and by the A to B transfer, and "met" requires default-tier MAE below single-scale MAE on set B with no photo worse by more than 10 percent (`RealPhotoHoldOutStudyTest`, owner fixtures, `Assume` only keeps CI green when they are absent).
  - *Given* `accuracy-study.md`, *When* `AccuracyStudyVerdictTest` (doc audit) reads it, *Then* it ends with exactly one of the two verdict lines, and "SM3 met" is rejected if the evidence table has fewer than 5 real photos, fewer than 6 tape-measured distances per photo, no door-frame photo, or an empty set B. A "met" verdict is also the only thing that may flip `ConstraintTuning.GRADIENT_DEFAULT_ON` to true.
**Files**: `CM/calibration/ConstraintTuning.kt`, `project_plans/sync-lock-and-image-annotator/implementation/accuracy-study.md` (new)

##### Task 3.8a: Study runner and tuning (M)
- Dev-only script/test taking tapped coordinates + ground truth JSON (owner supplies; fixtures stay synthetic in the public repo), prints the held-out table across tiers and baselines, writes thresholds. Wall-clock blocker: owner provides photos and ground truth. Also adds the set A / set B split, `RealPhotoHoldOutStudyTest` (I-E3-12) and `AccuracyStudyVerdictTest` (I-E3-13, doc audit, CI) so SM3 cannot be claimed on synthetic data alone.
- Files: `CT/calibration/ConstraintStudyRunner.kt` (new), `CM/calibration/ConstraintTuning.kt`, `JT/calibration/RealPhotoHoldOutStudyTest.kt` (new), `BT/calibration/AccuracyStudyVerdictTest.kt` (new). REGISTER: `CT` BUILD entry, `AllJvmTests.kt`, `AllBusinessTests.kt`.

#### Story 3.9: Optional focal refinement and optional lens-id profile matching
**As the** owner, **I want** the tilt tier to work on my existing photos without a focal length, with an optional exact-foreshortening refinement when a focal length can be trusted, and a calibration on the ultra-wide not reused for the main lens, **so that** a wrong EXIF focal length can never mis-rectify a photo and E3 does not wait on E4.
**Acceptance Criteria**:
- Nothing in the base tiers needs a focal source (no focal gate exists; this SUPERSEDES the iteration-1 "mandatory gate / required for TILT" wording, adversarial C8).
  - *Given* a photo with only EXIF `FocalLength` or none, *When* the calibrator runs, *Then* SCALE and GRADIENT are evaluated and `FocalSource = EXIF_ONLY` is recorded in `ConstraintFitRecord.focalSource`; no `FocalLengthUnknown`/`FocalLengthUntrusted` failure exists.
- Source ranking and the f-in-pixels derivation are defined.
  - *Given* `USER_CONFIRMED` (a 35 mm-equivalent value typed in the existing height-offset focal field) or `SIDECAR_LENS_RECORD` (Task 3.9c; zoom ratio 1; the record's `focalLengthMm` converted with the record's 35 mm-eq or the profile's), *Then* the refinement may run with `f_px = HeightOffset.focalPixels(f35, widthPx, heightPx)` (`HeightOffset.kt:36-38`, uncropped-frame assumption stated in the UI copy); *Given* `PROFILE` (matches EXIF focal against the profile's EXIF focal, so no independent evidence) or `EXIF_ONLY`, *Then* the refinement is never applied and the report says "EXIF-consistent, not independently confirmed". Zoom ratio != 1 marks "cropped, focal not applicable".
- Without E4 nothing else changes.
  - *Given* no `CaptureLensRecord`, *When* a profile is matched, *Then* the result equals today's make/model/focal match.
- With lens data (optional).
  - *Given* two profiles for the same model at focal 2.2 mm (ultra-wide) and 5.6 mm (main) and a photo recorded with focal 2.2 mm and lens id "2", *When* matched, *Then* the 2.2 mm profile is chosen and a photo with mismatching lens id is rejected with `ProfileRejection` reason lens-mismatch; zoom ratio != 1 marks "cropped, profile not applicable".
  - *Given* a `CaptureLensRecord` whose `source == SELECTED_LENS` (it names the chip, not the lens that took the photo), *Then* it never auto-selects a profile and the UI shows the record's source; *Given* `PREVIEW_RESULT_AT_SHUTTER` but `exifFocalMm` disagreeing with `focalLengthMm` by more than 15 percent, *Then* profile matching is disabled for that photo (ADR-004 decision 5; pre-mortem P2-11).
**Files**: `CM/calibration/FocalSource.kt` (new), `CM/calibration/CameraProfile.kt`, `CM/calibration/CalibrationService.kt`, `CM/db/sidecar/ImageSidecarManager.kt` (read accessor, Task 3.9c), `CT/calibration/CameraProfileTest.kt`, `CT/calibration/FocalSourceTest.kt` (new)

##### Task 3.9a: `FocalSource` resolver and the optional refinement input (S)
- Enum and resolver `(userConfirmed35mmEq, sidecarLensRecord?, profileMatch, exifFocal) -> FocalSource` plus `focalPixelsOrNull(...)` delegating to `HeightOffset.focalPixels`; wire into `ConstraintCalibrator` inputs as an optional parameter that defaults to "no refinement"; tests for the ranking table above and for "EXIF_ONLY and PROFILE never enable the refinement".
- Files: `CM/calibration/FocalSource.kt`, `CT/calibration/FocalSourceTest.kt`. REGISTER: `CT` BUILD entry.

##### Task 3.9b: Optional lens-id input to profile matching (S)
- **Decision: kept as an optional refinement** tied to requirements Scope item 4 ("record lens/focal length ... for calibration use"); it reuses the existing profile set (no new profile database, so it stays inside the Out of Scope line) and is droppable alone (see "Scope additions traced to requirements"). Add optional `lensId`/`zoomRatio` to the match key with property test "never matches when any provided field mismatches; order-independent"; a record with `source == SELECTED_LENS`, or with `exifFocalMm` disagreeing with `focalLengthMm` by more than 15 percent, contributes no `lensId` at all.
- Files: `CM/calibration/CameraProfile.kt`, `CM/calibration/CalibrationService.kt`, `CT/calibration/CameraProfileTest.kt`

##### Task 3.9c: Route the sidecar lens record to the fit input (S) (Sidecar lane, after 4.5a2)
- `ImageSidecarManager` gains a read accessor `readLensRecord(uuid)` (the editor's repository path rebuilds `ImageSensorData` from DB columns and `CaptureLensRecord` has no column, so the record is otherwise null at fit time); `CalibrationService` passes it to the `FocalSource` resolver. Photos without a record resolve to `EXIF_ONLY`. Test with a golden sidecar with and without the record.
- Files: `CM/db/sidecar/ImageSidecarManager.kt`, `CM/calibration/CalibrationService.kt`, `BT/db/sidecar/LensRecordFocalSourceTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

---

## Phase 4: Epic 4 — Camera lens switching and lens metadata

### Epic 4.1: List the rear lenses, switch between them, record which one took the photo
**Goal**: Success Metric 4 without regressing the preview binder or the AVD flow.

#### Story 4.1: Capability probe and fixture recording (spike; first; owner device)
**As the** maintainer, **I want** the owner's phone to report its cameras, **so that** classification and lens claims rest on recorded data.
**Acceptance Criteria**:
- Probe logs the facts.
  - *Given* the debug build on the owner's phone, *When* Tyler opens the probe (from the camera dialog overflow or Settings advanced), *Then* the Logs screen shows per camera id: facing, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, sensor size, `REQUEST_AVAILABLE_CAPABILITIES` (logical multi-camera flag), `getPhysicalCameraIds`, `LENS_INTRINSIC_CALIBRATION` and `LENS_DISTORTION` presence, `DISTORTION_CORRECTION_AVAILABLE_MODES`, `ZoomState` min/max, plus EXIF `FocalLength`/`LensModel` of one captured test photo per lens/zoom stop.
- Physical-id bind result per physical camera (ADR-004 decision 2; CameraX 1.4.1 already has `CameraSelector.Builder.setPhysicalCameraId`).
  - *Given* a logical camera with physical ids, *When* the probe runs, *Then* for each physical id it logs whether a bind with `setPhysicalCameraId` succeeded (`ok`/`fail:<exception>`) and the resulting `Camera2CameraInfo.getCameraCharacteristicsMap()` keys.
- Capture-metadata availability (decides the lens-record source, ADR-004 decision 5).
  - *Given* a still captured with a `Camera2Interop.Extender.setSessionCaptureCallback` registered, *When* the probe finishes, *Then* it logs whether `LENS_FOCAL_LENGTH`, `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` and `SCALER_CROP_REGION`/`CONTROL_ZOOM_RATIO` arrived in a result whose timestamp is within one frame of the shutter, per lens.
- Fixture export.
  - *Given* the probe output, *When* exported, *Then* it is saved as JSON fixtures `lens-fixture-<model>.json` that Story 4.2 tests load (public repo: device make/model only, no serial or location).
**Files**: `AM/platform/sensor/CameraCapabilityProbe.kt` (new), `AM/ui/components/CameraViewfinderDialog.android.kt` (entry point), `kmp/src/androidUnitTest/resources/camera/lens-fixture-*.json`

##### Task 4.1a: Probe implementation (M)
- Camera2 `CameraManager.getCameraCharacteristics` per id plus CameraX `availableCameraInfos`/`ZoomState`, per-physical-id bind attempts with `setPhysicalCameraId`, and a session capture callback recording result keys at shutter; write via `Logger` (persisted). Opt-in `@ExperimentalCamera2Interop`. Catch `Throwable` around every call. Debug-only entry; no new permission (CAMERA is held). Add `CapabilityProbeFormatTest` (U-E4-14): a missing characteristic never aborts the probe, and the exported fixture carries device make/model only (no serial or location).
- Files: `AM/platform/sensor/CameraCapabilityProbe.kt`, `AM/ui/components/CameraViewfinderDialog.android.kt`, `CT/platform/sensor/CapabilityProbeFormatTest.kt` (new; the probe's line formatter is pure). REGISTER: `CT` BUILD entry.

##### Task 4.1b: Owner run and fixture check-in (XS)
- Owner runs the probe, exports logs; commit sanitized fixture(s). Wall-clock blocker, owner Tyler. Result also answers: does the AVD expose extra lenses (Open Question 6)? Run the probe once on `Pixel_Fold` (`emulator -avd Pixel_Fold -no-window ...`, `ANDROID_HOME` exported) and record the answer. Its outcome selects the capture-record source in 4.5b.
- Files: `kmp/src/androidUnitTest/resources/camera/lens-fixture-*.json`

#### Story 4.2: Pure lens classification and labelling
**As the** owner, **I want** accurate lens chips, **so that** I never choose a "2x lens" that is just a digital crop.
**Acceptance Criteria**:
- Classification from recorded fixtures.
  - *Given* the owner-phone fixture with back cameras focal 2.2 mm, 5.6 mm, 9.0 mm (values replaced by recorded ones), *When* classified, *Then* kinds are ULTRA_WIDE, WIDE, TELE ordered by focal, labelled with ratio relative to WIDE (0.5x, 1x, 1.6x) and secondary "13 mm eq.".
- Logical camera hides lenses.
  - *Given* a fixture with one logical back camera, `minZoomRatio = 0.6`, `maxZoomRatio = 8`, *When* classified, *Then* chips are zoom stops 0.6x, 1x, 2x, 5x flagged `optical=false` for stops above the longest focal and labelled "Digital" in hint, and no lens is labelled by id.
- Front camera excluded; duplicates collapsed.
  - *Given* a fixture with a front camera and the ultra-wide listed under two ids, *When* classified, *Then* the front is absent and the ultra-wide appears once.
- Property.
  - *Given* any shuffled list of `CameraLensInfo`, *When* classified, *Then* the output is order-independent and never contains `facing = FRONT`.
**Files**: `CM/platform/sensor/CameraLens.kt` (new), `CM/platform/sensor/LensClassifier.kt` (new), `CT/platform/sensor/LensClassifierTest.kt` (new), fixtures

##### Task 4.2a: Types and classifier (S)
- `CameraLens`, `CameraLensInfo`, `LensKind`, `LensClassifier.classify(infos): List<CameraLens>` plus chip label formatting; thresholds from the fixture, not guessed (use provisional focal-ratio bands until Task 4.1b lands, then tighten).
- Files: `CM/platform/sensor/CameraLens.kt`, `CM/platform/sensor/LensClassifier.kt`

##### Task 4.2b: Fixture-driven and property tests (S)
- Load JSON fixtures from test resources (commonTest resource bundling per existing pattern); kotest-property for order independence. Add `LensClassifierFixtureMatrixTest` (U-E4-04), the **OEM CameraCharacteristics fixture matrix**: (a) Pixel-style logical multi-camera with physical ids and zoom 0.5-8, (b) Samsung-style separate back ids, (c) hidden-ultrawide logical camera with only a zoom range, (d) single lens without zoom range (no chips), (e) characteristics missing `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` or `SENSOR_INFO_PHYSICAL_SIZE` (never throws), (f) two back cameras with identical focal (depth/macro duplicates), (g) 35 mm-equivalent from sensor size within 5 percent. REGISTER: `CT` BUILD entry (or `AllAndroidUnitTests.kt` if placed in androidUnitTest).
- Files: `CT/platform/sensor/LensClassifierTest.kt`, `CT/platform/sensor/LensClassifierFixtureMatrixTest.kt` (new)

#### Story 4.3: `CameraSession` and `CameraLensCatalog` (Isolate via seam)
**As the** owner, **I want** reliable lens switching, **so that** the preview never goes black or freezes.
**Acceptance Criteria**:
- Serialised switching (design, fake-testable).
  - *Given* a `FakeCameraBackend` whose `bind` takes 300 ms of virtual time, *When* lens chips are tapped 5 times in 100 ms, *Then* exactly one bind runs for the last requested lens after the in-flight one completes, the shutter is disabled during rebind and a capture started before the switch completes is dropped.
- Failed bind keeps the previous lens.
  - *Given* lens "3" throws `CameraUnavailableException` on bind, *When* selected, *Then* the previous lens remains bound, the chip for "3" shows disabled with reason and a log line `camera bind lens=3 result=fail:...` is written.
- Physical lenses only where bind-tested.
  - *Given* a logical camera with physical ids, *When* the catalog lists lenses, *Then* a physical-id lens appears only if its `setPhysicalCameraId` bind succeeded in the fixture/runtime; otherwise zoom-ratio stops are listed.
- Binders scoped; `CameraSession` never calls `unbindAll()`.
  - *Given* the dialog switches lens while `AndroidCameraPreviewBinder` has a Preview bound on `ProcessLifecycleOwner` and `AndroidCameraFrameSource` has an `ImageAnalysis` bound, *When* the switch runs, *Then* only the session's own use cases are unbound (fake records `unbind(useCases)`; `unbindAll` is never called by the session); `AndroidCameraProvider`'s one-shot `capturePhoto` (L113/L161) is scoped to its own session and a test asserts tearing it down leaves the other binders' use cases bound; **the dialog's own `unbindAll()` (`CameraViewfinderDialog.android.kt:105,118`, VERIFIED by grep this pass) is removed and the dialog unbinds through the session (Task 4.4a)**.
- A throwing bind never crashes (pre-mortem P2-10).
  - *Given* a fake backend whose bind throws `IllegalArgumentException`, `SecurityException` or `CameraInfoUnavailableException` (not only `CameraUnavailableException`) on a lens switch or a physical-id bind, *When* it is selected, *Then* the previous lens stays bound, the chip is disabled with a reason, `camera bind lens=<id> result=fail:<class>` is logged and nothing reaches the uncaught-exception handler; `CancellationException` is rethrown. Every bind, `CameraX` future and `Camera2Interop` callback registration catches `Throwable`.
- Lifecycle.
  - *Given* a configuration change while a lens is selected, *When* the dialog recomposes, *Then* the selected lens id is restored from `rememberSaveable`.
**Files**: `AM/platform/sensor/CameraSession.kt` (new), `CM/platform/sensor/CameraLensCatalog.kt` (new), `AM/platform/sensor/CameraXLensCatalog.kt` (new), `AU/platform/sensor/CameraSessionTest.kt` (new)

##### Task 4.3a: State machine and backend interface (M)
- `CameraSession` with a `CameraBackend` interface (fake in tests; CameraX implementation separate), `bind/switchLens/setZoom/setFlash/unbind`, serial queue (last-wins), shutter gating, drop stale captures, own scope (no `rememberCoroutineScope` leakage per CLAUDE.md); **catches `Throwable` (rethrowing `CancellationException`) at every bind, future and interop registration; a failing bind keeps the previous lens (fake-backend test with `IllegalArgumentException`/`SecurityException`)**; own use cases only; `@ExperimentalCamera2Interop` opt-in scoped to the files that need it; physical-id bind results gate which chips exist (Task 4.3b).
- Files: `AM/platform/sensor/CameraSession.kt`, `AU/platform/sensor/CameraSessionTest.kt`. REGISTER: `AllAndroidUnitTests.kt`.

##### Task 4.3b: CameraX backend and catalog, zoom-chip first (M)
- CameraX 1.4.1 (no version bump): `availableCameraInfos`, `Camera2CameraInfo.getCameraCharacteristicsMap()` for per-camera focal lengths, zoom via `CameraControl.setZoomRatio`, `ZoomState` chips. Feeds `LensClassifier`. **Zoom-chip first (triad repair 1):** the catalog always produces the zoom-ratio stops (from the device zoom range) and the separately exposed back cameras; a **physical-id lens** (`CameraSelector.Builder.setPhysicalCameraId`) is listed only if its bind succeeds at runtime on that device, and the physical-id path is **UNVERIFIED until the owner probe run (Task 4.1b)**, so nothing in this task assumes it works; when no physical-id bind succeeds the dialog shows zoom stops and the honest line "Ultra-wide isn't available as a separate lens on this device", which still satisfies SM4 (requirements fallback acceptance). 4.1b therefore gates acceptance and the 4.5b record source, not this task.
- Files: `AM/platform/sensor/CameraXLensCatalog.kt`, `AM/platform/sensor/CameraXBackend.kt` (new)

#### Story 4.4: Capture dialog UI: lens chips, flash, zoom, error and permission states
**As the** owner, **I want** to pick a lens and basic controls, **so that** I can frame tight spaces and always have a way out.
**Acceptance Criteria**:
- Chips for real lenses (Success Metric 4).
  - *Given* the catalog returns three back lenses, *When* the dialog opens, *Then* three chips (e.g. 0.5x / 1x / 1.6x) are shown with the 1x chip selected by default (or the last-used lens), each >= 48 dp, content description includes focal length, selected chip announced as selected.
- Switch, held frame and hint.
  - *Given* the 0.5x chip tapped, *When* the rebind runs, *Then* the previous frame stays visible (brief crossfade, none under reduced motion), the shutter is disabled and the chip shows progress; *When* it completes, *Then* the chip is selected and a dismissible hint "Wide angle distorts edges; fine for tight spaces" appears; pinch-to-zoom moves the highlight to the nearest chip and chips only claim optical lenses.
- Single lens fallback.
  - *Given* the catalog returns one lens and `ZoomState` 1x-8x, *When* the dialog opens, *Then* only zoom stops are shown (no lens chips), preserving today's behavior otherwise; with one lens and no zoom range, no chips and no zoom row.
- Flash cycle.
  - *Given* the flash button, *When* tapped three times, *Then* it cycles Off, Auto, On with text and an announcement, one step per press.
- Failure states (F12).
  - *Given* a lens fails to bind, *Then* inline message "Ultra-wide isn't available on this device right now" (polite live region, ~3 s) shows and the preview is not black; *Given* all lenses fail or the camera is in use, *Then* the full-area message "Camera isn't available. Another app may be using it." with Try again, Pick from gallery and Close; *Given* a capture failure, *Then* "Couldn't take the photo. Try again." with the shutter re-enabled.
- Permission states (F12).
  - *Given* permission not yet granted, *Then* the existing `PermissionRationaleDialog` then the system prompt (Not now leads to gallery/close); *Given* denied (can ask again), *Then* Allow camera, Pick from gallery instead, Close; *Given* permanently denied, *Then* Open app settings, Pick from gallery instead, Close; *Given* permission revoked while open, *Then* the preview stops and the denied screen shows without losing unsaved state.
- Initial bind, flash availability and OEM-hidden lenses (UX-56, UX-57, triad repair 1).
  - *Given* the dialog opens and no frame has arrived, *Then* a neutral surface shows "Starting camera..." with a progress indicator (polite live region) and shutter and chips are disabled; *Given* no frame within 5 s of virtual time, *Then* the all-lenses-failed screen shows; *Given* the selected camera has no flash unit, *Then* the flash button is disabled with "Flash not available on this lens" (not hidden) and the cycle skips it; *Given* a fixture where ultra-wide and tele are hidden behind one logical camera, *Then* zoom-ratio chips with optical/"Digital" labels show with the line "Ultra-wide isn't available as a separate lens on this device", and the saved record is the logical camera plus zoom with `source = SELECTED_LENS`.
- Pinch-to-chip hysteresis and hint persistence (UX-59, UX-60).
  - *Given* a pinch whose ratio hovers within 5 percent of the span around the midpoint of two stops for five alternating movements, *Then* the highlight changes at most once and no lens rebind runs; *Given* the ultra-wide hint is dismissed, *When* the dialog is reopened or the process restarts, *Then* it is not shown again (Settings flag `camera_ultrawide_hint_dismissed`).
- Rotation/fold.
  - *Given* a rotation or fold change during rebind, *Then* the selected lens is restored from saved state, the shutter stays disabled until bound, the last frame is held, and nothing draws under system bars; at >= 600 dp chips and shutter move to a side rail.
**Files**: `AM/ui/components/CameraViewfinderDialog.android.kt`, `AM/ui/components/CameraLensChips.kt` (new), `AU/ui/components/CameraLensChipsTest.kt` (new)

##### Task 4.4a: Chips composable and dialog integration (M)
- `CameraLensChips` stateless composable driven by `List<CameraLens>`; the "Starting camera..." initial-bind state and the disabled-flash state (UX-56, UX-57); dialog uses `CameraSession` **and its two `unbindAll()` calls (L105, L118) are replaced by the session's own-use-case unbind** (they would tear down the preview binder's Preview and the frame source's `ImageAnalysis`, and `AndroidCameraProvider.kt:113,161` stays scoped to its own one-shot session); remembers last-used lens (Settings), restores via `rememberSaveable`; landscape/foldable reflow; chips wrap onto two rows and the secondary focal line wraps or folds into the content description at 200 percent (UX-68). Keep the `expect` signature.
- Files: `AM/ui/components/CameraLensChips.kt`, `AM/ui/components/CameraViewfinderDialog.android.kt`

##### Task 4.4b: Robolectric chip and fallback tests (S)
- Compose tests with a fake catalog for the criteria that do not need hardware; register in `AllAndroidUnitTests`.
- Files: `AU/ui/components/CameraLensChipsTest.kt`. REGISTER: `AllAndroidUnitTests.kt`.

##### Task 4.4c: Error, permission and rotation states (S)
- Implement and test the text-scale rule for the chips and top bar (360 dp, font scale 2.0, RTL; X-68e, in `CameraDialogStatesTest`), the F12 failure and permission screens, held-frame crossfade, dismissible hint with persisted dismissal, and pinch-to-chip sync with the hysteresis rule (5 percent of the span, 150 ms, no rebind from a pinch; UX-59, UX-60).
- Files: `AM/ui/components/CameraViewfinderDialog.android.kt`, `AU/ui/components/CameraDialogStatesTest.kt` (new). REGISTER: `AllAndroidUnitTests.kt`.

#### Story 4.5: Record the actual lens at capture; persist; keep the EXIF
**As the** owner, **I want** the saved photo to say which lens took it, **so that** calibration uses the right profile.
**Acceptance Criteria**:
- Recorded from capture metadata, not the UI choice (mechanism per ADR-004 decision 5; conditional on Task 4.1b).
  - *Given* the UI selected 1x on a logical camera and the session capture-callback snapshot taken at shutter reports active physical id "2", `LENS_FOCAL_LENGTH` 5.6 mm and zoom 1.0, *When* the photo is captured, *Then* `CaptureLensRecord(cameraId="0", physicalId="2", focalLengthMm=5.6, zoomRatio=1.0, source=PREVIEW_RESULT_AT_SHUTTER, exifFocalMm=<EXIF read before the fixer>)` is built from that snapshot, not from the chip; *Given* the probe showed the callback or active-physical-id key is not delivered, *Then* the record is built from the selected lens plus zoom at shutter with `source=SELECTED_LENS`; both paths are covered by fake-backend tests (`OnImageSavedCallback` carries no `CaptureResult`, so no test may assume one).
- EXIF survives the re-encode (known defect fixed).
  - *Given* a rotated captured JPEG processed by `ExifOrientationFixer.fixOrientation` (`AM/platform/sensor/ExifOrientationFixer.kt:31`; it decodes, re-encodes and rewrites only `Orientation`, dropping `FocalLength`, `Make`, `Model`, `DateTime`), *When* read back, *Then* those four tags (and `LensModel` when present) equal the pre-fix values; the sidecar lens record does not depend on EXIF. A Robolectric fixture test asserts the surviving tag set.
- Persisted in the sidecar.
  - *Given* an imported annotation with a `CaptureLensRecord`, *When* the sidecar is written and re-read, *Then* the record round-trips; an old sidecar without it reads as null; the keys live in a nested `SidecarLensRecord` and call `SidecarVersion.forContent`.
- Used at import, and only when the record can be trusted (pre-mortem P2-11).
  - *Given* a `PREVIEW_RESULT_AT_SHUTTER` record with `focalLengthMm = 2.2`, `exifFocalMm` within 15 percent of it, and a matching profile, *When* `ImageImportService` imports, *Then* the profile is snapshotted into `LensCalibration.profileId`; *Given* `source = SELECTED_LENS` or an `exifFocalMm` disagreement above 15 percent, *Then* no profile is auto-selected; the record's `source` is in the persisted `capture lens ... source=<..>` log line (Observability Plan) so the owner can read which path produced it on the Logs screen; no new UI.
**Files**: `CM/platform/sensor/PlatformImageFile.kt`, `CM/model/ImageAnnotation.kt` (`ImageSensorData`), `CM/db/sidecar/SidecarLensRecord.kt` (new), `CM/db/sidecar/ImageSidecarSchema.kt` (one reference line), `CM/db/sidecar/ImageSidecarManager.kt` (mapping), `CM/db/ImageImportService.kt`, `AM/ui/components/CameraViewfinderDialog.android.kt`, `AM/platform/sensor/ExifOrientationFixer.kt`

##### Task 4.5a1: Lens-record types and the nested sidecar record (S) (Sidecar lane, after 3.2c2)
- (Triad repair 1: the former Task 4.5a listed seven files and is split.) `CaptureLensRecord` nullable fields on `PlatformImageFile` and `ImageSensorData`; the nested `SidecarLensRecord` data class in a new file. No DB column (ADR-004).
- Files: `CM/platform/sensor/PlatformImageFile.kt`, `CM/model/ImageAnnotation.kt`, `CM/db/sidecar/SidecarLensRecord.kt` (new)

##### Task 4.5a2: Sidecar mapping, persistence and import merge (S) (Sidecar lane, after 4.5a1)
- A one-line reference to `SidecarLensRecord` in `ImageSidecarSchema.kt` and the model-to-sidecar mapping in `ImageSidecarManager.kt` (the loader at ~L175 lives there; Sidecar lane), calling `SidecarVersion.forContent`; merge in `ImageImportService.kt:139`; golden old-sidecar test. Whichever of E3/E4 lands second rebases the additive keys.
- Files: `CM/db/sidecar/ImageSidecarSchema.kt`, `CM/db/sidecar/ImageSidecarManager.kt`, `CM/db/ImageImportService.kt`, `BT/db/sidecar/CaptureLensSidecarTest.kt` (new). REGISTER: `AllBusinessTests.kt`.

##### Task 4.5b: Capture-record mechanism (M)
- Register `Camera2Interop.Extender.setSessionCaptureCallback` on the use-case builders; keep the latest result fields in an atomic holder; at shutter press snapshot it (nearest timestamp), choose `PREVIEW_RESULT_AT_SHUTTER` or fall back to `SELECTED_LENS` per Task 4.1b; read EXIF `FocalLength` before the fixer as `exifFocalMm`; pass the record through `takePhotoAndProcess` (L208-260, `OnImageSavedCallback`). Fake-backend tests for both sources.
- Files: `AM/ui/components/CameraViewfinderDialog.android.kt`, `AM/platform/sensor/CameraSession.kt`, `AU/platform/sensor/CaptureLensRecordTest.kt` (new). REGISTER: `AllAndroidUnitTests.kt`.

##### Task 4.5c: `ExifOrientationFixer` copies tags back after re-encode (S)
- After the re-encode and the `Orientation` reset, copy `FocalLength`, `FocalLengthIn35mmFilm`, `Make`, `Model`, `DateTime`, `LensModel` from the source via `ExifInterface`; unchanged behaviour on the no-rotation path; note the pre-existing 16 MP downscale interaction with the native-pixels calibration basis. Fixture test in `androidUnitTest` (Robolectric).
- Files: `AM/platform/sensor/ExifOrientationFixer.kt`, `AU/platform/sensor/ExifOrientationFixerLensTest.kt` (new). REGISTER: `AllAndroidUnitTests.kt`.

#### Story 4.6: Migrate the other binders onto `CameraSession`
**As a** maintainer, **I want** one binding owner, **so that** `DEFAULT_BACK_CAMERA` is not hard-coded four times and lens changes cannot throw.
**Acceptance Criteria**:
- No literal remains.
  - *Given* a source audit, *When* grepping `AM/` for `DEFAULT_BACK_CAMERA` outside `CameraXBackend`, *Then* zero hits (the four sites: dialog L108, `AndroidCameraProvider` L116, `AndroidCameraPreviewBinder` L54, `AndroidCameraFrameSource` L175).
- Preview binder and frame source contracts preserved.
  - *Given* `AndroidCameraPreviewBinder` bound with a Preview and `AndroidCameraFrameSource` with an `ImageAnalysis`, and the dialog binding Preview+ImageCapture, *When* the lens is switched, *Then* both rebind to the same lens through the session (analysis follows the selected lens) and neither is `unbindAll()`-ed.
- `AndroidCameraProvider.capturePhoto` unchanged externally.
  - *Given* existing `AndroidCameraProvider` tests, *When* run, *Then* they pass; its remaining `unbindAll()` calls are scoped to its own session and covered by the Story 4.3 assertion.
**Files**: `AM/platform/sensor/AndroidCameraProvider.kt`, `AM/platform/sensor/AndroidCameraPreviewBinder.kt`, `AM/platform/sensor/AndroidCameraFrameSource.kt`, `AM/platform/sensor/CameraSession.kt`, `AU/platform/sensor/CameraBinderSessionTest.kt` (new)

##### Task 4.6a: Provider, preview binder and frame source as session clients (M)
- Replace the four selector sites; keep the no-`unbindAll` composition rule (`AndroidCameraPreviewBinder.kt:18-24,52-59`, `AndroidCameraFrameSource.kt:170`) and the wedged-future guard (`AndroidCameraProvider.kt:80`). Audit test that greps `DEFAULT_BACK_CAMERA`. REGISTER: `AllAndroidUnitTests.kt`.
- Files: `AM/platform/sensor/AndroidCameraProvider.kt`, `AM/platform/sensor/AndroidCameraPreviewBinder.kt`, `AM/platform/sensor/AndroidCameraFrameSource.kt`, `AU/platform/sensor/CameraBinderSessionTest.kt`

#### Story 4.7: Wiring gates and owner device acceptance
**As the** owner, **I want** real-device proof, **so that** I trust it on my phone.
**Acceptance Criteria**:
- Wiring complete (CI-green).
  - *Given* `CameraSession`/`CameraXLensCatalog` seam implementations, *When* `WiringCompletenessTest` runs, *Then* each is constructed in a composition root or allow-listed with a dated reason; `wiring-catalog.txt` has a line `CameraLensCatalog  CameraLensCatalogEntryPointTest  effect:camera-lens-list`.
- Owner checklist passes (Success Metric 4; owner-verified).
  - *Given* a debug/release build on the owner's phone, *When* Tyler opens the camera from the annotator: lists every rear lens the device exposes (every separately bindable back camera plus every optical zoom stop, compared against probe output), switches among them in about 1 s, takes a photo per lens, and the sidecar of each shows the matching `focalLengthMm`/camera id and `source`, *Then* the checklist is ticked in the PR; a lens that does not switch is logged with `camera bind ... result=fail` and shown disabled with reason.
**Files**: `kmp/src/businessTest/resources/wiring-catalog.txt`, `kmp/src/businessTest/resources/wiring-allowlist.txt`, `AU/wiring/CameraLensCatalogEntryPointTest.kt` (new), PR checklist

##### Task 4.7a: Wiring entry-point test and catalog line (S)
- Entry-point test with `WiredAppFixture` asserting `ObservableEffect.assert("camera-lens-list")` returns the fake backend's lenses; add the catalog line; delete any allowlist line this wires. REGISTER: `wiring-catalog.txt`, `wiring-allowlist.txt`, `AllAndroidUnitTests.kt`.
- Files: `AU/wiring/CameraLensCatalogEntryPointTest.kt`

##### Task 4.7b: Owner device acceptance checklist (XS)
- PR checklist: probe vs chip list match; switch time; 1 photo per lens; sidecar lens record and EXIF tags; rotation mid-bind; permission revoked while open; fold/unfold; UI never black. Wall-clock blocker: owner Tyler.
- Files: PR description only

---

## Test plan summary (runnable without a device)

| Epic | Runs on a no-device machine | Needs device or owner |
|------|-----------------------------|----------------------|
| E1 | Overlap/coalesce/rerun tests with `FakeGitRepository` (businessTest), including coalesce during `Merging`/offline/vault-locked; model-based rerun-bit interleaving; `GitGateAuditTest`; `GateAssertingGitRepository`; `LockRecoveryDecisionTest` + `LockRecoveryClaimTest` + `LockClaimIntegrationTest` (real ordering with two `InstanceId`s: dead pid cleared, boot-id mismatch cleared, live owner kept and its record not overwritten, unknown kept, no record kept, pid reuse cleared, exclusive-create race, **two- and three-claimer recovery over a pre-existing dead record with 200 seeded interleavings and a surviving simulated live `index.lock`**, late claimer, unsupported-lock fail-closed, killed lock holder in a second JVM); `RepoOpGateReleaseStructureTest` (non-suspending release, throwing end-record still frees the gate); `QuiesceRealFlushTest` (real flush actor, non-empty queue); `GitSyncMergeUnitContainmentTest` (closed DB mid-unit) and the cancel-during-fetch test; `NoGateFallbackAuditTest`; `GateKeyResolutionTest`; `SyncMain` claim test; forced-cancel against real JGit on a temp repo (jvmTest); cancel-right-after-`merge()` test with `GitSyncConflictReloadE2ETest` and `DiskAppliedStateTest` with assertions unchanged (one constructor argument added); quiesce-vs-sync-holding-gate test; `SyncInProgressConsumersTest` (no backoff on a lost race); worker/cold vs foreground via `GitSyncWorkerGateAndRecordTest` and `ColdSyncRunnerTest`; Robolectric status/badge/warning-token tests | `/proc` ENOENT vs EACCES and `boot_id` readability on Android; force-stop and reboot mid-sync; JVM `startInstant` on the owner's desktop |
| E2 | `ToolPolicyTest` (property); `PlacementClassifierTest` (property); ViewModel auto-return/lock/undo-keeps-lock tests; `AnnotationViewTransformTest` property tests incl. default-is-Fit; hit-test-at-0.25 property test; Robolectric gesture (drag-measure, aim-place, tap-tap, second finger, off-canvas), toolbar-semantics and layout-bounds tests (androidUnitTest per CLAUDE.md); jvmTest only for pixels and desktop keyboard, behind `scripts/jvm-display-check.sh` | 12 MP jank baseline on the owner phone (Task 2.7a can use the existing harness; real phone numbers UNVERIFIED); the A-occlusion feel of drag-measure and the system-gesture inset value on the owner's phone |
| E3 | Golden-scene property tests incl. out-of-model families (heights above plane, two planes, tap bias); identifiability-gate, rank and gradient-SNR tests (focal-free GRADIENT tier on a tilted golden scene); old-reader decode of a SCALE fit as usable (`MANUAL_REFERENCE`); `setGeometry` keeps constraints and fit; typed-failure tests; `ConstraintFitApplierTest` precedence table; sidecar and DB-column forward/backward compat with golden fixtures and a frozen old-reader copy; fresh+upgraded migration test; override-persistence test; value-edit parse property test; presenter tests | Owner photos and tape-measured ground truth for Story 3.8 (the only evidence for SM3) |
| E4 | `LensClassifierTest` from recorded `CameraCharacteristics` fixtures; `CameraSessionTest` and `CaptureLensRecordTest` with a fake backend (both record sources); chip/permission/error Robolectric tests; sidecar and EXIF-fixer tag-survival tests; `WiringCompletenessTest` | Real lens enumeration, per-physical-id bind, capture-result delivery, hidden-lens behavior: owner device run |

**Phase 4 test additions** (same ids as `validation.md`; classes created by the named task; the validation file's rows were renamed to match where a name changed):

| Validation id | Test class | Task | Closes |
|---|---|---|---|
| U-E1-06 | `TornRecordIsUnknownTest` | 1.2a2 | P1-2, adv C4 |
| U-E1-07 | `ClockStepStartTimeTest` | 1.2a1 (policy), real `/proc` in 1.2f | P1-2, adv C3 |
| U-E1-43 | `PidNamespaceAbsentPidTest` | 1.2a2 | P1-2 |
| U-E1-10 | `GateKeyAliasTest` | 1.3a | P1-1, adv C1 |
| U-E1-13 | `ResolveGateKeyFailureTest` | 1.4a1 | P1-1, arch R3 |
| U-E1-44 | `ActiveRunNotClearedOwnTest` | 1.2c1 | P1-1 |
| U-E1-39 | `ForeignLockNewerThanDeadRecordTest` | 1.2c2 | P1-3, adv C2 |
| U-E1-40 | `ForceRemoveRacesLiveRunTest` | 1.2c2 | P1-3, adv C4 |
| U-E1-45 | `OwnerHeartbeatTest` | 1.2c2 | P1-3 (heartbeat bound) |
| U-E1-48 | `RecoveryLockInProcessMutexTest` | 1.2c1 | adv C6 (in-process mutex, `OverlappingFileLockException`) |
| I-E1-06 | `TwoJvmRecoveryRaceTest` | 1.2f | P2-6, adv C6 |
| I-E1-07 | `CrossProcessSyncOverDeadRecordTest` | 1.8b | adv C6 (service level) |
| I-E1-08/09 | `ProcessKillOrphanLockTest` | 1.2g | adv C6 (SIGKILL) |
| I-E1-04 | `JgitCancelSweepTest` | 1.1b (claim half in 1.2g) | cancel sweep |
| U-E1-24 | `GitSyncCancelBoundaryTest` (no push after unit conflict) + `GitSyncServiceConflictResolutionTest` | 1.5a | P1-4, adv C5 |
| U-E1-46 | `NonCancellableReturnAuditTest` | 1.5a | P1-4 |
| U-E1-37, U-E2-16 | `NewCodeAuditTest`, `ScopeEscapeAuditTest`, `ExitPathAuditTest` | 1.8a | validation, UX-47 |
| U-E2-09 | `AnnotationViewTransformTest` slow-pinch | 2.4a | P2-9, adv C10 |
| I-E2-03 | `AnnotationGestureLayerTest` inset injected and zero | 2.3c | P3-12 |
| U-E3-33 | `GoldenSidecarMeasurementIdentityTest` | 3.2c2 | P2-9 |
| U-E3-02 | `OutOfModelRobustnessTest` (7 families) | 3.3b3 (generators 3.1b) | P1-5 |
| U-E3-07 | `GradientNoiseFalsePositiveRateTest` (at most 1 percent) | 3.3b2 | P1-5, adv C8 |
| U-E3-08 / U-E3-09 | `GradientSignificanceTest` (tilt bound) | 3.3b2; `CroppedFrameRefusalTest` 3.3b3 | P1-5, adv C8/C9 |
| U-E3-31 / U-E3-32 | `GradientLooVetoTest` (3.3b2), `GradientDefaultOffTest` (3.3b3) | 3.3b2, 3.3b3 | P1-5 |
| I-E3-12 / I-E3-13 | `RealPhotoHoldOutStudyTest`, `AccuracyStudyVerdictTest` | 3.8a | SM3 honesty |
| U-E4-04 | `LensClassifierFixtureMatrixTest` | 4.2b | validation |
| U-E4-14 | `CapabilityProbeFormatTest` | 4.1a | validation |
| I-E1-16 | `PausedRetryNowAndDurabilityTest` | 1.7c | triad UX-51, UX-52 |
| I-E2-10 | `PlacementCountersLogTest` (the three `placement ...` log lines) | 2.3c | triad gap 2 |
| X-51..X-62 | see validation.md "UX Acceptance Tests" (added rows) | 1.7b/1.7c (X-51, X-52), 2.5b/2.6b (X-53, X-54, X-55, X-62), 4.4a/4.4c (X-56, X-57, X-59, X-60), 3.6b (X-58), 3.5a/3.6b (X-61) | triad UX gaps 6, 7 |
| X-63..X-70 | see validation.md "UX Acceptance Tests" (rows added in triad repair 2) | 2.3a/2.3e (X-63, X-64, X-65), 3.5a/3.5c (X-67), 3.6d/3.6e (X-66), 1.7b, 2.5b, 3.5c, 3.6b/3.6e, 4.4a/4.4c (X-68a..e), 1.7b/1.7c (X-69), 2.5c (X-70) | triad UX gaps 1 to 8, 11 |
| U-E1-47 | `GitRepositoryDefaultsFailClosedTest` | 1.2d1 | triad engineering gap 6 |

Run commands (all wrapped: `timeout 30m`, and Bazel through `scripts/bazel-lowprio.sh`): `timeout 30m scripts/bazel-lowprio.sh test //kmp:business_tests`; `timeout 30m ./gradlew testDebugUnitTest`; `scripts/jvm-display-check.sh -- timeout 30m scripts/bazel-lowprio.sh test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`. Every new test class is registered (by the wave's registration integrator) in `AllBusinessTests`/`AllJvmTests`/`AllAndroidUnitTests` and commonTest BUILD entries per CLAUDE.md; jvmTest files importing `androidx.compose.ui.test` carry `// jvmTest-reason:`; no `Thread.sleep`, `GlobalScope`, or real-clock `delay`.

## Effort estimate

| Story | Tasks | Classes | Est. raw tokens (tasks) | Output share | Est. CU incl. 1.75x verification | Wall-clock blocker |
|---|---|---|---|---|---|---|
| 1.1 Reproduce on today's code (tests first, plus open questi... | 4 | S/S/XS/S | 185k | 12% | 0.28M | - |
| 1.2 One `LockRecovery` claim protocol replaces three age/mti... | 10 | S/M/M/M/M/M/M/S/S/S | 1000k | 12% | 1.49M | - |
| 1.3 `RepoOpGate`, composition-root wiring and gate-first qui... | 8 | S/M/S/M/S/S/M/S | 665k | 12% | 0.99M | - |
| 1.4 Single flight at the service (Isolate via seam) | 9 | S/S/S/M/S/S/S/S/S | 570k | 12% | 0.85M | - |
| 1.5 Cooperative cancel; gate held until the blocking call re... | 2 | L/S | 295k | 12% | 0.44M | - |
| 1.6 Every trigger goes through the gate; runners never acqui... | 2 | S/S | 110k | 12% | 0.16M | - |
| 1.7 UI states and copy (own run is never an error; amber is ... | 3 | S/M/S | 240k | 12% | 0.36M | - |
| 1.8 Bypass-proof guard, observability, owner checklist | 3 | M/S/XS | 205k | 12% | 0.30M | owner force-stop check (device) |
| 2.1 Audit before changing behavior | 1 | S | 55k | 12% | 0.08M | - |
| 2.2 `ToolPolicy`, the ViewModel hook and Settings rows (ADR-... | 5 | S/M/S/S/S | 350k | 12% | 0.52M | - |
| 2.3 Drag-measure, aim-place, tap-tap, disarm and nudge (ADR-... | 5 | S/M/M/S/S | 425k | 12% | 0.63M | - |
| 2.4 Zoom-out floor, pasteboard, edge reach (ADR-006) | 3 | M/S/S | 240k | 12% | 0.36M | - |
| 2.5 Controls and status out of the image area; contrast; no ... | 3 | S/S/S | 165k | 12% | 0.25M | - |
| 2.6 Toolbar accessibility, Keep tool and desktop keyboard | 3 | M/S/S | 240k | 12% | 0.36M | - |
| 2.7 Performance and regression gate | 1 | S | 55k | 12% | 0.08M | device jank numbers (owner-verified) |
| 3.1 Accuracy harness and golden scenes (spike; first) | 2 | S/M | 185k | 12% | 0.28M | - |
| 3.2 Constraint model, override value type, persistence, migr... | 4 | M/M/M/S | 445k | 12% | 0.66M | - |
| 3.3 `ConstraintCalibrator` and `ConstraintFitApplier` (pure,... | 6 | M/M/M/S/S/S | 555k | 12% | 0.83M | - |
| 3.4 Value override model and ViewModel path (Isolate via sea... | 2 | S/M | 185k | 12% | 0.28M | - |
| 3.5 Inline value editor with the two-meaning choice and its ... | 3 | M/S/S | 240k | 12% | 0.36M | - |
| 3.6 The calibration pill, known-distances sheet and the stat... | 5 | S/M/S/S/S | 350k | 12% | 0.52M | - |
| 3.7 Re-fit integration, history and undo | 3 | S/M/S | 240k | 12% | 0.36M | - |
| 3.8 Accuracy study on real photos; set N and thresholds; dec... | 1 | M | 130k | 12% | 0.19M | owner photos + ground truth |
| 3.9 Optional focal refinement and optional lens-id profile m... | 3 | S/S/S | 165k | 12% | 0.25M | - |
| 4.1 Capability probe and fixture recording (spike; first; ow... | 2 | M/XS | 150k | 12% | 0.22M | owner device probe run |
| 4.2 Pure lens classification and labelling | 2 | S/S | 110k | 12% | 0.16M | - |
| 4.3 `CameraSession` and `CameraLensCatalog` (Isolate via sea... | 2 | M/M | 260k | 12% | 0.39M | - |
| 4.4 Capture dialog UI: lens chips, flash, zoom, error and pe... | 3 | M/S/S | 240k | 12% | 0.36M | - |
| 4.5 Record the actual lens at capture; persist; keep the EXI... | 4 | S/S/M/S | 295k | 12% | 0.44M | - |
| 4.6 Migrate the other binders onto `CameraSession` | 1 | M | 130k | 12% | 0.19M | - |
| 4.7 Wiring gates and owner device acceptance | 2 | S/XS | 75k | 12% | 0.11M | owner device acceptance |
| Registration integrators `INT-W1`..`INT-W9` (one XS step per wave, owned; triad repair 2) | 9 | XS x9 | 180k | 12% | 0.27M | - |

Raw task tokens: 8555k over 107 tasks in 31 stories, plus 180k for the 9 registration integrator steps (triad repair 2; XS 20k each, not tasks) = 8,735k raw (XS 20k, S 55k, M 130k, **L 240k** per task; classes recounted by script from the task headings after triad repair 1: XS 4, S 67, M 35, L 1; was 7,380k over 89 tasks with no L). Conversion: CU = raw x 0.85 (assumed mix: 12% output at weight 5, 18% fresh input at 1, 70% cache read at 0.1), then x1.75 verification multiplier (spec + quality review per story, 6-verify, CI reruns) = **13.0M CU** (12.99M including the integrators; 12.73M for the 107 tasks alone; was 10.98M at 89 tasks). Size band: **Large (3-15M CU)**, consistent with the requirements' INFERRED sizing; informational, not a gate (full scope is pursued). Triad repair 1 added 18 tasks and 1,175k raw by splitting tasks that violated the 3-5 file rule or carried L-risk (1.2a, 1.2c, 1.2d, 1.3b, 1.3d, 1.4a, 1.4d, 1.4e, 2.2b, 3.2c, 3.3b, 4.5a) and by re-classing Task 1.5a from M to **L** (240k, deliberately not split, reason in the task); splitting adds per-task context-read overhead, which is why the raw total rose by more than the new code. The L class exists in the table for that one task and for nothing else: ESTIMATION.md requires an L task to be split or justified, and 1.5a is justified. Earlier history: the first review repairs added about 1.6M raw tokens over the earlier 5,090k (ADR-007 and the gesture classifier, the claim protocol and quiesce task, the constraint-fit applier and precedence table, the identifiability gates, the CalibrationHistory extraction, the entry-point and consumer audits, and the F1-F15 stories); iteration 2 added about 0.45M raw (the recovery lock and its race tests, Task 1.3d codemod and root threading, quiesce flush and hold fixes, merge-unit containment, the focal-free gradient tier and `FocalSource` re-route); the Phase 4 patch added 225k raw by re-classing three tasks S to M (1.2a, 1.8a, 3.1b).

Per epic (phase numbering: Phase 1=E1, 2=E2, 3=E3, 4=E4):

| Epic | Stories | Tasks | Raw tokens | CU incl. verification |
|---|---|---|---|---|
| E1 | 8 | 41 | 3270k | 4.86M |
| E2 | 7 | 21 | 1530k | 2.28M |
| E3 | 9 | 29 | 2495k | 3.71M |
| E4 | 7 | 16 | 1260k | 1.87M |
| Integrators (cross-epic) | - | 9 steps | 180k | 0.27M |

**Overrun checkpoint**: if cumulative spend on completed stories exceeds the table value for those stories by more than 25% (for example, E1 above 6.08M CU), pause and report the delta and its cause (wrong estimate vs real surprise); informational, not an invitation to cut.

**Critical path in agent waves**: 9 waves (see Dependency Visualization); E1 is the deepest chain (gate, claim, service lane, cancel, UI, guard), then E2's Screen lane (8). With parallel fan-out the four epics run concurrently, so agent-time is bounded by E1's chain and the lanes, and calendar time by the wall-clock blockers below.

**Wall-clock blockers (the calendar is set by these, not by the code):**
1. Owner device run of the camera capability probe (Task 4.1b); owner Tyler; blocks 4.2 threshold tightening, the 4.5b record source and 4.7 acceptance.
2. Owner device acceptance for E4 (Task 4.7b) and the E1 force-stop checklist (Task 1.8c); owner Tyler. E1 may merge CI-green before 1.8c.
3. Owner photos and tape-measured ground truth to judge E3 accuracy (Story 3.8); owner Tyler. SM3 stays open until then.
4. CI wall time about 40 min per push (observed on PR #676), four epics means at least four CI-gated PR cycles plus repair pushes.
5. Release cadence: release-please PR plus about 30 min publish if the owner wants a build on the phone; owner decides when to merge or release (PRs stay draft until then).
6. Human review latency on each draft PR (E1 is data-safety and should be reviewed first).

**Basis**: bands and weights INFERRED; cost class bands, output share and cache mix are assumptions (see ESTIMATION.md), recalibrate after the first implementer run (suggested: after Story 1.2).

## Self-review notes

- Every Success Metric maps to a story: SM1 -> 1.1, 1.2, 1.4, 1.5, 1.6, 1.8; SM2 -> 2.2, 2.3, 2.4; SM3 -> 3.4, 3.3, 3.8 (not met by synthetic tests alone, see ADR-003 Consequences); SM4 -> 4.2, 4.4, 4.5, 4.7.
- Directive checks: gate keyed by `RepoOpGate.keyFor` over `GitWriteLockNaming`, acquired at pipeline entry points only, `tryAcquire` (ADR-001, 1.3, 1.4); one lock order gate then `GitWorktreeLocks` (ADR-001 decision 7, Task 1.3c); dispositions per Tech Debt table; no OpenCV/EJML (ADR-003); no human-time units anywhere.
- Known soft spot (iteration 2): the GRADIENT tier's value over SCALE is an INFERRED SNR (about 23 at 5 degrees, ADR-003 decision 5) until Story 3.8 measures it on the owner's photos; the focal refinement is off by default and changes results by at most 0.4 percent at 5 degrees (Story 3.9).
- Cross-references checked by script at the end of the repair pass: every task ID in the wave list and lane list exists as a heading; every ADR path in the header exists; the Repair Log covers every finding ID.

## UX criterion to task trace (consistency NITPICK 4)

`design/ux.md` section 10 has 70 criteria (50 original, UX-51 to UX-62 added in triad repair 1, UX-63 to UX-70 in triad repair 2); each maps to the tasks that build and test it (ux.md ids; validation.md has the test rows `X-NN`).

| UX ids | Task(s) |
|---|---|
| UX-01, UX-03, UX-04, UX-05, UX-06, UX-07, UX-08, UX-09 | 1.7a (model), 1.7b (Compose, copy, warning token), 1.7c (paused retry); copy at 1.4e1 |
| UX-02 | 1.4a1, 1.6a (`AlreadySyncing` event) |
| UX-10, UX-14 | 2.2b1, 2.3b, 2.6a |
| UX-11, UX-12, UX-13 | 2.3a-c (classifier, layer, tests), 2.3e (strip, chip disarm) |
| UX-15, UX-16, UX-17 | 2.5b, 2.6a, 2.6b |
| UX-18, UX-19, UX-24 | 2.4a, 2.4b |
| UX-20, UX-21, UX-22 | 2.5a, 2.5b, 2.4c (pasteboard frame) |
| UX-23 | 2.3d (handle-tap nudge) |
| UX-25, UX-26, UX-27, UX-29, UX-30 | 3.5a, 3.5c, 3.4b |
| UX-28 | 3.4a |
| UX-31, UX-32, UX-33, UX-34, UX-37, UX-38 | 3.6a, 3.6b |
| UX-35, UX-36 | 3.6c, 3.6d, 3.7a |
| UX-39, UX-40, UX-43 | 4.2a, 4.4a |
| UX-41, UX-42 | 4.3a, 4.4c |
| UX-44, UX-45, UX-46 | 4.4c |
| UX-47 | every story's error table; checked by the validation walk (validation.md section "UX Acceptance Tests") |
| UX-48 | 1.7b, 2.6a, 3.5c, 4.4c plus `NewCodeAuditTest` (1.8a) |
| UX-49 | 1.7b (warning token), 2.4c, 2.5b (computed contrast tests) |
| UX-50 | 2.4c (zoom-to-Fit), 2.6a (auto-return pulse), 4.4c (lens crossfade) |
| UX-51, UX-52 | 1.7a (`retryNow` action on the model), 1.7b (button, cause-neutral copy), 1.7c (`retryNow` without consuming the budget, durability, overdue retry) |
| UX-53 | 2.5b (Pan pad in its own pan row, Pan toggle, one visibility rule), 2.6a (custom actions), 2.6b (tests) |
| UX-54, UX-55 | 2.3d (crosshair session entry), 2.6a (custom actions), 2.6b (tests) |
| UX-56, UX-57 | 4.4a (initial-bind state, disabled flash), 4.4b (tests) |
| UX-58 | 3.6a (presenter), 3.6b (empty state), 3.6e (tests) |
| UX-59, UX-60 | 4.4c (hysteresis, persisted hint dismissal) |
| UX-61 | 3.5a (value-edit snackbar), 3.6b (delete-row snackbar) |
| UX-62 | 2.5b (second-row flow layout), checked by `AnnotatorLayoutTest` |
| UX-04 (live region on Paused, amended) | 1.7a (one announcement per entry), 1.7b (announcement), X-04 |
| UX-63, UX-65 | 2.3a (`StripPriority`, tested in `EditorChromePolicyTest`), 2.3e (strip render, Cancel, `StatusStripTest`), 2.5b (pan row) |
| UX-64 | 2.3a (`EditorBackPolicy`), 2.3e (`PlatformBackHandler` body, `StatusStripTest`) |
| UX-66 | 3.5a (Distance-only action), 3.6d (Area/Angle strip message), 3.6e (`ValueEditorScopeTest`) |
| UX-67 | 3.5a (entry-unit chip), 3.5c (test) |
| UX-68 | 2.5b (first and second toolbar rows), 1.7b (`SyncStatusTextScaleTest`), 3.5c (`ValueEditorTextScaleTest`), 3.6b/3.6e (rows), 4.4a/4.4c (chips and top bar) |
| UX-69 | 1.7a, 1.7b, 1.7c |
| UX-70 | 2.5c (counter, string, `VoiceHintTest`), 2.3e (renders level 5) |

## Repair Log

Pass: SDD Phase 3 repair, 2026-11-14. Inputs: `architecture-review.md` (A-B1..B3, A-C1..C11, five nitpicks A-N1..N5), `adversarial-review.md` (V-B1..V-B5, the E1/E2/E3/E4/estimation concern bullets, nine minors V-M1..M9) and `design/ux.md` findings F1..F15. Contested or design-changing claims were re-verified against code before the fix was written; evidence is cited per row. Docs changed: this plan, ADR-001, ADR-002, ADR-003, ADR-004, ADR-005, ADR-006, new ADR-007, `design/ux.md`. No source code was touched.

### Architecture blockers

| Id | Change (or reason rejected) | Evidence |
|----|-----------------------------|----------|
| A-B1 | `SyncTrigger` kept untouched; new `SyncOrigin` for logging, `SyncRunInfo`, rerun; `toTrigger()` maps the five sync-calling origins; glossary, ADR-001 decision 1, Tasks 1.4a, mapping test. Adversary proposed the name `SyncTriggerSource`; `SyncOrigin` chosen per the architecture review's remediation. | `FirstSyncConfirmation.kt:42` enum; `GitSyncService.kt:286,297` uses |
| A-B2 | Quiesce takes the gate first: ADR-001 decision 7, Task 1.3c, Story 1.3 AC, `GateBeforeWorktreeOrderTest`. ADR-001's old "gate then worktree, no inversion" claim re-verified and corrected: it was true inside sync, false for quiesce. Flush paths take only the worktree lock, so no new inversion. | `GraphMoveQuiesceStrategy.android.kt:93-101`; `GitShadowWorktree.kt:198`; `GitShadowFlushActor.kt:48`; `PlatformFileSystem.kt:1005` |
| A-B3 | `resolveGateKey` (read-only) first, gate acquired before any `_syncState` write, `rateLimitRetry.cancel()` or counter begin; unreadable config uses fallback key `graph:<id>`; ADR-001 decision 4; Story 1.1 and 1.4 ACs (coalesce during Merging/offline/vault-locked). | state writes at `GitSyncService.kt:304,311,319,331,336,523`; cancel at L296, L542 |

### Architecture concerns

| Id | Change | Evidence |
|----|--------|----------|
| A-C1 | `decide` and types in commonMain; file I/O behind `LockFiles` port implemented in `JC`; `JvmProcessLiveness` in `JM` (ProcessHandle is Java 9, Android minSdk 26), `AndroidProcessLiveness` in `AM`. Tasks 1.2a/1.2b. | architecture review; `GitOperationSupport.kt` lives in `jvmCommonMain` |
| A-C2 | Verdict channel: new defaulted `GitRepository` methods `beginOwnedOperation`, `endOwnedOperation`, `forceRemoveLock`; implementers and fakes listed (iOS, wasm, Stub, test fakes keep compiling via defaults). Task 1.2d. | `removeStaleLockFile` implementers by grep: GitRepository, Ios, Android, Jvm, Wasm, StubGitRepository, 3 tests |
| A-C3 | `SyncInProgress` consumer audit as its own Task 1.4e: `toUiMessage`, `toSyncErrorMessage`, `DomainErrorTest` L41/133/261, `ScheduledSyncRunner.runFull` explicit Skip(Busy) with no backoff, `liveTick`, coordinator, safety actions, `BranchRepairService`, `TaskOutcomeMapping`; stale copy at `DomainError.kt:390,450` replaced. | `ScheduledSyncRunner.kt:107-118` read |
| A-C4 | `RepoOpGate` is a required, non-defaulted constructor parameter; threaded through `StelekitAppDependencies`/`GraphContentGitSyncSetup`; identity test `repo-gate-shared`; `SteleKitApplication` path corrected to `AP`. | `GraphContentGitSyncSetup.kt:464` fallback; `StelekitAppDependencies.kt:215`; find: `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt` |
| A-C5 | "Divide hit radius by view scale" deleted; open question answered (screen px); property test kept. | `AnnotationHitTest.kt:19-30`; gesture layer L119-120 |
| A-C6 | One clamp rule; `FIT_SCALE` introduced before the floor moves; default and `isFit` use it; "25 percent visible" statement replaced; pinch property scoped to unclamped region; default-is-Fit regression test. ADR-006 rewritten. | `AnnotationViewTransform.kt:13,57,61,109-112,140-145` read |
| A-C7 | Fourth site `AndroidCameraFrameSource.kt:175` added to Story 4.6; analysis follows the selected lens (explicit decision); "three" corrected to four in ADR-004, tech debt, Story 4.6. | grep: 4 `DEFAULT_BACK_CAMERA` sites |
| A-C8 | `CalibrationHistory` named collaborator extracted first (Task 3.7c), 3.7a only delegates; tech-debt row states the VM must not grow. | architecture review |
| A-C9 | (a) `insertMeasurementAnnotation` stub signature change listed (Task 3.2b); (b) `.sq` and `AddColumn` both, fresh and upgraded tests; (c) VERIFIED `GeometryCalibration` is persisted in `calibration_geometry_json` too, so constraints/fit ride there and get compat tests; (d) sealed `MeasurementValue` (ADR-002 decision 6). | `GeometryCalibration.kt:39-52` |
| A-C10 | Change at git call sites only; no `jobs.schedule` import of git types; likely an assertion test, since no `runOnce(... Replace)` exists for git-sync; `WorkManagerTaskScheduler` removed from the file list. Task 1.6b. | grep per the review |
| A-C11 | Cold path uses `tryAcquire` and `Skipped(Busy)`; only quiesce waits. ADR-001 decision 8, Story 1.3 and 1.6 ACs. | `ColdSyncRunner.kt:51` waiting `withLock` |
| A-N1 | `RepoOpGate.keyFor(repoRoot)` wrapper. | accepted |
| A-N2 | Trailing-slash key property test (Story 1.3 AC). | accepted |
| A-N3 | Owner record and locks use `shadow.resolveForJGit(repoRoot)` on SAF graphs (Story 1.2 AC). | accepted |
| A-N4 | New files marked "(new)"; header note. | accepted |
| A-N5 | Glossary recounted by script: 40 terms. | script output |

### Adversarial blockers

| Id | Change | Evidence |
|----|--------|----------|
| V-B1 | Claim protocol redesigned: read existing record, decide, act, then write ours with exclusive create; `ClearedOwn` is only possible while holding the gate; second-instance integration test uses the real ordering; `SyncMain` participates through the same protocol (own `InstanceId`, exit code on `OwnerAlive`); the old "always delete" trap closed. ADR-001 decisions 12-13; Tasks 1.2a-e. | `SyncMain.kt:59` builds its own `JvmGitRepository`; ADR-001 old decision 6 wrote the record first |
| V-B2 | The #676 invariant is preserved: merge unit (merge, beginGitMerge, auto-resolve, `finally` reload) is one `NonCancellable` unit with no cancellation check between `merge()` returning and the end of the reload; "byte-identical" retracted and replaced by "edited only at four named seams"; new cancel-after-merge test; `GitSyncConflictReloadE2ETest` and `DiskAppliedStateTest` must pass unmodified. ADR-001 decision 9; Task 1.5a; Story 1.5 AC. | merge returns ~L394, `try`/`finally { reloadMergedFiles }` from ~L413; `reloadMergedFiles` L255-264 only protects `endGitMerge` |
| V-B3 | One ownership rule: only pipeline entry points acquire; runners never acquire; `SyncInProgress` is a non-failure everywhere (typed Skip(Busy), no backoff); cold uses `tryAcquire`. ADR-001 decisions 3, 6, 8; Stories 1.3, 1.4, 1.6 rewritten with the self-coalesce, no-backoff and both-direction cold ACs. | `GitSyncService.kt:221` (`fullSync` = `sync(g, Automatic)`), `ScheduledSyncRunner.kt:107-118`, `ColdSyncRunner.kt:51` |
| V-B4 | New ADR-007 (annotator gesture semantics): drag-measure only for DISTANCE with no pending point, release farther than 48 dp from the press; aim-place (existing) otherwise and for the second point at any distance; pure `classifyPlacement`; feedback ghost line past 48 dp; cancel/abandon rules; `AnnotationNonDragMode` changes enumerated (session reset on auto-return, handle-tap into the existing nudge pad, step set kept). Stories 2.3 and ACs, tasks 2.3a-e, ADR-005 decision 6, ux.md S2 and S4. | `AnnotationGestureLayer.kt:186-209`; `AnnotationNonDragMode.kt` read (`NudgeStep { ONE_PIXEL, TEN_PIXELS, ONE_PERCENT }`); `AnnotationEditorViewModel.kt:453` session reset |
| V-B5 | **SUPERSEDED in part by the iteration-2 GRADIENT design (C8) and the Phase 4 patch: the `TILT` tier named below is now the focal-free `GRADIENT` tier, which writes the plane, ships default-off and has the t-gate/validity/crop rules of ADR-003 decision 5; the precedence table itself still stands.** ADR-003 rewritten with an explicit storage/precedence table (SCALE clears the plane, otherwise `pixelsPerMeter` is ignored; PER_AXIS/TILT write a `PlaneKind.CONSTRAINT_FIT` plane; K writes `lens.k`; `keepingGeometry` clears constraints/fit/plane; paper plane clears constraints; height offset keeps everything; `priorBasis` restores on delete), `PlaneCalibration.kind` discriminator (not `referenceName`), non-zero extents and null check-residual so old builds and `MeasurementAdvisor` never divide by zero, advisor branches on kind, `ConstraintFitApplier` as the only writer, old-build behaviour stated precisely with a frozen-copy test. Tasks 3.2a, 3.3c, 3.3d, 3.7a. Not fully verified: how `HeightOffset` composes with a plane is UNVERIFIED by me; Task 3.2a reads it and pins it with a test before 3.3c depends on it. | `LensMeasurement.kt:42-68` (VERIFIED: ppm ignored when plane exists; homography maps undistorted native px to metres); `keepingGeometry`/`setGeometry` `AnnotationEditorViewModel.kt:~1204-1221`; `PlaneCalibration` `GeometryCalibration.kt:30-37`; `MeasurementAdvisor.kt:93-100` |

### Adversarial concerns (E1)

| Id | Change | Evidence |
|----|--------|----------|
| V-E1a gate acquisition point | Same as A-B3; plus coalesce-during-`Merging` AC. | `refuse()` writes state, `GitSyncService.kt:523` |
| V-E1b lost follow-up | Rerun bit set and checked under the gate's own lock; `releaseOrHandOff` launches the follow-up on the service's supervised scope; model-based interleaving test; ADR-001 decision 5. | adversarial finding accepted |
| V-E1c `liftMassChangeGuard` | Refuse the lifted press while a run is in flight and never queue it; a follow-up never carries the flag; Tasks 1.4a, 1.6a. | `GitChangeGuards.kt:148-150` per the review (not re-opened); consistent with the typed-count design |
| V-E1d gate across dialog | `abortActiveMerge` awaits `confirm` before acquiring and re-checks repo state; Task 1.4c AC. | `SyncSafetyActions.kt:61-76` per the review |
| V-E1e missing entry points | Task 1.4d: `GitSetupScreenSaveLogic.kt:98`, `BranchRepairService.kt:58` via `changeBackBranch`, clone (`GraphManager.kt:530`, clone workers, key from destination path), `SyncMain`; allow-list with reasons in 1.8a. | `GitSyncService.kt:138` `changeBackBranch` read |
| V-E1f `SyncTrigger` collision | See A-B1. | |
| V-E1g rate-limit retry dropped | `RateLimitRetry` job calls `gate.awaitFree` before retrying; `cancel()` after acquire; AC with `retryAfterSeconds = 0`. ADR-001 decision 10; Task 1.4b. | `SyncRunGuard.kt:34-43`; `RateLimitRetry.kt:19-22` read |
| V-E1h lease leak | Acquire, record write and release inside one `try/finally`; `tryAcquire ?: return` before the `try`; record-write failure is log-and-continue; AC throws from `beginOwnedOperation`. | accepted |
| V-E1i Android liveness half-verified | Kept as owner-verified (Task 1.8c); fail-closed consequence and JVM empty-`startInstant` Unknown named in Unresolved Questions and Story 1.2 ACs; E1 can merge CI-green before the device check. | accepted |
| V-E1j composition-root paths | `AM/SteleKitApplication.kt` corrected to `AP/SteleKitApplication.kt`; `StelekitAppDependencies` and `GraphContentGitSyncSetup` added to Task 1.3b. | find output |
| V-E1k stale copy | `DomainError.kt:390,450` copy replaced in Task 1.4e; "tap to retry" removed for Unknown/NoRecord. | `DomainError.kt` per the review |

### Adversarial concerns (E2)

| Id | Change | Evidence |
|----|--------|----------|
| V-E2a hit radius | Agreed with A-C5 (two reviewers concur; verified in code): change dropped. | `AnnotationHitTest.kt:19-30` |
| V-E2b clamped centres | Agreed with A-C6; property scoped; no pan below Fit (ADR-006 alternatives). | `AnnotationViewTransform.kt:140-145` |
| V-E2c stickiness undo | Decision: stickiness (and `pendingKnownDistance`) are not undoable; `restoredOver` copies them from the live state; `UndoHistory.kt` in the 2.2 file list; undo AC. ADR-005 decision 4. | `UndoHistory.kt:43-53` read |
| V-E2d test audit list | Audit task rewritten to run the suite with the default flipped and list real dependents; starting list from grep (CrashGuard:147, UndoHistoryTest:76, AnnotationGeometryTest, MeasurementInjectionTest, KeyboardTest:88-89, ToolbarShortcutTest, AnnotationEditorUnitTest:84-191, HeightOffsetTest); the old guess removed. | grep output of `selectTool(AnnotationTool.DISTANCE)` across businessTest |
| V-E2e settings UI | Task 2.2c adds the two Settings rows. | Requirements Risk Control |
| V-E2f serialization | Lanes defined (VM, Screen, Service, Sidecar, Repo) and a registration-integrator rule for all test registries; 3.4b and 3.7a placed in waves; E3 Screen edits batched into Task 3.6d. | wave table |
| V-E2g desktop keyboard | Task 2.6c adds a Keep tool shortcut and tests mouse drag-measure; Story 2.6 AC. | accepted |

### Adversarial concerns (E3)

| Id | Change | Evidence |
|----|--------|----------|
| V-E3a identifiability | Gates per tier (direction, position, radial, noise-floor); tilt parametrisation = tilt angle, azimuth, scale (roll is a gauge); Jacobian rank check and conditioning test. ADR-003 decision 5; Task 3.3b; Story 3.3 ACs. The rough noise estimate for `k` is the reviewer's INFERRED figure and is used only to motivate the gate. | ADR-003 |
| V-E3b circular test | Out-of-model scene families added to the harness (3.1) and as a blocking AC in 3.3; real-photo hold-out in 3.8 against single-scale AND the best existing calibration. | adversarial finding accepted |
| V-E3c focal trust | **SUPERSEDED by C8 (iteration 2) and the GRADIENT design: no focal gate exists, nothing in the base tiers needs a focal source, `EXIF_ONLY` does not make anything ineligible, and the whole of Story 3.9 is optional.** (Iteration-1 text, kept for history: `FocalSource` and a mandatory gate (Task 3.9a); EXIF-only focal makes TILT ineligible; Story 3.9 split into mandatory and optional parts.) | EXIF dropped by `ExifOrientationFixer.kt:86-102` per the review, confirmed by read |
| V-E3d SM3 | Story 3.8 requires an explicit outcome line ("met" or "not met for distortion"); SM3 cannot be closed with it blank; ADR-003 Consequences states SCALE-from-N is noise averaging, not distortion correction. | adversarial finding accepted |
| V-E3e legacy basis | UI behaviour defined: known-distance disabled with reason and link to Calibrate (Stories 3.5, 3.6; ux 7.1). | accepted |
| V-E3f mixed plane | `mixedSurfaces` flag and hint (3.3, 3.6; ux 7.4). | accepted |
| V-E3g old-build claim | "Remain correct" softened to "readable and correct until a re-save"; downgrade drops constraints, fit, kind, valueSource; frozen-copy test. Migration Plan and ADR-003 decision 8. | `GeometryCalibrationCodec` `ignoreUnknownKeys` read |

### Adversarial concerns (E4)

| Id | Change | Evidence |
|----|--------|----------|
| V-E4a physical binding | ADR-004 corrected (CameraX 1.4.1 has the APIs); path B available now but bind-tested per device; Story 4.1 probes `setPhysicalCameraId` per physical id; Pattern Decisions E4(c) reworded. | `javap` on `camera-core-1.4.1.aar` and `camera-camera2-1.4.1.aar` (gradle cache, run this pass): `CameraSelector$Builder.setPhysicalCameraId`, `Camera2Interop$Extender.setPhysicalCameraId`, `setSessionCaptureCallback`, `Camera2CameraInfo.getCameraCharacteristicsMap` |
| V-E4b capture result | Mechanism specified: session capture callback holder snapshotted at shutter (primary), selected lens + zoom (fallback), pre-fixer EXIF focal as cross-check; the AC is conditional on the probe; no test may assume a `CaptureResult` from `OnImageSavedCallback`. ADR-004 decision 5; Tasks 4.1a, 4.5b. Unverified until the owner run: whether results arrive within a frame of the shutter. | `CameraViewfinderDialog.android.kt:233` |
| V-E4c EXIF | Fixer copies the dropped tags back (Task 4.5c); AC states the known result and a surviving-tag test; 16 MP interaction noted. | `ExifOrientationFixer.kt:84-102` read |
| V-E4d four sites / `unbindAll` | Four sites; `CameraSession` itself never calls `unbindAll`; two other callers scoped and tested. | grep: `unbindAll` at provider L113/L161, dialog L105/L118 |

### Estimation and scope

| Id | Change | Evidence |
|----|--------|----------|
| V-S1 | Serialization complete via lanes (VM, Screen, Service, Sidecar, Repo) and the registration-integrator rule; "disjoint files" wave claim restated. | |
| V-S2 | Wave text recomputed (9-wave critical path, stated once); 1.7a moved earlier (needs only `inFlight` and the verdict); sidecar and registry cross-epic edges added to the diagram. | |
| V-S3 | "CI-green" vs "owner-verified" closure defined in Risk Control; E1 ships CI-green with 1.8c as a follow-up. | |
| V-S4 | Device-untestable parts labelled (fake cancel validates the fake, not JGit; Android shadow path covered by owner checklist). | |
| V-M1 | `runInterruptible`-less Cancel looks dead: Cancel shows "Stopping..." (Task 1.5a, 1.7b). | |
| V-M2 | Busy counter balanced, never begun on coalesce (Task 1.4a). | |
| V-M3 | Two-graphs-one-repo and relocate key-change test added to Task 1.1d. | |
| V-M4 | `GitSyncServiceRegistry` noted as probably an `object`; Task 1.1d. UNVERIFIED by me. | |
| V-M5 | `HomographyFitter` private `solve` made internal in Task 3.3a. | |
| V-M6 | Record carries host/boot id; mismatch is Unknown (ADR-001 decision 12). | |
| V-M7 | Depth toast: both the misleading copy and the layout slot (Task 2.5a). | |
| V-M8 | Test-run commands wrapped in `timeout` and `scripts/bazel-lowprio.sh` in the Test plan. | CLAUDE.md |
| V-M9 | Effort table recomputed by script from the task headings (see Effort estimate); per-epic sums changed because tasks were added. | script output |

### UX findings F1-F15

| Id | Resolution |
|----|-----------|
| F1 | Story 3.6 and Tasks 3.6a/3.6b: the pill (`CalibrationConfidenceBadge`/`CalibrationStatusBar`) is the actionable entry; the banner opens the same sheet; legacy methods keep their labels and "Not calibrated" only when calibration is null/NONE. |
| F2 | Exact-2 rung (agreement X percent) in Story 3.3 (N=2 report) and Story 3.6 ladder; presenter test. |
| F3 | Story 2.5 AC and Task 2.5b: white semibold label on the dark toolbar with filled indicator; contrast computed against `TOOLBAR_BACKGROUND_COLOR`; `onPrimaryContainer` rejected (fill does not extend behind the label in the owner screenshot). |
| F4 | ADR-005 decision 3 and Task 2.6a: separate 48 dp Keep tool toggle plus long-press/double-tap; nested lock icon rejected (overlaps the disarm tap). Nudge: NOT rebuilt; the existing `AnnotationNonDragMode` pad (1 px/10 px/1 percent, move mode) is reused and Task 2.3d ensures the handle-tap entry and auto-return reset; ux.md S4 and UX-23 edited to match (it had said 1 px / 5 px and an "inspector strip"). |
| F5 | Adopted: row in the toolbar second row, rail at >= 600 dp (Story 2.5 AC, Task 2.5b). |
| F6 | Decision: hide the global mic FAB while the annotation editor is active (Task 2.5c); host found by Task 2.1a (my grep of `ui/` found no FAB, so the host is UNVERIFIED). ux.md 3.1 edited. |
| F7 | ADR-001 decision 14, Task 1.7c, Story 1.7 AC: two automatic retries (about 30 s, 2 min) then red Failed; `pausedRetry` published. |
| F8 | `followUpQueued` in `SyncRunInfo`; "One more sync will run after this" in Story 1.7 AC and Tasks 1.7a/b (UX-03 kept). |
| F9 | `AlreadySyncing` one-shot UI event as a polite live-region announcement only (Story 1.6 AC, Task 1.6a); ux.md copy table edited. |
| F10 | Story 2.3 disarm-paths AC and ADR-007 decision 4; VM `discardFirstPoint`/`disarm` in Task 2.3b. |
| F11 | `pendingKnownDistance` flag in the VM (Task 3.6c; state added in 2.2b), Story 3.6 round-trip AC. |
| F12 | Task 4.4c and Story 4.4 ACs (retryable denial, revoked while open, all lenses failed, held-frame crossfade, dismissible hint, pinch-to-chip). |
| F13 | Task 3.5c and Story 3.5 error-state AC (save failure, implausible ratio, zero-length segment, edited marker). |
| F14 | Targets: sync Error/Paused row (1.7b), pill (3.6b), zoom buttons (2.5b), measurement labels (3.5a), handles: grab radius is already screen dp, property-tested with a 24 dp minimum (2.4b). |
| F15 | Verified no theme token (amber hard-coded `Color(0xFFF59E0B)` at `SyncStatusBadge.kt:165,203,255,348`, `SyncStatusSheet.kt:125`, grep this pass); Task 1.7b adds a light/dark `warning`/`onWarning` pair with a 4.5:1 test; ux.md design-system line edited. |

### Rejected or partially accepted

- A-B1 name: adversarial suggested `SyncTriggerSource`; `SyncOrigin` kept (same remediation, different name).
- V-E2 (hit radius) and A-C5 are one finding seen twice; accepted once.
- UX S4 "inspector strip" and "1 px / 5 px": rejected in favour of the shipped nudge pad (F4 row); ux.md updated.
- ux.md "tap-tap creates the same measurement as a drag": kept, but clarified that tap-tap means loupe-aimed press/release per point (ADR-007), because that is the existing gesture.
- Nothing was dropped without a replacement. UNVERIFIED items carried into tasks: `GitSyncServiceRegistry` kind (1.1d), mic FAB host (2.1a), Settings host (2.1a), HeightOffset-with-plane composition (3.2a), capture-callback delivery and per-physical-id bind (4.1), Android `/proc` ENOENT/EACCES (1.8c).

### Iteration 2

Pass: SDD Phase 3 repair, iteration 2, 2026-11-14. Inputs: the re-review `adversarial-review.md` (1 blocker V2-B1 = "B1", concerns C1-C12, six minors) and the re-review `architecture-review.md` (0 blockers, R1-R6, three nitpicks). Docs changed: this plan, ADR-001, ADR-003, ADR-006, ADR-007, `design/ux.md`. ADR-002, ADR-004 and ADR-005 were checked and needed no change (ADR-005 line 18 already says `DragMeasure`; the nit about `Drag(` text found nothing to edit). No source code was touched. Counts after this pass (recounted by script from the headings): 4 epics, 31 stories, **89 tasks** (85 + 1.2f, 1.3d, 1.5b, 3.9c); glossary 42 terms; effort 7,155k raw, 10.6M CU.

#### Blocker

| Id | Change | Evidence |
|----|--------|----------|
| V2-B1 | The dead-record delete was by path, so two JVM processes could both decide `ClearedDeadOwner` and the loser's delete removed the winner's live `index.lock` and fresh record. ADR-001 decision 12 now takes a **kernel-held `FileChannel.tryLock` on a permanent `.git/stelekit-sync.recover` file** for every deleting verdict, re-reads and re-decides the exact record under it, deletes locks first then the record, then `CREATE_NEW`. Chosen over (a) a `CREATE_NEW` claim token (needs its own dead-holder deletion, same race one level down), (b) rename-to-tombstone (restore is not atomic), (c) content/mtime-checked delete (TOCTOU), because a kernel lock needs no staleness rule: the kernel releases it on process death. Unsupported filesystem fails closed (`OwnerUnknown(RecoveryLockUnsupported)`). Proof sketch and the re-walk of same-process worker, pid reuse, force-stop leftover, late claimer, dead lock holder and non-participating git are in the ADR. New AC and Task 1.2f: two-claimer and three-claimer tests over a **pre-existing dead record** (barrier hooks, 200 seeded interleavings, a simulated live `index.lock` that must survive), late claimer, unsupported-lock, killed lock holder in a second JVM. Task 1.2c now unit-tests the claim over a fake port. | `adversarial-review.md` B1; `LockFiles` port; no `tryLock` use exists in `kmp/src` today (grep), so this is new code. UNVERIFIED until Task 1.2f runs: `tryLock` semantics on the owner's desktop filesystem and on Android app-private storage (both local ext4/f2fs in the expected case). |

#### Adversarial concerns

| Id | Change | Evidence |
|----|--------|----------|
| C1 | Verdict rule split: same host and both `bootId` non-null and different is `Dead`; a different hostname on a shared folder stays `Unknown`; a null boot id falls through to the pid/start-tick probe. The record gains `bootId`; the previous overloaded `host = boot_id` is removed. Android source: `/proc/sys/kernel/random/boot_id`, fallback `Settings.Global.BOOT_COUNT` (API 24+, minSdk 26), else null. Story 1.2 AC, Task 1.2a/b, Story 1.8c reboot step, Unresolved Question. | AOSP sepolicy made `proc_random` readable to every domain after an `untrusted_app` denial on `boot_id` (Bug 69294418, 2017): [gerrit change](https://gerrit.omnirom.org/plugins/gitiles/android_system_sepolicy/+/9d9c370f31556c33f62e7889f64ad3a2728f1863%5E%21), found by web search this pass (INFERRED to apply to the owner's device; Task 1.8c checks) |
| C2 | Release is unconditional: `finally { try { withContext(NonCancellable) { counter.end(); endOwnedOperation } } finally { releaseOrHandOff } }`; `releaseOrHandOff` is a non-`suspend` `fun`; `endOwnedOperation` deletes only a record whose `instanceId` and `startedAtMs` match. `RepoOpGateReleaseStructureTest` (reflection: not suspend; throwing end-record frees the gate) plus a source audit that every acquire is released in a `finally` ending in `releaseOrHandOff`; Story 1.4 AC. ADR-001 decision 4, Tasks 1.3a, 1.4a. | adversarial C2; ADR-001 old decision 4 structure |
| C3 | Defined policy, not a timeout: chunked reload (200 paths) with progress and `heldMs` log, closed-DB `Throwable` contained inside the unit with `reloadIncomplete` (merged files are on disk, next open reconciles; pinned by a real-`GraphLoader` test), `GraphManager.shutdown()` stays synchronous and does not wait, `GitSyncService.shutdown()` never cancels the unit, quiesce surfaces "a sync is still finishing". New Task 1.5b and Story 1.5 AC. | `GitSyncService.kt:255-264` (reload with `finally` only around `endGitMerge`); `GraphManager.kt:1614-1630` (`shutdown` closes the DB synchronously after `service.shutdown()`) read this pass. UNVERIFIED: that next-open reconcile detects the files by mtime; Task 1.5b's test decides it. |
| C4 | `ensureActive()` placed immediately before the unit (after `fetchResult`, before `_syncState = Merging` and `merge()`), outside `NonCancellable`; cannot reintroduce #676 because no merge has started. Cancel-during-fetch AC. ADR-001 decision 9, Task 1.5a. | `GitSyncService.kt:~391-396` (`if (fetchResult.hasRemoteChanges) { _syncState.value = SyncState.Merging; gitRepository.merge(config)` ) read this pass |
| C5 | Chose a one-argument `sg` codemod over a test factory: `GitSyncService` has about 20 defaulted parameters (read, `GitSyncService.kt:57-98`), so a same-name factory would have to copy them all. Recount (grep): 25 files construct it, **1 production (`GraphContentGitSyncSetup.kt:448`) and 24 tests** (15 businessTest, 5 jvmTest, 4 androidUnitTest); the adversarial "31 files" counted comment and class-declaration hits, and `GraphManager.kt`/`StelekitAppDependencies.kt` only mention the class. "Unmodified" reworded to "assertions and fixtures unchanged apart from one constructor argument". New Task 1.3d (W3, Service lane first) adds the required parameter, the codemod, the `StelekitAppPlatformIntegrations.repoOpGate` field, the root construction and the fixtures, so no wave uses what a later wave adds; 1.3b (cold runner, scheduler factory, quiesce construction) moves to W4. `NoGateFallbackAuditTest` keeps production free of a default. | grep counts above (`/tmp` file list); `GitSyncConflictReloadE2ETest.kt:93` |
| C6w (wave order) | 1.2b to W3 (ports defined by 1.2a in W2); 1.2c to W3 against a fake port; real-directory tests split to 1.2f (W4); 1.2e to W5 after 1.2d (W4); `SyncOrigin` enum moved to 1.4e (W2) so `SyncInProgress(heldBy)` compiles. Wave header states the rule "no task uses what a same-or-later wave introduces". Critical path stays 9 waves. | wave list |
| C6x | Accepted in part: dead-owner deletes only locks whose mtime is not before the dead record's `startedAtMs` minus 2 s. The footprint-by-`op` filter was not adopted (the record's `op` does not name lock files). Story 1.2 AC. | ADR-001 step 5 |
| C6 | `KNOWN_DISTANCES` dropped; every fit tier writes the existing `MANUAL_REFERENCE`, provenance is `fit != null`. Frozen-loader test that the SCALE fit is usable and a source audit that no `CalibrationMethod` value is added. ADR-003 decisions 2 and 8, Story 3.2 AC, Task 3.3c. | enum `ImageAnnotation.kt:25-40`; `valueOf ... getOrDefault(NONE)` at `ImageSidecarManager.kt:175` and `SqlDelightImageAnnotationRepository.kt:148`; `MANUAL_REFERENCE` already written by `MultiPointCalibration.kt:131`, `PlaneReference.kt:95`, `CalibrationService.kt:60`; badge/status branches `CalibrationConfidenceBadge.kt:55`, `AnnotationEditorScreen.kt:1305`. UNVERIFIED: the confidence mapping to reuse (Task 3.3c reads `MultiPointCalibration.kt:131` first). |
| C7 | `setGeometry`'s hand-written predicate (`AnnotationEditorViewModel.kt:~1213`) replaced by the single emptiness rule `merged != GeometryCalibration()`; `keepingGeometry` also clears constraints and fit; reflection property so a new field cannot be dropped; AC "SCALE fit, no height offset, edit twice". Tasks 3.2a, 3.7a, Story 3.2. | code read this pass (L1204-1225) |
| C8 | Redesigned. The tilt tier is now the focal-free GRADIENT tier: the image-to-plane map for a tilted plane is `(d/f)[[1,0,0],[0,1/cos tau,0],[0,-tan(tau)/f,1]]`, so the first-order information is the projective row `g = tan(tau)/f` (a scale gradient) and needs no focal; f only converts `g` to an angle and adds the second-order `1/cos(tau)` (0.38 percent at 5 degrees, 1.5 percent at 10). The tier stores as the existing `PlaneCalibration.homography`, so old builds measure. The trusted-focal path is an optional refinement (off by default) with a defined derivation: `HeightOffset.focalPixels(f35, w, h)` (35 mm-eq over 43.2666 mm diagonal, no sensor width needed). `FocalSource` re-ranked: `PROFILE` compares EXIF to EXIF so it adds no trust; only `USER_CONFIRMED` and a sidecar lens record at zoom 1 qualify; Task 3.9c reads the sidecar lens record into the fit (adding DB columns rejected: ADR-004 keeps it sidecar-only). SM3 reading, ADR-003 decision 5, Stories 3.3, 3.9, Tasks 3.3a/b, 3.9a-c. | `HeightOffset.kt:26,36-38` read; `ImageSensorData` has `focalLength35mmEq` but no lens record (`ImageAnnotation.kt:92-102`); `CameraProfile.kt:20-30`; derivation and SNR below are my own arithmetic, INFERRED until Story 3.1/3.8 measure them |
| C8 DoF per tier (recomputed) | SCALE P=1 (N>=3 for LOO, N>=1 approx); PER_AXIS P=2 (N>=4, optional, off); GRADIENT P=3 = `w0, gx, gy` (scale, tilt magnitude, azimuth; roll is a gauge) so N>=5; GRADIENT_AND_K P=4 so N>=6; rule N - P >= 2. The adversarial figures match: gradient across the half-frame `tan(5 deg) * 2000/3100 = 5.6 percent`; with 0.3 percent tap noise and N=6 the standard error is `0.003/(sqrt(6)*0.5) = 0.25 percent`, SNR about 23 (about 9 at 2 degrees). New significance gate `|g| > 3 SE` and `NoMeasurableTilt`. | ADR-003 decision 5 |
| C9 | Direction-spread gate removed from the tilt tier (first-order information is position spread) and kept for PER_AXIS only; PER_AXIS is optional, off by default and reported separately in Story 3.8; presenter hint reworded ("different part of the photo"). ux.md updated. | adversarial C9 |
| C10 | Drag-measure is gated to `viewScale >= FIT_SCALE`; below Fit a long drag is aim-place and the strip says "Tap point A". After a drag-measure commit, A's handle drags with the loupe and the strip says "Drag an end to fine-tune". `classifyPlacement` gains `viewScale`. ADR-007 decisions 1-2, Story 2.3 ACs, Tasks 2.3a/b. I considered snapping A through the loupe at every zoom (dwell timer): rejected, ambiguous with aim-place. | `AnnotationGestureLayer.kt:264-271` VERIFIED: vertex drag calls `onLoupe(handle.touch(...))`; `placeGesture` L186-209 |
| C11 | The canvas viewport is laid out inside `WindowInsets.systemGestures` left and right, so the outermost pixel at Fit is clear of the back-gesture zone and an edge-started drag is a valid press; a press or release outside the rectangle is off-canvas (cancel). Compose `systemGestureExclusion` rejected as the primary fix (Android caps exclusion at 200 dp of height per edge; recalled from the Android docs, not re-fetched this pass, so UNVERIFIED). ADR-006 decision 6, ADR-007 decision 4, ux.md edge-case row, Story 2.4 AC, Task 2.4c. The inset value on the owner's phone is UNVERIFIED until Task 2.7a. | grep found no existing `systemGestures`/exclusion use in `ui/annotate` |
| C12 | `ImageSidecarManager.kt` added to Task 4.5a's file list and the Story 4.5 files, and to the Sidecar lane (3.2a/3.2c -> 4.5a -> 3.9c). Task 2.3d moved into a new Gesture lane (2.3b -> 2.3d) and may not edit the Screen or ViewModel: it hands such a one-line change to the lane's worker. | adversarial C12 and minors |

#### Adversarial minors

| Item | Disposition |
|------|-------------|
| Wave count | Recounted: 89 task headings, every one appears once in the wave list (script check: missing 0, extra 0). |
| 2.3d outside any lane | Fixed (Gesture lane). |
| `TaskOutcomeMapping.kt` path | `CM/git/TaskOutcomeMapping.kt` (Task 1.4e, R4). |
| Quiesce order consistency | No change needed; the quiesce lane is now explicit and 1.3c is M. |
| ADR-007 cancelled drag returns to Select | Kept (product call, unchanged; listed under owner decisions with a default). |
| Effort table | Recomputed by script (89 tasks, 7,155k raw, 10.6M CU); per-epic E1 29 tasks 2,350k, E3 26 tasks 2,180k. |

#### Architecture concerns

| Id | Change | Evidence |
|----|--------|----------|
| R1 | `GitShadowFlushActor.flushLocked()` (caller holds the worktree mutex), `ShadowWorktreeQuiesceTarget.flushWhileLocked`, drain uses it; real-actor non-empty-queue regression test (`QuiesceRealFlushTest`); `QuiesceHold(lease, lock)` released together and on any exception, with `lock()` inside `try/catch` that releases the lease; audit that no worktree-lock holder calls `flush`. ADR-001 decision 7, Task 1.3c (now M), Story 1.3 ACs. | `GraphMoveQuiesceStrategy.android.kt:96-97` holds the mutex, `:124` calls `target.flush()`; `GitShadowFlushActor.kt:47-48` takes the same non-reentrant mutex; `flush` is a `suspend () -> List` lambda in `ShadowWorktreeQuiesceTarget` (L36-40). All read this pass. |
| R2 | `isFit` and the `clamped()` short-circuit use `abs(scale - FIT_SCALE) <= FIT_EPSILON`; ACs at 0.5 and 0.25 and 1.0005. ADR-006 decision 1, Story 2.4 AC, Task 2.4a. | `AnnotationViewTransform.kt:61` (`scale <= MIN_VIEW_SCALE + FIT_EPSILON`) and `:111` read this pass; `toggledAt` L89-90 then behaves correctly from 0.5 |
| R3 | `resolveGateKey` catches `Throwable`, retries once, returns `Resolved`/`Unreadable`; **Unreadable fails closed** (no git call under any other key; coalesce if the service is already running, else only the existing config-error path) instead of the reviewer's "take both keys" option, which adds a second lock order for the rare case. Clone key through the one `RepoOpGate.keyFor(String)`. Test: config-read failure while a run holds the repo key. ADR-001 decision 4, Tasks 1.4a, 1.4d, Story 1.4 AC. | `GitConfigRepository.getConfig` is `suspend ... Either` (architecture review, `GitConfigRepository.kt:16`) |
| R4 | `PeriodicSyncDriver.kt` added to Task 1.4e (`toTickOutcome` at L52-55 gains `SyncInProgress -> Skip(Busy)`); `TaskOutcomeMapping` path corrected; one test per path (`runFull` and `fetch`). | `PeriodicSyncDriver.kt:50-58` read this pass |
| R5 | `gateKeyFor: suspend (StorageMoveOperation) -> String?` constructor parameter on the quiesce strategy; a `UiPress` that loses to a `Quiesce` lease returns `SyncInProgress(heldBy = Quiesce)`, never sets the rerun bit, and the copy says "Moving your graph storage. Sync resumes afterwards." `SyncOrigin` gains `Quiesce`. ADR-001 decisions 1, 5, 7, Task 1.3c, Story 1.3 AC. | architecture R5 |
| R6 | Kept as an explicit Phase-4 gate in the wave section (3.2a W2 before 3.3c W4). | wave list |
| Nits | `recoverStaleLock` alias removed (only `beginOwnedOperation`); ADR-005 already says `DragMeasure`; one test per Skip(Busy) path (R4). | ADR-001 decision 12; ADR-005 line 18 |

#### Rejected or partially accepted (iteration 2)

- Adversarial B1 option "rename to a tombstone and verify": rejected (restore is not atomic); "recovery-claim token": rejected (same race one level down); the kernel lock was chosen and justified in ADR-001 Alternatives.
- C3 "add a timeout": not proposed by the reviewer and rejected here (it reintroduces #676); only chunking, containment and logging.
- C5 test-factory option: rejected in favour of the codemod (about 20 defaulted constructor parameters to mirror).
- C6x footprint-by-`op` filter: not adopted (the record's `op` does not identify lock files); mtime filter adopted.
- C8(b) "add DB columns for `CaptureLensRecord`": rejected (ADR-004 sidecar-only decision stands); a sidecar read accessor feeds the fit instead.
- C11 `systemGestureExclusion` as primary fix: rejected (200 dp per-edge cap, UNVERIFIED recall) in favour of laying the canvas out inside the gesture inset.
- R3 "take both keys in order": rejected in favour of fail-closed.
- The adversarial "31 files" count for C5 was recounted; the true figure is 25 (1 production, 24 test).

#### Decisions that need the owner (defaults proposed; none blocks CI-green work)

1. Drag-measure only at Fit or zoomed in; below Fit it is aim-place (ADR-007 decision 1). Default: yes, because the raw contact point is least accurate where the owner zooms out. Alternative: allow it at every zoom and rely on post-commit loupe refinement.
2. Canvas laid out inside the system-gesture inset on Android (ADR-006 decision 6): costs roughly 2 x 24-32 dp of image width at Fit on gesture navigation (INFERRED; 0 with three-button navigation). Default: apply; owner can zoom out for more reach. Verified only on the owner's phone (Task 2.7a).
3. A cancelled drag returns to Select (unchanged from iteration 1, adversarial minor suggested keeping the tool armed). Default: Select.
4. PER_AXIS stays off by default and is reported separately; GRADIENT becomes the default tier only if Story 3.8 shows a held-out gain on Tyler's photos. Default as stated.

### Phase 4 patch

Pass: SDD Phase 4 patch, 2026-11-14. Inputs: `pre-mortem.md` (14 failure modes PM-1..PM-14, 5 of them P1), `consistency-review.md` (1 blocker CB, concerns CC-1..5, nitpicks CN-1..6), `adversarial-review.md` (C1-C12 and six minors), `validation.md` ("Plan edits this validation asks for") and `architecture-review.md` (R1-R6 and nitpicks). Docs changed: this plan, ADR-001 through ADR-007 (all seven: status wording; content in 001, 003, 004, 005, 006, 007), `design/ux.md`, `requirements.md` (the two listed clarifications only), `validation.md` (names and ids only). No source code was touched. Counts after this pass, recounted by script: 4 epics, 31 stories, **89 tasks** (no task added; new tests were folded into existing tasks), glossary 46 terms (+4), effort 7,380k raw, **11.0M CU** (10.98M), E1 2,500k / E2 1,420k / E3 2,255k / E4 1,205k raw. Wave table re-checked by script: every task appears in exactly one wave (the only repeat is the pre-existing "gates the W5 task 4.5b" mention in the W2 line), no task uses a type introduced by a same-or-later wave (`GateAssertingGitRepository` moved from W8 to W3; the service-level two-JVM test moved from W4 to Task 1.8b because it needs the W5 gate).

#### Pre-mortem

| Finding | Change (or reason rejected) | Evidence |
|---|---|---|
| PM-1 (P1) gate-key aliasing; R3 key split | Gate key = hash of `File.canonicalFile` of the resolved `.git` directory (`RepoOpGate.keyFor(File)`), no divergent fallback: any resolution or canonicalisation failure is `Unreadable`, fail closed; `RepoOpGate` no longer wraps `GitWriteLockNaming`. Task 1.2c step 0 refuses with `SyncInProgress` when an `ActiveRunRegistry` entry exists for the canonical record path, so `ClearedOwn` is reachable only for a corpse. Tests `GateKeyAliasTest`, `ResolveGateKeyFailureTest`, `ActiveRunNotClearedOwnTest`. ADR-001 decisions 2, 4, 12; Tasks 1.2c, 1.3a, 1.4a, 1.4d; Story 1.3 and 1.4 ACs. | `GitWriteLockNaming.kt:32` `lockNameFor(remoteUrl: String)`, no `canonical*` or `toRealPath` in the file (grep) |
| PM-2 (P1) liveness / torn record | Linux and Android read raw `/proc/<pid>/stat` field 22 ticks on the JVM too and compare them exactly (no wall clock is involved, so no tolerance is needed; the 2 s tolerance applies only to the macOS/Windows `startInstant` path). Record gains `pidNs`; an absent pid in a different or unreadable pid namespace is `Unknown`. Empty/partial/unparseable record is `OwnerUnknown(TornRecord)`; publication is atomic (temp file + `createLink`, fallback `CREATE_NEW`). The probe is re-run under the recovery lock. Tests `TornRecordIsUnknownTest`, `PidNamespaceAbsentPidTest`, `ClockStepStartTimeTest`. ADR-001 decision 12; Tasks 1.2a, 1.2b, 1.2c; Story 1.2 AC. | `awk '{print $22}' /proc/self/stat` printed 90761690; `readlink /proc/self/ns/pid` printed `pid:[4026531836]` (run this pass). The `startInstant` = `btime` + ticks claim is INFERRED from the JDK implementation, not run |
| PM-3 (P1) foreign/forced lock deletes; adv C2, C4ii | ADR-001's backwards proof sketch (f) corrected. **The pre-mortem's proposed rule ("a lock newer than the dead record's `startedAtMs` is foreign") was REJECTED as written**: the dead owner's own lock always postdates its `startedAtMs` (the record is written at begin, JGit creates the lock later), so it would never clear a real corpse. Replaced by a heartbeat upper bound: the owner touches the record's mtime every 5 s, and a dead owner's lock is deletable only if `startedAtMs - 2 s <= lock.mtime <= record.mtime + 10 s` and `lock.mtime <= now - 5 s`; any failing lock means nothing is deleted and `OwnerUnknown(ForeignLockSuspected)`. `forceRemoveLock` is a gated entry (gate, recovery lock, re-read, re-decide, refuse on `OwnerAlive`). Tests `ForeignLockNewerThanDeadRecordTest`, `ForceRemoveRacesLiveRunTest`, `OwnerHeartbeatTest`. ADR-001 decisions 3, 12, 12a; Tasks 1.2a-d, 1.2e, 1.4a; Story 1.2 AC. | ADR-001 old step 5 and sketch (f) read this pass; ordering of record-then-lock from ADR-001 step 6 |
| PM-4 (P1) merge-unit exit rebinding; adv C5 | Task 1.5a extracts `private suspend fun mergeUnit(...): MergeUnitOutcome` (sealed `Continue`/`Exit`), translated at one call site; AC "after a conflict or commit failure in the unit `push` is never called" in `GitSyncServiceConflictResolutionTest` and `GitSyncCancelBoundaryTest`; `NonCancellableReturnAuditTest` forbids `return@withContext` inside `NonCancellable` blocks. ADR-001 decision 9; Story 1.5 AC. | `GitSyncService.kt:429`, `:456`, `:464` still `return@withContext` inside the merge region (grep this pass: 45 more elsewhere in the file) |
| PM-5 (P1) calibration worse-but-trusted; adv C8, C9 | GRADIENT gate is a Student-t test for N - P dof with a leave-one-out veto; an EXIF 35 mm-equivalent validity bound (order of magnitude only, above 10 degrees means "tilt too large", SCALE kept); crop/zoom refusal (`CroppedOrZoomedFrame`); GRADIENT ships **default-off** (`GRADIENT_DEFAULT_ON = false`, Settings `calibration_gradient_tier`) until the Story 3.8a real-photo study; pill shows tier plus estimated accuracy. Tests `GradientNoiseFalsePositiveRateTest` (at most 1 percent at N = 5 and 6), `GradientLooVetoTest`, `GradientSignificanceTest`, `CroppedFrameRefusalTest`, `GradientDefaultOffTest`. **Renumbering, not rejection:** the pre-mortem named Tasks 3.3a/3.3b/3.3c, but in this plan the GRADIENT tiers are Task 3.3b (3.3a is SCALE/PER_AXIS) and the applier is 3.3c, so the gate, bound and refusal live in 3.3b and the applier's "never write a plane for a refused result" in 3.3c. ADR-003 decision 5; Story 3.3 ACs; Tasks 3.3b, 3.3c, 3.6a; Risk Control. | df = 2: `2*(1-(0.5+3/(2*sqrt(11)))) = 0.0955` and `t_{0.995,2} = 9.925` computed this pass; 3 and 4 dof (5.841, 4.604) are table values, INFERRED until the test computes them |
| PM-6 (P2) only one JVM tests recovery; adv C6 | `TwoJvmRecoveryRaceTest` (2 JVMs x 40 rounds) and `ProcessKillOrphanLockTest` in Task 1.2f; `CrossProcessSyncOverDeadRecordTest` in Task 1.8b; recovery file opened `CREATE`+`WRITE`; in-process mutex keyed by canonical recovery-file path plus `OverlappingFileLockException` backstop (`RecoveryLockInProcessMutexTest`); `.git`-is-a-file is `OwnerUnknown`/out of scope. | adversarial section 1 (JDK contract, not run) |
| PM-7 (P2) quiesce key and press | Quiesce `gateKeyFor` is built from the same canonical resolver (one key by construction, `GateKeyResolutionTest`); presses are refused while a `Quiesce` lease is held and never set the rerun bit (already in iteration 2; stated and tested in Story 1.3). The pre-mortem's "follow-up re-resolves config" alternative was not needed because no follow-up exists in that case. | Story 1.3 ACs |
| PM-8 (P2) wave order of the fake; adv C7 | `GateAssertingGitRepository` created in Task 1.3d (W3); 1.4a tests use it; 1.8b only makes it the default. Wave line updated. | wave table |
| PM-9 (P2) Fit sticky; adv C10 | Task 2.4a accumulates the unsnapped scale and snaps only for `isFit`/`toggledAt`; test with 200 events of factor 0.9995 (ends near 0.905); golden old-sidecar test `GoldenSidecarMeasurementIdentityTest` (Task 3.2c) asserts existing `distanceMeters` byte-identical after the `setGeometry` predicate change. ADR-006 decision 1. | `AnnotationViewTransform.kt:78-79` (`scale * zoom` from the current scale) and `:109-111` (snap) |
| PM-10 (P2) camera dialog crash | Catch `Throwable` (rethrow `CancellationException`) at every bind, future and interop registration; the dialog's `unbindAll()` calls are replaced by session-owned unbind (Task 4.4a); fake backend throwing `IllegalArgumentException`/`SecurityException` keeps the previous lens; `@ExperimentalCamera2Interop` opt-in scoped; physical-id results gate chips. ADR-004 decision 1; Story 4.3 AC; Tasks 4.3a, 4.4a. | `CameraViewfinderDialog.android.kt:105,118`; `AndroidCameraProvider.kt:113,161` (grep this pass) |
| PM-11 (P2) trusted wrong capture record | `SELECTED_LENS` records never auto-select a profile; `exifFocalMm` disagreement above 15 percent disables profile match; `source` is in the persisted log line (no new UI). ADR-004 decision 5; Story 3.9 and 4.5 ACs; Tasks 3.9b, 4.5a. | ADR-004 decision 5 |
| PM-12 (P3) inset test | Cancel path tested with an injected non-zero inset and a zero inset (Task 2.3c; ADR-007 decision 4). | ADR-007 |
| PM-13 (P3) release ordering | 1.8c stays an open, unticked PR-checklist item after the CI-green E1 merge; all unreadable probes stay `Unknown` meanwhile (Risk Control, ADR-001 Consequences). | |
| PM-14 (P3) sidecar lane rebase | Already ordered; made an explicit Phase-4 gate in the lane list (3.2c W3 -> 4.5a W4 -> 3.9c W6). | lane list |

#### Consistency review

| Finding | Change | Evidence |
|---|---|---|
| CB BLOCKER two-finger contradiction | One behaviour everywhere: a second finger cancels only the in-flight placement, hands over to pinch/pan, keeps any already-placed first point, and the tool stays armed. Edited: ux.md section 3.1 disarm line, section 3.3 rows (3 rows), UX-11, UX-13; ADR-005 decision 7; ADR-007 decision 4 (its own pending-discard list also named a two-finger gesture); plan Story 2.3 second-finger and disarm ACs, Task 2.3c. The "keeps A" refinement is mine (a user must be able to pinch-zoom between A and B); see owner decisions. | grep: no remaining "two-finger gesture" disarm text in ux.md/ADR-*.md |
| CC-1 "joined" signal | requirements.md Observability line amended to log line plus `AlreadySyncing` UI event, no `SyncState` change, with the reason (a new `SyncState` value breaks exhaustive consumers and would hide a prior failure); matches ADR-001 decisions 5 and 6. | ADR-001 d5/d6 |
| CC-2 three names | Glossary row "Keep tool" = `ToolStickiness.LOCKED` = `annotator_sticky_tools`; ADR-005 decision 8. | |
| CC-3 stale Repair Log rows | V-B5 and V-E3c marked SUPERSEDED (iteration-2 GRADIENT design and this pass); Story 3.9's first AC reworded to say it supersedes the old gate. | |
| CC-4 scope drift | New section "Scope additions traced to requirements": 1.2e (data-safety fix for an ungated cross-process writer, SM1), 3.9b (kept, optional, tied to Scope item 4, droppable alone), Settings rows (Risk Control line), `calibration_gradient_tier`. | `SyncMain.kt:59` per ADR-001 context |
| CC-5 SM3 N | requirements.md Success Metric 3 now says N is provisional per tier and finalised by the owner-run Story 3.8a study; plan Unresolved Questions line matches. | |
| CN-1 2.3d Files line | Standard `- Files:` line with the lane stated; the hand-off note kept in the body. | |
| CN-2 ADR-007 text | Consequences reworded: ADR-005 no longer names `PlacementGesture`; ADR-007 owns `DragMeasure`. | ADR-005 grep |
| CN-3 UX-50 | Auto-return pulse (Task 2.6a) and zoom-to-Fit (Task 2.4c) now respect remove-animations; lens crossfade unchanged. | |
| CN-4 UX trace | New "UX criterion to task trace" table (all 50 ids). | |
| CN-5 "tilt tier" | ux.md names `GRADIENT` and the default-off state. | |
| CN-6 ADR status | All seven ADRs: "Accepted pending owner review (Phase 4 patch)". The 1.8b-fake ordering note is moot (fake now W3). | |

#### Adversarial review (pass 2)

| Finding | Change |
|---|---|
| C1 | PM-1. |
| C2 | PM-3 (rule decided, with the rejection of the pre-mortem paraphrase). |
| C3 | PM-2. |
| C4 | PM-2 (torn record, i) and PM-3 (`forceRemoveLock`, ii). |
| C5 | PM-4. |
| C6 | PM-6 (cross-process tests, in-process mutex, `CREATE`+`WRITE`, `heldBy = null` for an out-of-process recoverer). |
| C7 | PM-8. |
| C8, C9 | PM-5. |
| C10 | PM-9. |
| C11, C12 | Already resolved in iteration 2; no change. |
| Minors | `isFit-or-greater` helper and zero-inset test: PM-12; `AP/MainActivity.kt` line reference dropped from Task 1.3b; `/a-b` vs `/a/b` collision fixed by the hash of the canonical path; `SyncInProgress(heldBy = null)` for out-of-process recoverers; lock-order note needs nothing. |

#### Architecture review (re-review)

R1 (flushLocked, atomic hold), R2 (symmetric `isFit`), R3 (fail closed), R4 (Task 1.4e file list), R5 (quiesce key lambda, press refusal) were absorbed in iteration 2; this pass only strengthens R3 and R5 with the canonical key (PM-1, PM-7) and re-checked R2 against `AnnotationViewTransform.kt:61,111`. R6 (3.2a before 3.3c) stays an explicit gate. Nitpicks: `recoverStaleLock` alias already dropped; ADR-005/007 wording aligned (CN-2); one Skip(Busy) test per path already in Task 1.4e.

#### Validation edits

| Request | Where |
|---|---|
| Cross-process race and SIGKILL tests | Task 1.2f (`TwoJvmRecoveryRaceTest`, `ProcessKillOrphanLockTest`), service-level `CrossProcessSyncOverDeadRecordTest` in Task 1.8b (needs the W5 gate) |
| Liveness skew and torn record | Tasks 1.2a/1.2b (`ClockStepStartTimeTest`, `TornRecordIsUnknownTest`, `PidNamespaceAbsentPidTest`) |
| `NewCodeAuditTest`, `ScopeEscapeAuditTest` | Task 1.8a (now M) |
| Slow-pinch detent | Task 2.4a, Story 2.4 AC |
| Out-of-model families e/f/g, real-photo hold-out with set A/B, verdict doc audit | Tasks 3.1b (now M), 3.3b, 3.8a |
| Monte Carlo significance and crop tests | Task 3.3b |
| OEM fixture matrix, probe format test | Tasks 4.2b, 4.1a |
| Adversarial C2 "Phase 5 rule" | Decided here (heartbeat bound); validation U-E1-39 now asserts it rather than only fail-closed |

validation.md was changed only where an id or name changed: U-E1-06/07/10/13/39/40 and U-E3-07 class names, I-E1-06 renamed `TwoJvmRecoveryRaceTest`, new rows U-E1-43..46, U-E1-48, U-E3-31, U-E3-32, U-E3-33, U-E3-07 tightened to at most 1 percent (pre-mortem), and the "Plan edits requested" list marked applied.

#### Rejected, remapped or partially accepted (Phase 4 patch)

- PM-3 literal rule rejected (see above); heartbeat bound adopted.
- PM-2 "tolerance" for raw ticks: not adopted (ticks are integers since boot and carry no clock; a tolerance would only admit pid reuse), applied to `Instant` comparisons only.
- PM-5 task numbers remapped to this plan's numbering (3.3b/3.3c), content accepted in full.
- `CrossProcessSyncOverDeadRecordTest` placed in 1.8b rather than 1.2f (wave order); `ProcessKillOrphanLockTest` runs at repository level in 1.2f for the same reason.
- PM-7 alternative "follow-up re-resolves config" not needed: presses are refused, not queued, while a quiesce lease is held.
- Validation's U-E3-07 allowed 5 percent false positives; tightened to 1 percent per the pre-mortem.

#### Decisions that need the owner (defaults proposed; none blocks CI-green work)

1-4. Unchanged from iteration 2 (drag-measure only at or above Fit; canvas inside the system-gesture inset; a cancelled drag returns to Select; PER_AXIS off).
5. A second finger keeps an already-placed first point and the tool stays armed (new this pass). Default: keep (so the owner can pinch-zoom before placing B). Alternative: discard A, which makes a zoom between A and B impossible without re-placing A.
6. GRADIENT ships default-off until the Story 3.8a study (previous default: eligible whenever its gates passed). Default: off; the owner can enable `calibration_gradient_tier` to try it on their photos.
7. A stale lock that postdates the dead owner's last heartbeat now takes the amber path (paused retry, then manual confirm) instead of auto-clearing. Default: apply; it only costs a manual confirm after a hard kill that also starved the heartbeat.
8. ADR status "Accepted pending owner review": the owner confirming the plan is what finally accepts them.

### Triad repair iteration 1

Pass: SDD triad repair, iteration 1, 2026-11-14. Inputs: the three triad lens analyses (product, UX, engineering). Docs changed: `requirements.md` (SM2, SM3a/SM3b, SM4, performance SLO, usage scenario, priority rationale), this plan, `design/ux.md`, `implementation/validation.md` (ids and names only), ADR-001, ADR-004, ADR-005, ADR-007 (one paragraph or clause each); ADR-002, ADR-003, ADR-006 needed no change. No source code was touched. Counts after this pass, recounted by script from the headings: 4 epics (E1 8 stories / 41 tasks, E2 7 / 21, E3 9 / 29, E4 7 / 16), **31 stories, 107 tasks** (was 89; +18 from splits and one new task, 1.2g), glossary 49 terms (+3), UX criteria 62 (+12), effort 8,555k raw, **12.73M CU** (was 7,380k, 10.98M; Large band unchanged), cost classes XS 4, S 67, M 35, L 1. Wave table re-checked by script: every one of the 107 tasks appears in exactly one wave (the only repeated ids are prose mentions of 4.1a, 4.3b and 4.5b in the W1/W2 lines); the "Files" lists were re-counted by script and no task lists more than five hand-written files except the scripted codemod 1.3d3 (justified in its text).

#### Product gaps

| Gap | Change (or reason rejected) | Evidence |
|-----|-----------------------------|----------|
| P1 SM2 "small fraction" and SM4 ~1 s / 60 fps unquantified | SM2: floor 0.25 of Fit means width at most 25 percent and area at most 6.25 percent of the viewport (0.25 squared; Fit area never exceeds the viewport), outermost pixel stored exactly 0.0 or 1.0 at Fit and at the floor; baseline named (floor equals Fit today, 100 percent of width, about 72 percent of the area for the 3000x4000 sample in a 1080x2000 viewport, computed). SM4: median at most 1,000 ms and worst case at most 2,000 ms over 10 switches, measured as chip tap to first preview frame via the existing `camera bind ... ms=` log line; frame SLO as p95 at most 16.7 ms and jank under 5 percent on the existing harness. All thresholds labelled PROPOSED (OC-14); baselines stay UNVERIFIED until Task 2.7a (pre-change run on the same device and script) and Story 4.1 (today there is no switch to time). | `kmp/TESTING_README.md` lines 27-37 (32 ms JANK badge, 16 ms limit) and 82-84 (jank under 5 percent normal); arithmetic in this row |
| P2/P3 SM3 has no done-state; no absolute accuracy target | SM3 split into **SM3a** (value editing, CI-closable by U-E3-16..20, Epic 3 may be labelled "CI-green, SM3a closed, SM3b open") and **SM3b** (calibration accuracy, owner-study-gated, strictly lower than single-scale and within a PROPOSED tolerance of at most 2 percent of true length, equivalent to 0.36 cm on an 18 cm object, with a 0.5 cm floor under 25 cm; OC-13). validation.md R6 and the SM table updated. The synthetic harness is stated to reproduce a baseline only on synthetic scenes. | `validation.md` already carried R5/R6 as SM3a/SM3b; `I-E3-13` still rejects "SM3 met" without the real-photo table |
| P3 no usage scenario or frequency | Added to requirements "Users / Consumers": 5 to 15 measurements per photo, several sessions a week, sync several times a day; labelled INFERRED with OC-16 for the owner to confirm. I do not know the owner's real frequency; the text says so. | requirements.md |
| P4 no fallback for item 4 if lenses are hidden | SM4 fallback acceptance (zoom-ratio chips, optical/"Digital" labelling, record = logical camera plus zoom with `source = SELECTED_LENS`, honest "Ultra-wide isn't available as a separate lens on this device"); Story 4.4 AC; ux.md 8.3 row; Task 4.3b rewritten zoom-chip first; ADR-004 clause. | ADR-004 decision 2 already forbade inventing chips; `CameraViewfinderDialog.android.kt` fallback unchanged |
| P5 no priority rationale for items 2-4 | New requirements section "Priority and ordering rationale" (sync data safety first; annotator second: least design risk and builds the canvas and sheets 3 and 4 reuse; measurements third: SM3a cheap, SM3b needs owner photos so it starts early; camera fourth: most device-only risk, probe in W1, lens record is an optional input to calibration via Task 3.9, so calibration does not wait for it). | plan Dependency Visualization cross-epic edges (E4.5a optional input to E3.9) |
| P6 scope drift (SyncMain) | No change: already traced in "Scope additions traced to requirements" and accepted by the product lens. | plan section "Scope additions" |
| P7 ADRs pending | Kept "Accepted pending owner review"; added the consolidated "Owner confirmations" table (OC-1..OC-18, each with default and "if no"). | plan section "Owner confirmations (consolidated)" |

#### UX gaps

| Gap | Change (or reason rejected) | Evidence |
|-----|-----------------------------|----------|
| U1 UX-18 / S3 flow 1 "25 percent stays visible" contradicts ADR-006 | UX-18 and 4.2 step 1 rewritten to ADR-006 decision 2 (whole image visible and centred below Fit; far edge `slack` short of the viewport edge at or above Fit; no 25 percent invariant); validation names `AnnotationViewTransformTest` U-E2-10 and X-18 corrected. | ADR-006 decision 2 text; `validation.md` U-E2-10 name previously asserted "KeepAtLeast25PercentVisible" |
| U2 48 dp threshold unvalidated; no counter | Task 2.3b emits three `PlacementCounters` log lines (drag-measure, aim-place, undo within 5 s of a drag-measure); Task 2.7a now carries owner-device pass criteria (20 + 20 gestures at Fit and 2x: at least 18 drag-measures classified, at most 1 aim-place misclassified, at most 3 immediate undos, none unnoticed) and the tuning step for `DRAG_COMMIT_DP` (PROPOSED numbers, OC-6); new tests I-E2-10 and D-E2-01 text. | `AnnotationGestureLayer.kt:186-209` (existing aim path), ADR-007 decision 1-2 |
| U3 long-press/double-tap vs TalkBack | TalkBack custom actions "Keep tool" / "Release tool" on every chip (UX-55, Task 2.6a, X-55); ux 3.1 and 3.4. The "show the hint again after a settings reset" suggestion was not adopted separately: hint counters live in Settings and reset with it already (plan Story 2.3 AC). | ux.md 3.1 |
| U4 jargon "Just relabel"; preselect conflict | Renamed "Change label only" everywhere (ux.md, plan Stories 3.4/3.5, validation test names); one preselection rule (uncalibrated: always known distance; calibrated: neither on the first edit of a session, last choice on later edits; not persisted), UX-26 and Story 3.5 AC aligned; drag-confirm initial focus is "Keep my value". | ux.md 6.1 (old 6.1 said "remembered for the session", UX-26 said "neither preselected") |
| U5 paused sync: no Retry now, durability, cause-asserting copy | Enabled "Retry now" (coalescing-safe, counts as the current try, never adds retries; UX-51); the wireframe's "tries 1 of 3" corrected to "1 of 2" (ADR-001 decision 14 has two retries); cause-neutral body copy (the old "Another git operation may be running" asserted a cause for `OwnerUnknown`); durability: the retry timer is an in-process coroutine, not persisted, and the durable re-evaluation is the WorkManager periodic task, with an overdue retry running once on first observation (UX-52, Task 1.7c). Tasks 1.7a (action contract on the model, so 1.7b does not use a 1.7c symbol in its own wave), 1.7b, 1.7c; I-E1-16, X-51, X-52; ADR-001 decision 14 paragraph. | `RateLimitRetry.kt:22` (`scope.launch`), `WorkManagerTaskScheduler.kt:158,185` (`enqueueUniquePeriodicWork`) read this pass; **UNVERIFIED:** whether an app-resume sync trigger exists today (a grep of `commonMain` and `androidMain` for resume triggers found none), so the plan does not rely on one |
| U6 WCAG 2.5.7 pan; TalkBack placement path | Pan pad (four 48 dp arrows in the status-strip slot, 25 percent of the viewport per press, hidden at or below Fit) plus TalkBack pan actions (UX-53, Tasks 2.5b, 2.6a); explicit announced crosshair path for TalkBack placement (UX-54, Tasks 2.3d, 2.6a); new glossary term `PanPad`. | ux.md 3.4 and 4.1 |
| U7 missing states (a)-(f) | (a) initial-bind loading with 5 s failure (UX-56), (b) flash unavailable disabled with reason (UX-57), (c) empty Known distances sheet (UX-58), (d) 360 dp / 200 percent font / RTL second-row criteria with a wrapping flow layout (UX-62), (e) Undo snackbar duration indefinite under touch exploration or switch access (UX-61), (f) landscape small phones: covered by the same flow-layout rule, the rail stays at 600 dp or more (no new criterion). Also pinch-to-chip hysteresis (UX-59) and hint dismissal persistence per install (UX-60, Settings flag). | ux.md 4.1, 6.1, 7.2, 8.2, 8.3 |
| U8 UNVERIFIED sizes and contrast | No change: the criteria stay testable and the plan's Task 2.5b computed-contrast tests remain the gate; the labels stay UNVERIFIED in ux.md section 1. | triad lens itself rates this "ensure tests exist", which X-16, X-22 and X-49 already are |
| U9 inconsistent cancel semantics | One table in ux.md 3.3 (second finger and off-canvas or back-gesture release both cancel only the press and keep the tool and any placed A; only tapping the active chip, tapping Select, Back/Esc disarm); plan Story 2.3 AC and Task 2.3c, ADR-005 decision 7, ADR-007 decision 4 aligned; new owner confirmation OC-9 (this **supersedes the earlier default 3 "a cancelled drag returns to Select"**) with a single flipping constant `disarmOnOffCanvas`. | triad UX gap 9; the earlier decision list item 3 in this log |
| U10 FAB hidden, no voice capture in the editor | Recorded as OC-12 and in ux.md 3.1 and Story 2.5 AC; the FAB host remains UNVERIFIED until Task 2.1a. | plan Unresolved Questions (FAB host) |
| U11 camera chip flicker, hint persistence | See U7 (UX-59, UX-60). | |
| UX-47 only a manual walk | `ExitPathAuditTest` (already a row in validation.md as X-47, but with no owning task) is now owned by Task 1.8a and iterates the state values of the presentation models asserting non-empty `exits`; a purely visual exit (scrim, Back) still gets one phone walk. | `validation.md` X-47 row |

#### Engineering gaps

| Gap | Change (or reason rejected) | Evidence |
|-----|-----------------------------|----------|
| G1 Task 1.3d: about 11 production files and about 28 test sites | Split into 1.3d1 (required `gate`, nullable root field, 3 files), 1.3d2 (gate built once in the roots, 4 files), 1.3d3 (scripted `sg` codemod, M, with a stated verification: equal `sg` counts before/after, diff-shape check, three source sets compile, `NoGateFallbackAuditTest`) and 1.3d4 (`GateAssertingGitRepository`, no-fallback audit and the two wired fixtures, 4 files). Per-file sites listed in 1.3d3. **Recount by command this pass:** 24 test files and 27 construction sites (androidUnitTest 4 files / 5 sites, businessTest 15 / 17, jvmTest 5 / 5) plus 1 production site (`GraphContentGitSyncSetup.kt:448`); the earlier "24 sites (15/5/4)" counted files, the triad's "about 28" counted the production site and the sites, and the 5 jvmTest files that call `buildTestGitSyncService` need no edit. | `for s in ...; grep -rlE '(^\|[^a-zA-Z.])GitSyncService\(' ...` output in this pass |
| G2 L-risk and over-five-file tasks 1.2c, 1.4a, 3.3b, 1.4d (plus any others by script) | 1.2c -> 1.2c1 (claim steps 0-4 and 6, with a fail-closed placeholder for step 5) and 1.2c2 (foreign-lock window, heartbeat, `forceRemoveLock`); 1.4a -> 1.4a1 (`resolveGateKey`, wrapper, acquire/release) and 1.4a2 (atomic rerun, heartbeat start, origin mapping); 3.3b -> 3.3b1 (solver and gates), 3.3b2 (t-test, LOO veto, tilt bound, selection), 3.3b3 (crop refusal, default-off, out-of-model robustness); 1.4d -> 1.4d1 and 1.4d2. **Script found six more over five files** (`parse the Files line`: 1.2a nine, 1.2d six, 1.3b six, 1.4e eleven, 3.2c six, 4.5a seven): split into 1.2a1/1.2a2, 1.2d1/1.2d2, 1.3b1/1.3b2, 1.4e1/1.4e2/1.4e3, 3.2c1/3.2c2, 4.5a1/4.5a2 (3.2b is five hand-written files plus generated output, kept with the counting rule). Task 1.5a (one extraction in the 806-line hot file with three `return@withContext` rebinds) is **re-classed L** (240k) with its reason, instead of being split, because a half-extracted merge region would leave the #676 reload invariant unprotected; the table now has one L task and its total is recomputed. | the script and its output in this pass; `GitSyncService.kt:429,456,464` |
| G3 3.3b/3.3c same wave, 3.3c uses failure types 3.3b introduces | The `CalibrationFailure` variants, including `CroppedOrZoomedFrame` and `TiltTooLarge`, are defined in Task 3.3a (`ConstraintModels.kt`, W3); 3.3c (W4) uses only those; 3.3b1 W4, 3.3b2 W5, 3.3b3 W6 in a new Calibrator lane. A "Triad repair 1 gate" sentence added to the wave section. | wave table |
| G4 4.3b (W3) vs the open question "blocks 4.3b" | Plan is zoom-chip first: 4.3b always yields zoom stops plus separately exposed cameras, lists a physical-id lens only if its bind succeeds at runtime, and treats the physical-id path as UNVERIFIED until the owner probe 4.1b; the Unresolved Question now says 4.1b gates acceptance, 4.5b's record source and the 4.2 threshold tightening, not 4.3b; the W2 wave note agrees. ADR-004 clause. | Unresolved Questions line and wave table now agree (read back) |
| G5 1.2f (W4) uses `beginOwnedOperation` that 1.2d (W4) adds | 1.2f now drives `LockRecovery.claim` over the real `JavaIoLockFiles` and `JvmProcessLiveness` (W3 inputs); the repository-level assertions (real `beginOwnedOperation` ordering, `ProcessKillOrphanLockTest`, the cancel-sweep claim half) moved to new Task 1.2g (S, W5, after 1.2d1/1.2d2). Test table rows re-pointed (I-E1-08/09, I-E1-04). | wave table |
| G6 construction-site count drift | See G1; the recount is also a command in Task 1.3d3 to re-run at implementation time. | G1 |
| Task 2.2b: 9-test repair list bounded from the 2.1a output | Split into 2.2b1 (production: ViewModel, `UndoHistory`, new test; 3 files) and 2.2b2/2.2b3 (test-file repair batches of four, test files only, no Screen source); a rule says that if the 2.1a scratch run lists more than the starting eight failing files, its PR names extra batches of at most five files each and the integrator updates counts. Paths verified by `find`. | `find` output: six business/common/jvm paths corrected in the plan |
| Same-file lanes after the splits | Lane block rewritten: VM lane starts at 2.2b1; Service lane 1.3d1 -> 1.4a1 -> 1.4a2 -> 1.4b -> 1.4c -> 1.5a -> 1.5b -> 1.7c; new Claim lane (1.2a2 -> 1.2c1 -> 1.2c2), Calibrator lane, Cold/launcher lane (1.3b1 -> 1.3b2 -> 1.4d2); Sidecar lane gains 3.2c1/3.2c2 and 4.5a1/4.5a2 ordering. | lane block |

#### Rejected, remapped or partially accepted (triad repair 1)

- Engineering "split 3.3b into two": three tasks instead, because two would still list eight files (the t-test, LOO veto and EXIF bound need five files and the refusal and robustness tests three more).
- Engineering "1.3d1 plumbing about 7 files, M": split further into 1.3d1 and 1.3d2 so each worker run stays at three or four files.
- Engineering "1.5a keep one task but mark L-risk": done as an explicit L class with a stated reason; it is the only L task.
- UX gap 8 (UNVERIFIED sizes and contrast): no change, the criteria are already test-backed.
- UX gap 3 "show the first-three-times hint again after a settings reset": not a separate change, because the hint counters are Settings keys and reset with Settings.
- UX gap 7(f) (landscape small phones): no new criterion; the wrap rule covers it and the rail threshold stays 600 dp.
- Product gap 6 (scope drift): no change, already traced.
- No scope was cut: every new row is an addition or a split; every story, task and criterion that existed still exists (renumbered tasks keep their suffix-stable ids).

#### New owner confirmations (defaults proposed; none blocks CI-green work)

OC-9 (cancel semantics: off-canvas or back-gesture release keeps the tool armed; supersedes the earlier default "returns to Select"), OC-12 (voice capture unavailable in the editor, recorded), and the PROPOSED numbers OC-6 (48 dp), OC-13 (SM3b tolerance) and OC-14 (SM2, SM4 and frame thresholds), plus OC-16 (usage scenario) and OC-17 (Pan pad, TalkBack crosshair path, second-row rule). The full table is in "Owner confirmations (consolidated)".

### Triad repair iteration 2

Pass: SDD triad repair, iteration 2, 2026-11-14. Inputs: the UX lens (needs-work, 0 blockers, 12 gaps) and the engineering lens (ready, 8 housekeeping gaps) of triad round 2. Docs changed: `design/ux.md`, this plan, `implementation/validation.md` (ids and names only), ADR-001, ADR-002, ADR-005, ADR-006 (one clause each), and a scope note on `architecture-review.md`, `consistency-review.md` and `pre-mortem.md`. No source code was touched. Counts after this pass, recounted by script from the headings: 4 epics (E1 8 stories / 41 tasks, E2 7 / 21, E3 9 / 29, E4 7 / 16), **31 stories, 107 tasks** (unchanged: every fix extends an existing task), glossary **51** terms (+2: `StripPriority`, `EditorBackPolicy`), UX criteria **70** (+8: UX-63 to UX-70, plus amended UX-04, UX-05, UX-51, UX-53), classes XS 4, S 67, M 35, L 1, effort **8,555k raw for tasks plus 180k for 9 owned registration integrator steps = 8,735k raw, 12.99M CU** (was 12.73M). Wave table verified by script (below).

**Script checks run this pass.** (1) The wave placement table has 107 task rows and 107 distinct ids equal to the 107 `##### Task` headings (set equality), every `depends-on` id exists and sits in the same or an earlier wave (0 violations), and 9 `INT-Wn` rows exist. (2) Headings counted: 31 stories, 4 epics, per-epic tasks 41/21/29/16. (3) Glossary rows 51; UX criteria in `design/ux.md` 70. (4) The five-file rule: every Files line is at most five hand-written files once `REGISTER:` entries are excluded (Task 1.5a lists exactly five).

#### UX gaps

| Gap | Change (or reason rejected) | Evidence |
|-----|-----------------------------|----------|
| 1 pan pad shares the strip slot with live prompts | One strip with a six-level priority (error, tool prompt or pending state, first-three-times hint, status text, voice hint, idle), one message at a time, lower messages return when a higher one clears (ux.md 3.5, pure `StripPriority`, Task 2.3a). The pan pad moves out of the strip into its **own pan row** between the canvas and the toolbar, so zoomed tap-tap shows "Point A placed. Tap point B" with Cancel and the four arrows at once. The row counts toward the 40 percent toolbar budget at 200 percent font and folds into the "Zoom" popup with the zoom group when over it. UX-63, X-63, Task 2.3e/2.5b. | ux.md 3.5 and 4.1 |
| 2 pan pad visibility stated three ways | One rule: the Pan toggle and row are available exactly when `viewScale >= FIT_SCALE` (ADR-006 decision 2 allows slack-pan at Fit) and the row shows while `[Pan]` is on; strictly below Fit the toggle is disabled with "Nothing to pan". Replaced in ux.md 4.1, UX-53, plan Story 2.5 AC, Task 2.5b, glossary `PanPad`, and one clause in ADR-006 decision 2. | ADR-006 decision 2 text ("At and above Fit: the offset is clamped so the far edge stays slack short") |
| 3 Back with an armed tool; predictive back | `EditorBackPolicy` (Task 2.3a, wired in 2.3e): open sheet, dialog or nudge session first; then a first Back disarms (discarding a pending point, with the existing "Discard N unfinished points?" dialog for two or more unfinished multi-step points); then, in Select with nothing pending, the existing leave check. **Evidence changed the design:** the first draft assumed no leave dialog exists; the code already has `backOutcome` and `viewModel.resolveBack()`, so the policy reuses them instead of adding one. The handler stays `enabled = true`, which is how predictive back already behaves. UX-64, X-64, ADR-005 decision 7 clause. | `LeavePolicy.kt:8-27` (`BackOutcome.Close/ConfirmDiscard/SaveFailed`), `AnnotationEditorScreen.kt:324-332` (`PlatformBackHandler(enabled = true)` calling `resolveBack()`), `androidApp/src/main/AndroidManifest.xml:42` (`enableOnBackInvokedCallback="true"`), read this pass. UNVERIFIED: the on-device predictive-back feel (no custom preview is drawn), checked on the phone |
| 4 no visible Cancel for pending point A | A 48 dp "Cancel" beside the prompt in the strip: discards A, **keeps the tool armed** (narrowest effect; Back and the chip disarm). UX-65, X-65, Task 2.3e, `discardFirstPoint()` already planned in 2.3b. | ux.md 3.3 table |
| 5 value editor scope; unit chip | Default (new **OC-19**): Distance only; tapping an Area or Angle value selects it with "Only distance values can be edited" and no "Edit value" action; Label keeps its text editing. Reason: an angle cannot calibrate and `MeasurementValue` stores metres (ADR-002 decision 6), so extending needs a new value kind; the extension is the "if no" path. The editor's unit chip sets the **entry unit only**, never the global display unit or the stored metres. UX-66, UX-67, X-66, X-67, ADR-002 decision 6a. | `plan.md` glossary `MeasurementValue` (Computed/Overridden in metres) |
| 6 UX-62 covers only the second row | New UX-68 and section 9a: first toolbar row (7 chips become a flow layout), sync row and sheet, value editor sheet, Known distances rows, camera chips and top bar at 360 dp, 200 percent font and RTL. Tests X-68a..e in Tasks 2.5b, 1.7b, 3.5c, 3.6e, 4.4c. | arithmetic: 7 x 48 dp = 336 dp of 360 dp before padding |
| 7 three sync action labels; retry-budget semantics | One vocabulary: "Sync now" starts a user-initiated run (Idle, Failed), "Retry now" only in Paused, "Try again" is not used for sync (it stays in camera and save-failure copy). **Retry now no longer consumes the budget** (it is an extra attempt; the try number does not move and a failed manual attempt re-arms the pending automatic try), so five quick presses cannot reach red; "Sync now" from Failed restarts the two-try budget (one deliberate press per cycle, no unattended loop). This reverses triad repair 1's "counts as the current try" (OC-3 row, glossary `RetryNow`, UX-51, Story 1.7 AC, Tasks 1.7a/1.7c, ADR-001 decision 14, X-51, I-E1-16 renamed). UX-69, X-69. | ux.md 2.1, 2.2 step 5; the two-quick-presses risk is the lens finding |
| 8 UX-04 lacks the Paused live-region announcement | UX-04 amended and X-04b added: one polite announcement per entry into Paused or Failed ("Sync paused. Retrying automatically."), not per retry tick; Tasks 1.7a/1.7b. | ux.md 2.4 already described it; now a criterion |
| 9 contrast and sizes UNVERIFIED | Recorded in ux.md section 10 ("Verification status"): they stay UNVERIFIED until Task 2.5b (and 1.7b, 3.6b for their rows) runs X-16 `ToolbarContrastTest`, X-17 `ToolbarLayoutBoundsTest`, X-22 `ZoomControlsTest`, X-49 `ThemeContrastTest`, X-31 `CalibrationPillTest` and X-04 (warning pair). No new claim of measured values is made. | validation.md rows |
| 10 camera copy, flash-cycle minors | Rejected as a change: UX-42, UX-44 and UX-57 already cover lens failure, permission states and the no-flash cycle; the loading and all-failed screens deliberately share the "Camera isn't available" copy. | ux.md 8.3 |
| 11 no in-editor hint that voice is after Back | UX-70: the idle strip says "Voice notes: go Back to use the mic" the first three opens (Settings counter `annotator_voice_hint_count`, lowest strip priority); Task 2.5c, `VoiceHintTest`; OC-12 row amended. | ux.md 3.1 |
| 12 UX-47 automates only presentation-model states | Rejected as a change: visual exits (scrim, Back) stay a one-time phone walk, as UX-47 already says; the new Back order is covered by X-64. | UX-47 text |

#### Engineering gaps

| Gap | Change (or reason rejected) | Evidence |
|-----|-----------------------------|----------|
| 1 Task 1.5a says 3 hand-written files, Files lists 5 | Text now says exactly 5 (two production, three test) and the L class is re-justified: it rests on the single-file extraction with three `return@withContext` rebinds, not on file count; it is also at the five-file cap, so no scope may be added. The "counting rule" paragraph names it. | `GitSyncService.kt:429`, `:456`, `:464` are `return@withContext` (grep this pass) |
| 3 wave placement is prose only | New **wave placement table** (task id, wave, lane, depends-on; 107 rows plus 9 `INT` rows) and a script check (above): one row per task, set-equal to the headings, no dependency in a later wave. | script output above |
| 4 per-wave registration integrator uncounted and unowned | 9 owned `INT-W1`..`INT-W9` XS steps, one per wave, run by a dedicated worker after the wave's last task, editing only the registries; counted in the effort table (180k raw, 0.27M CU) and the per-epic table. Not tasks (107 unchanged). | Registration rule paragraph; effort table |
| 5 Task 2.6c missing from the Screen lane order | Lane line now `2.3e -> 2.4c -> 2.5a -> 2.5b -> 2.6a -> 2.6c -> 3.6d` with the note that 2.6c edits only the shortcut map (W8, after 2.6a); the wave table encodes it. | `AnnotationToolbar.kt` listed in 2.3e, 2.5b, 2.6a, 2.6c |
| 6 Task 1.2d1 default implementations must be fail-closed | Stated in the task: defaults on `GitRepository` (`GitRepository.kt:20`; unported implementers iOS, wasm, `StubGitRepository` in `CT/git/testsupport/StubGitRepository.kt`) return `OwnerUnknown(RecoveryLockUnsupported)` from `beginOwnedOperation` (never `NoLock`) and refuse `forceRemoveLock`; test `GitRepositoryDefaultsFailClosedTest` (U-E1-47). Test doubles needing `NoLock` get an injectable verdict in `GateAssertingGitRepository` (Task 1.3d4) rather than a weaker production default. Files now five. | the files exist as cited |
| 7 1.3d1..d4 mid-series state is non-working | Risk Control gets a "series that must not be split" bullet: one PR, no bisect, cherry-pick or release between 1.3d1 and 1.3d2 (null `repoOpGate` means no `GitSyncService` can be built), bisect must skip the commits between 1.3d1 and 1.3d4; sentence added to Task 1.3d1. | Task 1.3d1 text ("no fallback") |
| 8 E4 4.5a1 depends on E3 3.2c2 | One-line PR-order note under Owner confirmations and in Risk Control: E4's PR merges after the E3 PR containing sidecar Task 3.2c2. | Sidecar lane in the Dependency Visualization |
| 2 review artifacts describe the 89-task plan | A scope note at the top of `architecture-review.md`, `consistency-review.md` and `pre-mortem.md`: they cover the 89-task baseline; the 18 split tasks and later additions are covered by this Repair Log and `validation.md` only. The reviews are not rewritten. | those files' headers |

#### Rejected, remapped or partially accepted (triad repair 2)

- UX gap 10 and 12: no change (reasons above).
- UX gap 5: the extension of the editor to Area and Angle is **not** built; it is the owner's call (OC-19), because it changes the stored value model.
- No scope was cut: every story, task and criterion that existed still exists; this pass adds criteria, tests and one cost line.

#### New owner confirmations (defaults proposed; none blocks CI-green work)

- **OC-19** (new): value editor opens for Distance only; Area and Angle values are not editable; the editor's unit chip is an entry unit only. Default as stated. If no: extend to Area and Angle, which adds a value kind to ADR-002's `MeasurementValue` and one editor variant per kind.
- OC-3 changed (not new): Retry now no longer consumes a retry; "Sync now" from Failed restarts the budget. Default as stated; if no, one line in `PausedRetryPolicy`.
- OC-12 amended: the editor shows a three-time hint that voice is available after Back.
