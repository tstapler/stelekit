package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.FormulaReferenceTransform
import dev.stapler.stelekit.table.TableEdit

/**
 * Rewrites the references of one formula for a structural edit (ADR-003). Only reference spans whose
 * address changes are replaced; everything else stays byte-verbatim, so whitespace, function-name case
 * and number spelling survive. Returns null when no reference changes. Pure and iterative.
 */
object RefRewriter {
    private const val REF_ERROR = "#REF!"

    fun asTransform(): FormulaReferenceTransform = FormulaReferenceTransform { cell, edit ->
        rewrite(cell.value, edit)?.let(::CellText)
    }

    fun rewrite(text: String, edit: TableEdit): String? {
        val mapper = mapperFor(edit) ?: return null
        val expr = (FormulaParser.parse(text) as? FormulaParse.Ok)?.expr ?: return null
        val splices = ArrayList<Triple<Int, Int, String>>()
        val stack = ArrayList<Expr>()
        stack.add(expr)
        while (stack.isNotEmpty()) {
            when (val e = stack.removeAt(stack.size - 1)) {
                is Expr.Ref -> mapper.ref(e)?.let { splices.add(Triple(e.span.start, e.span.end, endpointText(text, e, it))) }
                is Expr.Range -> rangeSplices(text, e, mapper, splices)
                is Expr.Bin -> { stack.add(e.right); stack.add(e.left) }
                is Expr.Neg -> stack.add(e.operand)
                is Expr.Pct -> stack.add(e.operand)
                is Expr.Call -> e.args.forEach { stack.add(it) }
                is Expr.Num, is Expr.Str, is Expr.RefError -> Unit
            }
        }
        if (splices.isEmpty()) return null
        val sb = StringBuilder(text)
        for ((start, end, repl) in splices.sortedByDescending { it.first }) sb.replace(start, end, repl)
        return sb.toString()
    }

    private fun rangeSplices(text: String, r: Expr.Range, m: Mapper, out: MutableList<Triple<Int, Int, String>>) {
        val from = m.rangeEnd(r.from, r.to) ?: return
        if (from === POISON) {
            out.add(Triple(r.span.start, r.span.end, REF_ERROR))
            return
        }
        val (a, b) = from
        if (a != AddressOrNull(r.from.row, r.from.col)) out.add(Triple(r.from.span.start, r.from.span.end, endpointText(text, r.from, a.toPair())))
        if (b != AddressOrNull(r.to.row, r.to.col)) out.add(Triple(r.to.span.start, r.to.span.end, endpointText(text, r.to, b.toPair())))
    }

    /** New (row, col) of a reference, null when unchanged, or marks deletion. */
    private data class AddressOrNull(val row: Int, val col: Int) {
        fun toPair() = row to col
    }

    private val POISON: Pair<AddressOrNull, AddressOrNull> = AddressOrNull(-1, -1) to AddressOrNull(-1, -1)

    private interface Mapper {
        /** New (row, col) for a single-cell reference; null when unchanged; (0, 0) when deleted. */
        fun ref(r: Expr.Ref): Pair<Int, Int>?

        /** Both new range endpoints in original order, [POISON] when the whole range is deleted, null when untouched. */
        fun rangeEnd(from: Expr.Ref, to: Expr.Ref): Pair<AddressOrNull, AddressOrNull>?
    }

    private class AxisMapper(val onRows: Boolean, val line: Axis) : Mapper {
        override fun ref(r: Expr.Ref): Pair<Int, Int>? {
            val v = if (onRows) r.row else r.col
            val n = line.point(v) ?: return DELETED
            if (n == v) return null
            return if (onRows) n to r.col else r.row to n
        }

        override fun rangeEnd(from: Expr.Ref, to: Expr.Ref): Pair<AddressOrNull, AddressOrNull>? {
            val a = if (onRows) from.row else from.col
            val b = if (onRows) to.row else to.col
            val (na, nb) = line.range(a, b) ?: return POISON
            if (na == a && nb == b) return null
            return if (onRows) {
                AddressOrNull(na, from.col) to AddressOrNull(nb, to.col)
            } else {
                AddressOrNull(from.row, na) to AddressOrNull(to.row, nb)
            }
        }
    }

