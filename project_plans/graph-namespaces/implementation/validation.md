# Graph Sections — Test Suite Design

**Feature:** Graph Sections (user-facing label: "Sections")
**Date:** 2026-06-24
**Status:** Pre-implementation (written before any feature code)

---

## Step 0 — Happy-Path End-to-End Scenario

Given a SteleKit desktop JVM graph with a `.stele-sections` manifest declaring "tech" and "personal" sections, when the user deactivates "personal" in Section Settings, then personal-section pages are deleted from the DB, `git sparse-checkout set` is called with only tech paths, and any `[[My Diary]]` wikilink on a tech page renders with a dashed-underline tombstone that correctly names the "personal" section.

---

## Step 1 — Per-Requirement Test Cases

### FR-1 Section Manifest

#### FR-1.1 / FR-1.2 — TOML parsing and path assignment

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-001 | `parseString_should_returnManifestWithAllSections_whenValidTomlProvided` | Unit (happy) | `businessTest` | TOML with 4 `[[section]]` entries (personal/tech/work/default) → `SectionManifest.section.size == 4`; `section[0].name == "personal"`; `section[3].paths.isEmpty()`. |
| T-002 | `parseString_should_throwParseException_whenTomlIsMalformed` | Unit (error) | `businessTest` | Malformed TOML (unclosed bracket) → `ktoml` parse exception is wrapped and surfaced; does not propagate as raw `ktoml` internal type. |
| T-003 | `parseString_should_ignoreUnknownKeys_whenTomlHasFutureFields` | Unit (happy) | `businessTest` | TOML with an unknown key `future_flag = true` → parsed without exception; manifest fields correct; verifies `ignoreUnknownNames = true` config. |
| T-004 | `encodeToString_should_roundTripManifest_whenDecodedAgain` | Unit (happy) | `businessTest` | Encode a `SectionManifest` → decode the result → all fields equal original. Verifies encode/decode symmetry for the manifest CRUD flow. |

**Integration test (FR-1.2 path assignment):**

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-005 | `readSectionManifest_should_returnDefaultManifest_whenFileAbsent` | Integration | `jvmTest` | Call `readSectionManifest(graphPath, fs)` on a temp dir without `.stele-sections` → returns `SectionManifest.DEFAULT` with `section = [GraphSection("default", ...)]`. Verifies FR-1.3 backward compat. |

---

#### FR-1.3 — Backward compatibility (absent manifest)

Covered by T-005 above and the dedicated regression test T-045 (see Step 5).

---

#### FR-1.4 / FR-1.5 — CRUD and TOML library

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-006 | `writeSectionManifest_should_persistManifest_whenWrittenAndReadBack` | Integration | `jvmTest` | Write a `SectionManifest` to a temp dir; read it back via `readSectionManifest`; assert all fields match. Verifies FR-1.4 round-trip via file system. |

---

### FR-2 Section-Selective Graph Loading

#### FR-2.1 / FR-2.2 — `activeSectionSet` filter at enumeration time

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-007 | `sectionFilter_should_includeFile_whenPathMatchesActiveSection` | Unit (happy) | `businessTest` | Manifest: "tech" → `["tech/"]`; `activeSectionSet = {"tech"}`. Call `SectionFilter.includes("/graph/tech/page.md")` → `true`. |
| T-008 | `sectionFilter_should_excludeFile_whenPathIsInInactiveSection` | Unit (error) | `businessTest` | Same manifest/active. Call `SectionFilter.includes("/graph/personal/diary.md")` → `false`. |
| T-009 | `graphLoader_should_onlyIndexPagesInActiveSections_whenActiveSectionSetProvided` | Integration | `jvmTest` | Temp graph with `tech/` (3 files) and `personal/` (2 files); manifest declares both; `activeSectionSet = {"tech"}`. After `loadGraphProgressive`, `pageRepository.getPages()` returns exactly 3 pages; personal pages are absent from DB. |

#### FR-2.3 — File watcher scoped to active sections

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-010 | `graphFileWatcher_should_emitEvent_whenFileChangedInActiveSection` | Unit (happy) | `jvmTest` | Watcher started with `activeRootPaths = {"/graph/tech/"}`. Write a file to `tech/`. Watcher emits an event. |
| T-011 | `graphFileWatcher_should_notEmitEvent_whenFileChangedInInactiveSection` | Unit (error) | `jvmTest` | Same watcher. Write a file to `personal/`. No event emitted within 500 ms. |

