# SteleKit Assets Viewer — Phase 4 Validation

**Date**: 2026-06-28
**Input artifacts**: requirements.md, plan.md, adversarial-review.md, ADR-001, ADR-002
**Verdict**: PASS WITH CONCERNS (see Gate 4)

---

## 1. Adversarial Blocker Resolution

The adversarial review returned BLOCKED on three compile-breaking issues. Verification that
the implementation plan patches all three before assigning a readiness verdict:

| ID  | Blocker | Plan Patch | Resolved? |
|-----|---------|------------|-----------|
| B1  | `AssetFilter.entries` and `filter.name` do not exist on sealed classes | Task 44a replaces `.entries` with `AssetFilter.all`; task 44b adds `fun AssetFilter.displayName()` extension | YES |
| B2  | `AssetFilter.FILES` dropped from sealed class without notice | Task 44 sealed class definition includes `object FILES : AssetFilter()` and `FILES` in `AssetFilter.all` | YES |
| B3  | Missing `wasmJsMain` actual for `PlatformFileOpener`; web build fails | Task 22a explicitly adds `PlatformFileOpener.wasmJs.kt` no-op stub | YES |

Adversarial concerns C1 through C4 are also resolved in the plan:
- C1 (ADR-001 contradiction): Task 38 adds `cursorSortKey`/`cursorUuid` compound fields; task 39 dispatches BY_NAME/BY_SIZE to compound keyset queries.
- C2 (loadJob race): Task 11 shows `loadJob?.cancel()` before `loadJob = scope.launch { ... }`.
- C3 (initial load OFFSET): Task 39 narrative confirms `getAssetPage(cursorMs = null)` for initial load.
- C4 (else-branch catch-all): Task 44c adds explicit branches per variant and logs unexpected ones.

---

## 2. Test Suite Design

### Conventions (from existing tests)

```kotlin
// Imports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

// Test class structure
class FooTest {
    private fun repo() = InMemoryAssetRepository()  // factory, not field
    @Test fun `descriptive backtick name`() = runTest { ... }
}
```

New test classes must be added to `AllBusinessTests.kt` (`@Suite.SuiteClasses`) alongside
any registration required by Bazel `kt_jvm_test` targets.

### Testability requirement (3 private functions must be extracted)

Three helper functions defined as `private` in composable files cannot be imported by
businessTest. The implementation must extract them as `internal` before tests can compile:

| Private function | Location per plan | Required extraction |
|---|---|---|
| `coilModelFor(filePath, relativePath): String` | inline `val` in `AssetItemCard.kt` | Extract as `internal fun coilModelFor(...)` in same file |
| `emptyStateHeadline(filter)`, `emptyStateBody(filter)` | private in `AssetBrowserEmptyState.kt` | Extract as `internal fun` in `AssetBrowserUiState.kt` alongside `displayName()` |
| `formatFileSize(bytes: Long): String` | private in `AssetDetailScreen.kt` | Extract as `internal fun` in a new `AssetFormatters.kt` (commonMain) |

---

### TC-REQ1 — Image Thumbnails

**Class**: `AssetItemCardCoilModelTest`
**Location**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/assets/AssetItemCardCoilModelTest.kt`
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ1-001 | `coilModelFor prefixes bare POSIX path with file://` | `coilModelFor("/storage/.../photo.jpg", "../assets/images/photo.jpg")` starts with `"file://"` |
| TC-REQ1-002 | `coilModelFor passes saf:// paths unchanged` | `coilModelFor("saf://authority/...", ...)` equals input unchanged |
| TC-REQ1-003 | `coilModelFor passes already-prefixed file:// paths unchanged` | `coilModelFor("file:///path/to/file", ...)` does not double-prefix |

---

### TC-REQ2 — Asset Detail Screen

