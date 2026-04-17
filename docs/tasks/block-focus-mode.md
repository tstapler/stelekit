# Block Focus Mode (Zoom-In / Block Page)

**Feature**: Zoom into any block to make it the root of the view, with a breadcrumb trail for navigation back up the hierarchy.

**Priority**: Launch-critical — core outliner interaction pattern used by power users for deep hierarchies.

---

## Epic Overview

### User Value
Power users building deeply-nested outlines cannot focus on a subtopic without visually losing context or manually collapsing siblings. Block Focus Mode lets a user "zoom into" any block — that block becomes the page root, its children fill the view, and a breadcrumb trail at the top allows one-click navigation back up to any ancestor or the parent page.

### Success Metrics
- Clicking the bullet on any block navigates to block-focus view in < 100 ms (same-session, blocks already loaded)
- Breadcrumb renders full ancestry chain: `Page Name > Parent > … > Current Block`
- Each breadcrumb item is tappable, navigating to that level instantly
- Back button (existing `goBack()`) correctly exits block-focus and returns to the previous screen
- Zero regression on existing `Screen.PageView` navigation

### Scope
**In scope**:
- New `Screen.BlockFocus` sealed class variant
- `BlockFocusView` composable (re-uses `BlockList`, `BlockGutter`)
- `BreadcrumbBar` composable
- ViewModel methods: `navigateToBlockFocus(blockUuid)`, `buildBreadcrumb(blockUuid)`
- Bullet click-to-zoom gesture in `BlockGutter`
- Navigation history integration (back/forward)

**Out of scope**:
- URL/deep-link routing to a block UUID (post-launch)
- Embedded block previews / transclusion
- Search result navigation to block-focus

### Constraints
- Must not break existing `Screen.PageView` history stack
- `IBlockRepository.getBlockAncestors()` already exists — use it
- `IBlockRepository.getBlockChildren()` already exists — use it
- No new SQLDelight schema changes required (all data is already stored)
- Must work on Desktop (JVM), Android, iOS — no platform-specific APIs

---

## Architecture Decisions

| ADR | File | Decision |
|-----|------|----------|
| ADR-001 | `project_plans/stelekit/decisions/ADR-001-block-focus-screen-variant.md` | Extend `Screen` sealed class with `BlockFocus(block, page)` rather than overloading `PageView`. Keeps routing clean, preserves history semantics, avoids conditional rendering logic in `PageView`. |
| ADR-002 | `project_plans/stelekit/decisions/ADR-002-breadcrumb-ancestry-query.md` | Use existing `IBlockRepository.getBlockAncestors()` for breadcrumb data. Avoids duplicating ancestor-walk logic; already returns ordered list from immediate parent to root block. |
| ADR-003 | `project_plans/stelekit/decisions/ADR-003-bullet-click-zoom-gesture.md` | Add `onZoomIn: (() -> Unit)?` callback to `BlockGutter`. Null in `PageView` (disables feature); non-null in `BlockFocus` / `PageView` top-level call. Keeps `BlockGutter` free of navigation concerns. |

---

## Story Breakdown

### Story 1 — Navigation Model [3–4 days]

> As a user, I can navigate to a block-focus view so that I can concentrate on a specific subtopic and its children.

**Acceptance Criteria**:
- `Screen.BlockFocus(block: Block, page: Page)` exists in `AppState.kt`
- `StelekitViewModel.navigateToBlockFocus(blockUuid)` resolves the block and its parent page, then calls `navigateTo(Screen.BlockFocus(...))`
- Navigation history correctly stacks BlockFocus entries alongside PageView entries
- `goBack()` from BlockFocus returns to the previous screen (PageView or another BlockFocus)
- `App.kt` / `MainLayout.kt` routes `Screen.BlockFocus` to `BlockFocusView`

---

#### Task 1.1 — Extend Screen sealed class and ViewModel method [Small: 2h]