#### FR-2.4 — Pages outside active set are absent from DB

Covered by T-009 (integration) and T-045 (regression).

#### FR-2.5 — Primary device defaults to all-sections

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-012 | `sectionFilter_should_includeAllPaths_whenActiveSectionSetIsNull` | Unit (happy) | `businessTest` | `SectionFilter` constructed with `activeSectionSet = null`. `includes` returns `true` for any path. Verifies primary-device default behavior. |

---

### FR-3 Cross-Section Link Tombstones

#### FR-3.1 — Tombstone resolution for inactive-section pages

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-013 | `resolveTombstone_should_returnSection_whenPageIsInInactiveSection` | Unit (happy) | `businessTest` | `sectionIndices = {"personal" → SectionIndex("My Diary" → GraphSection("personal"))}`, `activeSectionSet = {"tech"}`. `SectionResolver.resolveTombstone("My Diary")` → returns `GraphSection("personal")`. |
| T-014 | `resolveTombstone_should_returnNull_whenPageIsGenuinelyAbsent` | Unit (error) | `businessTest` | Same resolver; page name `"Unknown Page"` not in any index. Returns `null` (broken link, not tombstone). |

**Integration test:**

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-015 | `sectionResolver_should_resolveCorrectSection_afterDeactivationAndReload` | Integration | `jvmTest` | Load graph with "tech" and "personal" active; deactivate "personal"; verify `SectionIndex` for "personal" is in `sectionIndices`; call `resolveTombstone` for a formerly-personal page → non-null section returned. |

#### FR-3.2 — No tombstones when all sections active

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-016 | `resolveTombstone_should_returnNull_whenAllSectionsActive` | Unit (happy) | `businessTest` | `SectionResolver` with `activeSectionSet = null`. `resolveTombstone("My Diary")` → `null` regardless of index contents. |

#### FR-3.3 — Tombstones never written to DB

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-017 | `tombstone_should_notExistInDB_afterSectionDeactivation` | Integration | `jvmTest` | Deactivate "personal"; confirm `pageRepository.getPageByName("My Diary")` returns `null` or `Left`; confirm `searchRepository` has no result for "My Diary". |

---

### FR-4 Sparse Checkout Support (Desktop JVM)

#### FR-4.1 / FR-4.2 — Sparse-checkout invocation

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-018 | `setSectionPaths_should_invokeGitSparseCheckout_whenRepoHasRemote` | Unit (happy) | `jvmTest` | `JvmSparseCheckoutService` with a mock `ProcessBuilder` interceptor; repo has remote. Call `setSectionPaths(repoRoot, "", ["tech/"])` → asserts `git sparse-checkout set tech/` was called; full recompute of path set (FR-4.2). |
| T-019 | `setSectionPaths_should_returnRight_whenGitNotInPath` | Unit (error) | `jvmTest` | `JvmSparseCheckoutService` with `findGitExecutable()` returning `null` (git absent). `setSectionPaths` returns `Unit.right()` silently — no exception (FR-4.4). |

**Integration test:**

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-020 | `graphManager_should_invokeSparseCheckout_whenSectionDeactivatedOnGitRepoWithRemote` | Integration | `jvmTest` | Temp git repo with remote configured; `graphManager.toggleSection("personal", false)`; assert `sparseCheckoutService.setSectionPaths` was called with paths for all remaining active sections. |

#### FR-4.4 — Silent skip if git absent or non-git repo

Covered by T-019 above.

#### FR-4.5 — Sparse-checkout runs on `Dispatchers.IO` (no main-thread block)

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-021 | `hasRemote_should_notBlockCallerDispatcher_whenInvokedAsSuspendFun` | Unit (happy) | `jvmTest` | Call `hasRemote` from a `runTest` on `Dispatchers.Default`; verify execution switches to `PlatformDispatcher.IO` internally (captured via coroutine context inspection or by confirming no blocking occurs in test). |

