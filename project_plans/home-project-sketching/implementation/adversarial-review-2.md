# Adversarial review 2: home-project-sketching plan (fresh eyes)

Reviewed: `requirements.md`, `implementation/plan.md`, `decisions/ADR-001..009`, `research/*.md`, then the code the plan cites. The prior reviews (`architecture-review.md`, `adversarial-review.md`) were opened only after the findings below were formed (section "Comparison with prior reviews"). `plan.md` was not edited.
Labels: VERIFIED = opened a file or ran a command; INFERRED = reasoned from what was opened.

**Verdict: BLOCKED** (3 BLOCKER, 8 MAJOR, 7 MINOR).

Mechanical checks (VERIFIED):
- A grep for the address and household-name tokens over the project directory returned nothing (empty output, exit 1). Privacy is nonetheless NOT clean; see M6.
- A script over plan Section 9 deps vs Section 10c waves found zero wave violations (every listed dependency sits in a strictly earlier wave). The listed dependencies are incomplete, however; see B1 and M4.
- Story count 65, per-epic raw-token sums (110/685/500/740/630/1295/1055/110k = 5,125k) and the 9.0M CU figure recompute correctly.
- Critical-path depths recompute: 5.7 = 9 waves, 7.1 = 10, 6.6 = 11, 3.8 = 8.

---

## BLOCKER

### B1. Fixtures are consumed in W0-W2 but produced in W3, and OD-H1 is treated as gating only Story 1.5
- Evidence: Story 4.1a (W0) must run "on the real sanitised `closet-elevation.svg`". Story 4.1b (W1) uses `FIX` SVGs, 4.2 (W2) asserts scales 11.0638 and 34.2289 "from the closet SVGs", 5.3a (W0) asserts "the closet fixture parses to the expected paths". The `FIX` files are created by Story 1.5a, which sits in W3 and depends on 1.4 (W2). None of 4.1a, 4.1b, 4.2, 5.3a, 5.2, 4.3, 4.8 lists 1.5 as a dependency. Section 8 says OD-H1 blocks "Story 1.5 (can start with synthetic numbers) and the CLAIM of success metric 1". That understates it: with a synthetic SVG, the ACs of 4.2, 4.3, 4.8 (exact scale 11.0638, five zone labels bind at confidence >= 0.8, widths 136 / 43 5/16 / 40 7/8, shelf pitch 41/5) are meaningless, and the plan has no synthetic CadQuery-style SVG generator.
- Why it matters: W0 cannot be executed as written, and the owner decision that unblocks half of E4/E5 is not on the critical path list. Running the sanitiser also reads the owner's private files, which needs the owner's explicit go.
- Fix: split 1.5a (sanitiser, manifest, fixture files; needs only 0.1 and 0.2) into its own story in W1, and add it as a dependency of 4.1a/4.1b/4.2/4.3/4.8/5.2/5.3a/5.3b. Make OD-H1 a blocker of all of them, and add a "synthetic generator" story (or state that metric 1 and the E4 ACs are unclaimed) for the OD-H1 = "no" branch. Add "what happens to 4.2/4.3/4.8 if OD-H1 = no" to Section 8.

### B2. No story creates a project or binds its sources, so the post-sync hook (root of unit U-A) has no input
- Evidence: grep of `plan.md` for project creation, "source pages", pickers and setup finds nothing but the LLM provider picker. `sketch_projects` is written by `saveSketchProject` (2.2) but nothing calls it from a user flow. Story 5.3b says the yaml comes "from the snapshot in the sidecar (OD-H2)", Story 2.5 says "a picked `closet.yaml` is stored as `sources/yaml-<hash8>.yaml`", but no story builds the file-picker UI, the snapshot step, the list of wiki pages that supply table/prose sources, the list of drawing sheets in a project, or the UI that flips `digestEnabled`/`analyseAfterSync` (5.0 is a settings class only). Story 5.8's `ProjectSourceCollector` is only tested over `FIX` files; in production it has no way to know which pages to read. Story 5.7's relevance hash is "tables, yaml snapshot, svg refs" of a project that nothing can define.
- Why it matters: success metric 3 ("on wiki sync ... regenerated for any project whose sources changed") is not deliverable by any set of stories, and the U-A reachability root `PostSyncProjectJob` would be a no-op in the shipped app.
- Fix: add an E5 story "Project definition and source binding" (create project with generated slug and display name, choose table pages by page name stored only in the local sidecar `refTable`, pick yaml and snapshot it, attach drawing sheets, settings UI for `digestEnabled`/`analyseAfterSync`), before 5.8, and size it M. Decide where page names live: they are page titles, so keep them out of `settings_json` and out of any LLM payload (the registry `refTable` path already does that).

