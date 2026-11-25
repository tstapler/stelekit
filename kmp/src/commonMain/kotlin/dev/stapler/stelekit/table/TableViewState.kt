package dev.stapler.stelekit.table

data class SortIndicator(val col: Int, val ascending: Boolean)

/**
 * Session-only presentation state (ADR-007): never serialized, never a document edit. Widths are dp
 * values as plain floats so `table/` carries no Compose types.
 */
data class TableViewState(
    val columnWidthsDp: Map<Int, Float> = emptyMap(),
    val freezeHeaderRow: Boolean = true,
    val freezeFirstColumn: Boolean = false,
    val sort: SortIndicator? = null,
) {
    /** Non-finite widths are ignored; others clamp to [MIN_WIDTH_DP]..[MAX_WIDTH_DP]. */
    fun setWidth(col: Int, widthDp: Float): TableViewState {
        if (col < 1 || !widthDp.isFinite()) return this
        return copy(columnWidthsDp = columnWidthsDp + (col to widthDp.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)))
    }

    fun widthOf(col: Int): Float = columnWidthsDp[col] ?: DEFAULT_WIDTH_DP

    fun withFreezeHeaderRow(on: Boolean) = copy(freezeHeaderRow = on)
    fun withFreezeFirstColumn(on: Boolean) = copy(freezeFirstColumn = on)
    fun withSort(col: Int, ascending: Boolean) = copy(sort = SortIndicator(col, ascending))

    /** Any edit that changes row order (manual move, insert, delete, paste) invalidates the indicator. */
    fun onRowOrderChanged() = if (sort == null) this else copy(sort = null)

    companion object {
        const val MIN_WIDTH_DP = 48f
        const val MAX_WIDTH_DP = 640f
        const val DEFAULT_WIDTH_DP = 120f
    }
}