    /** One axis of a structural edit: maps a coordinate, and a range's two coordinates. */
    private interface Axis {
        /** New coordinate, or null when the coordinate was deleted. */
        fun point(v: Int): Int?

        /** New endpoints in the original order, or null when the whole range is deleted. */
        fun range(a: Int, b: Int): Pair<Int, Int>? {
            val na = point(a)
            val nb = point(b)
            return if (na != null && nb != null) na to nb else null
        }
    }

    private class InsertAxis(val before: Int, val count: Int) : Axis {
        override fun point(v: Int) = if (v >= before) v + count else v
    }

    private class DeleteAxis(val at: Int, val count: Int) : Axis {
        private val last = at + count - 1
        override fun point(v: Int): Int? = when {
            v < at -> v
            v > last -> v - count
            else -> null
        }

        override fun range(a: Int, b: Int): Pair<Int, Int>? {
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            if (lo >= at && hi <= last) return null
            val nlo = if (lo in at..last) at else if (lo > last) lo - count else lo
            val nhi = if (hi in at..last) at - 1 else if (hi > last) hi - count else hi
            return if (a <= b) nlo to nhi else nhi to nlo
        }
    }

    private class MoveAxis(val from: Int, val count: Int, val before: Int) : Axis {
        override fun point(v: Int): Int {
            val end = from + count
            return when {
                before in from..end -> v
                v in from until end -> if (before > end) before - count + (v - from) else before + (v - from)
                before > end && v in end until before -> v - count
                before < from && v in before until from -> v + count
                else -> v
            }
        }

        // Endpoints follow the data and a swapped pair is normalized to min:max (ADR-003).
        override fun range(a: Int, b: Int): Pair<Int, Int> {
            val na = point(a)
            val nb = point(b)
            return minOf(na, nb) to maxOf(na, nb)
        }
    }

    private class SortMapper(val fromRow: Int, val toRow: Int) : Mapper {
        override fun ref(r: Expr.Ref): Pair<Int, Int>? = if (r.row == fromRow && fromRow != toRow) toRow to r.col else null
        override fun rangeEnd(from: Expr.Ref, to: Expr.Ref): Pair<AddressOrNull, AddressOrNull>? = null
    }

    private val DELETED = 0 to 0

    private fun mapperFor(edit: TableEdit): Mapper? = when (edit) {
        is TableEdit.InsertRows -> AxisMapper(true, InsertAxis(edit.before, edit.count))
        is TableEdit.InsertCols -> AxisMapper(false, InsertAxis(edit.before, edit.count))
        is TableEdit.DeleteRows -> AxisMapper(true, DeleteAxis(edit.at, edit.count))
        is TableEdit.DeleteCols -> AxisMapper(false, DeleteAxis(edit.at, edit.count))
        is TableEdit.MoveRows -> AxisMapper(true, MoveAxis(edit.from, edit.count, edit.before))
        is TableEdit.MoveCols -> AxisMapper(false, MoveAxis(edit.from, edit.count, edit.before))
        is TableEdit.SortMove -> SortMapper(edit.fromRow, edit.toRow)
        else -> null
    }

    /** `#REF!` for a deleted single reference, else the original token with only the changed parts re-spelled. */
    private fun endpointText(text: String, ref: Expr.Ref, to: Pair<Int, Int>): String {
        if (to == DELETED) return REF_ERROR
        val (row, col) = to
        val original = text.substring(ref.span.start, ref.span.end)
        val lower = original.firstOrNull { it.isLetter() }?.isLowerCase() == true
        val letters = CellAddress.columnLabel(col).let { if (lower) it.lowercase() else it }
        return (if (ref.absCol) "$" else "") + letters + (if (ref.absRow) "$" else "") + row
    }
}
