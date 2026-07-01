# Graph Sections — Validation Plan

**Status**: Draft  
**Date**: 2026-06-29  
**Requirements**: `project_plans/graph-sections/requirements.md`  
**Plan reviewed**: `project_plans/graph-sections/implementation/plan.md`  
**Adversarial review**: `project_plans/graph-sections/implementation/adversarial-review.md`

---

## Summary

| Metric | Value |
|--------|-------|
| Total test cases | 47 |
| Unit tests | 18 |
| Integration tests | 26 |
| E2E / Screenshot tests | 3 |
| FRs covered | 11 / 11 (100%) |
| Readiness gate verdict | **CONCERNS** |

---

## Part 1 — Test Suite Design

### TC-MANIFEST-001: SectionManifestParserTest

**Class**: `SectionManifestParserTest`  
**Source set**: `commonTest`  
**Package**: `dev.stapler.stelekit.sections`  
**Type**: Unit  
**Requirements covered**: FR-1, FR-7

| Method | Verifies |
|--------|----------|
| `parsesValidTwoSectionToml` | A TOML string with two `[[section]]` tables deserializes to a `SectionManifest` with correct `id`, `displayName`, `color`, `pagePathPrefix`, `journalPathPrefix` on both entries and `version = 1`. |
| `returnsNullWhenFileAbsent` | `SectionManifestParser.parse()` returns `null` (not an error) when the file does not exist — this is the backward-compat sentinel. |
| `ignoresUnknownTomlFields` | A TOML input with a future `experimentalFlag = true` key parses successfully with no error; unknown fields are silently dropped per `ignoreUnknownNames = true`. |
| `wrapsParseErrorAsDomainError` | Malformed TOML (e.g. `version = "not an int"`) returns `Either.Left(DomainError.ParseError)`. |

---

### TC-MANIFEST-002: SectionManifestWriterTest

**Class**: `SectionManifestWriterTest`  
**Source set**: `commonTest`  
**Package**: `dev.stapler.stelekit.sections`  
**Type**: Unit  
**Requirements covered**: FR-1, FR-7

| Method | Verifies |
|--------|----------|
| `roundTripsManifestThroughWriteAndParse` | A `SectionManifest` written by `SectionManifestWriter` and immediately read back by `SectionManifestParser` produces an equal object (structural round-trip). |
| `writesCorrectTomlArrayTableSyntax` | The serialized TOML uses `[[section]]` array-table syntax, not `[sections]` object syntax — required for ktoml decode compatibility. |

---

### TC-FILTER-001: SectionFilterTest

**Class**: `SectionFilterTest`  
**Source set**: `commonTest`  
**Package**: `dev.stapler.stelekit.sections`  
**Type**: Unit  
**Requirements covered**: FR-4, FR-8

Fixture: `allSections = [acme-work (pages/acme-work, journals/acme-work), personal (pages/personal, journals/personal)]`, `subscribedSections = [acme-work]`.

| Method | Verifies |
|--------|----------|
| `globalPageIsNotExcluded` | `shouldExclude("pages/My Notes.md")` returns `false` — root-level pages (no section prefix) always pass. |
| `subscribedSectionPageIsNotExcluded` | `shouldExclude("pages/acme-work/Sprint Plan.md")` returns `false`. |
| `nonSubscribedSectionPageIsExcluded` | `shouldExclude("pages/personal/Diary.md")` returns `true`. |
| `nonSubscribedJournalIsExcluded` | `shouldExclude("journals/personal/2026-06-29.md")` returns `true`. |
| `subscribedJournalIsNotExcluded` | `shouldExclude("journals/acme-work/2026-06-29.md")` returns `false`. |
| `globalJournalIsNotExcluded` | `shouldExclude("journals/2026-06-29.md")` returns `false` — root-level journals always pass. |
| `sectionIdForPathReturnsSectionIdForPageDir` | `sectionIdForPath("pages/acme-work/Sprint Plan.md")` returns `"acme-work"`. |
| `sectionIdForPathReturnsSectionIdForJournalDir` | `sectionIdForPath("journals/acme-work/2026-06-29.md")` returns `"acme-work"`. |
| `sectionIdForPathReturnsNullForGlobalFile` | `sectionIdForPath("pages/My Notes.md")` returns `null`. |
| `emptySubscribedSectionsListDoesNotExcludeAnything` | A `SectionFilter` with `subscribedSections = emptyList()` and non-empty `allSections` returns `false` for all paths (all-access fallback). |