**Objective**: Add `Screen.BlockFocus` and `navigateToBlockFocus()` to the navigation model.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` (Block, Page types)

**Prerequisites**:
- Understand `Screen` sealed class and `navigateTo(screen: Screen)` in `StelekitViewModel`
- Understand `Block.parentUuid` and `Block.pageUuid` fields

**Implementation approach**:
1. Add `data class BlockFocus(val block: Block, val page: Page) : Screen()` to `AppState.kt` after the `PageView` variant.
2. Update `navigateTo()` status message `when` branch to handle `Screen.BlockFocus` (e.g., `"Zoomed into block"`).
3. Add `fun navigateToBlockFocus(blockUuid: String)` to `StelekitViewModel`:
   - `blockRepository.getBlockByUuid(blockUuid).first()` to get the block
   - Look up page via `cachedAllPages.find { it.uuid == block.pageUuid }` (fallback: `pageRepository.getPageByUuid(block.pageUuid)`)
   - Call `navigateTo(Screen.BlockFocus(block, page))`
4. Handle the null-page case: fall back to `navigateToPageByUuid(block.pageUuid)` (navigate to full page if page not yet loaded).

**Validation strategy**:
- Unit test: `navigateToBlockFocus()` with a known blockUuid transitions `uiState.currentScreen` to `Screen.BlockFocus`
- Unit test: `goBack()` after `navigateToBlockFocus()` returns to previous screen
- Unit test: missing block UUID emits status message, does not crash

**INVEST check**:
- Independent: No UI work, no new composables
- Negotiable: Status message wording is flexible
- Valuable: Enables all subsequent UI tasks
- Estimable: 2h with high confidence
- Small: 2 files, 1 new sealed class + 1 new ViewModel method
- Testable: Pure state machine logic, easily unit-tested

---

#### Task 1.2 — Wire Screen.BlockFocus into App routing [Micro: 1h]

**Objective**: Route `Screen.BlockFocus` to a placeholder `BlockFocusView` composable so the app does not crash and story integration can begin.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (or `MainLayout.kt` — whichever contains the screen routing `when` block)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`

**Prerequisites**:
- Task 1.1 complete (`Screen.BlockFocus` exists)
- Understand how `Screen.PageView` is routed in `App.kt`/`MainLayout.kt`

**Implementation approach**:
1. Open the `when (currentScreen)` routing block in `App.kt` or `MainLayout.kt`.
2. Add `is Screen.BlockFocus -> BlockFocusView(...)` with a stub composable that renders the block content as placeholder text.
3. The stub accepts `(block: Block, page: Page, viewModel: StelekitViewModel)` — matching the final signature.

**Validation strategy**:
- Manual smoke test: trigger `navigateToBlockFocus` from debug menu, verify app renders without crash
- Compile-time: Kotlin `when` exhaustiveness — compiler will warn if `BlockFocus` is unhandled

---

### Story 2 — Breadcrumb Trail [2–3 days]

> As a user, I can see a breadcrumb trail at the top of the block-focus view and tap any crumb to navigate up the hierarchy.

**Acceptance Criteria**:
- `BreadcrumbBar` composable renders `Page Name > Block preview (truncated) > … > Current Block`
- First crumb navigates to `Screen.PageView(page)`
- Middle crumbs (ancestor blocks) navigate to `Screen.BlockFocus(ancestorBlock, page)`
- Last crumb (current block) is non-clickable / visually distinct
- Breadcrumb items truncate at ~40 characters with ellipsis

---

#### Task 2.1 — ViewModel: build breadcrumb data [Small: 2h]

**Objective**: Add a suspend function / StateFlow to `StelekitViewModel` that resolves the full ancestor chain for a given block UUID.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/data/repositories/IBlockRepository.kt` (getBlockAncestors)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`

**Prerequisites**:
- `IBlockRepository.getBlockAncestors(blockUuid)` returns `Flow<Result<List<Block>>>` with oldest ancestor first
- Task 1.1 complete

