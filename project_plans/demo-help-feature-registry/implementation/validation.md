# Validation Plan

**Project**: demo-help-feature-registry | **Date**: 2026-10-11 | **Phase**: 4 (validate) | **Status**: planning only, no code written

Inputs read: `../requirements.md`, `plan.md` (including "Amendments from adversarial review" A-1..A-14, which supersede conflicting text), `adversarial-review.md`, `../decisions/ADR-001..003`, `../research/pitfalls.md`. Labels: VERIFIED = file opened or command run while writing this; INFERRED = reasoned.

Test naming convention (consistent across every row): `methodName_should_X_When_Y`, camelCase prefix with underscores, no backticks (valid on JVM, Android and wasm). Shell tests use `test_<subject>_<expectation>_<condition>` function names in `scripts/test/docs-staleness-test.sh`. All Kotlin test classes sit in `kmp/src/businessTest/kotlin/dev/stapler/stelekit/docs/registry/` (tests) unless a different path is given in the plan (`DemoGraph*Test` in `.../docs/`, `DemoFileSystemContentParityTest` and `DemoGraphSizeBudgetTest` in `.../db/`, `HelpTargetTest` in `commonTest`, `HelpMenuTest` in `androidUnitTest`). Production code lives in `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/featureregistry/` (Amendment A-3).

## Requirements derived from requirements.md

| Id | Requirement | Source in requirements.md |
|---|---|---|
| **REQ-1** | A new Screen / settings category / command / git-sync state / shortcut with no declared coverage warns in Phase A and fails from Phase B, with a message naming the fix | Success Metrics bullet 1; Scope "Lint/CI rules" |
| **REQ-2** | Registry integrity: entries cover real candidates, ids unique, no candidate double-claimed, exemptions carry a reason enum, owner and an unexpired date | Scope "feature registry data model"; Constraints "single source of truth plus automated checks" |
| **REQ-3** | Demo-graph coverage: every registered `demoPage` exists and is non-empty | Success Metrics bullet 2; Problem Statement (demo graph) |
| **REQ-4** | Every demo page parses through the real loader/parser | Success Metrics bullet 2 ("parses (loaded through the real demo-graph loader)") |
| **REQ-5** | Wikilinks in demo pages resolve (dangling links baselined, shrink-only) | Scope "link checks"; plan A2.1 |
| **REQ-6** | Website coverage: every registered `sitePage` exists, and site internal links are validated | Problem Statement (website); Scope "website coverage ... link checks" |
| **REQ-7** | In-app help coverage has a definition (help = registered demo page, Kotlin `*Docs` retired) and Screen annotation/registry parity holds during migration | Problem Statement (in-app help); Open Question 1; Amendment A-9 |
| **REQ-8** | Staleness signal: "feature source changed but docs/demo/site did not" is reported per PR | Success Metrics bullet 3 |
| **REQ-9** | Noise budget: fewer than 1 false-positive failure per ~20 feature-touching PRs, tracked | Success Metrics bullet 4 |
| **REQ-10** | Existing gaps are baselined and the baseline (and exemption list) can only shrink | Success Metrics bullet 5 |
| **REQ-11** | Contributor and agent notification (CLAUDE.md section, PR template, failure messages, make target) | Scope "Contributor/agent notification" |
| **REQ-12** | Runs under both Bazel (`//kmp:business_tests`) and Gradle (`jvmTest`) | Constraints bullet 2 |
| **REQ-13** | KMP portability as amended: registry in jvmMain (not shipped to Android/iOS/wasm), no kotlin-reflect, no JVM-only API in commonMain, ISO-string dates | Constraints bullet 3; Amendments A-3, A-2 |
| **REQ-14** | Committed generated `DemoFileSystem.kt` stays in sync with the demo resources | Verified Current State (DemoFileSystem); plan A2.2 |
| **REQ-15** | Estimation uses price-weighted cost classes and named wall-clock blockers only, never human-time units | Constraints bullet 4 |

## Requirement -> Test Mapping

