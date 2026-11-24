package dev.stapler.stelekit.table

import dev.stapler.stelekit.table.TableEdit.ClearRange
import dev.stapler.stelekit.table.TableEdit.DeleteCols
import dev.stapler.stelekit.table.TableEdit.DeleteRows
import dev.stapler.stelekit.table.TableEdit.InsertCols
import dev.stapler.stelekit.table.TableEdit.InsertRows
import dev.stapler.stelekit.table.TableEdit.MoveCols
import dev.stapler.stelekit.table.TableEdit.MoveRows
import dev.stapler.stelekit.table.TableEdit.SetAlignment
import dev.stapler.stelekit.table.TableEdit.SetCell

private const val REF_ERROR = "#REF!"

private fun refused(reason: RefusalReason) = TableEditOutcome.Refused(reason)
private fun applied(doc: TableDoc, summary: EditSummary) = TableEditOutcome.Applied(doc, summary)

internal class RewriteResult(val line: RowLine, val rewritten: Int, val broken: Int)

/** The one reference-transform hook: every formula cell's text goes through [reference]; null leaves it untouched. */
internal fun RowLine.rewriteFormulas(reference: FormulaReferenceTransform, edit: TableEdit): RewriteResult {
    var out: MutableList<CellText>? = null
    var rewritten = 0
    var broken = 0
    for (i in cells.indices) {
        val cell = cells[i]
        if (!cell.value.startsWith("=")) continue
        val next = reference.apply(cell, edit) ?: continue
        if (next == cell) continue
        if (out == null) out = cells.toMutableList()
        out[i] = next
        rewritten++
        if (REF_ERROR in next.value && REF_ERROR !in cell.value) broken++
    }
    return if (out == null) RewriteResult(this, 0, 0) else RewriteResult(withCells(out), rewritten, broken)
}

internal fun TableDoc.rewriteAllFormulas(reference: FormulaReferenceTransform, edit: TableEdit): Pair<TableDoc, RewriteResult> {
    var rewritten = 0
    var broken = 0
    fun go(line: RowLine): RowLine {
        val r = line.rewriteFormulas(reference, edit)
        rewritten += r.rewritten
        broken += r.broken
        return r.line
    }
    val doc = copy(header = go(header), body = body.map { go(it) })
    return doc to RewriteResult(header, rewritten, broken)
}

/** New column list: each entry is the old column index it comes from, or null for a new empty column. */
private fun RowLine.remap(map: List<Int?>): RowLine {
    if (map.size == cells.size && map.withIndex().all { (i, v) -> i == v }) return this
    val keepRaw = rawCells.size == cells.size
    val newCells = ArrayList<CellText>(map.size)
    val newRaw = ArrayList<String>(map.size)
    for (old in map) {
        when {
            old == null -> { newCells.add(CellText("")); newRaw.add("") }
            old < cells.size -> { newCells.add(cells[old]); if (keepRaw) newRaw.add(rawCells[old]) else newRaw.add("") }
        }
    }
    return RowLine(newCells, if (keepRaw) newRaw else emptyList(), null, cr)
}

private fun DelimiterRow.remap(map: List<Int?>, width: Int): DelimiterRow {
    val a = aligns + List((width - aligns.size).coerceAtLeast(0)) { ColumnAlign.NONE }
    val r = rawCells + List((width - rawCells.size).coerceAtLeast(0)) { "" }
    val na = ArrayList<ColumnAlign>()
    val nr = ArrayList<String>()
    for (old in map) {
        if (old == null) { na.add(ColumnAlign.NONE); nr.add("") }
        else if (old < a.size) { na.add(a[old]); nr.add(r[old]) }
    }
    return DelimiterRow(na, nr, null, cr)
}

private fun TableDoc.remapColumns(build: (Int) -> List<Int?>): TableDoc {
    val width = columnCount
    val dMap = build(maxOf(width, delimiter.aligns.size))
    return copy(
        header = header.remap(build(header.cells.size)),
        delimiter = delimiter.remap(dMap, maxOf(width, delimiter.aligns.size)),
        body = body.map { it.remap(build(it.cells.size)) },
    )
}

private fun movePermutation(size: Int, from: Int, count: Int, before: Int): List<Int> {
    val all = (1..size).toList()
    val block = all.subList(from - 1, from - 1 + count)
    val rest = all.filterIndexed { i, _ -> i !in (from - 1) until (from - 1 + count) }
    val insertIdx = if (before > from + count - 1) before - 1 - count else before - 1
    return rest.toMutableList().apply { addAll(insertIdx, block) }
}

internal class StructuralOps(private val reference: FormulaReferenceTransform) {

    fun setCell(doc: TableDoc, edit: SetCell): TableEditOutcome {
        val a = edit.address
        if (a.row > doc.rowCount || a.col > doc.columnCount) return refused(RefusalReason.OutOfRange)
        val text = CellText(edit.text.value.replace("\r\n", "\n").replace('\r', '\n'))
        val next = doc.setCell(a, text)
        return applied(next, if (next === doc) EditSummary.NoChange else EditSummary.CellsChanged(1))
    }