**Implementation approach**:
1. Add `data class BreadcrumbItem(val label: String, val target: Screen)` to `AppState.kt`.
2. Add `suspend fun buildBreadcrumb(block: Block, page: Page): List<BreadcrumbItem>`:
   - Call `blockRepository.getBlockAncestors(block.uuid).first().getOrNull() ?: emptyList()`
   - Prepend a `BreadcrumbItem(page.name, Screen.PageView(page))`
   - Append one `BreadcrumbItem` per ancestor block: label = first 40 chars of `block.content`, target = `Screen.BlockFocus(ancestorBlock, page)`
   - Append a final non-navigable item for the current block (no target, or a sentinel `null` target)
3. Call `buildBreadcrumb` inside `navigateToBlockFocus` and store result in `AppState.blockFocusBreadcrumb: List<BreadcrumbItem>`.

**Validation strategy**:
- Unit test: `buildBreadcrumb` with a 3-level hierarchy returns items in correct order (page, grandparent, parent, current)
- Unit test: Empty ancestors list (root-level block) returns `[PageItem, CurrentItem]`
- Unit test: Content truncated at 40 chars

---

#### Task 2.2 — BreadcrumbBar composable [Small: 2h]

**Objective**: Create `BreadcrumbBar.kt` composable that renders the breadcrumb trail with chevron separators and click navigation.

**Context boundary**:
- New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BreadcrumbBar.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (BreadcrumbItem)
- Supporting: Material3 theming, `MaterialTheme.typography.bodySmall`

**Prerequisites**:
- Task 2.1 complete (`BreadcrumbItem` type exists)

**Implementation approach**:
1. Create `@Composable fun BreadcrumbBar(items: List<BreadcrumbItem>, onNavigate: (Screen) -> Unit, modifier: Modifier = Modifier)`.
2. Use a horizontal `LazyRow` (handles overflow on long chains) with items separated by `Text(" > ")`.
3. All items except the last are rendered as `TextButton` (or clickable `Text`) calling `onNavigate(item.target!!)`.
4. Last item rendered as plain `Text` with `MaterialTheme.colorScheme.onSurfaceVariant` color.
5. Wrap the whole bar in a `Surface` with subtle background for visual separation from block content.

**Validation strategy**:
- Screenshot test (Roborazzi): 1-item breadcrumb (page only), 3-item breadcrumb, long-text truncation
- Unit test: click on item at index `n` calls `onNavigate` with the correct `Screen`

---

### Story 3 — BlockFocusView composable [2–3 days]

> As a user, I see the focused block's content and all its children in the main content area, with a zoom-in trigger on each block's bullet.

**Acceptance Criteria**:
- `BlockFocusView` renders `BreadcrumbBar` at top, then the focused block's content as a header, then its children via `BlockList`
- Blocks are editable in focus mode (same editing experience as `PageView`)
- Each child block's bullet triggers `navigateToBlockFocus` for deeper zoom
- The focused block itself has no bullet zoom-in (it is the root)

---

#### Task 3.1 — BlockFocusView composable [Medium: 3h]

**Objective**: Implement the `BlockFocusView` screen composable, wiring BreadcrumbBar and BlockList.

