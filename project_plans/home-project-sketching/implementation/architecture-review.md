# Architecture review: home-project-sketching plan

> **Resolution status (2026-11-09):** every BLOCKER and MAJOR finding below, and every MINOR, is resolved in `plan.md` and the ADRs; finding ids in that file's "Review resolution" table use the prefix `A-`. Findings text below is the original review (sanitised of private names).

Reviewed: `implementation/plan.md` and `decisions/ADR-001..009` against `main` (9db1a8d58e) and `CLAUDE.md`. All code references below were opened in this review (VERIFIED) unless marked INFERRED.

**Verdict: BLOCKED** (2 BLOCKER, 9 MAJOR, 8 MINOR). Both blockers are plan-mechanics defects that make the wave plan unexecutable as written (CI gates), not design flaws. They are cheap to fix in the plan.

## BLOCKER

### B1. Wave plan lands pure code with no production consumer; the CI `reachability` job fails
- Evidence: `.github/workflows/ci.yml:181-205` runs `scripts/ci/reachability-scan.py --check-baseline scripts/ci/reachability-baseline.txt`. The script (header, lines 1-12) lists production types "referenced from no other production file" (tests excluded) and exits 1 on any type not in the baseline. CLAUDE.md: the baseline "shrinks", it does not grow.
- Plan impact: W0-W3 deliver `FusionEngine`, `LinearSolver`, `LengthParser` (consumed only by tests until 5.2/5.4), `SnapEngine`, `SketchCsvWriter`, `DigestRenderer`, `OutboundLlmGate`, `ProjectAnalysisService`, `PostSyncProjectJob`, `SvgReader`/`LabelBinder` (until 4.4), as independently mergeable stories. Each is a candidate until a production file mentions it. The plan never mentions this gate (it only discusses wiring allowlist and catalog).
- Fix: (a) state a merge rule in section 10: every story's PR must add its first production reference (for E1, a thin `ProjectFusionService` in `CM/projectbridge/` that 1.4 introduces and 5.4/3.7 later consume), or (b) stack E1-E5 on one integration branch and merge as a unit, or (c) add dated, shrinking baseline lines per story (worst; the file is meant to shrink). Add `scripts/ci/reachability-scan.py --check-baseline ...` to the section 17 checklist.

### B2. Test registration is mis-specified: new tests will compile but never run under `bazel test //kmp:business_tests`
- Evidence:
  - Plan section 13 says "New businessTest/commonTest classes need no Bazel edit if the target globs (verify)".
  - `kmp/src/businessTest/kotlin/BUILD.bazel:135-137`: `business_tests` runs `test_class = "dev.stapler.stelekit.AllBusinessTests"`, a JUnit `@Suite.SuiteClasses` list. `AllBusinessTestsCompletenessTest` fails if a businessTest `*Test.kt` is not listed. So all 26 businessTest classes in section 13 need `AllBusinessTests` entries.
  - `kmp/src/commonTest/kotlin/BUILD.bazel:105-` `business_common_tests` has an **explicit `srcs` list** (not a glob). The 10 commonTest classes in section 13 (`InchesPropertyTest`, `LengthParserGoldenTest`, `LinearSolverTest`, `FusionEngineTest`, `SnapEnginePropertyTest`, `SketchHistoryTest`, ...) run in business_tests only if added there **and** to `AllBusinessTests`. Otherwise they run only under `jvm_tests`, which needs a display and has no completeness guard. The plan's "business_tests green" checkpoint would pass vacuously for the whole of E1/E3.
  - Fixtures (Story 1.5): `kmp/src/businessTest/resources/BUILD.bazel` is `glob(["*.txt"])` only. `home-sketch/closet/*.svg|yaml|json|md|` are invisible under Bazel. A new `java_library(resources=..., resource_strip_prefix=...)` plus a `business_tests` dep is required (pattern: `business_test_data_as_resources`, BUILD.bazel:111-115). The plan's "filegroup entry ... (or the businessTest resources target)" is not sufficient.
- Fix: add a task to each of 1.1-1.6, 3.1-3.3, 4.1 for the three edits (`business_common_tests` srcs, `AllBusinessTests` list, fixture `java_library`); add a Phase-6 check that counts the new classes in a `bazel test //kmp:business_tests` result. Prefer putting pure-logic tests in `commonTest` only if you also do the srcs edit; otherwise put them in `businessTest` (one registration, still runs on JVM). Property tests in commonTest are still the right home for wasmJs coverage per CLAUDE.md.

