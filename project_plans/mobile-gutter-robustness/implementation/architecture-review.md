# Architecture Review: mobile-gutter-robustness
**Date**: 2026-11-23
**Verdict**: CONCERNS
**Companion**: `adversarial-review.md` documents runtime/correctness blockers (2 blockers, 5 concerns); this review applies structural design lenses to the same plan. Cross-references below note where both reviews flag the same issue.

---

## Blockers

None from the architecture lenses. The two compilation/runtime blockers from `adversarial-review.md` (`isDragging` scope gap in Story 4.2.2; bullet secondary tap with no AC or gesture-conflict test) are confirmed from a design perspective but are not re-documented here.

---

## Concerns

- [ ] **Story 3.1.2 — ISP violation: `canX` params added to `BlockGutter` are dead**  
  Story 3.1.2 adds `canMoveUp/Down/Indent/Outdent` to `BlockGutter` with the stated purpose: "BlockGutter will use them in Phase 4 to disable quick-action sheet menu items." But Phase 4 (Story 4.1.1 and 4.2.1) renders `BlockGutterActionSheet` inside `BlockItem`, not inside `BlockGutter`. The `if (showQuickActionSheet) { BlockGutterActionSheet(canMoveUp = canMoveUp, ...) }` block in Story 4.2.1 is authored from `BlockItem`'s scope; `BlockGutter` never references these params. Result: `BlockGutter`'s signature grows by 4 params that it accepts but never uses — a textbook Interface Segregation violation. The `canX` values should flow `BlockList → BlockItem → BlockGutterActionSheet` directly; they have no reason to pass through `BlockGutter`. Drop them from `BlockGutter`'s signature entirely.

- [ ] **Story 4.2.2 — Missing `LaunchedEffect(isEditing)` dismiss guard**  
  The plan adds `LaunchedEffect(isDragging)` and `LaunchedEffect(isInSelectionMode)` guards in `BlockItem` but omits a guard for `isEditing → true`. On mobile, `ModalBottomSheet` captures pointer events over its surface, so touch-triggered edit entry is blocked while the sheet is visible. However, keyboard navigation on a narrow JVM window (`isMobile = true` on COMPACT layouts) or programmatic `onStartEditing()` from a sibling (focus-move via arrow key) can enter edit mode without a pointer event. When that happens, the sheet remains open floating over the editor. Pitfalls research (§3) explicitly notes the narrow-JVM mixed mode. Add `LaunchedEffect(isEditing) { if (isEditing) showQuickActionSheet = false }` alongside the other two guards. — Note: this concern is independent of the `isDragging`-not-in-scope blocker in `adversarial-review.md`.

- [ ] **Story 1.1.2 — `copyBlockRef` omits the `id::` property check required by features.md**  
  `features.md §2 "Copy Block Ref"` states: "If `block.properties["id"]` is absent (older block), the copy should write the uuid to `id::` before returning the ref string — or display a 'Block needs an ID property first' toast and trigger the save." Story 1.1.2's implementation writes `"(($blockUuid))"` to the clipboard with no mention of this check. Logseq only assigns `id::` on explicit cross-reference; most blocks in an imported graph have none. Without the property, the `((uuid))` ref is non-functional on file round-trip (the identifier has no `id::` anchor to resolve against). The story must either add the write (calling `blockRepository.saveBlock` to inject `properties["id"] = blockUuid` before copying) or document the conscious deferral. This is a functional correctness gap, not a cosmetic one.

- [ ] **Phase 6 — `GutterMenuHintStore` injection path is unresolved**  
  Story 6.1.1 specifies `GutterMenuHintStore` is "Injected into `StelekitViewModel` (or accessed via a `CompositionLocal`)." These two paths have different testability and lifetime implications:
  - **ViewModel path**: the store becomes a constructor dependency; `WiringCompletenessTest` requires it listed in a composition root; the `StateFlow<String?> gutterMenuHintBlockUuid` must be tested against a fake store; existing screenshot tests that construct `StelekitViewModel` directly may need updating.
  - **CompositionLocal path**: avoids ViewModel modification but makes the state untestable at the ViewModel unit-test level, and requires a new `CompositionLocalProvider` in every test that exercises `BlockGutter` in view mode.

  Neither is wrong in principle, but the unresolved "or" leaves the Phase 6 implementer making an architecture decision mid-sprint with no ADR backing. Resolve before Phase 6 begins — a one-line design decision note is sufficient.

---

## Nitpicks

- **4 `canX` booleans tunneled 3 layers** (`BlockList → BlockItem → BlockGutterActionSheet`) could be grouped into a `BlockActionCapabilities(canMoveUp, canMoveDown, canIndent, canOutdent)` data class. No semantic change; reduces param-list growth by 3 items at each of the two boundaries and makes the grouping intention explicit. Worth doing if the ISP concern above is addressed (once `canX` is dropped from `BlockGutter`, the total param count drops from 4 additions to 1 at each boundary).

- **`BlockItem` reaches ~60 params after this plan** (current: 52; plan adds 8: `canMoveUp`, `canMoveDown`, `canIndent`, `canOutdent`, `onCopyBlockRef`, `onDeleteBlock`, `showGutterMenuHint`, `onGutterMenuHintShown`). The extend-as-is disposition is explicitly justified in the plan — Roborazzi goldens + 7-`LaunchedEffect` chain make a concurrent refactor high-risk. The justification holds. Flag: each "extend-as-is" pass widens the gap between the current state and what a future decomposition needs to unwind. The post-merge refactor task should be promoted from "tracked in backlog" to a named milestone so it doesn't slip indefinitely.

- **`onTapDragHandle: (() -> Unit)?` nullable callback vs. `BlockGutterMode` sealed class**: The nullable callback cleanly expresses optionality and is consistent with the existing nullable pattern in this codebase (e.g., `onNavigateAllSuggestions`, `onArchiveUrl`). A sealed `BlockGutterMode` would be more explicit about the view/edit/selection state machine but would require migrating the existing `isInSelectionMode: Boolean` param — more churn for no concrete benefit here. The nullable callback is acceptable as-is.

- **SRP on `BlockItem`**: The 7 → 9 `LaunchedEffect` count is flagged for awareness rather than action. The pattern (each effect handles one reactive concern) is structurally sound; the volume is what signals God Object territory, not the pattern itself. The planned tech-debt PR is the right venue.

---

## ADR-000 Constitution

`docs/adr/ADR-000-architecture-constitution.md` does not exist. The findings above are grounded in the project's existing CLAUDE.md architecture rules (Arrow `Either` boundaries, `BlockActionCapabilities`-style data grouping, `WiringCompletenessTest`, `PlatformDispatcher.DB` conventions) and standard SOLID analysis.