**Context boundary**:
- New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/BlockFocusView.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BreadcrumbBar.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` (reference for block state wiring)

**Prerequisites**:
- Tasks 1.1, 1.2, 2.1, 2.2 complete
- Understand `BlockStateManager.observePage()` / `unobservePage()` pattern from `PageView.kt`

**Implementation approach**:
1. Create `@Composable fun BlockFocusView(block: Block, page: Page, breadcrumb: List<BreadcrumbItem>, blockStateManager: BlockStateManager, viewModel: StelekitViewModel, ...)`.
2. Call `blockStateManager.observePage(page.uuid, page.isContentLoaded)` in a `DisposableEffect`.
3. Collect `allBlocks[page.uuid]` from `blockStateManager.blocks`.
4. Filter blocks: only show `block` itself + all descendants. Use `childrenByParent` approach (same as `BlockList` internal logic). Alternatively, call `blockRepository.getBlockChildren(block.uuid)` — but prefer in-memory filtering from already-loaded `allBlocks` for zero-latency rendering.
5. Render:
   - `BreadcrumbBar(breadcrumb, onNavigate = viewModel::navigateTo)`
   - `HorizontalDivider`
   - Focused block header: render `block.content` as `headlineMedium` Text (read-only preview; tap to edit is acceptable but not required for MVP)
   - `BlockList` with `blocks = filteredChildren` (children only, not the root block itself in the list)
   - Pass `onZoomIn` to `BlockList` → `BlockItem` → `BlockGutter` (Task 3.2)

**Validation strategy**:
- Manual test: navigate to a block with 3 children, verify only those children appear
- Manual test: editing a child block in focus mode saves correctly
- Screenshot test: `BlockFocusView` with 2 breadcrumb levels

---

#### Task 3.2 — Add onZoomIn callback to BlockGutter [Small: 2h]

**Objective**: Add an optional `onZoomIn` callback to `BlockGutter` that fires when the user clicks the bullet dot, and wire it through `BlockItem` → `BlockList` → callers.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`

**Prerequisites**:
- Task 1.1 complete (so `navigateToBlockFocus` exists to pass as callback)
- Understand existing gutter layout: drag handle, collapse toggle, bullet dot

**Implementation approach**:
1. In `BlockGutter.kt`, add `onZoomIn: (() -> Unit)? = null` parameter.
2. Wrap the existing bullet `Box` in a `clickable { onZoomIn?.invoke() }` modifier when `onZoomIn != null`. Apply a `Modifier.semantics { role = Role.Button; contentDescription = "Zoom into block" }`.
3. Visual hint: when hovered (desktop), show a cursor change or subtle highlight. For MVP, click-only is sufficient.
4. Propagate `onZoomIn: ((blockUuid: String) -> Unit)? = null` through `BlockItem` and `BlockList` (nullable, defaults to null = disabled).
5. In `PageView.kt` (top-level `BlockList` call), wire `onZoomIn = { uuid -> viewModel.navigateToBlockFocus(uuid) }`.
6. In `BlockFocusView.kt`, wire the same callback for child blocks (enabling recursive zoom).

**Validation strategy**:
- Unit test: `BlockGutter` with `onZoomIn = null` does not make bullet clickable
- Unit test: `BlockGutter` with `onZoomIn` set calls it on bullet tap
- Compile check: existing call sites that don't pass `onZoomIn` still compile (default = null)

---

### Story 4 — Polish & Edge Cases [1–2 days]

> As a user, the block-focus experience handles edge cases gracefully and is visually polished.

**Acceptance Criteria**:
- Block with no children shows "No child blocks" empty state
- Root-level block (no parent) has breadcrumb of just `[Page Name > Current Block]`
- `navigateToBlockFocus` for an unloaded block triggers content load before navigation
- Back navigation from block-focus updates `TopBar` title correctly
- Keyboard shortcut (optional): `Cmd+.` or `Alt+Z` to zoom into currently-focused block

---

#### Task 4.1 — Empty state and content-load guard [Micro: 1h]

**Objective**: Handle the "no children" empty state in `BlockFocusView` and ensure block content is loaded before navigating.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/BlockFocusView.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

**Implementation approach**:
1. In `BlockFocusView`, when filtered children list is empty, render a `Text("No child blocks")` placeholder with a `clickable { viewModel.addBlockToPage(...) }` hint (same pattern as `PageView`).
2. In `navigateToBlockFocus()` in `StelekitViewModel`: if `block.isLoaded == false`, call `blockStateManager?.loadPageContent(block.pageUuid)` before calling `navigateTo(Screen.BlockFocus(...))`. This mirrors how `PageView` handles `isContentLoaded`.