Types: Unit = pure function or class test (businessTest/commonTest); Integration = touches a data store (repo files, bundled resources), CI job or shell script against a throwaway git repo; Proof run = CI workflow demonstrated red on a scratch branch (cannot be a unit test).

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-1 | `docs/registry/FeatureRegistryRulesTest.kt` | `ruleUnregisteredCandidate_should_ReportWarningWithStubAndFilePath_When_ModeIsWarn` | Unit | Synthetic candidate set has `screen:Foo` with no entry; checker in WARN mode returns one warning containing `ScreenFeatures.kt` and a copy-pasteable `FeatureEntry(` stub, test does not fail |
| REQ-1 | `docs/registry/FeatureRegistryRulesTest.kt` | `ruleUnregisteredCandidate_should_Fail_When_ModeIsFail` | Unit | Same input with `FeatureGate.registrationMode = FAIL` returns a failure naming `screen:Foo` |
| REQ-1 | `docs/registry/FeatureRegistryStructureTest.kt` | `allCandidates_should_BeRegisteredOrExempt_When_RealRegistryLoaded` | Integration | Real `FeatureCandidates.all()` (75 candidates: 15 screens, 13 settings, 24 commands, 14 sync states, 9 shortcuts) against real `FeatureRegistry.all`; asserts unregistered count is 0 |
| REQ-1 | `docs/registry/FeatureRegistryStructureTest.kt` | `screenCandidates_should_MatchPermittedSubclasses_When_Enumerated` | Integration | `Screen::class.java.permittedSubclasses` size (15) equals explicit `screen:` list; same for `SyncState` (14); a 16th subclass would turn this red with the registry message, not a compile error (Amendment A-2; spike S0.1) |
| REQ-1 | `.github/workflows/bazel-ci.yml` (B1.2.2 scratch PR) | proof run: add dummy 16th `Screen` on throwaway branch | Proof run | After promotion (B1.2) the `business_tests` step fails with the T-A1.1.4 message; revert, never merge |
| REQ-2 | `docs/registry/FeatureRegistryRulesTest.kt` | `ruleDuplicateId_should_Fail_When_TwoEntriesShareId` | Unit | Two synthetic entries with id `flashcards` produce an R4 failure |
| REQ-2 | `docs/registry/FeatureRegistryRulesTest.kt` | `ruleDanglingCovers_should_Fail_When_EntryCoversMissingCandidate` | Unit | Entry covers `cmd:does.not.exist`; R2 fires with entry id in message |
| REQ-2 | `docs/registry/FeatureRegistryRulesTest.kt` | `ruleExemption_should_Fail_When_DetailBlankOwnerMissingOrDateMalformed` | Unit | Three synthetic exemptions (blank detail, blank owner, malformed `expires` such as `2026-1-5`) each yield R3 failures; a valid exemption yields none (happy path) |
| REQ-2 | `docs/registry/FeatureRegistryRulesTest.kt` | `ruleExemptionExpiry_should_WarnWithin14DaysAndFailOnlyInScheduledMode_When_DateNearOrPast` | Unit | Injected `today`: 10 days before `expires` gives a `::warning::` only; past `expires` is a failure only when `expiryMode = SCHEDULED` (Amendment A-5, never on PR) |
| REQ-2 | `docs/registry/FeatureRegistryRulesTest.kt` | `ruleDoubleClaim_should_Fail_When_CandidateCoveredByTwoEntries` | Unit | `settings:VAULT` in two entries fires R5 |
| REQ-2 | `docs/registry/FeatureRegistryStructureTest.kt` | `realRegistry_should_PassIntegrityRules_When_CheckedAgainstCandidates` | Integration | R2-R5 over the committed registry with real clock, expiry only a warning inside 14 days (Amendment A-5) |
| REQ-2 | `.github/workflows/feature-registry-expiry.yml` (T-C1.1.2) | proof run: `workflow_dispatch` with an exemption dated in the past on a scratch branch | Proof run | Scheduled run uses `--nocache_test_results` and goes red naming the expired id; a cached PR-time run stays green (Bazel cache time bomb defused) |
| REQ-3 | `docs/registry/FeatureRegistryContentTest.kt` | `demoPage_should_ExistAndBeNonEmpty_When_RegisteredAndNotBaselined` | Unit | Fake resource provider returns text for `Flashcards`; rule passes |
| REQ-3 | `docs/registry/FeatureRegistryContentTest.kt` | `demoPage_should_FailOrWarnPerMode_When_PageMissingOrBlank` | Unit | Missing page and blank page: WARN in Phase A, FAIL for non-baselined id in Phase B mode; baselined id stays WARN |
| REQ-3 | `docs/registry/FeatureRegistryContentTest.kt` | `registeredDemoPages_should_ExistInBundledDemoGraph_When_RunUnderBothRunners` | Integration | Real bundled `demo-graph/pages` via `DemoGraphResources.kt` (jar and file URLs) for all registry rows |
| REQ-3 | `docs/registry/ScreenCoverageTest.kt` | `screenRows_should_HaveExistingDemoPage_When_ScreenHasHelpPageParity` | Integration | Port of `DemoGraphCoverageTest` (5 `@HelpPage` screens: Daily Notes, Flashcards, All Pages, Unlinked References, Block Editing) |
| REQ-4 | `docs/DemoGraphParseTest.kt` | `parsePage_should_ReturnNonEmptyBlocks_When_ValidDemoMarkdown` | Unit | `MarkdownParser().parsePage` on an inline fixture returns blocks, no exception |
| REQ-4 | `docs/DemoGraphParseTest.kt` | `parsePage_should_BeReportedWithPageName_When_ParserThrowsOrReturnsEmpty` | Unit | Injected throwing/empty parse function yields a failure message naming the page |
| REQ-4 | `docs/DemoGraphParseTest.kt` | `allDemoMarkdownFiles_should_Parse_When_LoadedFromClasspath` | Integration | All 25 `.md` files under `demo-graph/` (recount: `git ls-files kmp/src/commonMain/resources/demo-graph \| grep -c '\.md$'`) parse and recurse into `children` |
| REQ-5 | `docs/DemoGraphLinkResolutionTest.kt` | `wikilinkTargets_should_Resolve_When_PageOrJournalFileExists` | Unit | Pure `normalizePageName` plus fixture page set resolves `[[Flashcards]]`, case and extension variants as `GraphLoader` does |
| REQ-5 | `docs/DemoGraphLinkResolutionTest.kt` | `wikilinkTargets_should_ReportPageAndTarget_When_LinkDangling` | Unit | Fixture with `[[Nowhere]]` reports page name and target; not in baseline means failure |
| REQ-5 | `docs/DemoGraphLinkResolutionTest.kt` | `danglingLinkBaseline_should_OnlyShrink_When_StaleEntryListed` | Integration | Real `docs/registry/demo-link-baseline.txt` bundled as resource: an entry no longer dangling fails the test (forces deletion) |
| REQ-6 | `docs/registry/FeatureRegistryContentTest.kt` | `sitePage_should_Exist_When_RegisteredAndMarketed` | Unit | Fake site-doc name set containing `flashcards`; rule passes |
| REQ-6 | `docs/registry/FeatureRegistryContentTest.kt` | `sitePage_should_FailOrWarnPerMode_When_MdxMissingForMarketedRow` | Unit | Missing `.mdx` for a MARKETED row; DEMO_ONLY row with null `sitePage` is not a gap |
| REQ-6 | `docs/registry/FeatureRegistryContentTest.kt` | `registeredSitePages_should_ExistInBundledSiteDocs_When_RunUnderBazel` | Integration | `site_user_docs_as_resources` bundle (9 mdx today); proven by deleting a bundled mdx locally and seeing red |
| REQ-6 | `site/astro.config.mjs` + `.github/workflows/site-ci.yml` (A4.1) | proof run: scratch PR with a deliberately broken internal link | Proof run | `starlight-links-validator` (pin from S0.2, first candidate 0.20.0) fails `npm run build` |
| REQ-7 | `docs/registry/ScreenAnnotationParityTest.kt` | `screenRow_should_MatchHelpPageAnnotation_When_ScreenIsAnnotated` | Unit | Reflection on `@HelpPage` (RUNTIME retention) agrees with the registry row |
| REQ-7 | `docs/registry/ScreenAnnotationParityTest.kt` | `screenRow_should_Fail_When_ExemptionStatusDisagreesWithAnnotation` | Unit | Synthetic mismatch (registry exempt, annotation present) fails; includes `AssetDetail`, which the old hand-mirrored list omitted |
| REQ-7 | `docs/registry/ScreenAnnotationParityTest.kt` | `exemptionParity_should_HoldAgainstAppStateSource_When_BundledAsResource` | Integration | `@HelpExempt` is SOURCE retention, so regex over bundled `AppState.kt` (or one-time diff recorded in PR if that half is dropped per A-14); deleted in B3.1 |
| REQ-7 | `commonTest/.../docs/registry/HelpTargetTest.kt` (optional C3.1, D4) | `forScreen_should_ReturnDemoPageName_When_ScreenMapped` | Unit | `HelpTarget.forScreen` returns `Flashcards` for the flashcards screen |
| REQ-7 | `commonTest/.../docs/registry/HelpTargetTest.kt` (optional C3.1, D4) | `forScreen_should_ReturnFallback_When_ScreenExempt` | Unit | Exempt screen returns fallback target, not null/exception |
| REQ-7 | `androidUnitTest/.../ui/components/HelpMenuTest.kt` (optional C3.1, D4) | `helpMenu_should_BeEnabledAndRoute_When_ScreenMapped` | Integration | Robolectric Compose test: button enabled, routes to expected page for 5 mapped Screens, fallback for exempt (TopBar.kt:355-366) |
| REQ-8 | `scripts/test/docs-staleness-test.sh` | `test_staleness_should_ReportHit_When_SourceChangedAndDocsUntouched` | Integration | Throwaway git repo, mapped source edited, no doc touched: one `feature <id>: ...` line, exit 0 in warn mode |
| REQ-8 | `scripts/test/docs-staleness-test.sh` | `test_staleness_should_ReportNothing_When_WhitespaceOnlyOrTestOnlyDiff` | Integration | `git diff -w --quiet` case and `*Test*.kt`-only case produce no hit |
| REQ-8 | `scripts/test/docs-staleness-test.sh` | `test_staleness_should_Skip_When_TrailerLabelOrBotActor` | Integration | `Docs-Exempt:` trailer with reason, `docs-exempt` label env, `release-please[bot]` actor each yield no hit and an echoed summary line; empty trailer reason is rejected |
| REQ-8 | `scripts/test/docs-staleness-test.sh` | `test_staleness_should_ExitNonZero_When_FailModeAndFeatPr` | Integration | `--fail` mode (B2.1) exits non-zero on a hit; extended with a `feat` case |
| REQ-8 | `.github/workflows/ci.yml` job `docs-staleness` (A3.1.3) | proof run: scratch PR touching a mapped source without docs | Proof run | Job summary lists the hit; sticky comment only when token allows; also fires on title `edited` (A-13) |
| REQ-8 | `docs/registry/FeatureRegistryRulesTest.kt` | `exportedRegistry_should_ContainSourcesAndPages_When_SerializedForScript` | Unit | `FeatureRegistryExport` JSON round-trips `id, sources, demoPage, sitePage, audience, exempt`; command rows exclude `EssentialCommands.kt` from `sources` (A-10) |
| REQ-9 | `scripts/test/docs-gate-report-test.sh` (new, NEW action NA-5) | `test_report_should_ComputeFpRate_When_PrsLabelledDocsGateFp` | Integration | Stub `gh` shim returns canned PR JSON: 20 feature-touching PRs, 1 labelled `docs-gate-fp` gives rate 0.05 |
| REQ-9 | `scripts/test/docs-gate-report-test.sh` (new, NEW action NA-5) | `test_report_should_NotDivideByZero_When_NoFeatureTouchingPrs` | Integration | Empty PR list prints a no-data line, exit 0 |
| REQ-9 | `docs/registry/FeatureRegistryRulesTest.kt` | `warnings_should_BeEmittedAsGithubWarningLines_When_RuleWarns` | Unit | Warn output starts with `::warning::` so the CI step can grep it into `$GITHUB_STEP_SUMMARY` (A-6); `report.md` file not used |
| REQ-10 | `docs/registry/FeatureRegistryStructureTest.kt` | `baseline_should_ListOnlyCurrentGaps_When_EntryResolved` | Unit | Fake baseline lists `images:demo` which now has a page: test fails, forcing deletion (shrink mechanism) |
| REQ-10 | `docs/registry/FeatureRegistryStructureTest.kt` | `exemptionsBaseline_should_EqualRegistryExemptIds_When_Compared` | Unit | Added exemption missing from `feature-exemptions-baseline.txt` (and the reverse) fails |
| REQ-10 | `.github/workflows/ci.yml` ratchet step (A3.2.1) | proof run: scratch PR adding one id to `feature-coverage-baseline.txt` | Proof run | Step compares to merge-base (`git show "$MERGE_BASE:<file>"`, pattern of `ci.yml:157-166`) and fails naming the id; passes with label `baseline-reclassification` only on the D1 PR |
| REQ-10 | `docs/registry/feature-coverage-baseline.txt` via `:kmp:writeFeatureBaseline` | `writeFeatureBaseline_should_BeDeterministicAndSorted_When_RunTwice` (Gradle task check in A1.1.6 PR; script `scripts/test/baseline-determinism-test.sh`, NEW action NA-6) | Integration | Two consecutive runs give byte-identical output (Amendment A-12) |
| REQ-11 | `docs/registry/FeatureRegistryMessagesTest.kt` | `message_should_ContainPathAndExemptSyntax_When_BuiltForEveryRule` | Unit | For R1..R5, content rules, baseline rule: each message contains a repo path and the word `exempt` syntax |
| REQ-11 | `docs/registry/FeatureRegistryMessagesTest.kt` | `message_should_Fail_When_PathOrExemptSyntaxMissing` | Unit | A builder stub returning a vague message ("coverage missing") fails the audit helper |
| REQ-11 | `scripts/test/docs-check-target-test.sh` (NEW action NA-7) | `test_makeDocsCheck_should_ListGradleAndStalenessSteps_When_DryRun` | Integration | `make -n docs-check` output contains `jvmTest --tests '*registry*'` and `scripts/docs-staleness.sh`, not `checkDocCoverage` (A-7); CLAUDE.md section line count `<= 40` and mentions `make docs-check` |
| REQ-12 | `docs/AllBusinessTestsCompletenessTest.kt` (existing) | `allBusinessTests_should_ListEveryClass_When_NewRegistryTestsAdded` | Unit | Existing test stays green with the 9+ new classes registered in `AllBusinessTests.kt` |
| REQ-12 | `kmp/src/businessTest/kotlin/BUILD.bazel` | `bazel test //kmp:business_tests` (full target) | Integration | Registry, content and demo tests run from bundled resources (`site_user_docs_as_resources`, `feature_baseline_as_resource`); recorded as W3 per story |
| REQ-12 | `docs/registry/FeatureRegistryContentTest.kt` | `repoFileLocator_should_ReadSameBytes_When_ClasspathOrGradleWalkUp` | Unit | `DocRepoLocator.siteUserDocNames()` returns the same set via classpath and via walk-up fallback (fake roots) |
| REQ-12 | `./gradlew :kmp:jvmTest --tests '*registry*'` (local, recorded in each story PR per A-1) | same registry classes | Integration | Gradle parity run; not CI-enforced (named gap, see Readiness Gate concerns) |
| REQ-13 | `docs/registry/FeatureRegistryStructureTest.kt` | `registryPackage_should_NotBeReferencedFromCommonMain_When_SourcesScanned` | Unit | Scan of `kmp/src/commonMain` finds no import of `dev.stapler.stelekit.featureregistry` (until D4 = yes) |
| REQ-13 | `docs/registry/FeatureRegistryStructureTest.kt` | `expires_should_CompareAsIsoStrings_When_DatesDiffer` | Unit | Lexicographic compare of `2026-10-11` vs `2026-12-01` behaves; malformed date (`2026-1-5`) is rejected as R3 failure |
| REQ-13 | `bazel build //kmp:desktop_app`, `./gradlew :kmp:detekt` | `JvmOnlyApiInCommonMainRule` (existing detekt) | Integration | Detekt green and no kotlin-reflect dep added to `business_tests`; wasm/Android compile unaffected because registry is jvmMain |
| REQ-14 | `db/DemoFileSystemContentParityTest.kt` | `readFile_should_EqualResourceText_When_DemoPathRead` | Unit | `DemoFileSystem().readFile(path)` equals resource text byte for byte for one fixture path |
| REQ-14 | `db/DemoFileSystemContentParityTest.kt` | `readFile_should_ReportPath_When_ContentDiffers` | Unit | Injected stale copy yields a failure naming the path and the regenerate command |
| REQ-14 | `.github/workflows/ci.yml` job `demo-fs-sync` (A2.2.2) | proof run: scratch commit with stale `DemoFileSystem.kt` | Proof run | `./gradlew :kmp:generateDemoFileSystem` then `git diff --exit-code` turns red with regenerate-and-commit message |
| REQ-14 | `db/DemoGraphSizeBudgetTest.kt` | `demoGraphSize_should_StayUnderOneMegabyte_When_ResourcesSummed` | Integration | Sum of resource bytes under the 1 MB budget (35,666 bytes today); synthetic oversize input fails |
| REQ-15 | `scripts/test/plan-units-lint-test.sh` (NEW action NA-8) | `test_lint_should_PassPlanFiles_When_NoHumanTimeUnits` | Integration | A digit-plus-unit grep (hours, days, weeks, minutes) over `plan.md` and `decisions/ADR-*.md` returns nothing except allowlisted `14 days` (expiry warning window, a date rule not an estimate) and the `timeout 30m` command |
| REQ-15 | `scripts/test/plan-units-lint-test.sh` (NEW action NA-8) | `test_lint_should_Fail_When_FixtureContainsHours` | Integration | Fixture with "about 3 hours" makes the lint exit non-zero |

