# Adversarial Review — SteleKit Assets Viewer Implementation Plan

**Verdict: BLOCKED**

Three compile-breaking issues are present. One accepted ADR is directly contradicted by the implementation plan. Several lesser concerns would cause runtime bugs. None of these are theoretical edge cases — they are straight-line paths through the implementation.

---

## BLOCKER 1 — `AssetFilter.entries` and `filter.name` will not compile after sealed-class migration

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserScreen.kt`, lines 161 and 173

**Current code** (confirmed by reading the file):
```kotlin
val filters = AssetFilter.entries   // line 161 — enum API
...
label = { Text(filter.name.lowercase()...) }  // line 173 — enum property
```

`.entries` and `.name` are `enum class`-specific members. Neither exists on a sealed class or its companion object. After Epic 6 task 44 migrates `AssetFilter` to a sealed class, both lines are compile errors.

**What the plan says**: Task 44 says "Update all `when(selectedFilter)` call sites — most exhaustive `when` expressions will fail to compile, surfacing all call sites automatically." This statement is wrong on two counts: (1) `AssetBrowserViewModel.loadAssets()` uses `when { state.selectedFilter == AssetFilter.ALL -> ... }` — a boolean-subject `when`, not exhaustive. It does NOT fail to compile. (2) The `ScrollableFilterChipRow` call site uses `.entries` and `.name`, neither of which is a `when` expression, so the claim that "exhaustive `when` will surface all call sites" misses both of these.

**Fix required**: Before removing the enum, the plan must explicitly enumerate the base variants as a list (e.g., a companion `val baseFilters: List<AssetFilter>`) and replace `.name` with a display name property or extension function on the sealed type.

---

## BLOCKER 2 — `AssetFilter.FILES` variant is silently dropped

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserUiState.kt`, line 16

**Current enum** (confirmed by reading the file):
```kotlin
enum class AssetFilter {
    ALL, IMAGES, PDFS, AUDIO, VIDEO, DOCUMENTS, FILES, ORPHANED
}
```

**Plan's sealed class** (task 44):
```kotlin
sealed class AssetFilter {
    object ALL : AssetFilter()
    object IMAGES : AssetFilter()
    object PDFS : AssetFilter()
    object AUDIO : AssetFilter()
    object VIDEO : AssetFilter()
    object DOCUMENTS : AssetFilter()
    object ORPHANED : AssetFilter()
    data class TAG(val name: String) : AssetFilter()
}
```

`FILES` is absent. `AssetFilter.FILES` appears in `ScrollableFilterChipRow` via `.entries` (currently rendered as a chip labelled "Files"). After the migration, any direct reference to `AssetFilter.FILES` is a compile error. Even if no code references it by name at migration time, if it was user-visible, removing it is a silent behavior change with no notice in the plan. The plan must either add `object FILES : AssetFilter()` or explicitly document the variant is being intentionally removed.

---

## BLOCKER 3 — Missing `wasmJsMain` actual for `PlatformFileOpener`

**Reference**: ADR-002 Consequences section explicitly states: "A `wasmJsMain` no-op stub is required for compilation even though Web is a non-goal."

**Plan file change summary** lists only:
- `platform/PlatformFileOpener.kt` (commonMain expect)
- `platform/PlatformFileOpener.jvm.kt` (jvmMain actual)
- `platform/PlatformFileOpener.android.kt` (androidMain actual)

No `wasmJsMain` actual is included. The project builds the web target (`bazel build //kmp:web_app`). An `expect fun` with no `actual` in a compiled platform is a hard build error: "Expected function 'rememberPlatformFileOpener' has no actual declaration in module kmp.wasm". The ADR authors knew this and flagged it; the plan didn't carry the fix forward.

**Fix**: Add `kmp/src/wasmJsMain/kotlin/.../platform/PlatformFileOpener.wasmJs.kt` with a no-op stub, and add this file to the File Change Summary.

---

## CONCERN 1 — Plan contradicts ADR-001 for BY_NAME / BY_SIZE sort pagination

**ADR-001 status**: Accepted.

**ADR-001 decision** (Consequences section): "Sort-by-name and sort-by-size queries need a two-column cursor `(sortKey, uuid)` instead of a single `imported_at_ms` cursor. `AssetBrowserUiState` tracks the last-seen cursor pair for these sort modes."

**Plan's Epic 5, Story 5.2** adds only `nextCursorMs: Long?` to `AssetBrowserUiState`. There is no `lastSeenName: String?`, `lastSeenSize: Long?`, or `lastSeenUuid: String?` field.

**Plan's Epic 5, Story 5.3** says: "For `BY_DATE_ADDED` sort use keyset queries; for other sorts fall back to OFFSET (`cursorMs` ignored, use a running `offset` tracked in the ViewModel)."