## MAJOR

### M1. Wiring catalog and allowlist instructions are wrong and would fail `WiringCompletenessTest` / `ObservableEffectCatalogTest`
- `WiringAudit.SEAMS` is the fixed list `BackgroundSyncScheduler, AssetPipelinePlugin, ConsoleCommand, MeasurementDeviceFactory, WaybackMachineService, CameraProvider` (`businessTest/.../wiring/WiringAudit.kt:18-25`). `WiringCatalog.check` rejects any catalog line whose seam is not in it: `catalog line names an unknown seam` (WiringCatalog.kt:34). None of the new classes implements a seam.
- Plan Stories 2.4 and 5.7 and section 12 instruct adding `wiring-catalog.txt` / `wiring-allowlist.txt` lines for "each new seam". Adding them breaks the gate; not adding them is correct.
- Fix: delete those instructions. If an entry-point test for the digest/sketch flow is wanted, it belongs in `first-open-screens.txt` (3.3, already there) and in a normal integration test, not the wiring catalog. Only if you deliberately add a new seam interface (for example `ProjectSourceExtractor`) extend `SEAMS`, add the construction to a `GraphContent*Setup` root, and add the catalog line with a real `WiredAppFixture` test; the plan should say it chose not to.

### M2. Post-sync hook (5.7) assumes data `SyncState.Success` does not carry, and mis-places the coupling
- `SyncState.Success` has `localCommitsMade, remoteCommitsMerged, lastSyncAt, autoMergedPaths` only (`git/model/SyncState.kt:32-37`). `reloadPaths` is a local `val` inside `GitSyncService.sync` (GitSyncService.kt:453), not state. Story 5.7's "check whether `autoMergedPaths`/`reloadPaths` touch any project source" cannot be implemented without widening `SyncState.Success` (touches every `when` over it) or editing the sync pipeline. `autoMergedPaths` omits non-conflicting fast-forward changes, which are the common case.
- Plan lists `CM/git/GitSyncService.kt (hook)` as a modified file. `GitSyncService` is the sync pipeline (git package); a project-analysis callback in it inverts the dependency (git -> projectbridge).
- Android: `GitSyncWorker` obtains the service from `GitSyncServiceRegistry.getService(graphId)` and otherwise does a standalone fetch without a service (`androidMain/.../WorkManagerSyncScheduler.kt:143,164`). When the app is not running there is no `syncState` collector, so "Android GitSyncWorker path also fires" is false for background syncs.
- Fix: keep `GitSyncService` untouched. Subscribe in a composition root (`GraphContentGitSyncSetup.kt`, where `registerGitSyncService` already happens, line ~106) to `GitSyncCoordinator.syncState` (`ui/GitSyncCoordinator.kt:93`, already flatMapLatest over the active service), react to `Success` with a distinct `lastSyncAt`, and **decide relevance by content, not by path lists**: `PostSyncProjectJob` computes a hash of the project's source inputs (tables/yaml/svg refs) against the last-digested hash stored in `sketch_projects.settings_json`. This works for fast-forward changes, for background syncs that landed while the app was closed (run at graph open), and removes any `SyncState` change.
- Also record the feedback loop: the digest page write dirties the git tree; the next sync commits it. That is acceptable only because the write is byte-idempotent (AC in 5.4), so add an AC that a sync followed by the hook followed by a second sync produces zero new local commits when sources are unchanged.

### M3. Digest write path (ADR-006 option C, Story 5.4) conflicts with the `TargetWriter` contract and ADR text
- `TargetWriter.write(page, merged)` documents "only blocks absent from the file are inserted" and `ActiveTargetWriter`/`MarkdownTargetWriter` implement merge-insert semantics; there is no replace operation (`merge/TargetWriter.kt`, `MarkdownSplicer.kt` header: "only adds lines"). `RoundTripGuard.verifyInserted` requires "original tree plus exactly the requested blocks as last children" (RoundTripGuard.kt:~107).
- ADR-006 option C says "rewrite whole page via `GraphWriter`; off-graph: create-once, replace-whole-file only if the file has our page marker". That is a **third write path** that is neither the router's writers nor `GraphWriter`'s block save; it also conflicts with plan 5.4's "writes through `TargetWriterRouter.withWriter`". CLAUDE.md allows markdown writes of the active graph only via `GraphWriter`, and cross-graph only via the router. The digest is a same-graph write (project in the active graph), so the router's `GraphWriteLock`/cross-graph machinery is not obviously required.
- Fix: decide explicitly and write it in ADR-006 before 5.4: for the active graph use `GraphWriter.savePage`-style whole-page save of a page the app owns (page property `stelekit-digest-page:: true`) via the existing page-save path; for an inactive graph, extend `TargetWriter` with a marker-guarded `replaceOwnedPage(page, expectedMarker)` implemented in `MarkdownTargetWriter` with a hash precondition, and add it to `TargetWriterContractSuite` for both writers. Drop the claim that this is "via TargetWriterRouter.withWriter" for the same-graph case unless 5.1 shows `ActiveTargetWriter` can express replace. Story 5.1's spike should answer this narrower question (which path, which lock) rather than only "can we update our own block".

