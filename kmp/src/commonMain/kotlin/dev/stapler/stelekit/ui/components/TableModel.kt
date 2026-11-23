package dev.stapler.stelekit.ui.components

enum class TableColumnAlignment { LEFT, CENTER, RIGHT }

/**
 * A parsed GFM pipe table. Every row (header included) is padded to [columnCount] so a ragged
 * row cannot shift the shared column grid. [alignments] defaults unmarked columns to LEFT;
 * [explicitAlignments] keeps `null` for "no marker" so numeric auto-alignment can tell the two apart.
 */
data class TableData(
    val headers: List<String>,
    val alignments: List<TableColumnAlignment>,
    val rows: List<List<String>>,
    val explicitAlignments: List<TableColumnAlignment?> = alignments,
) {
    val columnCount: Int get() = headers.size
}

/** `:---` LEFT, `:---:` CENTER, `---:` RIGHT, `---` (no marker) null. */
fun parseAlignmentCell(cell: String): TableColumnAlignment? {
    val c = cell.trim()
    if (c.length < 2) return null
    val left = c.startsWith(':')
    val right = c.endsWith(':')
    return when {
        left && right -> TableColumnAlignment.CENTER
        right -> TableColumnAlignment.RIGHT
        left -> TableColumnAlignment.LEFT
        else -> null
    }
}

/** Splits one pipe row into trimmed cells; `\|` is a literal pipe, not a separator. */
fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '\\' && i + 1 < s.length && s[i + 1] == '|') {
            cur.append('|')
            i += 2
            continue
        }
        if (ch == '|') {
            cells.add(cur.toString().trim())
            cur.clear()
        } else {
            cur.append(ch)
        }
        i++
    }
    cells.add(cur.toString().trim())
    return cells
}

fun parseTableContent(raw: String): TableData {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return TableData(emptyList(), emptyList(), emptyList())

    val rawHeaders = splitTableRow(lines[0])
    val rawRows = lines.drop(2).map { splitTableRow(it) }
    val explicit = splitTableRow(lines[1]).map { parseAlignmentCell(it) }
    // GFM: the header row fixes the column count; body cells beyond it are dropped, short rows are padded.
    val columnCount = rawHeaders.size

    fun <T> List<T>.padTo(fill: T): List<T> = this + List((columnCount - size).coerceAtLeast(0)) { fill }
    val explicitPadded = explicit.take(columnCount).padTo(null)
    return TableData(
        headers = rawHeaders.padTo(""),
        alignments = explicitPadded.map { it ?: TableColumnAlignment.LEFT },
        rows = rawRows.map { it.take(columnCount).padTo("") },
        explicitAlignments = explicitPadded,
    )
}

private val NUMERIC_CELL = Regex(
    """^[+\-−]?[$€£]?(\d[\d,_]*(\.\d+)?|\.\d+)([eE][+\-]?\d+)?\s?(%|[A-Za-z/°²³µΩ]{1,5})?$""",
)

/** True for `1,234.5`, `-3`, `$4`, `12%`, `140 ft`, `**249**`; false for words and dates like `2026-11-21`. */
fun isNumericCell(text: String): Boolean {
    val t = text.filterNot { it == '*' || it == '~' || it == '`' }.trim().trim('_').trim()
    return t.isNotEmpty() && NUMERIC_CELL.matches(t)
}

/** A column is numeric when it has at least one non-empty body cell and every non-empty body cell is numeric. */
fun TableData.numericColumns(): List<Boolean> = List(columnCount) { col ->
    val cells = rows.map { it[col] }.filter { it.isNotBlank() }
    cells.isNotEmpty() && cells.all { isNumericCell(it) }
}

/** Explicit GFM markers win; an unmarked numeric column is RIGHT when [numericRightAlign], else LEFT. */
fun TableData.effectiveAlignments(numericRightAlign: Boolean): List<TableColumnAlignment> {
    val numeric = if (numericRightAlign) numericColumns() else emptyList()
    return List(columnCount) { col ->
        explicitAlignments.getOrNull(col)
            ?: if (numeric.getOrNull(col) == true) TableColumnAlignment.RIGHT else TableColumnAlignment.LEFT
    }
}
