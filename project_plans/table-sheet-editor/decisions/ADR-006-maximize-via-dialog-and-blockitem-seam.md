# ADR-006: Maximize through one commonMain Dialog; isolate `BlockItem` behind a seam

**Status**: Proposed (accepted when Task 5.1a passes on desktop and Task 8.2a passes on `Pixel_Fold`)
**Date**: 2026-11-24

## Context
`BlockItem.kt` is a 693-line dispatcher with 23 commits in six months; its `is BlockType.Table` branch (`:498`) is the only coupling the table needs (research/architecture.md sections 5 and 7). Precedents: `LocalOpenSketchAction` (`ui/components/AnnotateImageAction.kt:11`, provided at `GraphContentActiveShell.kt:350`), `CapturePreviewDialog`, `SearchDialog`.

## Decision
- **Tech debt disposition: Isolate via seam.** `BlockItem.kt` changes in exactly one branch: the `Table` call passes `blockUuid`, `onContentChange` and an `onOpenSheet` obtained from `LocalOpenTableSheetAction`. No cell logic enters `BlockItem`. `onStartEditing` stays as the raw-markdown escape hatch.
- `LocalOpenTableSheetAction: ((BlockUuid) -> Unit)?` (default null) mirrors `LocalOpenSketchAction`, provided once by `GraphContentActiveShell`, together with `LocalTableCommitAction: ((BlockUuid, String, Long) -> Unit)?` (routes inline commits through `updateBlockContent(..., restoreEditFocus = false)`; the `Long` is a lower bound and the manager mints the stored UI-space version, ADR-004) and `LocalTableSheetOwner: BlockUuid?` (the block the sheet currently owns; the inline table for it is read-only). The shell owns `TableSheetHost`: it renders a `Dialog(DialogProperties(usePlatformDefaultWidth = false))` for the open block, observes that single block (`getBlockByUuid`, never an unbounded query), and commits through `BlockStateManager.updateBlockContent`.
- Presentation: full-screen below 600dp, about 90% of the window at 840dp and above, on all four targets, one code path (a route would add navigation state; in-place expansion fights `LazyColumn` item height). Android Back dismisses; IME `adjustResize` plus `imePadding` and bring-into-view are verified on `Pixel_Fold`.
- Feature flags (Settings, default off): `table_inline_edit_enabled`, `table_sheet_enabled`, carried by `TableDisplayPrefs` and exposed in `EditorSettings`' Tables section. With both off, `TableBlock` takes today's code path and `TableBlockLayoutTest` is unchanged.
- "Edit as markdown" is reachable from the inline strip, the sheet top bar and the overflow menu, in every state, including flag flips mid-edit (the draft is committed first, never lost).
- Read-only: `TableSheetHost` takes `readOnly: Boolean = false`; when true it is browse mode (select, copy, view formulas) with edit, paste, sort and structure ops shown disabled with the reason "This graph is read-only". VERIFIED by grep: no active-graph read-only flag exists (`TargetWriterCapabilities` is the cross-graph write convention for off-graph merge targets, not a per-block flag), so nothing sets the parameter today; it is unit-tested and wired when a real flag exists.

## Consequences
One test surface for four platforms; `BlockItem` grows by a few lines. Desktop may feel cramped in a dialog; revisit in-place expansion only after dogfooding.

## Alternatives rejected
Refactor-first on `BlockItem` (risk and merge conflicts, no table benefit); a `Screen` route; per-platform expect/actual windows.
