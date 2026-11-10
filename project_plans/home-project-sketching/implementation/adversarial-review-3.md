# Adversarial review, round 3: home-project-sketching

Reviewer stance: fresh eyes. Findings were formed from `requirements.md`, `implementation/plan.md`, `decisions/ADR-001..009` and `research/*.md` BEFORE opening any `*review*.md`. Every code claim below was checked against `main` @ 9db1a8d58e (working tree, paths relative to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/` = `CM/` unless noted). Labels: VERIFIED = opened or run; INFERRED = reasoned.

**Verdict: BLOCKED** (1 BLOCKER, 10 MAJOR, 12 MINOR).

## Mechanical checks (all run, none are findings)

| Check | Result |
|---|---|
| Wave/dependency lint (throwaway script, scratchpad `lint.py`): parsed every `#### Story` `Deps` line, the mermaid edges and the wave table | 75 story headings (74 + conditional 1.5s); every story has a wave; 0 forward/same-wave dependency violations; 0 cycles; mermaid edges equal the `Deps` text for every story (only noise: 1.5s has no edges, and 3.8's `Deps` text mentions 6.9 as a soft fallback). Longest chains reproduce the plan: C-lane 11, 7.1 = 10, 5.7b = 9. |
| Effort recount (script) | Epic rows recompute to 165/740/555/870/630/1,850/1,130/110 = 6,050k raw tokens, 74 stories (3+8+6+9+8+19+19+2). Matches Section 16. |
| Slices | 9 named slices (SL-A,B,C,D,E,F,G,H,I), none over 3 stories, no story in two slices. |
| Privacy grep `private-term grep` over the directory | 0 hits. No street address, phone, email or username found either (`grep` for `tstapler`, `/home/`, emails, addresses: only the public GitHub repo slug in review text). |
| Transitive gate closure (script) | OD-H1 blocks 20 stories, OD-H7 5, OD-H4 4, OD-H2 5, external tail 7; 32 of 74 stories are blocked by at least one owner/external gate, 42 are executable now. |

---

## BLOCKER

### B1. Story 0.3 cannot be built as written: `saveAsset` is `INSERT OR IGNORE` with `ml_processed` hard-coded to 0

The plan's primary privacy control ("drawings never reach the cloud pipeline on every path") rests on Story 0.3 AC 2 and test (2): `registerAsset(intent = DRAWING)` on a content hash already indexed as `IMAGE` "UPGRADES the row to `DRAWING`", and `buildAssetEntry` returns `DRAWING` with `mlProcessed = true`.

Evidence (VERIFIED):
- `CM/repository/SqlDelightAssetRepository.kt:244-266` `saveAsset` calls `queries.insertAssetOrIgnore(...)`.
- `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq:1173-1175` `insertAssetOrIgnore` is `INSERT OR IGNORE ... VALUES (?, ..., ?, 0, 0, ?, 0, 'NONE')`: `ml_processed`, `ml_failed` and `is_orphan` are literals, so the entry's `mlProcessed = true` is silently dropped, and a second insert for an existing uuid is ignored.
- `CM/asset/AssetIndexService.kt:79-85` (`registerAsset`) returns the existing row when the hash exists; there is no update path anywhere in `AssetRepository` for `media_type`.

Consequences:
1. The hash-dedupe upgrade is impossible through `saveAsset`. It needs a new `UPDATE asset_index SET media_type = ...` query, a `RestrictedDatabaseQueries` `@DirectSqlWrite` stub, an `AssetRepository` method (+ `InMemoryAssetRepository` impl, + contract test), and regenerated `kmp/src/generated/sqldelight/` sources.
2. Therefore Story 0.3 is NOT "edits only existing production types" and NOT free of the schema merge queue: it edits `SteleDatabase.sq` and generated sources in W0, concurrently with 6.1 and 2.1. Section 5 / ADR-009 order the queue "6.1, then 2.1, then 6.2a" and omit 0.3. Its `Files` list omits `AssetRepository.kt`, `InMemoryAssetRepository.kt`, `SteleDatabase.sq`, `RestrictedDatabaseQueries.kt`, generated sources. That is more than 5 files, so the story is no longer "S".
3. `mlProcessed = true` cannot be persisted; DRAWING rows will sit at `ml_processed = 0` (SQL queue `selectUnprocessed...` at `.sq:1204`). This is survivable (audio/video rows already behave this way, and `canProcess` is IMAGE-only, see m12) but the AC and the test text are wrong.

Fix: rewrite Story 0.3 with an explicit `updateMediaType(uuid, DRAWING)` repository method and query, add 0.3 to the schema merge queue (order 6.1, 0.3, 2.1, 6.2a, or fold the query into the 2.1 migration PR), list all touched files, drop the `mlProcessed = true` claim (or add a `markProcessed` update), and re-size S to M. Update the dedupe test to assert the persisted `media_type` by re-reading the row (the read-back rule).

---

## MAJOR

### M1. The post-sync job has no way to reach `TargetWriterRouter`, the sketch repositories or the file system (Story 5.7a / 5.4c)

Plan: `PostSyncProjectJob` is "owned by `StelekitViewModel`"; `DigestWriter` writes "via `TargetWriterRouter.withWriter`".

Evidence (VERIFIED):
- The router is built only inside a Composable: `CM/ui/App.kt:100-150` (`rememberCrossGraphUi`, a `DisposableEffect`) via `createTargetWriterRouter(...)` (`CM/capture/ShareCaptureServices.kt:50`). If the host supplies neither `shareInbox` nor `copyHost`, no router exists at all; copy-only builds one with off-graph writes refused.
- `StelekitViewModel` is constructed from a `StelekitViewModelDependencies` bag (`CM/ui/StelekitViewModel.kt:107-132`) in `GraphContentViewModelSetup.kt:129` inside `remember`; the bag has no router, no `ActiveWriteHooks`, no sketch repositories, no graph file-system for `assets/`.
- The coordinator is constructed with explicit parameters (`CM/ui/GitSyncCoordinator.kt:61-70`) and `observeSyncState()` (`:121`, `internal`) has no callback seam.

Story 5.7a `Files` lists only `PostSyncProjectJob`, `ProjectSourceCollector`, `BackgroundJobs.kt`, `StelekitViewModel.kt` and one test. Missing: `StelekitViewModelDependencies`, `GraphContentViewModelSetup.kt`, `GitSyncCoordinator.kt`, `App.kt`/`StelekitAppCaptureDeps` (to hand the router to the job when it exists), every test that builds the deps bag. `activeTargetWriterFor` also returns a `RefusingWriter` (`CM/capture/ActiveWriteHooks.kt:57,67`) when the repo set has no actor/path; `as? OwnedPageWriter` is then `null` and the plan defines no behaviour for it (see m2).

Fix: add a 5.7a task "dependency plumbing" naming those files and the rule for a missing router (job reports "digest unavailable: no writer", not a crash); alternatively give the job its own router built from `GraphManager` with `createTargetWriterRouter` (single recipe), and say which. Re-count files against the >5 split trigger.

### M2. The hook triggers on `SyncState.Success`, which `GitSyncService` replaces with `LfsActionRequired` and which is transient

VERIFIED: `CM/git/GitSyncService.kt:497` `_syncState.value = if (lfsAttention.isEmpty()) success else SyncState.LfsActionRequired(lfsAttention)`. Any sync that left LFS-held files never publishes `Success`, so the digest silently never regenerates for those graphs; `syncState` is also combined with `LocalChangesPending` in the coordinator (`CM/ui/GitSyncCoordinator.kt:97-108`) and a StateFlow collector can miss a short-lived value.

Fix: trigger on the persisted `gitLastSyncAt` flow (`GitSyncCoordinator.gitLastSyncAt`, written by `recordLastSyncAt` at `GitSyncService.kt:490` before the state is chosen) rather than on a `Success` instance; keep "once at graph open". Add a test with a fake service ending in `LfsActionRequired`.

### M3. Sketch geometry is not part of `project-model.json`, so the cross-device claim in ADR-005 is unsupported

ADR-005: "A sketch drawn on one device reaches the others through normal git sync of `assets/`". Story 2.5's `ProjectModelJson` lists display strings, num/den, `modelRevision`, registry, observations, zones, `sources[]`. `sketch_sheets` and `sketch_primitives` (Section 5) are not in it, yet the reconciler "rebuilds `sketch_*` rows from it". On device B the sheets and primitives either never appear or the rebuild deletes local ones.

Fix: define the sheets/primitives sections in the `schema:1` JSON (with a size bound: `SketchScene` allows 10,000 primitives, and the ADR says "must stay small JSON"), or state that geometry is device-local and downgrade the ADR claim. Add a round-trip test sheet -> JSON -> second DB.

### M4. A git text conflict in `project-model.json` is treated as "no sidecar" and then overwritten

Story 2.0/2.5: "truncated or absent JSON is treated as 'no sidecar'"; reconcile: "DB newer and sidecar unchanged since last write -> write the sidecar". A merge conflict leaves `<<<<<<<` markers in the file (INFERRED from the digest-page analysis in ADR-006, which handles `Conflicted` only for the digest page; Story 5.1(c) probes it for the digest only). Invalid JSON == "no sidecar" == the local DB wins and rewrites the file, silently destroying the other device's side of the conflict that git was trying to surface. This contradicts "a pulled sidecar is never overwritten silently".

Fix: classify conflict-marker/invalid-UTF/invalid-JSON separately from "absent": keep the file, write the local state to `project-model.conflict-<hash8>.json`, surface it, and add a `SidecarReconcileTest` case with conflict markers. Same rule for `sources/*.yaml`.

### M5. No fallback if OD-H7 is answered "no", and the digest page is the most expensive item per user-visible byte

OD-H7 gates 5.4a, 5.4b, 5.4c, 5.7b, 7.1 and everything that needs a written digest (metric 3's "regenerated on sync"). Cost from Section 16: 5.1 S + 5.4a M + 5.4b L + 5.4c S = 55+130+260+55 = 500k raw tokens (8% of the plan) plus a CLAUDE.md exception, for one page. ADR-006 rejected append-only (B) but never priced an option that needs no exception: write the digest as a non-page asset under the already-planned `assets/stelekit-sketch/<slug>/exports/digest.md` via `AtomicFileWriter` (no `TargetWriter`, no marker/hash protocol, no `GraphWriter` interaction), and have the LLM step read that. A "no" currently leaves metric 3 and requirement "write the fused digest back to a dedicated page" with no path.

Fix: add the fallback as ADR-006 option E and a Story "5.4x digest as asset" (S) selected when OD-H7 = no; keep the owned page as the upgrade. Record in Section 8 what happens on "no".

### M6. Story 5.6b's new `PendingLlmSuggestion.ProjectAnalysisAnswer` is under-scoped and semantically wrong for the inbox

VERIFIED: `CM/llm/PendingLlmSuggestion.kt` variants are all page-mutating proposals (`BlockEdit`, `TagChange`, `NewPage`, `UnlinkedReference`) whose accept path writes the graph. Exhaustive handling sites that a new variant must touch: `CM/ui/screens/llm/LlmSuggestionReviewScreen.kt:175-187,219-222,226-229` (three `when`s), `CM/llm/LlmSuggestionWriter.kt:49-68` (the apply `when`), plus the `LlmSuggestionCoordinator`/`PageView`/`TagSuggestionViewModel` users (31 references total by grep). 5.6b `Files` lists only `LlmSuggestionInbox.kt` and `PendingLlmSuggestion.kt`. Also an info-only answer has no apply action; "never auto-applied" is vacuous and the review screen's Accept button text mapping has nothing to map to.

Fix: either list the 4 extra files and define "Accept = dismiss/copy" for the new variant, or (smaller) show the answer in the `JobTracker` result / a dedicated read-only panel and keep the inbox untouched. Re-size 5.6b (M -> M+).

### M7. Several "first production reference" claims in Section 10a do not hold, and 8 stories have an undeclared dependency on 5.7a

Checked against `scripts/ci/reachability-scan.py` (VERIFIED: a type is live iff its name appears as a token in ANY other production file; top-level `fun/val` segments are roots; comments count). Stories checked: 0.3, 2.0, SL-I, SL-A, SL-E, 4.5, 5.9b, 5.9c, SL-G, 4.8, 5.4a (11) and the 9 slices.

| Claim | Verdict |
|---|---|
| 0.3 `DrawingLocationRule` mentioned by `AssetIndexService` | holds |
| 2.0 `AtomicFileWriter` used by `MarkdownTargetWriter` | holds |
| SL-I `ProjectBridgeSettings` read by `PostSyncProjectJob`, job constructed by `StelekitViewModel` | holds (see M1 for the plumbing) |
| SL-G, SL-H, 5.9a, 5.9c, 5.9d, 3.4/3.5/3.7 (host reference chains) | hold, if each composable/action file is actually referenced from `SketchScreen` |
| SL-E "`DigestWriter` uses `DigestRenderer`/`LlmPayloadBuilder`" | FAILS for `LlmPayloadBuilder`: Story 5.4c's `DigestWriter` has no reason to call the LLM payload projection; its only honest consumer is the LLM step (5.6b, slice SL-F, a later wave). `LlmPayloadBuilder` (5.5a) is an unreferenced type in the SL-E PR and the `--check-baseline` job fails. |
| 5.9b | `ProjectSourceReader` has no referrer in 5.9b's PR: the only listed reference is `SketchScreen -> SourcePagePicker`. The collector stage that calls the reader is completed in 5.8 (W7), one wave AFTER 5.9b (W6). |
| 4.5 | `PngSheetAdapter` has no referrer: only `CalibratedLength` is named as called from the formatter hook; `ImportFlow` (4.4) is not in 4.5's `Files`. |
| SL-A (1.1+1.2+1.7) "collector's normalise stage" | The stage lives in `ProjectSourceCollector` (5.7a, SL-I). SL-A is W1, same wave as SL-I, but no `Deps` line or mermaid edge says SL-A (or 1.3/1.4 -> SL-B, SL-C, SL-D, 4.8, 5.2) depends on 5.7a. The collector shell returns an empty `CollectedSources`, so a "normalise stage" over nothing is not an honest reference either. |

The wave lint passes only because these dependencies are implicit. The "strictly earlier wave" claim in Section 10c and Section 17 is therefore not true for SL-A.

Fix: add the 5.7a edge to every story whose reference is a collector stage (1.2/1.7 via SL-A, 1.3/1.4, 4.1b/4.2/4.3, 4.8, 5.2, 5.3a/5.3b) and re-run the wave lint; move `LlmPayloadBuilder` into SL-F (5.5b/5.6b); add `ProjectSourceReader` call to the collector in 5.9b (a no-op stage guarded by bound sources) or merge 5.9b with 5.8; add the `PngSheetAdapter` call to `ImportFlow` in 4.5's `Files`. Run the plan's own throwaway-branch scan check BEFORE W0, not "before the first slice".

### M8. OD-H1 is framed as a privacy decision but the real numbers are already public in the plan, and the NFR calls them confidential

`requirements.md` NFR: "Security classification: confidential (home floor plans)". But `research/wiki-corpus-summary.md` (will be committed) inventories 27 real quantities, the stud-offset list, ceiling slope heights, rod heights, cabinet sizes and 12 discrepancy descriptions; plan ACs (1.2, 1.3, 1.4, 4.2, 4.3, 4.8, 5.5a) quote the same constants (11.0638, 34.2289, 136", 43 5/16, 40 7/8, zone widths). `git status` shows the directory untracked (`?? project_plans/home-project-sketching/`), so nothing is public YET, but OD-H1's synthetic fallback (Story 1.5s) protects nothing: the real corpus is already in prose. Conversely if the dimensions are acceptable to publish, the "confidential" label and the 1.5s fallback are noise. The sanitiser's "manifest vocabulary" also means the fixture is a rewrite, not a "frozen snapshot of the real sources" (see m6).

Names, addresses, titles and other-project details: clean (0 hits, VERIFIED). The residual personal information in the to-be-committed files is the owner's closet geometry and the fact that a primary-bedroom closet remodel exists; low sensitivity, but the plan should decide it explicitly.

Fix: make OD-H1 two questions: (a) may closet dimensions appear in the public repo at all (if no: replace the numeric constants in plan/research with synthetic ones BEFORE the first commit, which is the cheap moment); (b) may the sanitised fixture be committed. Reword the NFR to "dimensions are treated as non-sensitive; names, addresses, titles and filenames are confidential" if (a) is yes.

### M9. Nothing owns turning the feature on: no toggle UI for `homeSketch` and no graduation story

Story 5.0 makes `homeSketch` "a `Settings` boolean default false" with no Settings UI; 3.3 and 5.9a say that with the flag off nothing is reachable. OD-H6 ("graduation converts `@HelpExempt` to `@HelpPage`, moves the screen into `FirstOpenSmokeTest`, adds the demo-graph help page checked by `DemoGraphCoverageTest`") has no story, no cost row and no wave. Success metric 2 ("Owner can create a dimensioned sketch in-app") is unreachable by the owner without editing settings storage by hand; the project ends dormant by construction.

Fix: add Story 5.0b "flag toggle in Settings > Developer or Labs" (S) and Story 7.3 "graduation" (M: help page, `@HelpPage` conversion, `first-open-screens.txt` + `FirstOpenSmokeTest`, default flip), gated on OD-H6, and include them in the 74 count and Section 16.

### M10. "Excluded from the cloud pipeline on every path" has an uncovered path: attaching an exported or sketch-managed file elsewhere

Story 0.3 skips `exports/`, the sidecar and `sources/` as never-asset-rows. But a user can attach/drag an exported `sketch.svg` (or a copy of a drawing) into a page: `AssetIndexingController.registerAttachment` (`CM/asset/AssetIndexingController.kt:140-156`) copies it flat into `assets/` and calls `registerAsset`; the hash matches no row (exports are never indexed), so a fresh `IMAGE` row is created at a normal path and the cloud description plugin (`CloudClaudePlugin.canProcess` is mediaType==IMAGE, `CM/asset/pipeline/CloudClaudePlugin.kt:46-50`) is eligible. INFERRED that SVG reaches the plugin (not run), but the guarantee as worded ("every path") is false.

Fix: classify by content for files the app wrote: `SketchSvgWriter` output carries `<metadata>` with a model marker, and `buildAssetEntry` already reads the file bytes, so sniffing a marker (or checking hash against a small `exports` manifest in the sidecar) yields `DRAWING` regardless of path. Add it as test (4) in `DrawingExcludedFromCloudTest`.

---

## MINOR

1. ADR-006 says "two NEW variants" (`NotOwned`, `EditedByUser`) and calls `WriteRefusedReason` an enum with a `toUserMessage` mapping; Story 5.4a has THREE (`Conflicted`). VERIFIED: `WriteRefusedReason` is a `sealed interface` of data classes with an abstract `message` (`CM/merge/TargetWriter.kt:10-33`); there is no `toUserMessage`; exhaustive `when`s are over a sealed type. Fix the ADR and AC wording; each new variant must supply `message`.
2. `withWriter { (it as? OwnedPageWriter) }`: the cast is `null` for `RefusingWriter` and any wrapper. Specify the result (`Retryable`, same text as `RefusingWriter.busy()`) and test it.
3. `TwoDeviceConvergenceTest` models device B on the off-graph `MarkdownTargetWriter`, but in production both devices run the job for their ACTIVE graph (5.7b says so), i.e. both use `ActiveTargetWriter`. The Markdown writer's `replaceOwnedPage` (5.4a, M, with byte/CRLF criteria) is reachable only through a graph-switch race. Re-state the test as Active/Active over a shared fake file system, and consider moving 5.4a's byte-level criteria out of the critical path.
4. Story 0.2's `PrivateStringGuardTest` "committed tree passes" scans `project_plans/**` and `docs/**`, which Bazel's sandbox does not contain. `//:rich_editing_backlog_docs` is the precedent: add a filegroup + `*_as_resources` in 0.2 or declare the test Gradle-only. (`commonMain` sources are already bundled by `wiring_kmp_sources_as_resources`, so the Kotlin audits are fine.)
5. Metric 2: the budget is "first 10 runs, max x 1.5" (calibrated from itself), report-only until 6.11 (gated on #564 and 10 CI runs), and mixes a ratio with an absolute p95 24 ms. At plan end the performance claim is unfalsifiable in CI; state that metric 2's perf half is "reported" until 6.11 and keep the functional half (create/export) as the claimable part.
6. Metric 1 "frozen snapshot": the sanitiser rewrites free text to a manifest vocabulary, so the prose and table sources in `FIX` are partly synthetic; `dimension-map.json` is hand-authored, so the golden measures extraction given a perfect mapping and never measures the `NeedsMapping` rate. Add a count of `NeedsMapping` items in `expected-fusion.json` (expected 0 for the closet, > 0 for a mutated fixture).
7. Registry labels and dimension keys are free text and ride into the LLM payload ("labels from the registry"); the gate's graph denylist covers page names and aliases only, so a household name typed as a label passes. Restrict payload labels to a registry-defined vocabulary or run labels through an explicit user-visible preview before the first analysis.
8. `DemoGraphCoverageTest` is a `jvmTest` (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/DemoGraphCoverageTest.kt`), not a businessTest; Section 17 lists it with the business gates. It needs the display check wrapper.
9. `MigrationRunner` is hash-guarded (a changed migration hash is treated as tampering unless flagged). Story 2.1 pre-defines every sketch column; any later column need (for example a `count` dimension with NULL precision: `sketch_dimensions.precision_num/den` are not declared nullable in Section 5, yet ADR-002 says a count has no precision) must be a NEW `Migration`, not an edit. Make precision nullable now and say so.
10. Coherence of 74: the number holds arithmetically, but 19 stories (26%, E6) are the unrelated carry-over lane, 7 of them externally gated, 32 of 74 are transitively blocked by owner/external gates and 4.6/7.2 produce no repo artifact (their proofs live in a git-excluded cache and cannot be checked by CI or a reviewer). Report the "42 executable now" figure in Section 16 and consider splitting E6 into its own plan so the sketch plan's size and critical paths are not read as one project.
11. Sidecar writes deliberately bypass `GraphWriteLock` (ADR-005) but nothing coordinates them with an in-flight git merge (`GraphLoader.beginGitMerge`, `gitSyncBusyCounter`). A 500 ms autosave during a merge can write into a tree git is rewriting. Skip/queue sidecar writes while sync is busy.
12. Story 0.3 edits `OcrPlugin` and `OnDeviceLabelingPlugin` to "reject DRAWING"; VERIFIED both (`asset/pipeline/OcrPlugin.kt:22-23`, `OnDeviceLabelingPlugin.kt:21-22`) and `CloudClaudePlugin` already require `mediaType == IMAGE`, so a new `DRAWING` value is excluded by construction. Keep only the structural audit (the real value); drop the three plugin edits to shrink the PR and the #565 conflict surface.

---

## Items checked and found sound

- AssetMediaType decode: `valueOf` at `SqlDelightAssetRepository.kt:52,91` throws on unknown values; `media_type` is `TEXT NOT NULL` with no CHECK (`SteleDatabase.sq:1147`); the three exhaustive-`when` UI files are real (`AssetItemCard.kt:202-207`, `AssetBrowserScreen.kt:283-286`, `AssetBrowserViewModel.kt:335`). `walkFiles`/`buildAssetEntry`/`backfillGraph` semantics match the plan's description.
- `LlmSettings.getSelectedProviderId` returns `null` for blank = "Auto" (`CM/llm/LlmSettings.kt:28-30`); `LlmProviderSettings.kt:121` iterates `LlmFeature.entries`; `FakeLlmProvider` is a `private class` in `LlmSynthesisServiceTest.kt:20` (the plan hedges "extract if needed", correct).
- `TargetWriter` has exactly the five methods claimed; `ActiveTargetWriter` re-renders via `LogseqPageSerializer` with `properties = emptyMap()` and has the `isPageDirty` -> `PageBeingEdited` path, so a separate `OwnedPageWriter` implemented by Active/Markdown is feasible; the replace on Active composes from `deleteBlocks` + `saveBlocksDiff` + `graphWriter.savePage` with `rollbackPage` already present. Lock order via `TargetWriterRouter.withWriter` (one lock, `awaitPendingMigration` outside) is respected by the plan.
- `DirectSqlWriteOptInAllowlistTest` total is 41 (`:59`); `JobKind` is a plain enum in `jobs/BackgroundJobs.kt` with two exhaustive-style `when` sites in `WorkManagerJobRegistry.kt`; `business_common_tests` has explicit `srcs` and includes `FakeFileSystem.kt`; `AllBusinessTestsCompletenessTest` behaves as described; `WiringAudit.SEAMS` has six entries and none of the plan's new types implement them.
- Reachability script semantics match plan Section 10a (name-based, other-file token, comments count, `@Serializable`/`UiState` filtered).

## Top 3 findings

1. **B1**: Story 0.3's hash-dedupe upgrade and `mlProcessed = true` are impossible with the existing `INSERT OR IGNORE` + literal `ml_processed = 0` SQL; the story needs a new update query, stubs, generated sources and a place in the schema queue.
2. **M1**: the ViewModel-owned post-sync job cannot reach `TargetWriterRouter` (built only in a Composable, may not exist), the sketch repositories or the file system; 5.7a's file list omits the dependency bag, setup file and coordinator.
3. **M2 + M4 (hook and sidecar integrity)**: the trigger on `SyncState.Success` is skipped when LFS attention replaces it (`GitSyncService.kt:497`), and a git conflict in `project-model.json` is read as "no sidecar" and then overwritten, defeating "never overwritten silently".