### B3. The "drawings never reach the cloud pipeline" guarantee is bypassable: backfill scan, MIME-only classification and hash dedupe
- Evidence (VERIFIED in code):
  - `AssetIndexService.walkFiles` recursively scans `<notesPath>/assets/`, and `buildAssetEntry` classifies by MIME only (`AssetMediaType.fromMimeType`; `image/svg+xml` and `image/png` become `IMAGE`). `CloudClaudePlugin.canProcess` accepts exactly `AssetMediaType.IMAGE`.
  - ADR-005 / Story 2.5 put the sidecar, the yaml snapshot and (Story 3.7) exported SVGs under `<graph>/assets/stelekit-sketch/<slug>/`, which is inside that scanned tree. The backfill will register them as `IMAGE`/`DOCUMENT`/`FILE` rows, and with cloud consent granted the SVG/PNG rows become eligible for upload.
  - `registerAsset` is "idempotent on content hash" and returns the existing row. A drawing whose bytes were already indexed (backfill, a normal photo attach, `ImageImportService`) comes back as `IMAGE`, so the `DRAWING` class from Story 4.4 is never applied. Story 4.5's PNG path also goes through `ImageAnnotation`/`ImageImportService`, which registers images as `IMAGE` before the adapter runs.
  - `DrawingExcludedFromCloudTest` (4.4) registers a fresh PNG and SVG through `registerAsset` only, so it passes while these paths leak.
- Why it matters: requirements Security classification: "imported drawings are excluded from the cloud image-description pipeline"; the data is confidential home layout.
- Fix: exclude by identity, not by registration order: (a) `CloudClaudePlugin.canProcess` and the ML queue deny any asset whose path is under `assets/stelekit-sketch/` or whose `uuid`/`content_hash` appears in `sketch_sheets.source_asset_uuid`; (b) `registerAsset` upgrades an existing row to `DRAWING` instead of returning it; (c) tests for backfill over a tree containing the sidecar dir, a pre-indexed duplicate, and the 4.5 PNG import; (d) extend `CloudStructuralAuditTest` so the denial is structural. Task 4.4a can also close its open question: `asset_index.media_type` is `TEXT NOT NULL` with no CHECK (`SteleDatabase.sq:1147`, VERIFIED), so no migration is needed for a new value.

---

## MAJOR

### M1. `replaceOwnedPage` touches about 12 implementors and the plan's contract names types that do not exist
- Evidence: `TargetWriter` is implemented in production by `ActiveTargetWriter`, `MarkdownTargetWriter`, `RoutedTargetWriter`, `capture/ActiveWriteHooks.kt:RefusingWriter`, and in tests by at least seven fakes (`CopyFlowControllerTest`, `PullCopyFlowControllerTest`, `PullCopyFlowTest`, `PageMergeServiceTest` x2, `TargetWriterRouterInFlightSwitchTest`, `DbRestoreLockStressTest`, `MergeResilienceTest`); two more use `TargetWriter by d`. Story 5.4a lists only `TargetWriter.kt`, `OwnedFileGuard.kt`, `MarkdownTargetWriter.kt`, so as written the integration branch does not compile between 5.4a (W1) and 5.4b (W2) unless the new method has a default body. The contract says `Either<WriteRefusal, WriteOutcome>` with `Replaced` and refusals `NotOwned`/`EditedByUser`; the real interface is `Either<DomainError, WriteOutcome>` with `WriteOutcome.{Created,Updated,Unchanged}` and refusals carried as `DomainError.MergeError.WriteRefused(WriteRefusedReason)`. There is no `WriteRefusal` type.
- Fix: rewrite the 5.4a contract against the real types (new `WriteRefusedReason.NotOwned/EditedByUser`, reuse `Updated` or add `Replaced` and update exhaustive `when`s), give the interface method a default `Unsupported` refusal so 5.4a compiles alone, and list every file touched. Merge 5.4a+5.4b into one story or order them so each is CI-green.