### M4. LLM default is not off: unset `LlmSettings` means "Auto", not disabled
- `LlmSettings.getSelectedProviderId` returns `null` when unset, and `null` means "Auto" (first eligible provider) (`llm/LlmSettings.kt:29-30`, `LlmSynthesisService.kt:46-49`). `DISABLED_SENTINEL` is only honoured if it was explicitly stored. ADR-007 and plan section 7 state "`DISABLED_SENTINEL` = off, the default". For a new `PROJECT_ANALYSIS` feature, unset resolves to Auto and would send the digest to a remote provider as soon as the post-sync opt-in is toggled, without the user ever choosing a provider for this feature.
- Fix: `ProjectAnalysisService` must treat `null` as **disabled** for this feature (opposite of the existing three), documented in a one-line comment, with a test `unsetProviderNeverCalls`. Require an explicit provider id. Prefer `registry.find(id)` and refuse `PlatformOnDevice` + `supportsLongFormOutput == false` as `LlmSynthesisService` does.
- Also: `LlmFeature` additions need the settings picker UI and any `when (feature)` exhaustive sites (INFERRED; grep `LlmFeature` in `ui/` when implementing), and `PendingLlmSuggestion` is a tag-change/new-page type: an analysis answer needs a new variant or a different sink. Plan 5.6 says "results to inbox" but lists neither `LlmSuggestionInbox.kt` nor `PendingLlmSuggestion.kt` as touched.

### M5. `OutboundLlmGate` placement and scope are under-specified
- The gate is a pure check on the final prompt string. The provider surface is `provider.formatter.format(transcript, systemPrompt)` (`LlmFormatterProvider`), a voice-shaped API with no hook point. If the gate lives in `projectbridge/` and the caller must remember to call it, it is advisory.
- Fix: make the gate structural: `ProjectAnalysisService` takes a `GatedPrompt` value (private constructor, produced only by `OutboundLlmGate.check(...)`), and tests assert the service has no overload accepting a raw `String`. Also gate the *user question* and any free text, not only `ref` fields. Page names with the street address (OD-H1 mentions this) are user-controlled strings in dimension keys; the regex `^(table|svg|yaml|prose|user|derived):[A-Za-z0-9 ._#:-]+$` accepts "table:123 Main St Page#Zones". Replace human page names with a slug map and make the gate check against the project's slug set, not a character class.

### M6. Sidecar JSON is "authoritative" but lives in a directory the repo tells users to gitignore
- ADR-005 puts the authoritative copy at `<graph>/.stelekit/projects/<slug>/project-model.json`. `SidecarManager` KDoc: ".stelekit/ ... should NOT be committed to git; add `.stelekit/` to your graph's `.gitignore`". The `.stelekit/images/*.measure.json` precedent (ADR-005 cites it) follows the same ignore convention, so authoritative there means "authoritative over the DB cache on this device", not synced. A sketch drawn on desktop will not reach Android via git sync; the digest page is the only synced artifact and is lossy by design (capped at 2k tokens, no geometry).
- Fix: either (a) state in ADR-005 that sketches are device-local in v1 and the user guide (7.1) says so, or (b) move the sidecar to a non-ignored synced path (a page asset under `assets/`, registered via `AssetIndexService`) and treat `.stelekit/` as cache only. Pick (b) if cross-device sketching is a requirement; check `requirements.md` and the `LfsPathPolicy` path rules for large-file handling.
- Related duplication: Story 3.7's `ProjectSidecarWriter` re-implements what `db/sidecar/ImageSidecarManager.kt` / `ImageSidecarSchema.kt` do for `.measure.json` (schema versioning, atomic write, indexer). Reuse or generalise rather than add a second sidecar stack.