    fun clearRange(doc: TableDoc, edit: ClearRange): TableEditOutcome {
        val r = edit.range
        if (r.topLeft.row > doc.rowCount || r.topLeft.col > doc.columnCount) return refused(RefusalReason.OutOfRange)
        var next = doc
        var changed = 0
        for (row in r.topLeft.row..minOf(r.bottomRight.row, doc.rowCount)) {
            for (col in r.topLeft.col..minOf(r.bottomRight.col, doc.columnCount)) {
                val a = CellAddress(row, col)
                val cur = next.cell(a)
                if (cur != null && cur.value.isNotEmpty()) {
                    next = next.setCell(a, CellText(""))
                    changed++
                }
            }
        }
        return applied(next, if (changed == 0) EditSummary.NoChange else EditSummary.CellsChanged(changed))
    }

    fun insertRows(doc: TableDoc, edit: InsertRows): TableEditOutcome {
        if (edit.before < 2) return refused(RefusalReason.HeaderRow)
        if (edit.before > doc.rowCount + 1 || edit.count < 1) return refused(RefusalReason.OutOfRange)
        val blank = List(doc.columnCount) { CellText("") }
        val fresh = List(edit.count) { RowLine.fresh(blank, doc.header.cr) }
        val body = doc.body.toMutableList().apply { addAll(edit.before - 2, fresh) }
        return applied(rewrite(doc.copy(body = body), edit) { _, _ -> EditSummary.RowsInserted(edit.count) })
    }

    fun deleteRows(doc: TableDoc, edit: DeleteRows): TableEditOutcome {
        if (edit.at < 2) return refused(RefusalReason.HeaderRow)
        if (edit.count < 1 || edit.at + edit.count - 1 > doc.rowCount) return refused(RefusalReason.OutOfRange)
        val body = doc.body.filterIndexed { i, _ -> i + 2 !in edit.at until edit.at + edit.count }
        return applied(rewrite(doc.copy(body = body), edit) { _, r -> EditSummary.RowsDeleted(edit.count, r.broken) })
    }

    fun moveRows(doc: TableDoc, edit: MoveRows): TableEditOutcome {
        if (edit.from < 2 || edit.before < 2) return refused(RefusalReason.HeaderRow)
        if (edit.count < 1 || edit.from + edit.count - 1 > doc.rowCount || edit.before > doc.rowCount + 1) {
            return refused(RefusalReason.OutOfRange)
        }
        if (edit.before in edit.from..edit.from + edit.count) return applied(doc, EditSummary.NoChange)
        val perm = movePermutation(doc.rowCount, edit.from, edit.count, edit.before)
        val body = perm.drop(1).map { doc.row(it) }
        return applied(rewrite(doc.copy(body = body), edit) { _, r -> EditSummary.Moved(perm, r.rewritten) })
    }

    fun insertCols(doc: TableDoc, edit: InsertCols): TableEditOutcome {
        if (edit.before < 1 || edit.before > doc.columnCount + 1 || edit.count < 1) return refused(RefusalReason.OutOfRange)
        val idx = edit.before - 1
        val next = doc.remapColumns { n ->
            val base = (0 until n).toMutableList<Int?>()
            if (idx <= n) base.addAll(idx, List(edit.count) { null })
            base
        }
        return applied(rewrite(next, edit) { _, _ -> EditSummary.ColsInserted(edit.count) })
    }

    fun deleteCols(doc: TableDoc, edit: DeleteCols): TableEditOutcome {
        if (edit.count < 1 || edit.at < 1 || edit.at + edit.count - 1 > doc.columnCount) return refused(RefusalReason.OutOfRange)
        if (edit.count >= doc.columnCount) return refused(RefusalReason.LastColumn)
        val block = (edit.at - 1) until (edit.at - 1 + edit.count)
        val next = doc.remapColumns { n -> (0 until n).filter { it !in block } }
        return applied(rewrite(next, edit) { _, r -> EditSummary.ColsDeleted(edit.count, r.broken) })
    }

    fun moveCols(doc: TableDoc, edit: MoveCols): TableEditOutcome {
        val w = doc.columnCount
        if (edit.count < 1 || edit.from < 1 || edit.before < 1 || edit.from + edit.count - 1 > w || edit.before > w + 1) {
            return refused(RefusalReason.OutOfRange)
        }
        if (edit.before in edit.from..edit.from + edit.count) return applied(doc, EditSummary.NoChange)
        val perm = movePermutation(w, edit.from, edit.count, edit.before)
        val next = doc.remapColumns { n ->
            val zero = perm.map { it - 1 }
            if (n >= w) zero + (w until n) else zero.filter { it < n }
        }
        return applied(rewrite(next, edit) { _, r -> EditSummary.Moved(perm, r.rewritten) })
    }

    fun setAlignment(doc: TableDoc, edit: SetAlignment): TableEditOutcome {
        if (edit.col < 1 || edit.col > doc.columnCount) return refused(RefusalReason.OutOfRange)
        val aligns = doc.delimiter.aligns.toMutableList()
        while (aligns.size < doc.columnCount) aligns.add(ColumnAlign.NONE)
        if (aligns[edit.col - 1] == edit.align) return applied(doc, EditSummary.NoChange)
        aligns[edit.col - 1] = edit.align
        return applied(doc.copy(delimiter = doc.delimiter.withAligns(aligns)), EditSummary.AlignmentSet(edit.col, edit.align))
    }

    private fun rewrite(
        doc: TableDoc,
        edit: TableEdit,
        summary: (TableDoc, RewriteResult) -> EditSummary,
    ): Pair<TableDoc, EditSummary> {
        val (next, result) = doc.rewriteAllFormulas(reference, edit)
        return next to summary(next, result)
    }

    private fun applied(pair: Pair<TableDoc, EditSummary>) = TableEditOutcome.Applied(pair.first, pair.second)
}
