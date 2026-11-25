package dev.stapler.stelekit.table

/** What a cell sorts as: numbers first, then text, then [Blank] and [Error] last in both directions. */
sealed interface SortKey {
    data class Num(val value: Double) : SortKey
    data class Text(val value: String) : SortKey
    data object Blank : SortKey
    data object Error : SortKey
}

/** Supplies the computed sort key of a cell; `TableCalc` injects one so the engine stays a separate layer. */
fun interface SortKeyOf {
    fun keyOf(doc: TableDoc, address: CellAddress): SortKey

    companion object {
        /** Literal cell text: no formula evaluation, `=...` sorts as text. */
        val Literal = SortKeyOf { doc, address ->
            val raw = doc.cell(address)?.value?.trim().orEmpty()
            // One number grammar with the formula engine, so `1,234` sorts as the 1234 `=A2+1` reads.
            val num = NumberGrammar.parse(raw)
            when {
                raw.isEmpty() -> SortKey.Blank
                num != null -> SortKey.Num(num)
                else -> SortKey.Text(raw)
            }
        }
    }
}

/** A cell or range reference found in formula text, 1-based, normalized (r1 <= r2, c1 <= c2). */
internal data class FormulaRef(val r1: Int, val c1: Int, val r2: Int, val c2: Int, val isRange: Boolean)

/**
 * Read-only reference scan for sort decisions (totals detection, refusals). `table` cannot import
 * `table.formula`; rewriting stays with the injected transform, this only finds spans.
 */
internal object FormulaRefScan {
    private val CELL = Regex("^\\$?([A-Za-z]{1,3})\\$?([0-9]+)$")

    private fun isChunk(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '.'

    private fun parseCell(chunk: String): Pair<Int, Int>? {
        val m = CELL.matchEntire(chunk) ?: return null
        val row = m.groupValues[2].toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val col = m.groupValues[1].uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) }
        return row to col
    }

    fun scan(formula: String): List<FormulaRef> {
        val out = ArrayList<FormulaRef>()
        var i = 0
        val n = formula.length
        while (i < n) {
            val c = formula[i]
            if (c == '"') {
                i++
                while (i < n) {
                    if (formula[i] == '"') {
                        if (i + 1 < n && formula[i + 1] == '"') i++ else break
                    }
                    i++
                }
                i++
                continue
            }
            if (!isChunk(c)) { i++; continue }
            val start = i
            while (i < n && isChunk(formula[i])) i++
            val a = parseCell(formula.substring(start, i))
            if (a == null || (i < n && formula[i] == '(')) continue
            if (i < n && formula[i] == ':') {
                var j = i + 1
                while (j < n && isChunk(formula[j])) j++
                val b = parseCell(formula.substring(i + 1, j))
                if (b != null) {
                    i = j
                    out.add(FormulaRef(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second), true))
                    continue
                }
            }
            out.add(FormulaRef(a.first, a.second, a.first, a.second, false))
        }
        return out
    }
}

/** The totals rule the sort uses, public so a menu can say "Totals row stays at the bottom" before sorting. */
object SortTotals {
    /** True when the last body row is a totals row that a whole-body sort leaves in place. */
    fun hasTrailingTotals(doc: TableDoc): Boolean = SortOp.totalsStart(doc) <= doc.rowCount
}