### M7. Sketch editor claims reuse of annotation pieces that are not reusable as written
- ADR-008 and Story 3.6 say non-drag mode "shares the Epic C.8 implementation". C.8 (Story 6.5) is specified against the annotation editor: `AnnotationEditorViewModel` (1,100+ lines), `NormalizedPoint`/`MeasurementAnnotation`, `internal fun hitTestAnnotations(...)` in normalised coordinates (`ui/annotate/AnnotationHitTest.kt`). Nothing in 6.5's AC requires a coordinate-space-agnostic extraction, so 3.6 will either copy it ("no second placement path" fails) or block waiting on a refactor nobody scoped.
- Story 3.1 builds a new `SnapEngine`/hit test (correct, different space). The duplicated part is the placement state machine, crosshair/nudge stepping, touch-slop tap-vs-drag, loupe, and a11y semantics.
- Fix: add to Story 6.5 (or a new 6.5a) an AC: extract `PlacementController<P>` (point type generic, pure, no Compose) and `NudgeStepper` to a neutral package (`ui/placement/`, not `ui/annotate/`); annotate editor and `SketchCanvas` both depend on it; a shared contract test runs against both. Reuse the loupe only if 6.3 makes it coordinate-agnostic; otherwise say sketch has no loupe in v1.
- Dependency note: 3.6 depends on 6.5, which sits at the end of the serial C-lane (6.0 -> 6.2 -> 6.3 -> 6.4 -> 6.5), so the sketch a11y story waits for five carry-over stories. Acceptable only if the plan says so as a critical-path item; currently the critical path in section 10 omits it.

### M8. Schema-lane coupling (ADR-009, 2.1 depends on 6.2) puts a feature behind an unrelated, unaudited story
- `sketch_*` tables are additive and independent of `image_annotations.calibration_basis_version`. The dependency exists only because both edit `MigrationRunner.kt`, `SteleDatabase.sq`, and generated sources. `MigrationRunner.all` is an append-only list; `SteleDatabase.sq` additions are separate blocks; generated sources are deterministic and CI-enforced ("SQLDelight generated sources" job). Merge conflicts are mechanical (rebase, regenerate, rsync).
- Cost: 2.1 cannot start until 6.0 (audit of unknown size) and 6.2 (M, touching `CalibrationService`) merge. That makes the E2 -> E3 -> UI chain a 9-wave path gated on carry-over work and on open PRs, and the ADR's own risk register lists it.
- Fix: invert or decouple: sequence 2.1 first (schema lane is a queue; whoever is first wins) and let 6.2 rebase, or allow both in parallel with a merge-queue rule "second PR regenerates". Keep the serial rule only for *concurrent in-flight branches editing the same generated files*, not as a story dependency. Update the critical path to 2.1 -> 2.2 -> 2.3 -> 3.3 -> 3.4 -> 3.5 -> 3.8 (7 waves).
- Related: CLAUDE.md's migration rule is about `CREATE TABLE IF NOT EXISTS`. C.1 adds an `ALTER TABLE ... ADD COLUMN`, which uses the separate `AddColumn` migration op (`MigrationRunner.kt:44-53`), not the `IF NOT EXISTS` form the plan says "the same way". `MigrationRunnerSchemaSyncTest` covers tables only; add an explicit assertion for the new column.

### M9. No YAML dependency exists; Story 5.3 and the `closet.yaml` flow are unplanned on all platforms
- `kmp/build.gradle.kts` has no YAML library (ktoml exists but is excluded from wasmJs, line ~140); `MODULE.bazel` has none. Story 5.3 and ADR-005 import `closet.yaml`, commonMain, wasmJs-compiled.
- Fix: decide in 5.3: a constrained hand-written parser for the `key: value  # comment` subset the closet file uses (preferred: tiny, pure, commonTest-property-testable, no Bazel/Gradle change), or add a KMP YAML library with a pinned version in both `build.gradle.kts` and `MODULE.bazel` and verify wasmJs. Record the choice in an ADR amendment. The same applies to 4.1's Ksoup XML-mode spike (Ksoup is already in both builds, `MODULE.bazel:282` `ksoup-jvm`), but verify the wasm artifact too since the AC says wasmJs test sources compile.