### M2. The atomic-write helper in `ImageSidecarManager` that Story 2.5 and ADR-005 "generalise" does not exist
- Evidence: `ImageSidecarManager.writeSidecar` is `fileSystem.writeFile(path, json)` with no temp file or rename (VERIFIED, lines 36-62). The atomic logic is in `MarkdownTargetWriter` (temp file + `FileSystem.replaceFileAtomically` when `supportsAtomicReplace`, else `renameFile`, which never overwrites). `FileSystem.supportsAtomicReplace`/`replaceFileAtomically` default to `false` (VERIFIED, `platform/FileSystem.kt:129-132`), so on SAF-backed Android a torn write of a git-synced authoritative file is possible.
- Fix: replace the premise with "extract the temp+replace logic from `MarkdownTargetWriter` into a shared `AtomicFileWriter`" (and size 2.5b accordingly), specify the fallback when atomic replace is unsupported (write temp, verify read-back hash, then replace), and add a torn-write test with `FakeFileSystem`.

### M3. Active-graph `replaceOwnedPage` is under-analysed: property round-trip, hash timing, and a missing replace path
- Evidence: `ActiveTargetWriter.write` refuses any merged page that alters an existing block (`ExistingContentChanged`), so replace needs a new delete-old-blocks / save-new-blocks / roll-back path through `DatabaseWriteActor` plus `GraphWriter.savePage`. The same file renders with `pageRow.copy(properties = emptyMap())` because "the serializer writes page properties as bare lines that the loader reads back as a block, not as properties" (comment, VERIFIED). The owned marker (`stelekit-owned::`, `stelekit-digest::`, `stelekit-digest-hash::`) is specified as page properties, and extractors skip "pages whose page properties include `stelekit-digest::`" (ADR-003, Stories 1.4, 5.2). On the active graph the marker may be re-read as a first block, not a page property, so page-level self-exclusion and the hash-guard read-back can fail only on the active path. The hash is also "sha256 of the body" while the stored bytes are produced by the serializer, so the hash must be computed on the exact serialized output.
- Why it matters: this is the writer for the common case (the active graph); the spike (5.1) comes after ADR-006 is marked ACCEPTED and after sizes were fixed (5.4b = M).
- Fix: add to Story 5.1 explicit probes: (a) marker survives DB round-trip as a page property on the active writer, (b) body hash computed from `LogseqPageSerializer` output equals bytes on disk, (c) delete+insert replace with rollback. Re-size 5.4b after the spike (likely L), and make both extractor exclusion paths tolerant of marker-as-first-block.

### M4. Merge-unit labels contradict dependencies, and the unit design misreads the reachability scan
- Evidence (labels): Story 4.5 is tagged DIRECT to main but depends on 4.4 (U-B) and 6.2b; it references the `DRAWING` class created in 4.4, which will not be on main. 6.8 (DIRECT) depends on 2.3 (U-A), 3.3 (U-B) and, by its own AC, 4.4 (not listed). 6.11 (DIRECT) depends on 3.8 (U-B); 7.1 depends on U-A and U-B. These cannot merge to main before the units they depend on.
- Evidence (scan): `scripts/ci/reachability-scan.py` `live_names()` marks a type live if its name appears in ANY other production file (plus same-file propagation); it is a name-mention check, not reachability from a root. Only the topmost type of a chain (for example `PostSyncProjectJob`) fails. So the plan's blanket rule "pure code is never merged ahead of its first production reference" and the 27-story U-A single integration PR (about 30% of all stories, spanning 10+ waves) are far stricter than the CI gate requires, and delay all feedback from `diff-coverage`/mutation and CI to one end-of-project PR. Section 17 also says each DIRECT PR must exit 0 on the scan, which holds trivially for most.
- Fix: relabel 4.5/6.8/6.11/7.1 as "after U-A/U-B"; merge leaf stories (units, fusion, parser, repositories) to main in small PRs where the scan passes, and reserve the integration branch for the few root types that the scan would actually flag. Verify with `reachability-scan.py` on a branch before committing to the large-unit design.

### M5. `DomainLayerAuditTest` forbids `Double`/`Float` in `CM/units`, but Story 4.5 puts a Double-consuming converter there
- Evidence: Story 1.1 audit fails on `\b(Double|Float)\b|toDouble|toFloat` in `CM/units` and `CM/fusion` with "no allowlist". Story 4.5 adds `CM/units/CalibratedLength.kt`, "a pure function" converting a calibrated pixel measure to `Inches`/`Rational`. `Calibration.pixelsPerMeter` and `MeasurementAnnotation.valueMeters` are `Double` (codebase.md section 1, VERIFIED in model files by the research). The converter must accept a `Double`.
- Fix: move `CalibratedLength.kt` to `CM/sketchimport/` (not audited), or define the boundary: the converter takes `Long` micro-metres from a caller that does the Double rounding.