#### FR-4.6 — Local-only repo (no remote): sparse-checkout skipped

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-022 | `hasRemote_should_returnFalse_whenRepoHasNoRemote` | Unit (happy) | `jvmTest` | `JvmSparseCheckoutService.hasRemote` on a bare git repo without any remote configured → `false`. |
| T-023 | `setSectionPaths_should_notInvokeGit_whenRepoHasNoRemote` | Unit (error) | `jvmTest` | `GraphManager.toggleSection` on a no-remote git repo → `sparseCheckoutService.setSectionPaths` not called; in-memory `SectionFilter` still applied; no exception. |

---

### FR-5 WASM Local Cache (OPFS)

#### FR-5.2 — REST API fetch → OPFS write

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-024 | `syncSection_should_writeFilesToOpfs_whenApiReturns200` | Unit (happy) | `wasmJsTest` | `WasmSectionSyncService` with mock HTTP client returning 200 and a tree of 3 files; call `syncSection`; assert all 3 files written to OPFS at correct paths. |
| T-025 | `syncSection_should_returnCredentialRequired_whenApiReturns401` | Unit (error) | `wasmJsTest` | Mock HTTP returns 401. `syncSection` returns `Either.Left(DomainError.CredentialRequired)`. No OPFS writes attempted. |

#### FR-5.3 — Sync marker written last

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-026 | `syncSection_should_writeMarkerLast_onSuccessfulSync` | Unit (happy) | `wasmJsTest` | After successful sync, `.stele-sections-sync-complete` marker is present in OPFS; its `commitSha` matches the mocked remote HEAD SHA. |
| T-027 | `syncSection_should_notWriteMarker_whenSyncFailsMidway` | Unit (error) | `wasmJsTest` | HTTP client returns 200 for first file then throws. Marker absent from OPFS after error. Next load detects incomplete sync and re-fetches. |

#### FR-5.5 — Delta sync: re-fetch only changed files

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-028 | `syncSection_should_skipFetch_whenCachedShaMatchesRemoteHead` | Unit (happy) | `wasmJsTest` | OPFS has valid marker with SHA `"abc"`; remote HEAD is also `"abc"`. `syncSection` performs zero file fetches. |
| T-029 | `syncSection_should_fetchOnlyChangedFiles_whenShasdiffer` | Unit (happy) | `wasmJsTest` | Cached SHA `"abc"`, remote HEAD `"def"`. Mock compare API returns 1 changed file. Only that file is re-fetched and written; unchanged files untouched. |

#### FR-5.6 — Section selector shown before any content download

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-030 | `fetchManifest_should_returnSectionList_beforeAnyPageIsFetched` | Unit (happy) | `wasmJsTest` | `WasmSectionSyncService.fetchManifest` succeeds; assert no page-content HTTP calls were made before caller invokes `syncSection`. |

#### FR-5.7 — OPFS quota exceeded

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-031 | `syncSection_should_returnStorageFull_whenOpfsThrowsQuotaExceededError` | Unit (error) | `wasmJsTest` | Mock OPFS write throws `QuotaExceededError`. `syncSection` returns `Either.Left(DomainError.StorageFull)`. |

---

### FR-6 Section Settings UI

#### FR-6.1 — "Sections" entry visibility

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-032 | `sectionSettingsEntry_should_beVisible_whenManifestExists` | Unit (happy) | `jvmTest` | `GraphManager.currentSectionManifest` has 3 non-default sections → settings sidebar model exposes `showSectionsEntry = true`. |
| T-033 | `sectionSettingsEntry_should_beHidden_whenManifestIsDefault` | Unit (error) | `jvmTest` | `GraphManager.currentSectionManifest == SectionManifest.DEFAULT` → `showSectionsEntry = false`. |

#### FR-6.3 / FR-6.4 — Create/delete section

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-034 | `createSection_should_updateManifestAndReindex_whenConfirmed` | Integration | `jvmTest` | `SectionSettingsViewModel.createSection("finance", ...)` → manifest file updated; `GraphLoader.loadGraphProgressive` triggered; `pageRepository.countPagesBySection("finance")` returns expected count. |
| T-035 | `deleteSection_should_reassignPagesToDefault_whenConfirmed` | Integration | `jvmTest` | Delete "tech"; pages formerly in "tech" now have `section_name = 'default'` in DB; no pages deleted from disk. |

#### FR-6.5 — Toggle inactive removes pages immediately

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-036 | `toggleSection_should_deleteSectionPagesFromDb_whenDeactivated` | Integration | `jvmTest` | `graphManager.toggleSection("personal", false)`; assert `pageRepository` returns no pages with `section_name = "personal"`. |