## MINOR

### m1. Package layout does not follow the repo's layering
- CLAUDE.md layers: UI `ui/`, Repository `repository/`, DB `db/`, Domain `model/`, Parser. The plan adds five top-level packages (`units/`, `fusion/`, `sketch/`, `sketchimport/`, `projectbridge/`) and puts Compose UI under `sketch/ui/` and `sketchimport/ui/`, whereas screens live under `ui/` (`ui/annotate/`, `ui/assets/`, `ui/library/`). `ScreenRouter`/`App.kt` import from `ui/` packages.
- Fix: keep the pure packages (`units`, `fusion`) top-level (they are domain, like `domain/` and `tags/`); place Compose in `ui/sketch/` and `ui/sketchimport/`; keep `SketchViewModel` next to the other screen view models (`ui/sketch/`), not `sketch/`. State the dependency direction in the plan and enforce it with a source-scan test modelled on the `Double` ban: `units` and `fusion` import nothing from `sketch*`, `projectbridge`, `ui`, `db`, `repository`.
- The `Double` ban `rg ': Double' .../{units,fusion}` is weak (misses `toDouble()`, `Float`, generics). Use a businessTest source scan (as `WiringAudit` does) matching `\b(Double|Float)\b|toDouble|toFloat`, with an allowlist for `LengthFormatter` decimal output if needed.

### m2. `JobTracker.begin("project-digest", ...)` does not match the API
- `JobTracker.begin(kind: JobKind, title: String, onCancel)` takes the closed enum `JobKind` (`jobs/BackgroundJobs.kt:9-16`). Add `JobKind.ProjectDigest("Project digest")` in the plan; this also touches any exhaustive `when (JobKind)`. Title must not contain the project name (privacy line in section 6).

### m3. Repository set for `fusion_results` and zones is inconsistent between ADR-005, Story 2.1 and Story 2.3
- ADR-005 says a `fusion_results` JSON column on `sketch_projects`; Story 2.1's column list omits it. Story 2.1 says "constraints are derived from dimensions plus a zones JSON column" but no table lists a zones column. Story 3.5 stores named zones and sum constraints somewhere.
- Fix: one table definition in the plan for `sketch_projects` (include `fusion_results_json`, `source_hash`) and an explicit home for zones (`sketch_sheets.zones_json` or a `sketch_zones` table, with the migration entry if a table).

### m4. `ON DELETE CASCADE` should not be relied on
- `DatabaseOpenDiagnostics` logs `foreign_keys` state precisely because it varies; `LeafFirstBlockWipe.kt` exists because cascades are slow and ordered deletion is used for blocks. Story 2.1 declares "FK cascade" for `sketch_sheets`/`sketch_primitives`; INFERRED that enforcement is off on some drivers.
- Fix: `deleteProject` in Story 2.2 deletes leaf-first inside one actor transaction (primitives, sheets, dimensions, project) and the test asserts no orphan rows with `PRAGMA foreign_keys` both off and on.

### m5. Repository contract details
- Story 2.3 repositories name `SketchProjectRepository`, etc. in one file `SketchRepositories.kt`; repo convention is one interface per file plus `SqlDelight*`/`InMemory*` files. Minor.
- `getSheets(projectUuid)` "bounded by <= 50 sheets, asserted" is a runtime assertion; CLAUDE.md prefers compile-time/limit parameter. Use `getSheets(projectUuid, limit = 50)` and surface `HasMore`.
- `sketch_dimensions` has no `unique(project_uuid, key, source_kind, source_ref)` index; fusion re-import will duplicate rows. Add the unique key and use `INSERT OR REPLACE` via a stub.
- `QueryPlanAuditTest` needs index definitions for `sketch_primitives(sheet_uuid, z)` and `sketch_dimensions(project_uuid)`; the plan lists the audit but not the `CREATE INDEX IF NOT EXISTS` entries in `MigrationRunner.all`.

