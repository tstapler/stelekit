package dev.stapler.stelekit.ui.components

/**
 * Column widths (px) shared by every row of a table.
 *
 * [minWidths] is each column's narrowest legible width (longest word), [maxWidths] its
 * unwrapped width. With [TableOverflow.WRAP] the columns shrink toward their minimum to fit [available]; with
 * no wrap they keep their natural width (capped at [maxColumnWidth] so one long cell cannot
 * dominate) and the caller scrolls horizontally. When even the minimums exceed [available],
 * the minimums are returned and the caller scrolls.
 */
fun computeColumnWidths(
    minWidths: IntArray,
    maxWidths: IntArray,
    available: Int,
    overflow: TableOverflow,
    maxColumnWidth: Int = Int.MAX_VALUE,
): IntArray {
    require(minWidths.size == maxWidths.size) { "min/max column counts differ" }
    val mins = IntArray(minWidths.size) { minWidths[it].coerceAtLeast(0) }
    val maxs = IntArray(maxWidths.size) { maxWidths[it].coerceAtLeast(mins[it]) }

    if (overflow == TableOverflow.SCROLL) return IntArray(maxs.size) { maxs[it].coerceAtMost(maxColumnWidth).coerceAtLeast(mins[it]) }

    val sumMax = maxs.sumOf { it.toLong() }
    val sumMin = mins.sumOf { it.toLong() }
    if (sumMax <= available) return maxs
    if (sumMin >= available) return mins

    val spare = available - sumMin
    val extra = LongArray(maxs.size) { (maxs[it] - mins[it]).toLong() }
    val extraTotal = extra.sum()
    val widths = IntArray(maxs.size) { mins[it] + (spare * extra[it] / extraTotal).toInt() }
    var leftover = (available - widths.sumOf { it.toLong() }).toInt()
    while (leftover > 0) {
        var gave = false
        for (i in widths.indices) {
            if (leftover > 0 && widths[i] < maxs[i]) {
                widths[i]++
                leftover--
                gave = true
            }
        }
        if (!gave) break
    }
    return widths
}