**Validation strategy**:
- Unit test: `navigateToBlockFocus` with an unloaded block triggers `loadPageContent`
- Manual: zoom into a block whose page was never opened; children render after load

---

#### Task 4.2 — TopBar title and back-navigation label [Micro: 1h]

**Objective**: Update `TopBar` to show the focused block's content preview as the title when in `Screen.BlockFocus`.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`

**Implementation approach**:
1. In `TopBar.kt`, locate the `when (currentScreen)` title logic.
2. Add `is Screen.BlockFocus -> currentScreen.block.content.take(40).let { if (it.length == 40) "$it…" else it }` as the title string.
3. Ensure the back button is visible when `canGoBack == true` (already handled by `AppState.canGoBack`).

**Validation strategy**:
- Manual: navigate to block-focus; TopBar title shows truncated block content
- Manual: use back button; title reverts to previous screen name

---

## Dependency Visualization

```
Story 1 (Navigation Model)
  └─ Task 1.1  ──┬──► Task 1.2
                 │
Story 2 (Breadcrumb)
  ├─ Task 2.1 (needs 1.1) ──► Task 2.2 (needs 2.1)
  │
Story 3 (BlockFocusView)
  ├─ Task 3.1 (needs 1.1, 1.2, 2.1, 2.2) ──► [integration complete]
  └─ Task 3.2 (needs 1.1) ──────────────────► wired in 3.1
  │
Story 4 (Polish)
  ├─ Task 4.1 (needs 3.1) 
  └─ Task 4.2 (needs 1.2)

Parallel tracks possible:
  [1.1] → [2.1, 3.2] in parallel → [2.2, 3.1] → [4.1, 4.2]
```

---

## Integration Checkpoints

**After Story 1**: `navigateToBlockFocus("some-uuid")` can be called from the debug menu/command palette and app routes to a placeholder view without crash. Back navigation works.

**After Story 2**: `BreadcrumbBar` renders correctly in isolation (Compose Preview or screenshot test) and `buildBreadcrumb()` unit tests pass.

**After Story 3**: Full end-to-end flow works: click bullet → zoom in → see children → click breadcrumb → return to page. All existing `PageView` editing tests still pass.

**Final (after Story 4)**: Edge cases handled, TopBar title correct, empty-state renders. Feature is launch-ready.

---

## Known Issues (Potential Bugs)

### Bug 001: Stale block reference after concurrent edit [SEVERITY: Medium]

**Description**: `navigateToBlockFocus(blockUuid)` resolves the block from `cachedAllPages` / `blockRepository` at navigation time. If the block content is edited between the click and the navigation completing (race condition), `Screen.BlockFocus.block` holds stale content.

**Mitigation**:
- `BlockFocusView` does NOT use `Screen.BlockFocus.block.content` for rendering children — it re-fetches from `blockStateManager.blocks` (live flow). The `block` field in `Screen` is used only for breadcrumb label and TopBar title.
- Stale breadcrumb label is cosmetic-only; no data loss.

**Prevention**: When building the breadcrumb label in `BreadcrumbBar`, prefer `blockStateManager.blocks.value[page.uuid]?.find { it.uuid == block.uuid }?.content` over `block.content` directly.

**Affected files**: `BlockFocusView.kt`, `StelekitViewModel.kt`

---

### Bug 002: Collapsed blocks in block-focus view [SEVERITY: Low]

**Description**: `BlockStateManager.collapsedBlockUuids` persists across navigation. A block collapsed in `PageView` may appear collapsed in `BlockFocusView`, hiding children without the user expecting it.

**Mitigation**: On entry into `BlockFocusView`, optionally call `blockStateManager.expandBlock(block.uuid)` to ensure the root block is never collapsed. Document this as intended behavior.