### m6. ADR status and cross-ADR consistency
- Every ADR is `PROPOSED`; ADR-006 is explicitly unfinished. Plan sections 3 and 8 treat them as decided (for example OD-H3 "Dedicated page (ADR-006 option C)" is a default for an undecided ADR). Mark which are gating (ADR-006, ADR-004's reader choice) and which are decided; add the spike-result amendment tasks to 4.1/5.1 as ACs (already partly there).
- ADR-001 says the PNG sheet reuses `ImageAnnotation` and needs C.1 (6.2); ADR-009 makes 6.2 gate 2.1. Only 4.5 needs 6.2 (plan Story 4.5 deps are correct); see M8.
- ADR-003 "Independence rule" uses `stelekit-digest::` block marker, while ADR-006 option C writes a dedicated page and Story 5.4 says marker "first in the block". Reconcile: for a dedicated page the exclusion must be page-level (page property), and extractors must skip by page, not only by block. Add both to 1.4/5.3.
- ADR-002 (`Inches` Long sixteenths) vs lossy 0.005 precision values (`43.31"`): precision is carried separately, which is fine, but `Observation.value` of a decimal that is not on the 1/16 grid is not representable as `Inches`. The plan should say `Observation.value` is `Rational` (not `Inches`) with `Inches` only for grid-aligned values, or else `43.31` would be rounded at ingestion, defeating the negative control. Make the type explicit in Story 1.4.
- ADR-007 says tests use `FakeLlmProvider (businessTest)`; confirm it exists (grep) or add its creation to Story 5.6.

### m7. wasmJs constraints
- `Inches` as `@JvmInline value class` is fine in commonMain. `CheckedMath` avoiding `Math.*Exact` is correct. `kotlin.time`/`Clock` usage is fine. Plan 3.7 writes files via the graph's file layer; use `platform/FileSystem` (okio on common), not `java.io`.
- `FusionScaleTest` "median of 5 under 1 s" is a real-clock assertion. `TestHygieneAuditTest` bans real-clock `delay`, not timing assertions, but a wall-time assertion is flaky on CI runners; assert algorithmic work counts (pivot operations) in the test and keep wall time as a reported bench metric only. Same pattern the plan already used for 3.1.
- `Dispatchers.Default`/scope rules: `SketchViewModel` and `PostSyncProjectJob` both own scopes; ensure both attach `CoroutineExceptionHandler` (stated) and that the post-sync collector's body is guarded against `Throwable`, per the "Uncaught coroutine Throwables" section.

### m8. Smaller items
- Story 5.2 consumes `TableBlockNode`, whose `rows` are raw `List<List<String>>` (`parsing/ast/BlockNodes.kt:112-119`), so `**`, `[[links]]`, escaped pipes are literal text; fine, but the AC "escaped pipes kept in valueAsWritten" depends on `BlockParser.tryParseTable` unescaping behaviour. Add a characterization test first.
- The sketch route must be added to `ScreenRouter`/`AppState` behind the flag; plan names `AppState.kt` only. Also confirm where feature flags live: the plan says "find via `rg FeatureFlag`" and a grep of `commonMain` finds no `FeatureFlag` type, so this is probably a `Settings` boolean; name it in the plan before 3.3.
- Story 6.1 changes `SteleDatabase.sq` (query at ~1084); if it changes it needs the regeneration and rsync commands, and the W0 table lists it parallel with other stories that might touch the `.sq` (2.1 is later, fine) but conflicts with 6.2 in W1 if both edit `.sq`. State the order 6.1 -> 6.2.

## What is already sound
- Pure-logic placement (units, fusion, scene/snap, SVG reader) in commonMain with property tests, in line with CLAUDE.md.
- Writes via `DatabaseWriteActor` with `@DirectSqlWrite` stubs, `asDbFlowList`, bounded reads, and repo-owned scope rules are all listed.
- Compose gesture tests in `androidUnitTest`, flag default off, first-open-screen registration (`FirstOpenSmokeCatalogTest` exists).
- Tag-escaping fix (6.1) is correctly separated from the sketch repositories.

## Required plan edits before implementation (ordered)
1. Add the reachability merge rule (B1) and the Bazel/AllBusinessTests/fixture tasks (B2).
2. Remove wiring catalog/allowlist lines (M1).
3. Rewrite 5.7 around a content-hash trigger in `GraphContentGitSyncSetup`; leave `GitSyncService` and `SyncState` unchanged (M2).
4. Settle ADR-006 on the real write API and add the replace operation to `TargetWriter` plus contract suite if needed (M3).
5. Make unset `PROJECT_ANALYSIS` provider mean disabled; make the gate structural (M4, M5).
6. Decide sidecar sync semantics (M6) and the YAML parser (M9).
7. Add the `PlacementController` extraction AC to 6.5 (M7); decouple 2.1 from 6.2 (M8).
8. Apply the MINOR items while editing.