### M6. Privacy: the committed research files still contain real wiki page titles and other-project content, and the guard cannot catch either
- Evidence (details withheld here to keep this file public-safe): the research summary then listed several real wiki page titles and a dangling wiki link, described four other private home projects with real room dimensions and quantities, quoted near-verbatim page prose and cost figures, and named the source script, a data file and an agent worktree path. The directory was still untracked, so nothing was published yet.
- Guard gap: Story 0.2's CI patterns are structural (addresses, home paths, emails). Page titles and names come only from a local denylist that never runs in CI, so the guard could not fail on such a file (VERIFIED by reading the Story 0.2 AC; the token grep matched nothing because the leaked items were titles, not the tokens searched).
- Fix: scrub the flagged lines of the research summary (replace titles with `<page A>` roles, drop other-project values), keep the directory out of any commit until scrubbed, and add the real page-title tokens to the local denylist so the local run of `private-string-guard.py` fails first. Add a short pre-commit hook (or `make ready` step) that refuses to run without the denylist present.

### M7. ADRs are marked ACCEPTED while the owner decisions they depend on are pending
- Evidence: ADR-006 `Status: ACCEPTED` states it adds an exception to CLAUDE.md "never re-render an existing file", yet OD-H7 (the approval of that exception) is open and Section 8 lets work proceed up to "Merge of 5.4a". ADR-007 is `ACCEPTED` while OD-H4 (authorising the opt-in) is open. CLAUDE.md is the owner's rule file; the plan edits it (Story 5.4a, file list includes `CLAUDE.md`).
- Fix: set ADR-006/ADR-007 to PROPOSED until OD-H7/OD-H4 resolve; make 5.4a's CLAUDE.md edit a separate owner-approved commit, and state that 5.4a-5.4c, 5.7 and 7.1 are blocked on OD-H7 (not only the merge).

### M8. Metric 4 "Library parity" is satisfiable by deletion alone
- Evidence: `requirements.md` metric 4: Library parity = `rg 'GalleryViewModel|AssetBrowserViewModel|AssetItemCard' kmp androidApp` returns nothing. That passes if the classes are deleted without any parity. The parity table (D.6) is mentioned only inside Story 6.7's AC.
- Fix: make the metric "D.6 parity table all green (named tests) AND the rg returns nothing", and cite the test names.

---

## MINOR

1. Wrong paths in the plan: `PerFeatureProviderPicker.kt` and `LlmProviderSettings.kt` live in `ui/components/settings/` (Story 5.6a says `ui/settings/`); `AssetMediaType` is in `asset/AssetModels.kt` (Story 4.4 says `asset/AssetMediaType.kt`); `ui/placement/` and `ui/library/` do not exist on main (the latter arrives with PR #565). Verified by `ls`.
2. Owner-decision treatment: OD-H5 is listed as blocking "Story 7.2 delivery", but 7.2 is a report-only W0 story and its AC cites "what the shipped tests prove" (tests that exist only in W3-W4); either move 7.2 to after 5.8 or drop the claim. OD-R1 and OD-A1 are correctly modelled as gates on mutating steps only (6.13 steps 1-3 are read-only). OD-H3 is redundant if 5.0 sets a default title. Missing decision: whether the synced sidecar's `refTable` (human locators, including page titles) may live in the wiki remote; add OD-H8 (default: allowed only because the remote is the owner's private repo; confirm).
3. Story 5.7 AC "digest writes for a graph that is not active go through the off-graph writer" is unreachable from the hook: `GraphContentGitSyncSetup` is composed per active graph (`remember` keyed by active graph id), so the hook only fires for the active graph. Keep the off-graph test at the router level.
4. Sizing: Story 4.4 lists 9 files and 2.3 lists 7 while Section 16 states the rule "more than 5 files" splits a story; they carry task-level splits only. 6.7 (S) deletes 6 classes from a +4390/-130, 67-file PR and runs a parity table; likely M. 5.6a lists 6 files but the text says "about nine".
5. NFR mismatch: `requirements.md` says "sketch canvas >= 50 fps median on emulator-class device" while the plan budget is `frameP95Ms <= 24` and a relative ratio; the requirement should be rewritten to the relative form in `requirements.md`.
6. `CT/fusion/FusionModelGenerators.kt` (Story 1.6) is a commonTest helper used by businessTest but is not in R-REG's `business_common_tests` srcs rule (precedent: `LfsArbs.kt` is listed explicitly).
7. The generated slug is `proj-<6 hex>` (Story 2.1, gate regex), but fixtures use `proj-closet-1`; make the slug validator accept the fixture form or generate the fixture slug as hex, otherwise 5.5b "any project identifier other than the slug set" may reject its own golden.