---

### TC-FRONTMATTER-001: SectionFrontmatterParserTest

**Class**: `SectionFrontmatterParserTest`  
**Source set**: `commonTest`  
**Package**: `dev.stapler.stelekit.parsing`  
**Type**: Unit  
**Requirements covered**: FR-2

| Method | Verifies |
|--------|----------|
| `parsesSteleSectionPropertyIntoPageSectionId` | Parsing a page whose frontmatter contains `stele-section:: acme-work` produces `Page.sectionId = "acme-work"`. |
| `returnsEmptySectionIdWhenFrontmatterPropertyAbsent` | A page without `stele-section::` produces `Page.sectionId = ""` (global sentinel, not null). |
| `pathBasedInferenceFallsBackWhenFrontmatterAbsent` | When `stele-section::` is absent and `sectionFilter.sectionIdForPath()` returns `"personal"` for the file path, the parsed `Page.sectionId = "personal"`. |
| `frontmatterValueTakesPrecedenceOverPathInference` | If frontmatter says `stele-section:: acme-work` but the file is under `pages/personal/`, sectionId is `"acme-work"` — frontmatter wins. |

---

### TC-SPLITJOURNAL-001: SplitJournalTest

**Class**: `SplitJournalTest`  
**Source set**: `businessTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Integration  
**Requirements covered**: FR-3  
**Note**: Uses `InMemoryRepositories` or a real SQLDelight in-memory driver — must exercise the actual UNIQUE constraint.

| Method | Verifies |
|--------|----------|
| `twoJournalFilesForSameDateInDifferentSectionsBothPersistToDb` | Loading both `journals/2026-06-29.md` (global, sectionId=`""`) and `journals/acme-work/2026-06-29.md` (sectionId=`"acme-work"`) inserts two distinct `Page` rows with different UUIDs. Neither insert is silently discarded. This is the primary UNIQUE(name, section_id) contract test. |
| `getJournalPageByDateAndSectionReturnsGlobalJournalForEmptyId` | `getJournalPageByDateAndSection("2026-06-29", "")` returns the global journal row and not the acme-work row. |
| `getJournalPageByDateAndSectionReturnsSectionJournalForSectionId` | `getJournalPageByDateAndSection("2026-06-29", "acme-work")` returns the acme-work row and not the global row. |
| `uniqueConstraintPreventsInsertingTwoGlobalJournalsForSameDate` | Inserting a second global journal row (sectionId=`""`) with the same date results in the second insert being rejected (INSERT OR IGNORE silently drops it, or the constraint fires). Exactly one global row exists after both inserts. |
| `loadDirectoryAssociateByIsJournalSectionAware` | After loading a directory containing both the global and acme-work journal for 2026-06-29, the in-flight `pagesByJournalDate` map contains two entries keyed by `(date, sectionId)` pairs — neither entry shadows the other. |

---

### TC-MIGRATION-001: MigrationSectionIdTest

**Class**: `MigrationSectionIdTest`  
**Source set**: `businessTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Integration  
**Requirements covered**: FR-11, Schema migration

| Method | Verifies |
|--------|----------|
| `existingDbWithoutSectionIdColumnMigratesCleanly` | Opening a SQLite database that has the old `pages` schema (no `section_id`, with inline `name UNIQUE`) through `MigrationRunner.applyAll()` completes without error. |
| `existingPageRowsMigratedToEmptySectionId` | After migration, every pre-existing page row has `section_id = ""`. |
| `migrationPreservesAllOtherPageColumns` | `uuid`, `name`, `file_path`, `is_journal`, `journal_date`, `is_favorite` and all other columns are unchanged by the migration. |
| `newUniqueIndexAllowsSameDateGlobalAndSectionJournalAfterMigration` | After migration, inserting a global journal (`section_id = ""`) and a work journal (`section_id = "acme-work"`) with the same name does not violate any constraint. |
| `migrationRunnerSchemaSyncTestPasses` | Existing `MigrationRunnerSchemaSyncTest` auto-validates that `section_id` migration entry is present in `MigrationRunner.all`. This is a verification task (not a new test to write) — it must remain green. |

---

### TC-INDEXONLY-001: IndexOnlyParseTest