**Class A**: `AssetDetailViewModelTest`
**Location**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/assets/AssetDetailViewModelTest.kt`
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ2-001 | `load() cancels prior loadJob on repeated calls` | Call `load(uuid1)` then `load(uuid2)`; only uuid2 state propagates; no duplicate state emissions |
| TC-REQ2-002 | `load() transitions state from isLoading=true to asset present` | After repository emits `Right(asset)`, `state.asset == asset` and `state.isLoading == false` |
| TC-REQ2-003 | `load() sets error state on repository Left` | Repository emits `Left(DomainError.ReadFailed("boom"))`; `state.error == "boom"` |
| TC-REQ2-004 | `onForgotten() cancels the internal scope` | Call `onForgotten()`; `scope.isActive == false` (verify via exposed `isCancelled` in test subclass or check that further `load()` has no effect) |

**Class B**: `AssetDetailFormatterTest`
**Location**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/assets/AssetDetailFormatterTest.kt`
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ2-005 | `formatFileSize formats bytes below 1 MB as KB` | `formatFileSize(512_000L)` contains `"KB"` and not `"MB"` |
| TC-REQ2-006 | `formatFileSize formats bytes ≥ 1 MB as MB with one decimal` | `formatFileSize(1_500_000L)` equals `"1.5 MB"` |

---

### TC-REQ3 — Wired Action Menu

**Class A**: `AssetBrowserViewModelTest` (see below — shared class)

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ3-001 | `copyMarkdownLink generates ![]() for IMAGE assets` | Link starts with `"!["` and contains `relativePath` |
| TC-REQ3-002 | `copyMarkdownLink generates []() for non-IMAGE assets` | Link starts with `"["` (no leading `!`) |
| TC-REQ3-003 | `deleteAsset optimistically removes asset from state before repository call` | `_state.assets` excludes the target uuid before the coroutine completes |
| TC-REQ3-004 | `deleteAsset rolls back and reloads on repository error` | After repository returns Left, state error is set and `loadAssets()` is re-triggered |

**Class B**: `PlatformFileOpenerJvmTest`
**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/platform/PlatformFileOpenerJvmTest.kt`
**Type**: platform/JVM

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ3-005 | `JVM actual calls Desktop.getDesktop().open() with correct File` | Spy/fake `Desktop` captures call; `File.path` equals `absolutePath` passed to `openFile()` |

Note: `Desktop.getDesktop()` is a final JDK class. Test via a lightweight `DesktopAdapter` seam
injected into the JVM actual, or skip on headless CI with `assumeTrue(Desktop.isDesktopSupported())`.

**Class C**: `AndroidPlatformFileOpenerTest`
**Location**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/AndroidPlatformFileOpenerTest.kt`
**Type**: platform/Android

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ3-006 | `SAF paths produce Intent with ACTION_VIEW and content URI` | Intent action == `Intent.ACTION_VIEW`; data URI scheme == `"content"` |

---

### TC-REQ4 — Sort Order

**Class**: `AssetBrowserViewModelTest` + `AssetRepositoryTest` (additions to existing) + `AssetFilterTest` (new)