internal class SortOp(
    private val reference: FormulaReferenceTransform,
    private val keyOf: SortKeyOf,
) {
    fun apply(doc: TableDoc, edit: TableEdit.SortRows): TableEditOutcome {
        if (edit.col < 1 || edit.col > doc.columnCount) return TableEditOutcome.Refused(RefusalReason.OutOfRange)
        val first: Int
        val last: Int
        if (edit.rows != null) {
            if (edit.rows.first < 2) return TableEditOutcome.Refused(RefusalReason.HeaderRow)
            if (edit.rows.last > doc.rowCount) return TableEditOutcome.Refused(RefusalReason.OutOfRange)
            first = edit.rows.first
            last = edit.rows.last
        } else {
            first = 2
            last = totalsStart(doc) - 1
        }
        if (last < first) return TableEditOutcome.Applied(doc, EditSummary.NoChange)

        val keys = (first..last).associateWith { keyOf.keyOf(doc, CellAddress(it, edit.col)) }
        val ordered = orderRows(keys, edit.ascending)
        if (ordered == (first..last).toList()) return TableEditOutcome.Applied(doc, EditSummary.NoChange)

        // newRow[oldRow] after the permutation; ordered[k] is the old row that lands on row first + k.
        val newRowOf = HashMap<Int, Int>()
        ordered.forEachIndexed { k, old -> newRowOf[old] = first + k }
        val moved = ordered.filter { newRowOf.getValue(it) != it }

        var selfRef = 0
        var valueChange = 0
        for (old in moved) {
            val newRow = newRowOf.getValue(old)
            val cells = doc.row(old).cells
            var selfHere = false
            var changeHere = false
            cells.forEachIndexed { i, cell ->
                if (!cell.value.startsWith("=")) return@forEachIndexed
                for (ref in FormulaRefScan.scan(cell.value)) {
                    if (ref.isRange) {
                        if (newRow in ref.r1..ref.r2 && (i + 1) in ref.c1..ref.c2) selfHere = true
                    } else if (ref.r1 != old && ref.r1 >= 2) {
                        changeHere = true
                    }
                }
            }
            if (selfHere) selfRef++
            if (changeHere) valueChange++
        }
        if (selfRef > 0) return TableEditOutcome.Refused(RefusalReason.SortWouldSelfReference, selfRef)
        if (valueChange > 0) return TableEditOutcome.Refused(RefusalReason.SortWouldChangeValues, valueChange)

        var rewritten = 0
        val body = doc.body.toMutableList()
        for ((old, new) in newRowOf) {
            val r = doc.row(old).rewriteFormulas(reference, TableEdit.SortMove(old, new))
            rewritten += r.rewritten
            body[new - 2] = r.line
        }
        val blanks = keys.values.count { it == SortKey.Blank }
        val crossRow = (2..doc.rowCount).sumOf { row ->
            doc.row(row).cells.count { c ->
                c.value.startsWith("=") && FormulaRefScan.scan(c.value).any { !it.isRange && it.r1 != row && it.r1 >= 2 }
            }
        }
        return TableEditOutcome.Applied(doc.copy(body = body), EditSummary.Sorted(edit.col, blanks, rewritten, crossRow))
    }

    /** Stable; ascending or descending reverses only the non-last bucket, ties keep prior order either way. */
    private fun orderRows(keys: Map<Int, SortKey>, ascending: Boolean): List<Int> {
        val rows = keys.keys.sorted()
        val (sortable, last) = rows.partition { keys.getValue(it) is SortKey.Num || keys.getValue(it) is SortKey.Text }
        val cmp = Comparator<Int> { a, b ->
            val c = compareKeys(keys.getValue(a), keys.getValue(b))
            if (ascending) c else -c
        }
        return sortable.sortedWith(cmp) + last
    }

    private fun compareKeys(a: SortKey, b: SortKey): Int = when {
        a is SortKey.Num && b is SortKey.Num -> a.value.compareTo(b.value)
        a is SortKey.Num -> -1
        b is SortKey.Num -> 1
        a is SortKey.Text && b is SortKey.Text -> a.value.lowercase().compareTo(b.value.lowercase())
        else -> 0
    }

    companion object {
        /** First body row of the trailing totals run (rowCount + 1 when none), detected by dependency (ADR-003). */
        fun totalsStart(doc: TableDoc): Int {
            var start = doc.rowCount + 1
            for (row in doc.rowCount downTo 2) {
                if (!isTotalsRow(doc, row)) break
                start = row
            }
            return start
        }

        private fun isTotalsRow(doc: TableDoc, row: Int): Boolean = doc.row(row).cells.any { c ->
            if (!c.value.startsWith("=")) return@any false
            val others = FormulaRefScan.scan(c.value).flatMap { (it.r1..it.r2).toList() }.filter { it != row && it >= 2 }
            others.isNotEmpty() && others.all { it < row }
        }
    }
}
