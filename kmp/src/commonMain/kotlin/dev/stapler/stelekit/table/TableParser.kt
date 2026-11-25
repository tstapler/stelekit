package dev.stapler.stelekit.table

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface TableParseResult {
    data class Parsed(val doc: TableDoc) : TableParseResult
    data class Failure(val reason: String) : TableParseResult
}

internal class SplitRow(
    val indent: String,
    val lead: Boolean,
    val trail: Boolean,
    val raw: List<String>,
    val pad: String,
)

/** Splits one line into trimmed raw cells; mirrors the legacy `splitTableRow` pipe rules but keeps escapes. */
internal fun splitRow(line: String): SplitRow {
    val first = line.indexOfFirst { !it.isWhitespace() }
    val indent = if (first < 0) line else line.substring(0, first)
    val t = line.trim()
    val lead = t.startsWith("|")
    var s = if (lead) t.substring(1) else t
    val trail = s.endsWith("|") && !s.endsWith("\\|")
    if (trail) s = s.dropLast(1)
    val segments = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '\\' && i + 1 < s.length && s[i + 1] == '|') {
            cur.append("\\|")
            i += 2
            continue
        }
        if (ch == '|') {
            segments.add(cur.toString())
            cur.clear()
        } else {
            cur.append(ch)
        }
        i++
    }
    segments.add(cur.toString())
    val pad = if (segments.first().startsWith(" ")) " " else ""
    return SplitRow(indent, lead, trail, segments.map { it.trim() }, pad)
}

private val DELIMITER_CELL = Regex("^:?-+:?$")

/**
 * Finds the longest run of contiguous pipe lines that starts with a header and a delimiter line, and
 * keeps every other line verbatim as prefix/suffix. Pure; never throws.
 */
object TableParser {
    fun parse(content: String): TableParseResult = try {
        parseOrFail(content)
    } catch (e: Exception) {
        TableParseResult.Failure("unexpected parser error: ${e.message}")
    }

    private fun parseOrFail(content: String): TableParseResult {
        val lines = content.split("\n")
        fun text(i: Int) = lines[i].removeSuffix("\r")
        fun isPipeLine(i: Int) = text(i).contains('|') && text(i).isNotBlank()

        var bestStart = -1
        var bestEnd = -1
        for (i in 0 until lines.size - 1) {
            if (!isPipeLine(i) || !isDelimiterLine(text(i + 1))) continue
            var end = i + 2
            while (end < lines.size && isPipeLine(end)) end++
            if (end - i > bestEnd - bestStart) {
                bestStart = i
                bestEnd = end
            }
        }
        if (bestStart < 0) return TableParseResult.Failure("no header and delimiter line found")

        fun cr(i: Int) = lines[i].endsWith("\r")
        val head = splitRow(text(bestStart))
        val style = TableStyle(head.indent, head.lead, head.trail, head.pad, cr(bestStart))
        fun row(i: Int): RowLine {
            val raw = splitRow(text(i)).raw
            return RowLine(raw.map { CellText(TableCellCodec.unescape(it)) }, raw, text(i), cr(i))
        }
        val delimRaw = splitRow(text(bestStart + 1)).raw
        return TableParseResult.Parsed(
            TableDoc(
                prefix = lines.subList(0, bestStart),
                header = row(bestStart),
                delimiter = DelimiterRow(delimRaw.map { TableCellCodec.alignOf(it) }, delimRaw, text(bestStart + 1), cr(bestStart + 1)),
                body = (bestStart + 2 until bestEnd).map { row(it) },
                suffix = lines.subList(bestEnd, lines.size),
                style = style,
            ),
        )
    }

    private fun isDelimiterLine(line: String): Boolean {
        if (!line.contains('|')) return false
        val raw = splitRow(line).raw
        return raw.isNotEmpty() && raw.all { DELIMITER_CELL.matches(it) }
    }
}

/** Logs a parse failure once per block per session (tag `TableSheet`), then returns the failure as-is. */
class TableParseFailureLog(private val logger: Logger = Logger("TableSheet")) {
    private val seen = MutableStateFlow<Set<String>>(emptySet())

    fun parse(blockKey: String, content: String): TableParseResult {
        val result = TableParser.parse(content)
        if (result is TableParseResult.Failure && firstTime(blockKey)) {
            logger.warn("table parse failed for block $blockKey: ${result.reason}")
        }
        return result
    }

    private fun firstTime(key: String): Boolean {
        while (true) {
            val cur = seen.value
            if (key in cur) return false
            if (seen.compareAndSet(cur, cur + key)) return true
        }
    }
}