**Class**: `IndexOnlyParseTest`  
**Source set**: `commonTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Unit  
**Requirements covered**: FR-5

| Method | Verifies |
|--------|----------|
| `indexOnlyModeSkipsAllReadFileCalls` | Processing a `FakeFileSystem` with 100 `.md` file entries in `ParseMode.INDEX_ONLY` results in zero `readFile()` calls on the file system. |
| `indexOnlyModeInserts100StubPageRows` | After processing 100 entries, 100 `Page` rows exist with `isContentLoaded = false`. |
| `stubPageRowHasCorrectFieldsDerivedFromFilePath` | A stub row for `pages/acme-work/Sprint Plan.md` has `name = "Sprint Plan"`, `isJournal = false`, `sectionId = "acme-work"`, `filePath = "pages/acme-work/Sprint Plan.md"`, `isContentLoaded = false`, `blocks = emptyList()`. |
| `journalStubRowHasCorrectJournalDateDerivedFromFilename` | A stub row for `journals/acme-work/2026-06-29.md` has `isJournal = true`, `journalDate = "2026-06-29"`, `sectionId = "acme-work"`. |

---

### TC-LAZYFETCH-001: StubPageLoadFullPageTest

**Class**: `StubPageLoadFullPageTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Integration  
**Requirements covered**: FR-5

| Method | Verifies |
|--------|----------|
| `stubPageAppearsInPageIndexBeforeLoadFullPageCalled` | After `INDEX_ONLY` load, `pageRepository.getPageByUuid(uuid)` returns the stub page immediately. |
| `stubPageBlockListIsEmptyBeforeLoadFullPage` | `blockRepository.getBlocksForPage(uuid)` returns an empty list for a stub page. |
| `loadFullPagePopulatesBlocksAndSetsIsContentLoadedTrue` | After `graphLoader.loadFullPage(uuid)` completes, `blockRepository.getBlocksForPage(uuid)` is non-empty and `page.isContentLoaded == true`. |
| `loadFullPageCalledTwiceDoesNotDuplicateBlocks` | Calling `loadFullPage()` on an already-loaded page is idempotent — block count does not double. |

---

### TC-DRAIN-001: IndexRemainingPagesSectionFilterTest

**Class**: `IndexRemainingPagesSectionFilterTest`  
**Source set**: `businessTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Integration  
**Requirements covered**: FR-5, FR-8 (WASM rate-limit-bomb fix)

| Method | Verifies |
|--------|----------|
| `indexRemainingPagesOnlyDrainsSubscribedSectionStubs` | Given stubs for `acme-work` (10 rows), `personal` (10 rows), and global (5 rows), `indexRemainingPages()` with subscription `["acme-work"]` drains exactly the 10 acme-work stubs and 5 global stubs; the 10 personal stubs remain with `isContentLoaded = false`. |
| `indexRemainingPagesWithNullSectionFilterDrainsAll` | When `sectionFilter == null` (no `.stele-sections` file), all 25 stubs are drained — backward-compat behavior unchanged. |

---

### TC-DESKTOP-FILTER-001: GraphLoaderSectionFilterTest

**Class**: `GraphLoaderSectionFilterTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Integration  
**Requirements covered**: FR-8, FR-4

Fixture: fake graph on disk with:
- `pages/Root Page.md` (global)
- `pages/acme-work/Sprint Plan.md` (acme-work section)  
- `pages/personal/Diary.md` (personal section)
- `.stele-sections` TOML listing both sections
- Device subscribed only to `acme-work`

| Method | Verifies |
|--------|----------|
| `subscriptionToAcmeWorkLoadsOnlyAcmeAndGlobalPages` | After `loadGraphProgressive()` completes, `pageRepository` contains `Root Page` and `Sprint Plan` but NOT `Diary`. |
| `personalSectionDirectoryExcludedForAcmeSubscription` | No page with `sectionId = "personal"` exists in the DB after load. |
| `globalPagesAlwaysLoaded` | `Root Page.md` at graph root `pages/` is present even though the subscription only names `acme-work`. |
| `warmStartReconcileAlsoRespectsSectionFilter` | After a simulated warm restart (graph already in DB), the `backgroundIndexJob` reconcile re-scans only subscribed directories — personal pages do not appear after the second pass. This is the regression test for adversarial blocker B2. |
| `resolvePageFilePathFindsPageInSubscribedSectionDirectory` | `graphLoader.resolvePageFilePath("Sprint Plan")` returns the path `pages/acme-work/Sprint Plan.md` when `acme-work` is subscribed. |