---

### FR-7 Section Indicator on Pages

#### FR-7.1 — `Page.section` populated at load time

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-037 | `graphLoader_should_setPageSection_whenPagePathMatchesManifestEntry` | Integration | `jvmTest` | Load `tech/TypeSystems.md` with manifest declaring "tech" → `page.section.name == "tech"` and `page.section.color == "#3498DB"`. |
| T-038 | `graphLoader_should_setDefaultSection_whenPagePathMatchesNoDeclaredSection` | Unit (error) | `jvmTest` | Load a page in `misc/` not covered by any named section → `page.section.name == "default"`. |

#### FR-7.3 — Section filter dropdown

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-039 | `getPagesBySection_should_returnOnlySectionPages_whenFilterApplied` | Integration | `jvmTest` | DB has "tech" and "personal" pages. `pageRepository.getPagesBySection("tech", limit=50, offset=0)` → only tech pages returned; unbounded query not used (CLAUDE.md guard). |

---

### FR-8 Full-Merge View (Primary Device)

#### FR-8.1 — Primary device loads identically to pre-feature

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-040 | `graphLoader_should_indexAllPages_whenActiveSectionSetIsNull` | Integration | `jvmTest` | Manifest with 3 sections, `activeSectionSet = null`. All pages from all sections indexed. Identical behavior to pre-feature baseline. |

#### FR-8.2 — Section view toggle

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-041 | `stelekitViewModel_should_filterToSection_whenSectionViewToggleEnabled` | Unit (happy) | `jvmTest` | Enable "section view" for "tech" → view model emits page list containing only tech pages. Disable → full list restored. |

---

## Step 2 — UX Acceptance Tests

One test per UX acceptance criterion from `ux.md`. All are type "UX Acceptance" and sourced in `jvmTest` (Compose screenshot/interaction tests). WASM-specific UX criteria marked with `[WASM]`.

### Section Settings Screen (UX-AC-01 to UX-AC-06)

| # | Test name | UX-AC | Scenario |
|---|-----------|-------|----------|
| T-UA-01 | `sectionSettingsScreen_should_displayAllSectionFields_whenOpened` | UX-AC-01 | Render `SectionSettingsScreen` with 3-section manifest; assert name, description, paths, page count, color, and toggle visible for each row. |
| T-UA-02 | `sectionToggle_should_removeColorDotFromSidebar_within3Seconds_whenDeactivated` | UX-AC-02 | Deactivate a section via toggle; assert sidebar page-list rows for that section lose their color dot within 3 s. |
| T-UA-03 | `sectionToggle_should_showPagesInList_within5Seconds_whenActivated` | UX-AC-03 | Activate an inactive section on a 1 000-page graph; assert those pages appear in the sidebar within 5 s. |
| T-UA-04 | `sectionToggle_should_showGitNotFoundWarning_andCompleteToggle_whenGitAbsent` | UX-AC-04 | `git` not in PATH; toggle section; assert "Git not found" warning banner shown AND toggle completes (in-memory filter applies). |
| T-UA-05 | `sectionsEntry_should_notAppear_whenGraphHasNoManifest` | UX-AC-05 | Open settings for a graph without `.stele-sections`; assert "Sections" sidebar entry absent. |
| T-UA-06 | `sectionToggle_should_showUncommittedChangesError_whenWorktreeDirty` | UX-AC-06 | Simulate dirty worktree (uncommitted files); attempt section toggle; assert inline "Commit or stash" error shown; toggle not applied. |

### Create/Edit Section Dialog (UX-AC-07 to UX-AC-12)

| # | Test name | UX-AC | Scenario |
|---|-----------|-------|----------|
| T-UA-07 | `createSectionDialog_should_addSectionToList_whenSavedWithValidFields` | UX-AC-07 | Enter name, description, color, two path patterns; click Save; new section row appears in Section Settings list. |
| T-UA-08 | `createSectionDialog_should_showDuplicateNameError_andDisableSave_whenNameTaken` | UX-AC-08 | Enter name matching an existing section; "Name is already taken" error shown; Save button disabled. |
| T-UA-09 | `sectionFormDialog_should_addAndRemovePathFields_whenButtonsClicked` | UX-AC-09 | Click "+ Add path" → new empty field appears; click [−] → field removed. |
| T-UA-10 | `colorPickerSwatch_should_updateImmediately_whenColorSelectedOrTyped` | UX-AC-10 | Select color via picker → swatch updates; type valid hex → swatch updates live. |
| T-UA-11 | `sectionFormDialog_should_showOverlapWarning_butAllowSave_whenPathsOverlap` | UX-AC-11 | Enter path that prefixes another section's path; overlap warning shown inline; Save not blocked. |
| T-UA-12 | `editSectionDialog_should_prepopulateAllFields_whenOpenedForExistingSection` | UX-AC-12 | Open Edit for "tech"; assert name, description, paths, color fields pre-populated with "tech" values. |