This directly contradicts the accepted ADR. If `BY_NAME` and `BY_SIZE` fall back to OFFSET, the insert drift and invalidation churn problems documented in ADR-001 still occur for those sort orders during background ML writes. The ViewModel has no `offset` state variable for these paths either — task 41's `loadMore()` only reads `s.nextCursorMs`, with no handling for the OFFSET fallback path.

**Required action**: Either (a) amend ADR-001 to explicitly accept OFFSET fallback for non-date sorts and add `offset: Int` tracking to `AssetBrowserUiState`, or (b) implement the compound cursor for BY_NAME/BY_SIZE as ADR-001 mandates. As-is, the plan is internally inconsistent with an accepted architectural decision.

---

## CONCERN 2 — `AssetDetailViewModel.load()` does not cancel prior collection

**File**: Plan task 11, `AssetDetailViewModel`

The plan's `load()` method does:
```kotlin
fun load(assetUuid: AssetUuid) {
    scope.launch {
        assetRepository.getAssetByUuid(assetUuid).collect { ... }
    }
}
```

There is no job cancellation before launching. `getAssetByUuid` returns a `Flow` that lives until the collector is cancelled. If `load()` is called a second time (e.g., by a `LaunchedEffect` re-running on recomposition or rotation), two concurrent `collect` coroutines run on the same scope, both writing to `_state`. This causes duplicate state updates and potential races.

**Compare**: `AssetBrowserViewModel.loadAssets()` correctly does `loadJob?.cancel(); loadJob = scope.launch { ... }`. The same pattern must be applied in `AssetDetailViewModel`.

---

## CONCERN 3 — Epic 4 (`getSortedAssets`) still uses OFFSET; Epic 5 (`loadMore`) uses keyset — split invariant

**Files**: Tasks 32–33 (Epic 4) and tasks 39–41 (Epic 5)

`getSortedAssets(mediaType, query, sortOrder, limit, offset)` uses OFFSET throughout (Epic 4). Task 35 replaces `loadAssets()` to call `getSortedAssets(...)`. `getAssetPage(mediaType, sortOrder, cursorMs, limit)` uses keyset (Epic 5).

The result: the *first* page of any asset list is loaded via OFFSET (fragile under ML writes). Only subsequent pages appended by `loadMore()` use keyset. Any ML pipeline write that fires between screen entry and first-page render can cause a drift on the first OFFSET fetch. The keyset benefit is only realised from page 2 onward.

This is not a blocker by itself, but it means the drift mitigation in ADR-001 is only partially applied. The plan should make `loadAssets()` use `getAssetPage(cursorMs = null)` for the initial load so keyset applies from the start.

---

## CONCERN 4 — `AssetBrowserViewModel.loadAssets()` else-branch is a silent catch-all

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/assets/AssetBrowserViewModel.kt`, line 99

Current code:
```kotlin
else -> assetRepository.getAssets(limit = 50, offset = 0)
```

After the sealed-class migration, any `AssetFilter` variant not explicitly handled silently falls through to `getAssets()`. This currently catches `ORPHANED` (before Epic 7 is implemented). After Epic 6 adds `TAG`, any `TAG` variant not yet handled would also silently show all assets instead of tagged assets. The `when { }` block is not exhaustive (boolean subject), so the compiler does not catch missing branches.

**Fix**: Replace the else-branch with explicit branches for every known variant as they are added, and add a logging statement in an `else` to flag unexpected variants in tests.

---

## MINOR — `getAssetPage` in `InMemoryAssetRepository` is not specified in the plan

**File**: Task 40

Task 40 says: "Implement in `InMemoryAssetRepository` with `@Suppress("InMemoryPagination")`." No implementation code is shown. All other InMemoryAssetRepository additions in the plan include working code (tasks 34, 49, 57). This one is left as a note. Whoever implements it must infer the behaviour from the SqlDelight implementation, which risks inconsistency (e.g., handling of `cursorMs = null` for first-page detection).

---

## Summary Table

| ID | Type | Description |
|----|------|-------------|
| B1 | BLOCKER | `AssetFilter.entries` and `filter.name` compile errors after sealed-class migration |
| B2 | BLOCKER | `AssetFilter.FILES` dropped without notice or compile-time enforcement |
| B3 | BLOCKER | `wasmJsMain` actual for `PlatformFileOpener` missing; web build will fail |
| C1 | CONCERN | Plan contradicts accepted ADR-001: BY_NAME/BY_SIZE use OFFSET fallback instead of compound keyset cursor |
| C2 | CONCERN | `AssetDetailViewModel.load()` does not cancel prior collection; race condition on re-entry |
| C3 | CONCERN | Initial page load uses OFFSET; keyset only kicks in at page 2 — partial drift mitigation |
| C4 | CONCERN | `loadAssets()` else-branch silently swallows future unhandled sealed variants |
| M1 | MINOR | `getAssetPage` in `InMemoryAssetRepository` has no implementation code in the plan |