REQ-15 is a planning-document requirement, so its tests are shell lints over markdown rather than product code; if the owner does not want a standing lint, the one-time grep recorded in this file (see Readiness Gate) is the evidence.

## Test Stack

- Kotlin: `kotlin.test` annotations only (no Kotest Spec runner), `kotest-assertions-core` and `kotest-property` as plain libraries, per `CLAUDE.md` "Testing best practices". Property tests (`Arb`/`checkAll` inside `runTest`) for the pure rule functions in `FeatureRegistryRulesTest` (arbitrary candidate and entry sets: invariant "every rule violation produces exactly one message containing its subject id") and for `normalizePageName`.
- Runners: Bazel `bazel test //kmp:business_tests` (PR gate, `.github/workflows/bazel-ci.yml:138`), Gradle `./gradlew :kmp:jvmTest --tests '*registry*'` (local only, Amendment A-1). Display not required (businessTest). `timeout 30m` wrapper on every Bazel call.
- Shell: bash tests under `scripts/test/` (directory does not exist yet, VERIFIED by `ls`), shellcheck-clean, run in a temp git repo via `mktemp -d`; wired into `make docs-check` and the `docs-staleness` job.
- CI workflows: `actionlint -color`, `uvx 'zizmor==1.25.2' .`, plus one proof run on a scratch PR per new job.
- Robolectric (`androidUnitTest`) only for optional `HelpMenuTest`.
- Clock: all expiry rules take an injected `today: String`, never `System.currentTimeMillis`, so tests are deterministic.