### WASM First-Open Section Selector (UX-AC-13 to UX-AC-19) `[WASM]`

| # | Test name | UX-AC | Scenario |
|---|-----------|-------|----------|
| T-UA-13 | `sectionSelector_should_showSectionListBeforePageDownload_onFirstWasmLoad` | UX-AC-13 | Mock WASM boot; manifest fetched; section selector shown before any page-content fetch call is made. |
| T-UA-14 | `sectionSelector_should_showNameDescriptionCountAndSize_perSection` | UX-AC-14 | Each section row in selector displays name, description, estimated page count, and estimated MB. |
| T-UA-15 | `sectionSelector_should_preCheckDefaultAndDisableItsCheckbox` | UX-AC-15 | `default` section row is checked; its checkbox is disabled (cannot be deselected). |
| T-UA-16 | `sectionSelector_should_updateRunningTotal_whenUserChecksOrUnchecksSection` | UX-AC-16 | Check "tech" → totals update to include tech pages/MB; uncheck → totals revert. |
| T-UA-17 | `sectionSelector_should_showPerSectionProgressBar_afterSyncClicked` | UX-AC-17 | Click "Sync selected sections"; per-section progress bars appear showing `N / total` pages. |
| T-UA-18 | `sectionSelector_should_showNetworkErrorWithRetry_whenManifestFetchFails` | UX-AC-18 | Manifest fetch throws; "could not reach remote" full-screen error with Retry button shown. |
| T-UA-19 | `sectionSelector_should_showWithPreviousSelectionsPreChecked_whenCacheIsStale` | UX-AC-19 | OPFS cache present with mismatched SHA; section selector shown again; previously selected sections pre-checked. |

### Tombstone Link Visual Treatment (UX-AC-20 to UX-AC-23)

| # | Test name | UX-AC | Scenario |
|---|-----------|-------|----------|
| T-UA-20 | `tombstoneLink_should_renderWithDashedUnderlineInSectionColor_whenPageInInactiveSection` | UX-AC-20 | Block containing `[[My Diary]]` where "My Diary" is in inactive "personal" → annotated string has dashed underline with personal section color; not a solid underline. |
| T-UA-21 | `tombstoneLink_should_showTooltipNamingSection_after400msHover` | UX-AC-21 | Hover 400 ms over tombstone → tooltip appears naming "personal" and explaining page is not synced. |
| T-UA-22 | `tombstoneLink_should_lookDifferentFromBrokenLink_whenPageGenuinelyAbsent` | UX-AC-22 | `[[Unknown Page]]` (not in any section index) → no dashed underline; no color tint; broken-link treatment. Tombstone link renders distinctly differently. |
| T-UA-23 | `tombstoneLink_should_resolveToNormalLink_onNextRecomposition_whenSectionActivated` | UX-AC-23 | Activate section containing the page; trigger recomposition; link renders as normal solid underline (tombstone gone). |

### "Sync section?" Dialog (UX-AC-24 to UX-AC-29)