**`AssetFilterTest`**
**Location**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/assets/AssetFilterTest.kt`
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ4-001 | `AssetSortOrder has exactly 3 entries` | `AssetSortOrder.entries.size == 3` and contains BY_DATE_ADDED, BY_NAME, BY_SIZE |
| TC-REQ4-002 | `setSortOrder(BY_NAME) updates state.sortOrder` | `_state.value.sortOrder == AssetSortOrder.BY_NAME` after call |
| TC-REQ4-003 | `setSortOrder(BY_SIZE) resets all cursor fields to null` | `nextCursorMs`, `cursorSortKey`, `cursorUuid` all null after call |

**`AssetRepositoryTest` (additions)**

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ4-004 | `getSortedAssets(BY_NAME) returns alphabetical order` | Save entries "zebra.jpg", "apple.pdf"; first result is "apple.pdf" |
| TC-REQ4-005 | `getSortedAssets(BY_SIZE) returns largest-first order` | Save 1 KB and 5 MB entries; first result has larger `sizeBytes` |
| TC-REQ4-006 | `getSortedAssets(BY_DATE_ADDED) returns newest-first order` | Save entries with importedAtMs 100 and 200; first result has importedAtMs 200 |

---

### TC-REQ5 — Pagination / Infinite Scroll

**Class A**: `AssetBrowserViewModelTest`
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ5-001 | `loadMore() no-ops when isLoadingMore is true` | Seed state with `isLoadingMore=true`; call `loadMore()`; repository not called again |
| TC-REQ5-002 | `loadMore() no-ops when hasMore is false` | Seed state with `hasMore=false`; call `loadMore()`; repository not called again |
| TC-REQ5-003 | `loadMore() appends page to state.assets and advances nextCursorMs` | Load page 1 (50 items); call `loadMore()`; total items == 100; `nextCursorMs` equals last item's `importedAtMs` |
| TC-REQ5-004 | `filter change resets all cursor fields to null` | Set filter to IMAGES (non-null cursor exists); `nextCursorMs`, `cursorSortKey`, `cursorUuid` all null after `setFilter()` |
| TC-REQ5-005 | `sort order change resets all cursor fields to null` | Set sort to BY_NAME (non-null cursor); verify all cursor fields null after `setSortOrder()` |

**Class B**: `AssetRepositoryTest` (additions)
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ5-006 | `getAssetPage(cursorMs=null) returns first page ordered newest-first` | 60 entries inserted; first page returns 50 items; first item has highest `importedAtMs` |
| TC-REQ5-007 | `getAssetPage cursor from page 1 returns items not in page 1` | Page 1 cursor = last item's `importedAtMs`; page 2 contains disjoint items; union == all 60 |
| TC-REQ5-008 | `getAssetPage(BY_NAME) compound cursor produces disjoint pages` | Insert 60 items; pages 1 and 2 share no UUIDs; combined == all 60 |
| TC-REQ5-009 | `getAssetPage(BY_SIZE) compound cursor produces disjoint pages` | Insert 60 items with varied sizes; pages 1 and 2 share no UUIDs; combined == all 60 |

---

### TC-REQ6 — Tag-Based Grouping

**Class A**: `AssetFilterTest` (new)
**Location**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/assets/AssetFilterTest.kt`
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ6-001 | `AssetFilter.all contains exactly 8 static variants` | `AssetFilter.all.size == 8` |
| TC-REQ6-002 | `AssetFilter.all contains FILES variant` | `AssetFilter.FILES in AssetFilter.all` (regression for B2) |
| TC-REQ6-003 | `displayName() returns non-empty string for all 8 static variants` | All `AssetFilter.all.map { it.displayName() }` are non-blank |
| TC-REQ6-004 | `displayName() for TAG("nature") returns "nature"` | `AssetFilter.TAG("nature").displayName() == "nature"` |
| TC-REQ6-005 | `displayName() when expression is exhaustive — new variant fails at compile time` | Structural: verified by sealed class + when exhaustiveness; no runtime test needed |

**Class B**: `AssetRepositoryTest` (additions)
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ6-006 | `getDistinctTags() returns sorted deduplicated tags` | Save 3 entries with tags `["beta", "alpha"]`, `["alpha"]`, `["gamma"]`; result == `["alpha", "beta", "gamma"]` |
| TC-REQ6-007 | `getDistinctTags() excludes empty tag strings` | Entry with `tags = ["", "valid"]`; `""` absent from result |
| TC-REQ6-008 | `getDistinctTags() returns empty list when no assets have tags` | No entries saved; result == `emptyList()` |

**Class C**: `AssetBrowserViewModelTest`
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ6-009 | `selectTag("nature") sets selectedFilter to TAG("nature")` | `_state.value.selectedFilter == AssetFilter.TAG("nature")` |
| TC-REQ6-010 | `selectTag(null) resets filter to ALL` | `_state.value.selectedFilter == AssetFilter.ALL` |
| TC-REQ6-011 | `TAG filter loads only tagged assets` | Repository has assets with/without tag "nature"; VM with TAG("nature") filter exposes only tagged ones |