## Coverage Targets

- At least 80% line coverage for new registry code in `featureregistry/` (model, candidates, rule functions, message builder, export). Measured with Kover or JaCoCo on the businessTest run; if neither is wired for the Bazel path, measured on the Gradle run and reported in the A1.1 PR (INFERRED: coverage tooling availability not verified; named gap).
- Every public check function (R1..R5, content-existence, parse, link, ratchet-equality, message builder) has at least one happy-path and one error-path unit test, listed in the mapping table. Missing pair is a plan defect.
- Every new CI job and script has one proof run showing red (mutation table below).
- Zero `@Ignore`, retry or tolerance additions to make a test pass (`CLAUDE.md` "Root-cause failing tests").

## Mutation-Proof Table

Each gate must be shown able to fail. The mutation is applied on a scratch branch or in a synthetic input and reverted.

| Gate | Mutation that must turn it red | Evidence form |
|---|---|---|
| R1 unregistered candidate (hard mode) | Add a dummy 16th `Screen` subclass (B1.2.2); or delete one `ScreenFeatures.kt` row | `FeatureRegistryRulesTest` synthetic case plus scratch-PR red on `bazel-ci.yml` `business_tests` |
| R2 entry covers nothing | Rename a `Screen` subclass without updating `covers` | `ruleDanglingCovers_should_Fail_When_EntryCoversMissingCandidate` |
| R3 exemption invalid | Blank `detail`, blank owner or malformed `expires` in a row | `ruleExemption_should_Fail_When_DetailBlankOwnerMissingOrDateMalformed` |
| R3 exemption expired | Set `expires = "2020-01-01"` in a row | `ruleExemptionExpiry_should_WarnWithin14DaysAndFailOnlyInScheduledMode_When_DateNearOrPast`; scheduled `feature-registry-expiry.yml` run with `--nocache_test_results` red |
| R4 duplicate id | Copy a row with the same id | `ruleDuplicateId_should_Fail_When_TwoEntriesShareId` |
| R5 double-claimed candidate | List `settings:VAULT` in two rows | `ruleDoubleClaim_should_Fail_When_CandidateCoveredByTwoEntries` |
| Content existence (demo) | Delete `demo-graph/pages/Flashcards.md` (and not the registry row) | `registeredDemoPages_should_ExistInBundledDemoGraph_When_RunUnderBothRunners` red under both runners |
| Content existence (site, Bazel bundle) | Delete a bundled `site/src/content/docs/user/*.mdx` | Red under `bazel test //kmp:business_tests` only if the bundle works (plan A1.2 acceptance) |
| Demo parse | Insert content that makes `MarkdownParser` throw or return empty blocks | `allDemoMarkdownFiles_should_Parse_When_LoadedFromClasspath` |
| Wikilink resolution | Add `[[Nowhere]]` to a demo page | `wikilinkTargets_should_ReportPageAndTarget_When_LinkDangling`; real test red |
| Baseline staleness (shrink) | Leave a resolved id in `feature-coverage-baseline.txt` | `baseline_should_ListOnlyCurrentGaps_When_EntryResolved` |
| Baseline ratchet (CI) | Add an id to `feature-coverage-baseline.txt` vs merge-base | `ci.yml` ratchet step red on scratch PR |
| Exemption ratchet (C1.1) | Add an exemption without updating `feature-exemptions-baseline.txt`; later, grow the file | equality test red now; CI step red after C1.1 |
| Staleness script | Edit a mapped source with real (non-whitespace) change, no doc touch, `--fail` | `test_staleness_should_ExitNonZero_When_FailModeAndFeatPr` plus scratch-PR summary hit |
| DemoFileSystem drift | Edit a demo page, do not regenerate | `demo-fs-sync` red on scratch commit; `readFile_should_EqualResourceText_When_DemoPathRead` fixture |
| Size budget | Add a 2 MB file under `demo-graph/` | `demoGraphSize_should_StayUnderOneMegabyte_When_ResourcesSummed` |
| Site link validator | Add a broken internal link in an mdx | `site-ci.yml` red on scratch PR |
| AllBusinessTests completeness | Add a registry test class without listing it | `AllBusinessTestsCompletenessTest` red (existing) |
| Message audit | Replace one message with a vague string | `message_should_Fail_When_PathOrExemptSyntaxMissing` |
| Screen parity (migration) | Flip a `@HelpPage` on one Screen without changing the row | `screenRow_should_Fail_When_ExemptionStatusDisagreesWithAnnotation` |

