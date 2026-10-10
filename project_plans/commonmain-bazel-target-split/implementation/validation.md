# Validation Plan: commonmain-bazel-target-split

**Date**: 2026-09-04

## Happy Path Scenario

Given the current monolithic `common_srcs`/`srcs` bundling (baseline: `KotlinCompile
//kmp/src/androidMain/kotlin:android_main { kt: 711 }` takes 71s and re-runs on any
`commonMain` change), when `stats` (Tier 1 — the plan's own worked pilot example, 2 files,
zero `expect`/`actual`, zero `internal` usage, per `research/architecture.md` and plan.md
Story 2.2.1) is extracted into its own `kt_jvm_library`/`kt_android_library` target via the
`stelekit_kt_common_library` macro, with `kt_srcs`'s glob gaining a matching `exclude` entry in
the same commit, then:

1. `bazel build //kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats:stats` succeeds standalone.
2. A change confined to `stats/LibraryStatsProvider.kt` followed by `bazel build
   //kmp:android_app --config=android --profile=/tmp/after.json` shows a small, isolated
   `KotlinCompile //kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats:stats` action, and the
   monolithic `android_main`/Tier-3-equivalent `KotlinCompile` action does **not** re-execute
   (confirmed via `bazel analyze-profile` / the profile JSON's action-state field) — verified
   twice: once as the Epic 1.7.2 ABI-stability pre-flight gate (before any extraction exists),
   once as the Epic 3.1 go/no-go re-profiling checkpoint (after all 9 Tier 1 packages exist).
3. `bazel build //kmp:desktop_app //kmp:android_app --config=android` and `bazel test
   //kmp:jvm_tests //kmp:business_tests` remain green after the extraction.

This is corrected from the task prompt's illustrative `search` example per ADR-001: `search` was
reclassified into the 18-package Tier-3 cycle during research, so `stats` is the plan's actual
validated zero-risk leaf-package example.

## Requirement → Test Mapping

| Plan Element | Test File / Check | Test Name | Type | Scenario |
|---|---|---|---|---|
| Epic 1.1/Story 1.1.1 — `stelekit_kt_common_library` macro | `kotlin_common.bzl` | `macro_should_omitPlugins_when_noExtraPluginsOrSugarFlagsPassed` | Unit | Happy — plain call with no `extra_plugins`/`uses_compose`/`uses_serialization` yields `plugins = []` and the platform-default `kotlinc_opts` |
| Epic 1.1/Story 1.1.1 | `kotlin_common.bzl` | `macro_should_failFast_when_targetPassedToBothDepsAndAssociates` | Unit | Edge — `fail()` guard rejects a target listed in both `deps` and `associates`, naming the offending target |
| Epic 1.1/Story 1.1.1 | `kotlin_common.bzl` | `macro_should_spliceWellKnownPluginLabel_when_usesComposeOrUsesSerializationTrue` | Unit | Edge — sugar params append the correct plugin label into the effective `extra_plugins` list without editing the macro |
| Epic 1.1/Story 1.1.1 (Task 1.1.1c) | `stats/BUILD.bazel` | `macroSmokeTarget_should_buildStandalone_when_appliedToStatsPackage` | Integration | `bazel build //kmp/.../stats:stats` succeeds using the macro for the first time |
| Epic 1.1/Story 1.1.2 — `tier_manifest.bzl` | `tier_manifest.bzl` | `tierManifest_should_matchADR001_when_listsCompared` | Unit | Happy — `TIER_1/2/3_PACKAGES` exactly match ADR-001's 9/11/18 lists, verbatim |
| Epic 1.1/Story 1.1.2 | `tier_manifest.bzl` | `tierManifest_should_haveNoPackageAppearingInMultipleTiers` | Unit | Edge — partition invariant: the three lists are pairwise disjoint and their union is all 38 top-level packages |
| Epic 1.2 — rules_kotlin upgrade | `MODULE.bazel` | `rulesKotlinPin_should_includeFix1652_when_versionChecked` | Unit | Happy — `grep 'bazel_dep(name = "rules_kotlin"' MODULE.bazel` shows the version confirmed (not assumed) to contain PR #1652's merge commit |
| Epic 1.2 | `third_party/patches/rules_kotlin_kmp.patch` | `kmpPatch_should_applyCleanly_when_rulesKotlinUpgraded` | Integration | Edge — a patch that no longer applies fails fast at module-resolution time with a clear rejection message, not silently |
| Epic 1.2 | rules_kotlin upgrade PR | `ciBazelJobs_should_stayGreen_when_rulesKotlinUpgradedInIsolation` | CI | Integration — the version bump alone (no target-split change bundled) passes all Bazel Android/JVM/test CI jobs |
| Epic 1.3 — `.bazelrc` worker cap | `.bazelrc` | `workerCap_should_beSetToTwo_when_bazelrcInspected` | Unit | Happy — `grep -n "worker_max_instances=KotlinCompile"` returns exactly the two new lines |
| Epic 1.3 | CI workflow logs | `ciKotlinCompileWorkers_should_notEmitWorkResponseError_when_pilotBuildsUnderMemoryConstraint` | CI | Edge/regression — the exact prior-incident failure string ("Worker process did not return a WorkResponse") does not appear in CI logs |
| Epic 1.3 | CI workflow memory sample | `ciRunnerMemory_should_stayUnder16GbCeiling_when_pilotSliceBuilds` | CI | Integration — `free -h`/resource-usage sampling shows peak memory with margin below the 16GB runner budget |
| Epic 1.4 — `check_internal_visibility.py` | `scripts/check_internal_visibility.py` | `checkInternalVisibility_should_flagAllSixKnownPairs_when_scanningCommonMain` | Unit | Happy — reproduces exactly `cache→performance`, `db→migration`, `repository→git`, `util→model`, `util→ui`, `voice→llm`, matching `research/features.md`'s hand-verified table |
| Epic 1.4 | `scripts/check_internal_visibility.py` | `checkInternalVisibility_should_notFlagKotlinxFlowCollision_when_scanningRepositoryAndSearch` | Unit | Edge — reuses `research/features.md`'s exact false-positive example (`kotlinx.coroutines.flow.Flow` is not mistaken for `repository`'s internal `Flow`-returning function) |
| Epic 1.4 | `scripts/check_internal_visibility.py` | `checkInternalVisibility_should_ignoreInternalMentionInCodeComment_when_scanning` | Unit | Edge — the earlier-iteration comment false positive `research/features.md` found is not reproduced |
| Epic 1.4 | `scripts/check_internal_visibility.py` | `checkInternalVisibility_should_flagWildcardImportForManualReview_when_targetSymbolAmbiguous` | Unit | Edge — `import dev.stapler.stelekit.<pkg>.*` is flagged for manual review, not silently passed or silently treated as a hit |
| Epic 1.4 | `scripts/check_internal_visibility.py` | `checkInternalVisibility_should_runBidirectionally_when_invokedDuringPackageExtraction` | Integration | The Epic 2.2/4.1 recipe's step 2 usage — run with `<pkg>` as both declaring and consuming package before writing `BUILD.bazel` |
| Epic 1.5 — kibitzer config (`.claude/inspect.json`) | `.claude/inspect.json` | `kibitzerConfig_should_returnRealFindings_when_architectureAssessmentRunWithCommittedConfig` | Integration | Happy — `mcp__kibitzer__architecture_assessment` returns real findings instead of the "no `.claude/inspect.json` found" zero-config string |
| Epic 1.5 | `.github/workflows/` | `kibitzerConfig_should_haveZeroCiCoupling_when_workflowsGrepped` | Unit | Edge — `grep -rl "inspect.json\|kibitzer" .github/workflows/` returns nothing |
| Epic 1.6/Story 1.6.1 — expect/actual inventory | grep output table | `platformCoreInventory_should_listConcreteFiles_when_grepRunAcrossElevenPackages` | Manual | Happy — the deferred inventory produces a real file-path table, superseding architecture.md's placeholder |
| Epic 1.6/Story 1.6.1 | grep output table | `platformCoreInventory_should_recordException_when_inFullPackageHasNonExpectActualFile` | Manual | Edge — if `model`/`platform`/`cache`/`coroutines`/`util`/`performance`'s "in full" claim doesn't hold for every file, the exception is recorded, not assumed away |
| Epic 1.6/Story 1.6.2 — `:platform_core_srcs` filegroup | `kmp/src/commonMain/kotlin/BUILD.bazel` | `platformCoreFilegroup_should_resolveExpectActual_when_androidMainCompiles` | Integration | Happy — `bazel build //kmp:desktop_app //kmp:android_app --config=android` succeeds with no expect/actual-in-same-module conflict |
| Epic 1.6/Story 1.6.2 | `kmp/src/{androidMain,jvmMain}/kotlin/BUILD.bazel` | `platformCoreFilegroup_should_notDuplicateClaim_when_wiredAdditivelyIntoCommonSrcs` | Integration | Edge — wiring is additive to `kt_srcs`/`kt_expect_srcs`, not a replacement; no duplicate-source-claim error |
| Epic 1.6/Story 1.6.3 — uniform platform-core check | plan.md (this doc's sibling) | `platformCoreRelationshipCheck_should_appearInBothCacheAndUtilStories_when_planReviewed` | Manual | Edge — closes the adversarial-review BLOCKER: Story 4.1.1 (`cache`) and Story 4.2.1 (`util`) both gained the fold-vs-independent decision task, not just `model` |
| Epic 1.7/Story 1.7.1 — androidApp audit | `androidApp`'s `BUILD.bazel` | `androidAppBuild_should_haveNoMonolithShapeAssumption_when_inspected` | Manual | Happy (expected outcome) — no glob/resource rule assumes commonMain's current unsplit shape |
| Epic 1.7/Story 1.7.1 | `androidApp`'s `BUILD.bazel` | `androidAppBuild_should_beRecordedAsBlockingRisk_when_monolithShapeAssumptionFound` | Manual | Edge (contingency) — if a monolith-shape assumption is found, it's listed as a new Feasibility Risk before Epic 2.2 starts |
| Epic 1.7/Story 1.7.2 — ABI-stability spike (hard gate before Epic 2.2) | `:stats` `KotlinCompile` action profile | `statsTarget_should_notRecompile_when_unrelatedTier3FileChanges` | Integration | **Happy — this check IS the test.** Touch a file in `ui/` (or any Tier 3 package), rebuild `:stats` with `--profile`, confirm re-analysis only (action-cache hit), not re-execution |
| Epic 1.7/Story 1.7.2 | `:stats` `KotlinCompile` action profile | `statsTarget_should_blockEpic22_when_abiSpikeShowsReExecution` | Integration | Error/gate path — if `:stats` DOES re-execute, this is recorded as a blocking finding and Epic 2.2 does not proceed until root-caused or explicitly accepted |
| Epic 1.8 — CI duplicate-`.kt`-ownership check | `scripts/check_no_duplicate_kt_ownership.py` | `checkNoDuplicateOwnership_should_exitZero_when_repoStateCorrect` | Unit | Happy — clean state (every extraction followed the same-commit rule) exits zero |
| Epic 1.8 | `scripts/check_no_duplicate_kt_ownership.py` | `checkNoDuplicateOwnership_should_exitNonZeroAndNameFiles_when_packageClaimedByBothMonolithAndOwnTarget` | Unit | Edge — a deliberately-broken fixture (live per-package `BUILD.bazel` + missing `kt_srcs` exclude) is caught, with offending files and both claiming targets named |
| Epic 1.8 | `.github/workflows/*.yml` | `ciDuplicateOwnershipCheck_should_blockMerge_when_pathConditionedWorkflowStepFails` | CI | Integration — the wired, path-conditioned workflow step actually blocks merge on failure |
| Epic 2.1 — `associates` dual-target verification | smoke `kt_jvm_test` (temporary/kept) | `associatesTwoTargets_should_compile_when_bothInternalSymbolsReferenced` | Integration | Happy — `associates = [":stats", ":_associates_smoke_fixture"]` compiles and grants `internal` access to both. (Corrected: Story 2.1.1 uses a throwaway fixture created within the same story, not `:error` — `:error` does not exist until Story 2.2.2, later in the sequence.) |
| Epic 2.1 | smoke `kt_jvm_test` | `associatesTwoTargets_should_failVisibility_when_internalSymbolNotInAssociatesList` | Integration | Edge/negative control — a symbol from a target *not* listed in `associates` fails to resolve, confirming the smoke test actually asserts something |
| Epic 2.2/Story 2.2.1 — extract `stats` | `stats/BUILD.bazel`, `kmp/src/commonMain/kotlin/BUILD.bazel` | `statsTarget_should_buildStandalone_when_isolatedBuildRun` | Integration | Happy — `bazel build //kmp/.../stats:stats` succeeds after the same-commit `BUILD.bazel` + `kt_srcs` exclude edit |
| Epic 2.2/Story 2.2.1 | same | `statsPackageChange_should_invalidateOnlyStatsTarget_when_libraryStatsProviderEdited` | Integration | Happy — **this is the project's headline E2E scenario**: single-file change under `stats/` invalidates/recompiles only `:stats`, not the monolith |
| Epic 2.2/Story 2.2.2 — extract `error` (highest Tier-1 fan-in, 136 consumers) | `error/BUILD.bazel`, monolith `BUILD.bazel`s | `errorTarget_should_gainMonolithDepsEdge_when_extractedWithHighFanIn` | Integration | Edge — the still-monolithic `android_main`/`jvm_main_lib` gains a `deps` (not `associates`) edge on `:error` so its 136 still-monolithic consumers keep resolving |
| Epic 2.2/Story 2.2.2 | full graph | `errorPackageExtraction_should_notBreak136Consumers_when_fullGraphBuilt` | Integration | `bazel build //kmp:desktop_app //kmp:android_app --config=android` + `bazel test //kmp:jvm_tests //kmp:business_tests` stay green |
| Epic 2.2/Stories 2.2.3–2.2.9 — extract `logging`, `parsing`, `benchmark`, `docs`, `resilience`, `rtc`, `service` (×7) | `<pkg>/BUILD.bazel`, monolith `BUILD.bazel`s | `tier1Package_should_buildStandaloneAndFullGraph_when_extractedPerRecipe` (one instantiation per package, 7 total) | Integration | Happy+integration, templated — isolated build, then full-graph build/test, per package; each package's own fan-in (deps edge needed or true leaf) verified via its own Task-(a) grep, not assumed |
| Epic 2.3/Story 2.3.1 — Tier 1 test-source-set wiring | `{jvmTest,businessTest,androidUnitTest}/kotlin/BUILD.bazel` | `tier1TestWiring_should_needNoChange_when_noInternalUsageFoundByAudit` | Integration | Happy (expected outcome) — audit scoped to the 9 Tier 1 packages as declaring packages finds zero cross-package `internal` usage; "no change required" recorded explicitly, not silently skipped |
| Epic 2.3/Story 2.3.1 | same | `tier1TestWiring_should_addAssociatesEntry_when_auditFindsRealInternalDependency` | Integration | Edge (conditional) — if the audit does find a dependency, the test target gains an additive `associates` entry alongside its existing monolith one |
| Epic 3.1/Story 3.1.1 — go/no-go re-profiling checkpoint | `--profile` + jq top-N | `tier1KotlinCompileActions_should_beIndividuallySmall_when_reprofiledAfterPilot` | Integration | Happy — the same before/after methodology (cold `--disk_cache`, `--profile`, jq top-N) shows 9 small per-package actions, not one 711-file/71s action |
| Epic 3.1/Story 3.1.1 | action graph | `statsFileChange_should_triggerOnlyStatsRecompile_when_incrementalBuildProfiled` | Integration | Happy — reaffirms the headline E2E scenario post-pilot, at the checkpoint gate |
| Epic 3.1/Story 3.1.1 | recorded decision | `phase4Rollout_should_beBlocked_when_eitherReprofilingCriterionFails` | Manual | Error/gate path — if either criterion fails, Phase 4 does not start; the specific failure is root-caused first |
| Epic 3.1/Story 3.1.1 (optional Task 3.1.1e) | `git log --stat` sample | `recentPrTierClassification_should_estimateRealizedBenefitFraction_when_sampled` | Manual | Optional — classifies ~50 recent PRs by tier touched, giving the go/no-go real PR-pattern data instead of mechanism-proof alone; explicitly non-gating |
| Epic 4.1/Story 4.1.1 — extract `cache` (confirmed cross-package `internal` dependency) | `cache/BUILD.bazel`, plan.md | `cacheTarget_should_resolvePlatformCoreRelationshipExplicitly_when_extracted` | Manual | Edge — closes the BLOCKER: fold-vs-independent decision made and justified, not silently assumed |
| Epic 4.1/Story 4.1.1 | monolith `BUILD.bazel`s | `cacheTarget_should_grantAssociatesToStillMonolithicPerformance_when_extracted` | Integration | Happy — first real (non-smoke-test) `associates` use from a still-monolithic platform target onto a newly-extracted package; `performance`'s 3 identified files keep compiling |
| Epic 4.1/Story 4.1.2 — extract `model` (highest fan-in, 377 consumers, `:platform_core` candidate) | `model/BUILD.bazel` or `:platform_core_srcs` | `modelPackage_should_recordFoldVsIndependentDecision_when_platformCoreRelationshipResolved` | Manual | Edge — explicit written decision with justification, not assumed either way |
| Epic 4.1/Stories 4.1.3–4.1.11 — extract `coroutines`, `util`, `command`, `clipboard`, `flashcard`, `loader`, `outliner`, `parser`, `vault` (×9) | `<pkg>/BUILD.bazel` | `tier2Package_should_buildStandaloneAndFullGraph_when_extractedPerRecipe` (one instantiation per package, 9 total) | Integration | Happy+integration, templated — same recipe as Tier 1, plus each package's own Task-(a) check for known special cases (`:platform_core` candidacy for `coroutines`/`util`) verified, not assumed |
| Epic 4.2/Story 4.2.1 — cross-tier `util.roundTo` fix | `util/BUILD.bazel`, `model/BUILD.bazel`, monolith `BUILD.bazel`s | `utilRoundTo_should_remainVisibleToModelAndUiConsumers_when_utilExtracted` | Integration | Happy — `model`'s (Tier 2, extracted) and `ui`'s (Tier 3, still monolithic) one consuming file each keep resolving `roundTo`, via `associates` or a make-public decision |
| Epic 4.2/Story 4.2.1 | `bazel query somepath` | `utilMonolithDependency_should_showPathOnlyInMonolithToUtilDirection_when_cycleQueried` | Integration | Edge — no cycle introduced: `somepath(util, monolith)` returns empty, `somepath(monolith, util)` returns a path |
| Epic 5.1/Story 5.1.1 — finalize Tier 3 ADR, scope-bound implementation | plan.md task list | `phase5TaskList_should_excludeTier3ClusterImplementation_when_sddPhase5Run` | Manual | Happy — structural scope check: no task instructs writing `commonmain_cycle_cluster`'s `BUILD.bazel` |
| Epic 5.1/Story 5.1.1 | GitHub tracking issue | `tier3FollowOnIssue_should_existAndLinkAdr001_when_epic51Completed` | Manual | Edge — the dependency-inversion follow-on project's tracking issue exists, links ADR-001, and is titled unambiguously as *not* this project's remaining work |
| Epic 5.1/Story 5.1.1 | ADR-001 re-read | `adr001_should_stillHoldAdversarially_when_reReadAfterPhases2Through4Results` | Manual | Edge — the 18-vs-17 count correction, the (a)-vs-(b) rationale, and the reconsideration triggers all re-confirmed given what Phases 2-4's real extraction work surfaced |
| Migration Plan (whole plan) — per-package reversibility | `stats/BUILD.bazel`, `kmp/src/commonMain/kotlin/BUILD.bazel` | `migration_should_be_reversible` | Migration | Extract `stats` (Story 2.2.1), confirm `bazel build`/`bazel test` green, `git revert` that single commit, confirm the monolith's glob re-matches `stats/` immediately and `bazel build`/`bazel test` are still green with no cross-package impact |

## UX Acceptance Tests

N/A — pure infrastructure, no user-facing surface (no `design/ux.md` exists for this project;
this is a Bazel build-graph restructuring with no UI, API, or end-user-visible behavior change).

## Test Stack

- **Unit** (Starlark macro logic, `check_internal_visibility.py`, `check_no_duplicate_kt_ownership.py`,
  `tier_manifest.bzl`):
  - `kotlin_common.bzl`: `bazel_skylib`'s `unittest.bzl`/`analysistest` framework — asserts on the
    expanded rule's providers/attributes (`plugins`, `kotlinc_opts`) and on `fail()` behavior via
    `analysistest.make(expect_failure = True)`, without needing a real Kotlin compile per case.
  - `scripts/check_internal_visibility.py`, `scripts/check_no_duplicate_kt_ownership.py`: plain
    `pytest` (or `unittest`) against small fixture directories/known-pair tables — no new Gradle/
    Bazel test target needed, matching ADR-002's "bespoke Python script" choice.
  - `tier_manifest.bzl`: a small Python/Starlark diff check comparing the three lists against
    ADR-001's tables verbatim (can run as a `pytest` case or a `bazel_skylib` unittest).
- **Integration** (Bazel target builds/tests): real `bazel build`/`bazel test`/`bazel query`
  invocations against the actual repo — isolated per-package build first, then full-graph
  `//kmp:desktop_app //kmp:android_app --config=android` + `//kmp:jvm_tests //kmp:business_tests`,
  per the Migration Plan's mandatory verification step.
- **CI**: real GitHub Actions Bazel Android/JVM/test job runs — required, not optional, per
  requirements.md's Constraints ("CI-verified, incremental rollout is mandatory... Local 'it builds
  and passes' is not sufficient proof at this scale," citing the persistent-workers OOM precedent).
- **Migration reversibility**: scripted revert-and-rebuild check (`git revert` + `bazel
  build`/`bazel test`), run at least once against the `stats` pilot package.

## Coverage Targets and How to Measure

| Check type | Command | Target |
|---|---|---|
| Macro unit tests | `bazel test //:kotlin_common_test` (bazel_skylib `analysistest`) | Both the plugin-splice path and the `deps`/`associates` `fail()` guard exercised |
| `check_internal_visibility.py` unit tests | `pytest scripts/test_check_internal_visibility.py` | 6/6 known-good pairs reproduced exactly; the Flow-collision and comment false positives suppressed; wildcard imports flagged, not silently passed |
| `check_no_duplicate_kt_ownership.py` unit tests | `pytest scripts/test_check_no_duplicate_kt_ownership.py` | Exit 0 on the real, correctly-migrated repo state; exit non-zero naming files+targets on the deliberately-broken fixture |
| Per-package standalone build | `bazel build //kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkg>:<pkg>` | 9/9 Tier 1 + 11/11 Tier 2 packages succeed standalone |
| Full-graph build/test | `bazel build //kmp:desktop_app //kmp:android_app --config=android && bazel test //kmp:jvm_tests //kmp:business_tests` | Green after every one of the 20 package extractions, not just at the end |
| CI real-run verification | GitHub Actions Bazel Android/JVM/test jobs | Green, with memory-headroom evidence recorded, for every Phase 1/2/4 PR (rules_kotlin upgrade, worker cap, each Tier 1/2 extraction) |
| Go/no-go profiling checkpoint | `bazel build ... --profile=/tmp/after.json` + `bazel analyze-profile`/jq top-N | Tier 1's 9 `KotlinCompile` actions individually proportional to file count (no single action near 71s); a `stats`-only change re-executes only `:stats`'s action |
| ABI-stability pre-flight gate | `bazel build //kmp/.../stats:stats --profile=/tmp/abi_check.json` after touching an unrelated Tier-3 file | Re-analysis only, no re-execution, of `:stats`'s `KotlinCompile` action — hard gate before Epic 2.2 |
| Migration reversibility | `git revert <extraction commit>` + rebuild | Green both before and after revert, for at least the `stats` pilot package |

- All 15 of plan.md's epics (1.1–1.8, 2.1–2.3, 3.1, 4.1–4.2, 5.1) have at least one happy-path
  and one error/edge-path verification case above.
- All external dependencies flagged as risks in requirements.md (rules_kotlin version/patch
  compatibility, `associates`-at-scale, CI runner memory, kibitzer's config/determinism) have at
  least one integration- or CI-level real check, not a unit test alone.