**Affected files**: `BlockFocusView.kt`, `BlockStateManager.kt`

---

### Bug 003: getBlockAncestors not implemented in concrete repository [SEVERITY: High — verify before starting]

**Description**: `IBlockRepository.getBlockAncestors()` is declared in the interface but may not be fully implemented in the SQLDelight-backed `BlockRepository`.

**Mitigation**:
1. Before Task 2.1, verify `BlockRepository.getBlockAncestors()` implementation.
2. If missing: implement it as a recursive CTE or iterative parent-walk using `getBlockParent()` in a loop (acceptable for shallow hierarchies ≤ 10 levels).

**Affected files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/BlockRepository.kt`

**Related task**: Must be verified/fixed before Task 2.1 can be completed.

---

### Bug 004: Navigation history pollution with rapid zoom [SEVERITY: Low]

**Description**: Rapidly clicking zoom-in on child blocks builds up a long navigation history. `goBack()` requires many presses to return to the original page.

**Mitigation**: This is acceptable and expected behavior (matches Logseq). Document for users. Optionally add a "Back to page" shortcut in the breadcrumb's first item.

---

## Context Preparation Guide

### Task 1.1
- Load: `AppState.kt` (sealed Screen class, ~30 lines), `StelekitViewModel.kt` (lines 612–757: navigateTo, navigateToBlock)
- Understand: How `navigateTo(screen: Screen)` updates `navigationHistory`; how `Block.pageUuid` links to `Page.uuid`

### Task 1.2
- Load: `App.kt` or `MainLayout.kt` — find the `when (currentScreen)` routing block
- Understand: Composable wiring pattern for existing `Screen.PageView`

### Task 2.1
- Load: `IBlockRepository.kt` lines 76–82 (`getBlockAncestors`), `StelekitViewModel.kt` `navigateToPageByUuid` as reference pattern
- Verify: `BlockRepository.kt` (concrete impl) has `getBlockAncestors` — **critical prerequisite**

### Task 2.2
- Load: `BreadcrumbBar.kt` (new file — no prior reading needed), Material3 `TextButton` docs
- Reference: `TopBar.kt` for consistent styling

### Task 3.1
- Load: `PageView.kt` in full (315 lines) — the entire pattern is duplicated/adapted
- Load: `BlockList.kt` signature (lines 45–88)
- Understand: `DisposableEffect` pattern for `observePage` / `unobservePage`

### Task 3.2
- Load: `BlockGutter.kt` in full (127 lines), `BlockItem.kt` parameter list (lines 44–88), `BlockList.kt` parameter list (lines 45–88)
- Understand: Callback propagation pattern from `BlockList` → `BlockItem` → `BlockGutter`

### Task 4.1
- Load: `BlockFocusView.kt` (just written in 3.1), `StelekitViewModel.navigateToBlockFocus()`

### Task 4.2
- Load: `TopBar.kt` — find title `when` block

---

## Success Criteria

- [ ] All 7 atomic tasks completed and validated
- [ ] `Screen.BlockFocus` added; no exhaustiveness warnings in Kotlin `when` blocks
- [ ] `BreadcrumbBar` renders correct ancestry chain in screenshot test
- [ ] `navigateToBlockFocus` unit tests pass (found, not found, unloaded block)
- [ ] `BlockGutter.onZoomIn` callback does not regress existing collapse/drag behavior
- [ ] Back navigation from `Screen.BlockFocus` returns to correct prior screen
- [ ] Bug 003 (getBlockAncestors implementation) verified/fixed before Story 2
- [ ] No existing `PageView` or navigation tests broken
- [ ] Feature works on Desktop (JVM) — manual smoke test
- [ ] Feature works on Android — manual smoke test

---

## Next Steps

Use `/plan:next-step block-focus-mode` to get the recommended first task.

Before starting, verify Bug 003: check `BlockRepository.kt` for `getBlockAncestors` implementation completeness.