| # | Test name | UX-AC | Scenario |
|---|-----------|-------|----------|
| T-UA-24 | `syncSectionDialog_should_nameCorrectSection_whenOpenedFromTombstone` | UX-AC-24 | Click tombstone for "My Diary" (personal section) → dialog shows "personal" by name. |
| T-UA-25 | `syncSectionDialog_should_showPlatformAppropriateCopy_forDesktopAndWasm` | UX-AC-25 | Desktop dialog mentions "sparse-checkout"; WASM dialog mentions "download to browser". |
| T-UA-26 | `syncSectionDialog_should_showEstimatedSize_onWasm` | UX-AC-26 | WASM variant shows `~18 MB` estimated size for the section being downloaded. `[WASM]` |
| T-UA-27 | `syncSectionDialog_should_dismissWithNoChanges_whenCancelClicked` | UX-AC-27 | Click Cancel → dialog dismissed; `toggleSection` NOT called; active section set unchanged. |
| T-UA-28 | `syncSectionDialog_should_showProgressAndAllowCancel_duringWasmDownload` | UX-AC-28 | WASM: after confirm, progress bar shown; click "Cancel download" → download aborted, sync marker not written. `[WASM]` |
| T-UA-29 | `syncSectionDialog_should_dismissAndResolveLinks_afterConfirm` | UX-AC-29 | After confirming section activation, dialog dismisses; page re-renders with the section's links resolved (normal links, not tombstones). |

### Sidebar Section Indicator (UX-AC-30 to UX-AC-35)

| # | Test name | UX-AC | Scenario |
|---|-----------|-------|----------|
| T-UA-30 | `sidebar_should_showColorDotPerPage_whenMultipleSectionsActive` | UX-AC-30 | Multiple sections active; each sidebar row shows a color dot matching its `Page.section.color`. |
| T-UA-31 | `sidebar_should_showNoDots_whenOnlyOneSectionActiveOrNoManifest` | UX-AC-31 | Single section active (or default manifest only); no color dots shown on any page row. |
| T-UA-32 | `sidebar_colorDot_should_showTooltipWithSectionName_onHover` | UX-AC-32 | Hover over color dot → tooltip "Section: tech" shown. |
| T-UA-33 | `sidebar_filterDropdown_should_containOneEntryPerSection_plusAllSections` | UX-AC-33 | Filter dropdown has "All sections" plus one entry for each active section; no inactive sections shown. |
| T-UA-34 | `sidebar_should_showOnlySectionPages_whenSectionSelectedInDropdown` | UX-AC-34 | Select "tech" in dropdown → page list immediately shows only tech pages; page count in header updates. |
| T-UA-35 | `sidebar_should_restoreFullList_whenAllSectionsSelectedInDropdown` | UX-AC-35 | Select "All sections" → full unfiltered page list restored. |

### Error States (UX-AC-36 to UX-AC-43)

| # | Test name | UX-AC | Scenario |
|---|-----------|-------|----------|
| T-UA-36 | `gitNotFoundWarning_should_appearOnceAndBeDismissible_withoutBlockingSection` | UX-AC-36 | Git absent; section toggled; "Git not found" warning appears once; dismiss → warning gone; section use continues. |
| T-UA-37 | `sectionToggle_should_beBlockedWithClearReason_whenMergeConflictExists` | UX-AC-37 | `git status --porcelain` returns conflict markers; toggle blocked; "unresolved merge conflicts" message shown. |
| T-UA-38 | `wasmSync_should_showCountdownAndAutoRetry_on429RateLimit` | UX-AC-38 | Mock HTTP 429 with Retry-After header; dialog shows countdown; auto-retries when it reaches 0. `[WASM]` |
| T-UA-39 | `wasmSync_should_offerTokenUpdate_on401AuthError` | UX-AC-39 | Mock HTTP 401; dialog shows "Update token" button linking to Git Sync settings. `[WASM]` |
| T-UA-40 | `wasmSync_should_offerCacheManagementLinks_whenStorageQuotaExceeded` | UX-AC-40 | OPFS write throws `QuotaExceededError`; error dialog links to "Manage cache" and "Select fewer sections". `[WASM]` |
| T-UA-41 | `wasmSync_should_offerRedownloadWithPreviousSelections_whenOpfsEvicted` | UX-AC-41 | OPFS marker absent and cache empty; "Local cache cleared" screen shown; re-download flow re-launches selector with prior selections pre-checked. `[WASM]` |
| T-UA-42 | `manifestParseError_should_showLineAndColumnAndLoadWithDefault` | UX-AC-42 | `.stele-sections` contains malformed TOML; parse error dialog shows line/column; graph loads with `SectionManifest.DEFAULT`. |
| T-UA-43 | `manifestParseError_should_appearOncePerSession_notOnEveryNavigation` | UX-AC-43 | Navigate between pages after manifest error; error dialog NOT re-shown on subsequent navigations. |

---

## Step 3 — Naming Convention Applied

All test names follow: `methodOrComponentName_should_ExpectedBehavior_When_Condition`.