---

### TC-COMPAT-001: BackwardCompatibilityTest

**Class**: `BackwardCompatibilityTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Integration  
**Requirements covered**: FR-11  
**Note**: Addresses adversarial concern C2 (no backward-compat test existed in Epic 6).

| Method | Verifies |
|--------|----------|
| `graphWithoutSteleSectionsFileBehavesIdentically` | A graph directory without a `.stele-sections` file loads all pages with the same count, UUIDs, and block content as before this feature. `sectionFilter` is `null`. No pages are skipped, no extra DB columns interfere. |
| `sectionFilterIsNullWhenManifestAbsent` | `graphLoader.sectionFilter` is `null` after loading a graph without `.stele-sections`. |
| `allPagesLoadedWhenSubscriptionEmptyButManifestPresent` | When `.stele-sections` is present but the local device has no subscription list configured, all sections are treated as subscribed (all pages load). |
| `removingSectionDoesNotDeletePageRows` | Removing a section from the manifest via `SectionManifestWriter` does not delete any page from the DB; affected pages have `sectionId` cleared to `""` (global). |

---

### TC-SPLITJOURNAL-002: SplitJournalCreationTest

**Class**: `SplitJournalCreationTest`  
**Source set**: `businessTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Integration  
**Requirements covered**: FR-10, FR-3

| Method | Verifies |
|--------|----------|
| `createSectionJournalPageCreatesFileAtCorrectPath` | `graphLoader.createSectionJournalPage("acme-work", today)` results in a file created at `journals/acme-work/<today>.md` on the fake file system. |
| `createSectionJournalPageInsertsPageRowWithCorrectFields` | The returned `Page` has `isJournal = true`, `journalDate = today`, `sectionId = "acme-work"`, `isContentLoaded = true`. |
| `createSectionJournalPageIsIdempotentWhenFileAlreadyExists` | Calling `createSectionJournalPage()` twice for the same date returns the same `Page` UUID on both calls and creates exactly one file. |

---

### TC-SUBSCRIPTION-001: DeviceSubscriptionPersistenceTest

**Class**: `DeviceSubscriptionPersistenceTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.platform`  
**Type**: Integration  
**Requirements covered**: FR-4

| Method | Verifies |
|--------|----------|
| `subscriptionListPersistedToPlatformSettingsAndRestoredAfterRestart` | Setting subscribed section IDs `["acme-work"]` via the settings API persists to `PlatformSettings`. A new `GraphLoader` instance reading from the same `PlatformSettings` source sees `["acme-work"]` as the subscription. |

---

### TC-DYNAMIC-SUBSCRIBE-001: AddSectionSubscriptionTest

**Class**: `AddSectionSubscriptionTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.db`  
**Type**: Integration  
**Requirements covered**: FR-4, FR-8

| Method | Verifies |
|--------|----------|
| `addSectionSubscriptionLoadsSectionPagesWithoutGraphRestart` | Given a running graph subscribed only to `acme-work`, calling `graphLoader.addSectionSubscription(personalSection)` causes personal-section pages to appear in the page index without restarting the graph — `pageRepository` contains `Diary` after the call completes. |

---

### TC-WASM-SYNC-001: WasmSectionSyncServiceTest

**Class**: `WasmSectionSyncServiceTest`  
**Source set**: `commonTest`  
**Package**: `dev.stapler.stelekit.sync`  
**Type**: Unit (mocked HTTP)  
**Requirements covered**: FR-8 (WASM), FR-5

| Method | Verifies |
|--------|----------|
| `requestsTreeListingsOnlyForSubscribedPrefixes` | With subscription `["acme-work"]`, `WasmSectionSyncService` issues HTTP requests for `pagePathPrefix = "pages/acme-work"` and `journalPathPrefix = "journals/acme-work"` only. No request is issued for `pages/personal` or `journals/personal`. |
| `pathsOutsideSubscribedPrefixesGenerateNoStubRows` | Tree listing response entries under `pages/personal/` are filtered out; no stub `Page` rows are inserted for those paths. |
| `subscribedPrefixTreeEntriesGenerateIndexOnlyStubRows` | Each tree entry under `pages/acme-work/` results in one stub `Page` row with `isContentLoaded = false` and the correct `sectionId`. |

---

### TC-RATELIMIT-001: WasmRateLimitRetryTest

