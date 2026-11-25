package dev.stapler.stelekit.table

enum class TableSurface { Inline, Sheet }

enum class LadderKey { Back, Esc }

/** First matching rung of the single Back and Esc ladder (ux.md 3.3, UX-71). */
enum class LadderAction {
    DismissBanner, CloseMenu, HideIme, Done, CancelEdit, CollapseRange, CloseStrip, CloseSheet, NotHandled,
}

/** [mode] is null when no cell is active. */
data class LadderState(
    val conflictBannerOpen: Boolean = false,
    val menuOpen: Boolean = false,
    val imeVisible: Boolean = false,
    val mode: GridMode? = null,
    val selectionSize: Int = 1,
    val stripVisible: Boolean = false,
)

object TableBackLadder {
    fun next(surface: TableSurface, state: LadderState, key: LadderKey): LadderAction = when {
        state.conflictBannerOpen -> LadderAction.DismissBanner
        state.menuOpen -> LadderAction.CloseMenu
        state.imeVisible && key == LadderKey.Back -> LadderAction.HideIme
        state.mode == GridMode.Edit ->
            if (key == LadderKey.Back) LadderAction.Done else LadderAction.CancelEdit
        state.mode == GridMode.Navigate && state.selectionSize > 1 -> LadderAction.CollapseRange
        surface == TableSurface.Sheet ->
            if (key == LadderKey.Back) LadderAction.CloseSheet else LadderAction.NotHandled
        state.mode == GridMode.Navigate && state.stripVisible -> LadderAction.CloseStrip
        else -> LadderAction.NotHandled
    }

    /** State after the host performs [action]; lets callers and tests iterate the ladder to its end. */
    fun after(state: LadderState, action: LadderAction): LadderState = when (action) {
        LadderAction.DismissBanner -> state.copy(conflictBannerOpen = false)
        LadderAction.CloseMenu -> state.copy(menuOpen = false)
        LadderAction.HideIme -> state.copy(imeVisible = false)
        LadderAction.Done, LadderAction.CancelEdit -> state.copy(mode = GridMode.Navigate, imeVisible = false)
        LadderAction.CollapseRange -> state.copy(selectionSize = 1)
        LadderAction.CloseStrip -> state.copy(mode = null, stripVisible = false)
        LadderAction.CloseSheet, LadderAction.NotHandled -> state
    }
}