---

### TC-REQ7 — Orphaned Assets Filter

**Class A**: `AssetRepositoryTest` (additions)
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ7-001 | `getOrphanedAssets() returns only entries with empty pageUuids` | Save orphan (`pageUuids=[]`) and referenced (`pageUuids=["page-1"]`); result contains only orphan |
| TC-REQ7-002 | `getOrphanedAssets() excludes assets with one or more page references` | Entry with `pageUuids=["p1", "p2"]` absent from result |
| TC-REQ7-003 | `countOrphanedAssets() returns correct count` | 2 orphans + 1 referenced saved; count == 2 |

**Class B**: `AssetBrowserViewModelTest`
**Type**: unit

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ7-004 | `ORPHANED filter delegates to getOrphanedAssets() not getAssets()` | Spy `InMemoryAssetRepository`; assert `getOrphanedAssets()` called and `getAssets()` not called when filter == ORPHANED |

---

### TC-REQ8 — Empty State

**Class**: `AssetBrowserEmptyStateLogicTest`
**Location**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserEmptyStateLogicTest.kt`
**Type**: unit (requires `emptyStateHeadline` / `emptyStateBody` extracted as `internal fun`)

| Test ID | Test name | Key assertion |
|---------|-----------|---------------|
| TC-REQ8-001 | `emptyStateHeadline(ALL) returns "No assets yet"` | `emptyStateHeadline(AssetFilter.ALL) == "No assets yet"` |
| TC-REQ8-002 | `emptyStateBody(ALL) returns page attachment hint` | `emptyStateBody(AssetFilter.ALL) == "Attach a file to any page to see it here"` |
| TC-REQ8-003 | `emptyStateHeadline(ORPHANED) returns "No orphaned assets"` | `emptyStateHeadline(AssetFilter.ORPHANED) == "No orphaned assets"` |
| TC-REQ8-004 | `emptyStateHeadline(TAG("nature")) includes tag name` | `emptyStateHeadline(AssetFilter.TAG("nature"))` contains `"nature"` |
| TC-REQ8-005 | `emptyStateBody(IMAGES) returns generic filter message` | `emptyStateBody(AssetFilter.IMAGES)` contains `"filter"` or `"search"` (not the ALL-specific copy) |
| TC-REQ8-006 | `emptyStateHeadline is exhaustive — covers all 8 static variants and TAG` | Structural: sealed `when` in `emptyStateHeadline()` is exhaustive; compiler enforces. Verify no `else` fallthrough. |

---

## 3. Test Suite Summary

| Class | Source set | # Tests | Requirements covered |
|-------|-----------|---------|----------------------|
| `AssetItemCardCoilModelTest` (new) | businessTest | 3 | REQ-1 |
| `AssetDetailViewModelTest` (new) | businessTest | 4 | REQ-2 |
| `AssetDetailFormatterTest` (new) | businessTest | 2 | REQ-2 |
| `AssetBrowserViewModelTest` (new) | businessTest | 15 | REQ-3, REQ-4, REQ-5, REQ-6, REQ-7 |
| `AssetRepositoryTest` (additions to existing) | businessTest | 14 | REQ-4, REQ-5, REQ-6, REQ-7 |
| `AssetFilterTest` (new) | businessTest | 5 | REQ-4, REQ-6 |
| `AssetBrowserEmptyStateLogicTest` (new) | businessTest | 6 | REQ-8 |
| `PlatformFileOpenerJvmTest` (new) | jvmTest | 1 | REQ-3 |
| `AndroidPlatformFileOpenerTest` (new) | androidUnitTest | 1 | REQ-3 |
| **Total** | | **51** | **REQ-1 through REQ-8** |

**By type**:
- Unit / business-logic: 49
- Platform / JVM: 1
- Platform / Android: 1

**Requirements coverage**: 8 / 8 (100%)

---

## 4. Readiness Gate

### Gate 1 — Requirements coverage

| Requirement | Test count | Status |
|-------------|-----------|--------|
| REQ-1 Image thumbnails | 3 | COVERED |
| REQ-2 Asset detail screen | 6 | COVERED |
| REQ-3 Wired action menu | 6 | COVERED |
| REQ-4 Sort order | 6 | COVERED |
| REQ-5 Pagination / infinite scroll | 9 | COVERED |
| REQ-6 Tag-based grouping | 11 | COVERED |
| REQ-7 Orphaned assets filter | 4 | COVERED |
| REQ-8 Empty state | 6 | COVERED |

**Result: PASS** — all 8 requirements have ≥1 test case.

### Gate 2 — Plan completeness (orphaned tasks)

All 68 tasks reference types or methods that either already exist or are introduced within the
same plan. No task references an undefined type.

One gap from the adversarial review (M1) remains open: task 40 provides no implementation
code for `getAssetPage` in `InMemoryAssetRepository`. TC-REQ5-006 through TC-REQ5-009
will exercise this path; the implementer must infer correct null-cursor handling from the
SqlDelight version. This is an implementation risk, not a plan structural gap.

**Result: PASS** — no orphaned tasks.

### Gate 3 — Adversarial blockers resolved

All 3 BLOCKER items (B1, B2, B3) have explicit plan patches verified above in Section 1.
All 4 CONCERN items (C1–C4) are addressed in the plan.

**Result: PASS** — all 3 blockers present in plan patches.

### Gate 4 — No unbounded reads

**`getDistinctTags()` is a standing whole-graph observer without backpressure guards.**

The plan's `SqlDelightAssetRepository.getDistinctTags()` implementation (task 48):
```kotlin
queries.selectAllTagsJson()   // SELECT tags FROM asset_index — no LIMIT
    .asFlow()
    .mapToList(PlatformDispatcher.DB)
    .map { ... }
    .catchDbError()