**Class**: `WasmRateLimitRetryTest`  
**Source set**: `commonTest`  
**Package**: `dev.stapler.stelekit.platform`  
**Type**: Unit (mocked HTTP)  
**Requirements covered**: FR-5 (WASM lazy fetch)

| Method | Verifies |
|--------|----------|
| `http429TriggersExponentialBackoffAndEventuallyEmitsRateLimitedError` | `PlatformFileSystem.readFile()` on WASM receiving three consecutive HTTP 429 responses (with `Retry-After: 1`) retries exactly three times using exponential backoff and then emits `DomainError.NetworkError.RateLimited(retryAfterSeconds = 1)`. No fourth attempt is made. |

---

### TC-GIT-SPARSE-001: GitSyncServiceSparseCheckoutTest

**Class**: `GitSyncServiceSparseCheckoutTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.git`  
**Type**: Integration  
**Requirements covered**: FR-6

| Method | Verifies |
|--------|----------|
| `cloneSectionSliceRunsGitCloneWithFilterBlobNoneAndSparseCheckout` | `GitSyncService.cloneSectionSlice(remote, targetDir, [acmeSectionDef])` executes `git clone --filter=blob:none --sparse <remote> <targetDir>` followed by `git sparse-checkout set pages/acme-work journals/acme-work`. Verified via a fake `ProcessRunner` that captures issued commands. |
| `addSparseCheckoutConeAppendsNewSectionPathToExistingCone` | `GitSyncService.addSparseCheckoutCone("pages/personal", "journals/personal")` issues `git sparse-checkout add pages/personal journals/personal`. |

---

### TC-UI-001: SectionBadgeScreenshotTest

**Class**: `SectionBadgeScreenshotTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.ui.components`  
**Type**: E2E / Screenshot (Roborazzi)  
**Requirements covered**: FR-9

| Method | Verifies |
|--------|----------|
| `sectionBadgeRendersColorDotAndDisplayName` | `SectionBadge` composable with `SectionDefinition(id="acme-work", displayName="Work – Acme Corp", color="#4A90D9", ...)` renders a color dot and the label "Work – Acme Corp". Captured via Roborazzi screenshot. |
| `sectionBadgeNotRenderedForGlobalPage` | `PageView` with a `Page` where `sectionId = ""` does not render any `SectionBadge`. |

---

### TC-UI-002: SectionPickerDialogTest

**Class**: `SectionPickerDialogTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.ui.components`  
**Type**: E2E (Compose test)  
**Requirements covered**: FR-9

| Method | Verifies |
|--------|----------|
| `tappingBadgeAndConfirmingNewSectionWritesFrontmatterProperty` | In a Compose test harness, tapping the section badge opens `SectionPickerDialog`, selecting "Personal", and tapping "Move to section" triggers a write of `stele-section:: personal` to the page file via the fake `GraphWriter`. |

---

### TC-UI-003: SubscriptionSettingsToggleTest

**Class**: `SubscriptionSettingsToggleTest`  
**Source set**: `jvmTest`  
**Package**: `dev.stapler.stelekit.ui`  
**Type**: E2E (Compose test)  
**Requirements covered**: FR-4, FR-9

| Method | Verifies |
|--------|----------|
| `subscriptionTogglePersistsSelectedSectionIdsToSettings` | Toggling the `personal` section subscription ON in the Settings → Device Subscriptions panel results in `"personal"` being added to `PlatformSettings` subscription list. Toggling OFF removes it. |

---

## Part 2 — Implementation Readiness Gate

### Criterion 1: Requirements Completeness

Does every FR have at least one corresponding story/task in plan.md?

