package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.SortKey
import dev.stapler.stelekit.table.SortKeyOf
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEditor

/**
 * Composition point for the production engine: `table` cannot import `table.formula`, so the editor
 * wired with reference rewriting and computed sort keys is built here.
 */
object TableEngine {
    fun editor(): TableEditor = TableEditor(RefRewriter.asTransform(), computedSortKeys())

    /** Sorts by computed value; above the formula cell cap it falls back to literal text. */
    fun computedSortKeys(): SortKeyOf {
        // One calc per doc instance: a sort asks for every key of the same doc. A racing caller only recomputes.
        var cached: Pair<TableDoc, TableCalc?>? = null
        return SortKeyOf { doc, address ->
            val hit = cached
            val calc = if (hit != null && hit.first === doc) {
                hit.second
            } else {
                TableCalc.orNull(doc).also { cached = doc to it }
            }
            if (calc == null) SortKeyOf.Literal.keyOf(doc, address) else keyOf(calc, address)
        }
    }

    private fun keyOf(calc: TableCalc, address: CellAddress): SortKey = when (val v = calc.valueAt(address)) {
        is CellValue.Num -> if (v.value.isFinite()) SortKey.Num(v.value) else SortKey.Error
        is CellValue.Text -> SortKey.Text(v.value)
        CellValue.Blank -> SortKey.Blank
        is CellValue.Err -> SortKey.Error
    }
}