```

`selectAllTagsJson` fetches the `tags` column from every row in `asset_index`. On an 8 000+
asset graph this is an unbounded read. Each ML pipeline write to `asset_index` invalidates the
SQLDelight collector and re-materializes every tags row. The CLAUDE.md architecture rules
require standing whole-graph observers to include `conflate() + distinctUntilChanged()` (and
ideally a debounce) between `asFlow()` and `mapToList()` to suppress churn.

The current plan omits these guards.

**Required fix before implementation**:
```kotlin
queries.selectAllTagsJson()
    .asFlow()
    .conflate()                   // drop intermediate re-emissions under rapid ML writes
    .mapToList(PlatformDispatcher.DB)
    .map { jsonList -> ... }
    .distinctUntilChanged()       // suppress no-op re-renders when tag set is unchanged
    .catchDbError()
```

This does not require a new ADR — it is a CLAUDE.md compliance fix.

**Result: CONCERN** — one unbounded flow missing backpressure guards.

---

## 5. Overall Verdict

**PASS WITH CONCERNS**

All three adversarial blockers are patched in the plan. Requirements coverage is 100% (8/8).
No orphaned tasks. The implementation may proceed.

Before implementation starts, the plan owner should address:

1. **[Required for tests to compile]** Extract `coilModelFor(...)`, `emptyStateHeadline(...)`,
   `emptyStateBody(...)`, and `formatFileSize(...)` as `internal fun` in testable locations
   (specified per function in Section 2 above). Without this extraction, 11 unit tests
   (TC-REQ1-001–003, TC-REQ2-005–006, TC-REQ8-001–006) cannot import these functions.

2. **[Required for CLAUDE.md compliance]** Add `conflate() + distinctUntilChanged()` to the
   `getDistinctTags()` flow chain in `SqlDelightAssetRepository` (task 48).

3. **[Low-risk, complete before PR merge]** Provide `InMemoryAssetRepository.getAssetPage()`
   implementation code in the plan (task 40) so TC-REQ5-006 through TC-REQ5-009 have a
   defined reference to verify against. Null-cursor behavior must match the
   `IS NULL`-sentinel semantics of the SQLDelight keyset queries.