---

## Specific checks requested

- Parallel stories on the same files: the declared hazards (R-HAZARD) are credible. Not declared: 5.4a and 5.4b both edit `TargetWriterContractSuite`/`MergeUndo` (serialised by wave, OK); 4.4 edits `AssetIndexService.kt`/`CloudClaudePlugin.kt`, which PR #565 also touches (Library media class) and which the plan only calls "rebase"; 6.4b (C.5 serialized persistence) likely edits `DatabaseWriteActor.kt`/`SqlDelightImageAnnotationRepository.kt` while 2.2 (W1) and 6.1 (W0) edit the same actor/repository files; schedule 6.4b after 2.2 or declare the hazard.
- LlmSettings/LlmProvider null=Auto semantics: confirmed. `getSelectedProviderId` returns null for a blank value (`LlmSettings.kt:31-32`), `LlmSynthesisService` maps null to `firstOrNull()` (Auto) and only the explicit `DISABLED_SENTINEL` disables. The plan's "unset = disabled for this feature" is implementable, but `PerFeatureProviderPicker` also offers the Auto option for every `LlmFeature` entry, so 5.6a must hide Auto for `PROJECT_ANALYSIS` (not stated in the AC).
- `GitSyncCoordinator.syncState` exists (`ui/GitSyncCoordinator.kt`, derived from the active `GitSyncService.syncState`, `SyncState.Success.lastSyncAt` exists), so the hook design in 5.7 is feasible, with the caveat in MINOR 3. `SyncState.Success` is also what the Android worker would produce only if its service is composed; the plan correctly makes no claim for the worker.
- AllBusinessTests / `business_common_tests`: confirmed that `business_tests` globs `**/*.kt` (businessTest classes need no BUILD edit) and that `business_common_tests` uses an explicit `srcs` list; the R-REG rule is accurate. `AllBusinessTestsCompletenessTest` enforces registration (VERIFIED).
- SteleDatabase.sq + MigrationRunner: `AddColumn` op exists (`MigrationRunner.kt`), so 6.2a is feasible; `MigrationRunnerSchemaSyncTest` covers tables only, as the plan says.
- Success metrics falsifiability: 1, 2, 3 are falsifiable as reworded (hand-written constants, relative frame budget with mutation probe, stubbed-model golden prompt). 4 is weak for Library parity (M8). Metric 1 is explicitly unclaimed until OD-H1 is "yes" (good).

## Comparison with prior reviews

Prior findings that are correctly resolved in the current plan: private-data scrub of ADRs/plan/requirements (V-B1; but see M6 for the research file), writer sizing (V-B2), registry/collector for metric 1 (V-B3), LLM null = Auto (V-M1/A-M4), wave table recomputation (V-M4; no violations found), Bazel fixture bundling (V-M5).
New relative to both prior reviews: B1 (fixture ordering; prior review accepted "can start with synthetic numbers"), B2 (missing project/source-binding story; neither review asked who creates a project), B3 (asset backfill and hash-dedupe bypass; V-M2 was resolved only for the registration path), M1 (implementor count and non-existent contract types), M2 (non-existent atomic helper), M3 (active-path property round-trip), M4 (reachability misread plus mislabelled DIRECT stories), M5 (Double ban vs 4.5), M6 residual research-file leaks, M7 (ADR status), M8 (metric 4 gaming).

## Top 3
1. B1: fixtures and OD-H1 are needed from W0 but produced in W3; the plan cannot execute its own first wave.
2. B2: no story defines projects or binds their sources, so the post-sync hook and metric 3 have nothing to run on.
3. B3: drawings, sidecars and exported SVGs sit inside the scanned `assets/` tree and are classified by MIME with hash-dedupe, so the cloud-exclusion guarantee is bypassable.