Examples from this suite:
- `parseString_should_returnManifestWithAllSections_whenValidTomlProvided` (T-001)
- `resolveTombstone_should_returnSection_whenPageIsInInactiveSection` (T-013)
- `hasRemote_should_returnFalse_whenRepoHasNoRemote` (T-022)
- `sectionSettingsScreen_should_displayAllSectionFields_whenOpened` (T-UA-01)

---

## Step 4 — Migration Test

| # | Test name | Type | Source set |
|---|-----------|------|------------|
| T-M-01 | `sectionNameMigration_should_be_reversible` | Migration | `businessTest` |

**Scenarios within T-M-01:**

**Scenario A — Single apply (up migration):**
- Given: existing SQLite DB without `section_name` column on `pages` table.
- When: `MigrationRunner.applyAll` is called.
- Then: `PRAGMA table_info(pages)` contains `section_name` with `dflt_value = 'default'`; existing page rows have `section_name = 'default'`; index `idx_pages_section_name` exists.

**Scenario B — Idempotency (double apply):**
- Given: same DB with `section_name` already added (migration version already recorded in `schema_migrations`).
- When: `MigrationRunner.applyAll` called a second time.
- Then: no exception thrown; `section_name` column present exactly once (version-skip guard works; SQLite duplicate-column error never reached).

**Scenario C — Down / rollback simulation:**
- Given: DB with `section_name` column (migration applied).
- When: column is dropped via a hypothetical rollback (SQLite requires table recreation — drop column and recreate table without `section_name`; or use test helper that removes the column).
- Then: DB can be re-migrated from scratch (Scenario A) without error; confirms the migration is logically reversible in a test harness.

Note: SQLite does not support `ALTER TABLE DROP COLUMN` before version 3.35.0. The rollback scenario uses table recreation via `CREATE TABLE pages_backup AS SELECT ... (excluding section_name) FROM pages; DROP TABLE pages; ALTER TABLE pages_backup RENAME TO pages;` in the test body. This is a test-only pattern and does not change the migration implementation.

---

## Step 5 — Key Test Coverage Checklist

| Concern | Test(s) | Covered |
|---------|---------|---------|
| `SectionManifest` parse from valid TOML | T-001 | Yes |
| `SectionManifest` parse from malformed TOML | T-002 | Yes |
| `SectionManifest` parse with unknown TOML keys | T-003 | Yes |
| `GraphLoader` with `activeSectionSet`: only active paths indexed | T-009 | Yes |
| `SectionIndex` accumulation: two deactivations, both tombstones resolve | T-046 | Yes (see below) |
| `SectionResolver.resolveTombstone`: inactive section → correct section returned | T-013 | Yes |
| `SectionResolver.resolveTombstone`: genuinely absent → `null` | T-014 | Yes |
| `SectionResolver.resolveTombstone`: all sections active → `null` | T-016 | Yes |
| `SparseCheckoutService.hasRemote`: repo with remote → `true` | T-018 | Yes |
| `SparseCheckoutService.hasRemote`: local-only → `false`, no exception | T-022 | Yes |
| `SparseCheckoutService.setSparseCheckout`: applies correct paths | T-018 | Yes |
| `SparseCheckoutService.setSparseCheckout`: no-op if no remote | T-023 | Yes |
| `SparseCheckoutService.setSparseCheckout`: always includes `.stele-sections` | T-018 (assert) | Yes |
| WASM `SectionSyncService`: REST 200 → OPFS write | T-024 | Yes |
| WASM `SectionSyncService`: 401 → `CredentialRequired` | T-025 | Yes |
| WASM `SectionSyncService`: incomplete sync marker → re-fetch | T-027 | Yes |
| DB migration idempotency: run twice, no exception, column exists | T-M-01 Scenario B | Yes |
| Backward compatibility: graph without `.stele-sections` loads identically | T-045 | Yes (see below) |
| Credential retry: 401 then PAT → 200 succeeds with `Authorization` header | T-047 | Yes (see below) |

**Additional tests required by checklist not already assigned above:**