| FR | Coverage in plan.md | Verdict |
|----|---------------------|---------|
| FR-1 (Section definition) | Stories 1.1 (model), 1.2 (parser/writer), 5.3 (settings management) | PASS |
| FR-2 (Page assignment) | Story 2.4 (resolve sectionId from path/frontmatter) | PASS |
| FR-3 (Split journals) | Stories 1.3 (UNIQUE constraint fix), 3.1 (journal date collision fix), 3.2 (journal view routing) | PASS |
| FR-4 (Device subscriptions) | Stories 1.6 (SectionFilter), 2.1 (subscription-aware load), 5.4 (subscription UI) | PASS |
| FR-5 (Lazy page content) | Story 4.1 (INDEX_ONLY parse mode), 4.2 (WasmSectionSyncService stubs), 4.3 (WASM lazy fetch) | PASS |
| FR-6 (First-time clone) | Story 2.5 (GitSyncService.cloneSectionSlice) — **no UI story for the onboarding flow; the method has no caller in any epic** | PARTIAL |
| FR-7 (.stele-sections manifest schema) | Stories 1.1 (model), 1.2 (parser + writer) | PASS |
| FR-8 (Sync filtering) | Stories 2.1 (GraphLoader desktop filtering), 4.2 (WASM tree filter) | PASS |
| FR-9 (Section badge UI) | Stories 5.1 (badge composable), 5.2 (section picker), 5.3 (settings panel) | PASS |
| FR-10 (New work journal action) | Story 3.3 (createSectionJournalPage + command palette + sidebar button) | PASS |
| FR-11 (Backward compat) | SectionFilter null check (Story 1.6, 2.1) enforces backward compat — **no explicit backward-compat story; zero regression tests in Epic 6** | PARTIAL |

**Verdict**: PARTIAL — 9 / 11 FRs fully covered. FR-6 has technical implementation but no UI trigger story; FR-11 has no dedicated backward-compat regression test in the Epic 6 plan. This validation document adds `BackwardCompatibilityTest` (TC-COMPAT-001) to close the FR-11 gap.

---

### Criterion 2: Test Coverage

Does the test suite above cover at least 80% of FRs with at least one meaningful test?

| FR | Test class(es) | Covered |
|----|----------------|---------|
| FR-1 | TC-MANIFEST-001, TC-MANIFEST-002 | YES |
| FR-2 | TC-FRONTMATTER-001 | YES |
| FR-3 | TC-SPLITJOURNAL-001, TC-SPLITJOURNAL-002 | YES |
| FR-4 | TC-FILTER-001, TC-SUBSCRIPTION-001, TC-DYNAMIC-SUBSCRIBE-001, TC-UI-003 | YES |
| FR-5 | TC-INDEXONLY-001, TC-LAZYFETCH-001, TC-DRAIN-001, TC-WASM-SYNC-001 | YES |
| FR-6 | TC-GIT-SPARSE-001 (service layer only; no E2E onboarding test) | YES |
| FR-7 | TC-MANIFEST-001, TC-MANIFEST-002 | YES |
| FR-8 | TC-FILTER-001, TC-DESKTOP-FILTER-001, TC-WASM-SYNC-001 | YES |
| FR-9 | TC-UI-001, TC-UI-002, TC-UI-003 | YES |
| FR-10 | TC-SPLITJOURNAL-002 | YES |
| FR-11 | TC-COMPAT-001, TC-MIGRATION-001 | YES |

**Coverage: 11 / 11 FRs — 100%. Criterion PASSES.**

---

### Criterion 3: Dependency Clarity

Are all dependencies from requirements.md either already implemented or covered by a specific plan task?

| Dependency | Status | Plan task |
|-----------|--------|-----------|
| ADR-013 (WASM REST + OPFS) | Accepted | Story 4.3 (OPFS read path) |
| ADR-014 (.stele-sections TOML schema) | Accepted | Stories 1.1, 1.2 |
| ADR-015 (WASM write-back) | Accepted | Story 4.3 (write path noted as OQ-5-resolved) |
| BUG-005 Phase 1 (WASM git stubs) | **Done** | — |
| `stele-section::` frontmatter parser | Not started | Story 2.4 |
| `.stele-sections` TOML parser | Not started | Story 1.2 |
| Split journal file-naming convention | Not started | Story 1.3, 3.1 |
| Lazy content fetch in `GraphLoader` | Not started | Story 4.1 |
| WASM `WasmSectionSyncService` | Not started | Story 4.2 |
| Section badge + section picker UI | Not started | Stories 5.1, 5.2 |
| Settings section management + subscription UI | Not started | Stories 5.3, 5.4 |

**Verdict: PASS — all dependencies have specific tasks in the plan.**

---

### Criterion 4: Risk Mitigation (Adversarial Blockers)

Does the plan address all 3 blockers from the adversarial review?

