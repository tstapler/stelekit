package dev.stapler.stelekit.table

/**
 * Re-emits untouched lines byte for byte; only dirty rows are rendered, in the detected [TableStyle].
 * A dirty row keeps the source text of every cell whose [CellText] is unchanged, so `<br/>`, `<BR>`
 * and a literal `\\|` in neighbouring cells survive. Computed values never reach this layer.
 */
object TableSerializer {
    fun serialize(doc: TableDoc): String {
        val out = ArrayList<String>(doc.prefix.size + doc.rowCount + 1 + doc.suffix.size)
        out.addAll(doc.prefix)
        out.add(rowText(doc.header, doc.style))
        out.add(delimiterText(doc.delimiter, doc.style))
        doc.body.forEach { out.add(rowText(it, doc.style)) }
        out.addAll(doc.suffix)
        return out.joinToString("\n")
    }

    private fun rowText(row: RowLine, style: TableStyle): String {
        val cr = if (row.cr) "\r" else ""
        row.source?.let { return it + cr }
        val parts = row.cells.mapIndexed { i, cell ->
            val raw = row.rawCells.getOrNull(i)
            if (raw != null && TableCellCodec.unescape(raw) == cell.value) raw else TableCellCodec.escape(cell.value)
        }
        return render(parts, style) + cr
    }

    private fun delimiterText(d: DelimiterRow, style: TableStyle): String {
        val cr = if (d.cr) "\r" else ""
        d.source?.let { return it + cr }
        val parts = d.aligns.mapIndexed { i, align ->
            val raw = d.rawCells.getOrNull(i)
            if (raw != null && TableCellCodec.alignOf(raw) == align) raw else TableCellCodec.delimiterCell(align)
        }
        return render(parts, style) + cr
    }

    private fun render(parts: List<String>, style: TableStyle): String {
        val cells = parts.ifEmpty { listOf("") }
        val padded = cells.map { if (it.isEmpty()) style.pad else style.pad + it + style.pad }
        // Line trimming on re-parse would swallow an empty edge cell unless its outer pipe is explicit.
        val lead = style.leadPipe || cells.first().isEmpty()
        val trail = style.trailPipe || cells.last().isEmpty()
        val line = style.indent + (if (lead) "|" else "") + padded.joinToString("|") + (if (trail) "|" else "")
        // A pipe-less line would stop being a table row on re-parse.
        return if (line.contains('|')) line else "|$line|"
    }
}