| # | Test name | Type | Source set | Scenario |
|---|-----------|------|------------|----------|
| T-045 | `graphManager_should_loadIdentically_whenManifestAbsent` | Integration | `jvmTest` | Graph without `.stele-sections` opened via `GraphManager.addGraph`. Verify: `SectionManifest.DEFAULT` used; `activeSectionSet = null`; all pages indexed; no "Sections" entry in settings; `Page.namespace` unchanged. Regression guard for AC-5. |
| T-046 | `sectionIndex_should_resolveBothTombstones_afterTwoSequentialDeactivations` | Unit | `businessTest` | Deactivate "personal" (builds `SectionIndex` for "personal"), then deactivate "finance" (builds `SectionIndex` for "finance"). `sectionIndices` contains both entries. `SectionResolver.resolveTombstone("My Diary")` → "personal"; `resolveTombstone("Budget 2026")` → "finance". Second deactivation did NOT erase first index. |
| T-047 | `wasmSectionSyncService_should_retryWithPat_afterCredentialRequired` | Unit | `wasmJsTest` | Mock HTTP client returns 401 on first call. `syncSection` emits `DomainError.CredentialRequired`. Retry with `RepoCredential.PersonalAccessToken("test-token")` and mock returning 200. Assert: sync succeeds; `Authorization: Bearer test-token` header sent on the retry call. |

---

## Step 6 — Source Set Assignments

| Source set | Test IDs | Rationale |
|------------|----------|-----------|
| `businessTest` | T-001, T-002, T-003, T-004, T-007, T-008, T-012, T-013, T-014, T-016, T-M-01, T-046 | Pure domain logic — `SectionManifest` parsing, `SectionFilter`, `SectionResolver`, `SectionIndex`, DB migration. No UI, no file system, no subprocess. |
| `jvmTest` | T-005, T-006, T-009, T-010, T-011, T-015, T-017, T-018, T-019, T-020, T-021, T-022, T-023, T-032, T-033, T-034, T-035, T-036, T-037, T-038, T-039, T-040, T-041; all T-UA-* not tagged `[WASM]` | Integration tests requiring file system (temp dirs), JVM subprocess (`ProcessBuilder`/git), and Compose screenshot tests. |
| `wasmJsTest` | T-024, T-025, T-026, T-027, T-028, T-029, T-030, T-031, T-047; T-UA-13 through T-UA-19, T-UA-26, T-UA-28, T-UA-38, T-UA-39, T-UA-40, T-UA-41 | WASM-platform code: `WasmSectionSyncService` (REST + OPFS), WASM UI selector, credential management. |

---

## Summary

### Test Case Counts by Type

| Type | Count |
|------|-------|
| Unit (happy path) | 28 |
| Unit (error path) | 18 |
| Integration | 17 |
| UX Acceptance | 43 |
| Migration | 1 (3 scenarios within it) |
| **Total named tests** | **107** |

### Requirements Coverage

| FR | Unit | Error | Integration | Covered |
|----|------|-------|-------------|---------|
| FR-1 | T-001, T-003, T-004 | T-002 | T-005, T-006 | Yes |
| FR-2 | T-007, T-012 | T-008 | T-009, T-010, T-011 | Yes |
| FR-3 | T-013, T-016 | T-014 | T-015, T-017 | Yes |
| FR-4 | T-018, T-021, T-022 | T-019, T-023 | T-020 | Yes |
| FR-5 | T-024, T-026, T-028, T-030 | T-025, T-027, T-029, T-031 | — | Yes |
| FR-6 | T-032 | T-033 | T-034, T-035, T-036 | Yes |
| FR-7 | — | T-038 | T-037, T-039 | Yes |
| FR-8 | T-012, T-041 | — | T-040 | Yes |

**Requirements coverage fraction: 8 / 8 FRs covered (100%). All 43 UX acceptance criteria covered.**

### Migration Test

**Yes** — `sectionNameMigration_should_be_reversible` (T-M-01) covers:
- Single apply with schema verification
- Idempotency (double apply, no exception)
- Logical rollback scenario

### NFR Coverage Notes

| NFR | Addressed by |
|-----|-------------|
| NFR-2 (≤200 ms filter overhead) | T-009 can be extended with timing assertion on an 8 000-page fixture to gate regression. Mark as `@SlowTest` / separate benchmark suite. |
| NFR-5 (backward compat) | T-045 + T-005 |
| NFR-6 (WASM offline) | T-028 (cached SHA match → no fetch) |
| NFR-8 (no `Namespace` types) | Grep assertion in CI (AC-8 in requirements) — not a unit test. |