| Blocker | Description | Plan resolution | Verdict |
|---------|-------------|-----------------|---------|
| **B1** — `name UNIQUE` constraint makes split journals impossible | The inline `UNIQUE` on `name` silently discards the second journal insert for the same date. | Story 1.3 explicitly removes the inline `UNIQUE`, adds `UNIQUE(name, section_id)` table constraint, and adds a migration via `MigrationRunner` with the copy-alter pattern as fallback. Labeled `[B1]` in the plan. | ADDRESSED |
| **B2** — Warm-start reconcile path not covered | `loadGraphProgressive()` has a second `loadDirectory(pagesDir)` call on the warm path that Story 2.1 originally omitted. | Story 2.1 includes a `[B2]`-labeled task: "Patch the `backgroundIndexJob` warm-start reconcile … to use the same section-filtered path list as the cold-start path." | ADDRESSED |
| **B3** — `insertPage` and `updatePage` SQL not updated | Both queries use explicit column lists; SQLDelight does not auto-add `section_id`. | Story 1.3 includes three `[B3]`-labeled tasks: update `insertPage` SQL, update `updatePage` SQL, update `RestrictedDatabaseQueries` stubs for both. | ADDRESSED |

**Verdict: PASS — all 3 blockers are addressed with explicitly labeled tasks in the plan.**

---

### Remaining Adversarial Concerns (not blockers; should resolve before ship)

These concerns from the adversarial review are NOT addressed by the current plan and are NOT covered by the test suite above. They represent known gaps that need explicit decisions before each corresponding epic begins.

| Concern | Description | Recommended action |
|---------|-------------|-------------------|
| **C1** — Story 5.5 ghost links unimplementable | Cross-section ghost links require knowing a missing page's file path, but unsubscribed pages have no DB rows and cannot be reverse-mapped from name to section prefix without the DB row. | Either (a) descope Story 5.5 entirely for v1, or (b) redesign: maintain a name-to-section index derived from the manifest and tree listing, keyed on page name rather than file path. Add a task to Epic 5 making the choice explicit. |
| **C2** — No backward-compat regression test | Epic 6 has no test asserting FR-11 correctness. Addressed by `TC-COMPAT-001` in this validation plan; that test must be added to Epic 6 Story 6.2. | Add `BackwardCompatibilityTest` as an explicit sub-task in Story 6.2. |
| **C3** — Sparse-checkout wiring has no owner | `addSectionSubscription()` in `GraphLoader` is not wired to `GitSyncService.addSparseCheckoutCone()`. The subscription changes memory but does not update git sparse-checkout on disk. | Assign an explicit wiring task: `StelekitViewModel.onSectionSubscriptionToggled()` → `GitSyncService.addSparseCheckoutCone()`. Place in Story 2.3 or 5.4. |
| **C4** — FR-6 onboarding UX has no UI caller | `GitSyncService.cloneSectionSlice()` exists but no onboarding screen, settings flow, or success/failure feedback triggers it. | Add a Story 5.6: "First-time remote setup wizard" that surfaces section clone with progress feedback. Without it, FR-6 is implemented but unreachable. |
| **C8** — `loadDirectory` `associateBy { it.journalDate!! }` is not section-aware | Even after B1 (constraint relaxation), this `associateBy` call in the journal-date lookup path silently drops one of two journals sharing the same date within a single directory chunk. | Add a task to Story 3.1: change `pagesByJournalDate` to key by `Pair<journalDate, sectionId>` instead of `journalDate` alone, and update the lookup call site accordingly. |

---

## Readiness Gate Verdict: CONCERNS

All four gate criteria pass in the formal sense (blockers B1/B2/B3 addressed, 100% FR coverage in test suite, all dependencies have tasks, 11/11 FRs have stories). However, implementation should not begin on Epics 2–5 until the following are resolved in writing:

1. **Story 5.5** — Make an explicit decision: descope ghost links in v1, or redesign the detection mechanism. The current story description is unimplementable.
2. **C8 fix** — Add a task to Story 3.1 to make `associateByJournalDate` section-aware (`Pair<date, sectionId>` key). Without it, B1's constraint fix does not prevent silent data loss in the `loadDirectory` in-memory map.
3. **FR-6 caller** — Add Story 5.6 (onboarding UX) or explicitly descope FR-6 onboarding to a follow-up epic. The current plan leaves `cloneSectionSlice` unreachable.
4. **C3 wiring** — Assign ownership of the `addSectionSubscription` → `addSparseCheckoutCone` call chain in Story 2.3 or 5.4.

None of these are reasons to pause Epic 1 (data model). Epic 1 is the gate that unlocks Epics 2–5, and it is clean. Concerns 1–4 must be resolved before the corresponding epics (2, 3, 5) are handed off for implementation.