## Requirement coverage count

Recompute with (run from the repo root, rows counted by REQ id in this file's mapping table):

```
for i in $(seq 1 15); do printf 'REQ-%s: ' $i; grep -c "^| REQ-$i |" project_plans/demo-help-feature-registry/implementation/validation.md; done
```

Output of that command on 2026-10-11 (rows per requirement):

| REQ | Rows | REQ | Rows | REQ | Rows |
|---|---|---|---|---|---|
| REQ-1 | 5 | REQ-6 | 4 | REQ-11 | 3 |
| REQ-2 | 7 | REQ-7 | 6 | REQ-12 | 4 |
| REQ-3 | 4 | REQ-8 | 6 | REQ-13 | 3 |
| REQ-4 | 3 | REQ-9 | 3 | REQ-14 | 4 |
| REQ-5 | 3 | REQ-10 | 4 | REQ-15 | 2 |

Total mapping rows: 61 (`grep -c '^| REQ-' validation.md`). All 15 requirements have at least 2 rows; minimum is REQ-15 (planning-doc requirement, shell lint only). The derivation table at the top uses bold ids (`**REQ-n**`) so it does not inflate the count.

## Readiness Gate

Run inline 2026-10-11. Criteria and evidence:

| # | Criterion | Evidence | Result |
|---|---|---|---|
| 1 | Every requirement has at least 1 test | Count command above: min 2 rows per REQ, 15 of 15 covered, 61 rows | PASS |
| 2 | No TODO/TBD placeholders in plan.md architecture/task sections | `grep -nE 'TODO\|TBD\|FIXME' implementation/plan.md` returned no matches (exit 1) | PASS |
| 3 | ADRs referenced by the plan exist | `ls decisions/` lists `ADR-001-registry-shape.md`, `ADR-002-derive-vs-declare.md`, `ADR-003-enforcement-strictness.md`; plan references exactly ADR-001, ADR-002, ADR-003 | PASS |
| 4 | No BLOCKER in adversarial-review.md | Latest section "Re-review (2026-10-11)" at `adversarial-review.md:65` has verdict CONCERNS with "0 blockers, 3 concerns (N1, N2, N3), 4 minors"; B1 and B2 resolved via Amendments A-1 and A-2. The first-pass top-line "Verdict: BLOCKED" (line 5) is superseded by the re-review | PASS (with open concerns) |

**Verdict: CONCERNS.** No criterion fails, but proceed only after the items below are acknowledged; none block starting Wave 0 spikes.

Open concerns carried into implementation:

1. Re-review N1 to N3 (stale body text in `plan.md` contradicting amendments, ADR-002:18 and ADR-003 path/R3 caveat, C3.1 jvmMain vs commonMain conflict) are text fixes, not yet made. `plan.md` is being edited concurrently (line 117 already reflects A-5); re-grep before implementing.
2. Gradle parity for the registry tests is a local run only (Amendment A-1; `ci.yml` runs no `jvmTest`). The REQ-12 Gradle row is therefore not CI-evidenced.
3. Coverage tooling (Kover/JaCoCo) for the 80% target is not verified to exist on the Bazel path (INFERRED); the target may be measurable only on Gradle.
4. Test-support work that this validation plan assumes but `plan.md` does not list as tasks (all defined as NA-5..NA-8 in `pre-mortem.md`): `scripts/test/docs-gate-report-test.sh` (NA-5), baseline determinism script (NA-6), `make docs-check` dry-run test (NA-7), human-time-unit lint (NA-8). `scripts/test/` does not exist yet (VERIFIED by `ls`). Also T-C1.1.2 (expiry workflow) is named in A-5 but absent from Phase C and the effort table (re-review N4).
5. Optional C3.1 rows (`HelpTargetTest`, `HelpMenuTest`) depend on D4 and on the N3 fix (data must be in commonMain for `commonTest`/Robolectric to see it); they are not required for the gate to ship.


## Coordinator note (post-gate)

After this file was written, `plan.md` stale text N1-N3 from the re-review was fixed in place and amendments A-15 (blocktype candidates + zero-covers canary) and A-16 (shell-test coverage in T-A3.1.2 and T-A6.1.1) were added. Add rows for rule R6 (empty `covers` needs an exemption) and the 12 `blocktype:` candidates to `FeatureRegistryRulesTest` when implementing. Gate verdict stays CONCERNS (Gradle parity is local-only; coverage tooling for the 80% target is unverified under Bazel).
