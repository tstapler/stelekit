package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.BlockPropertyKeys

/**
 * Insert [block] as the LAST child of the existing block at [parentPath] (indices among the
 * parsed outline, root to parent); an empty path appends a last root block. Last-only keeps
 * positional uuids of unlabeled blocks stable (R3).
 */
data class BlockInsertion(val parentPath: List<Int>, val block: MergeBlock)

data class SpliceRequest(
    val insertions: List<BlockInsertion> = emptyList(),
    /** Desired page properties; only keys missing from the file are inserted. */
    val pageProperties: Map<String, String> = emptyMap(),
)

data class SpliceResult(
    val text: String,
    val insertedBlocks: Int,
    val insertedPropertyKeys: List<String>,
    /** Keys present in the file with a different value (e.g. an alias/tags union); never rewritten. */
    val skippedPropertyKeys: List<String>,
)

sealed interface SpliceError {
    data class ParentNotFound(val path: List<Int>) : SpliceError
}

/**
 * Pure `(original text, insertions) -> new text` that only adds lines; every original byte is
 * kept in order. It is a line scanner rather than a re-render because re-serializing rewrites
 * headings, indent style and whitespace (Spike 0.1.4: exact round trip passes on 12% of pages).
 */
object MarkdownSplicer {
    private val BULLET = Regex("^([ \\t]*)-(?: (.*))?$")
    private val PROPERTY = Regex("^\\s*([^\\s:][^\\s:]*)::(?:\\s+(.*))?$")
    private const val TAB_WIDTH = 4

    private class Line(val text: String, val term: String)

    /** [isBullet] false marks a column-0 non-bullet line: the parser makes each one its own root block. */
    private class Bullet(val line: Int, val depth: Int, val indent: String, val content: String, val isBullet: Boolean = true) {
        val children = mutableListOf<Bullet>()
        var end = line
    }

    private class Scan(val bullets: List<Bullet>, val roots: List<Bullet>, val pagePropBullet: Bullet?)

    private class Edit(val afterLine: Int, val order: Int, val lines: List<String>)

    fun splice(original: String, request: SpliceRequest): Either<SpliceError, SpliceResult> {
        val blocks = request.insertions.sumOf { count(it.block) }
        if (original.isEmpty()) {
            val page = MergePage("", properties = request.pageProperties, blocks = request.insertions.map { it.block })
            val text = MergeRenderer.renderNewPage(page)
            return SpliceResult(text, blocks, request.pageProperties.keys.filter { it != BlockPropertyKeys.ID }, emptyList()).right()
        }
        val lines = splitLines(original)
        val style = detectStyle(lines)
        val scan = scan(lines)
        val edits = mutableListOf<Edit>()

        val groups = request.insertions.groupBy { it.parentPath }
        for ((path, group) in groups) {
            val parent = if (path.isEmpty()) null else resolve(scan, path) ?: return SpliceError.ParentNotFound(path).left()
            val rendered = MergeRenderer.renderBlocks(
                group.map { it.block },
                baseIndent = parent?.let { it.indent + style.indentUnit }.orEmpty(),
                style = style,
            )
            if (rendered.isEmpty()) continue
            // Root appends go after trailing blank lines: moving them would change how the parser reads the tail (Spike 0.1.4).
            val after = parent?.end ?: lines.lastIndex
            edits += Edit(after, parent?.depth ?: -1, rendered)
        }

        val (propEdit, inserted, skipped) = planProperties(lines, scan, request.pageProperties)
        propEdit?.let { edits += it }
        return SpliceResult(assemble(lines, edits, style.eol), blocks, inserted, skipped).right()
    }

    /** Path (parser-tree indices) of the first real bullet, or null when the file has none. */
    fun firstBulletPath(original: String): List<Int>? {
        val scan = scan(splitLines(original))
        val i = scan.roots.indexOfFirst { it.isBullet }
        return if (i < 0) null else listOf(i)
    }

    private fun count(b: MergeBlock): Int = 1 + b.children.sumOf(::count)

    private fun splitLines(text: String): List<Line> {
        val out = mutableListOf<Line>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) {
                out += Line(text.substring(start), "")
                break
            }
            val crlf = nl > start && text[nl - 1] == '\r'
            out += Line(text.substring(start, if (crlf) nl - 1 else nl), if (crlf) "\r\n" else "\n")
            start = nl + 1
        }
        return out
    }

    private fun detectStyle(lines: List<Line>): FileStyle {
        val crlf = lines.count { it.term == "\r\n" }
        val lf = lines.count { it.term == "\n" }
        val indents = lines.mapNotNull { BULLET.matchEntire(it.text)?.groupValues?.get(1) }.filter { it.isNotEmpty() }
        val unit = when {
            indents.any { '\t' in it } -> "\t"
            indents.isNotEmpty() -> " ".repeat(indents.minOf { it.length })
            else -> "\t"
        }
        return FileStyle(eol = if (crlf > lf) "\r\n" else "\n", indentUnit = unit)
    }

    private fun width(indent: String) = indent.sumOf { if (it == '\t') TAB_WIDTH else 1 }

    private fun lastContentLine(lines: List<Line>): Int = lines.indexOfLast { it.text.isNotBlank() }

    private fun scan(lines: List<Line>): Scan {
        val all = mutableListOf<Bullet>()
        val widths = ArrayList<Int>()
        val stack = ArrayList<Bullet>()
        val boundary = HashMap<Bullet, Int>()
        var inFence = false
        var inDirective = false
        val lastContent = lastContentLine(lines)
        lines.forEachIndexed { i, l ->
            val trimmed = l.text.trimStart()
            val fence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            if (!inFence && !inDirective) {
                val m = BULLET.matchEntire(l.text)
                val column0 = l.text.isEmpty() || !l.text[0].isWhitespace()
                if (m == null && column0 && i < lastContent) {
                    while (stack.isNotEmpty()) boundary[stack.removeAt(stack.lastIndex)] = i
                    widths.clear()
                    val prose = Bullet(i, 0, "", l.text, isBullet = false)
                    widths += 0
                    stack += prose
                    all += prose
                }
                if (m != null) {
                    val indent = m.groupValues[1]
                    val w = width(indent)
                    while (widths.isNotEmpty() && widths.last() >= w) {
                        widths.removeAt(widths.lastIndex)
                        boundary[stack.removeAt(stack.lastIndex)] = i
                    }
                    val b = Bullet(i, widths.size, indent, m.groupValues[2])
                    stack.lastOrNull()?.children?.add(b)
                    widths += w
                    stack += b
                    all += b
                }
            }
            if (!inDirective && fence) inFence = !inFence
            if (!inFence) {
                if (trimmed.startsWith("#+BEGIN_", ignoreCase = true)) inDirective = true
                else if (trimmed.startsWith("#+END_", ignoreCase = true)) inDirective = false
            }
        }
        stack.forEach { boundary[it] = lines.size }
        all.forEach { b ->
            val limit = boundary.getValue(b)
            b.end = (limit - 1 downTo b.line).firstOrNull { lines[it].text.isNotBlank() } ?: b.line
        }
        val roots = all.filter { it.depth == 0 }
        val pagePropBullet = roots.firstOrNull()?.takeIf { first ->
            val body = lines.subList(first.line + 1, first.end + 1).filter { it.text.isNotBlank() }
            first.isBullet && first.content.isBlank() && first.children.isEmpty() && body.isNotEmpty() &&
                body.all { PROPERTY.matches(it.text) }
        }
        return Scan(all, roots.filter { it !== pagePropBullet }, pagePropBullet)
    }

    private fun resolve(scan: Scan, path: List<Int>): Bullet? {
        var level = scan.roots
        var node: Bullet? = null
        for (i in path) {
            node = level.getOrNull(i) ?: return null
            level = node.children
        }
        return node
    }

    private data class PropPlan(val edit: Edit?, val inserted: List<String>, val skipped: List<String>)

    /**
     * The loader reads page properties only from a leading property-only bullet. A bare `key:: v`
     * preamble parses as an ordinary first block, so editing it would change an existing block
     * (and adding one would shift positional uuids): in that case every missing key is skipped.
     */
    private fun planProperties(lines: List<Line>, scan: Scan, wanted: Map<String, String>): PropPlan {
        val desired = wanted.filterKeys { it != BlockPropertyKeys.ID }
        if (desired.isEmpty()) return PropPlan(null, emptyList(), emptyList())
        val bullet = scan.pagePropBullet
        val firstBullet = scan.bullets.firstOrNull { it.isBullet }
        val preamble = lines.indices.takeWhile { firstBullet == null || it < firstBullet.line }
            .filter { PROPERTY.matches(lines[it].text) }
        val existing = (bullet?.let { (it.line + 1..it.end).toList() } ?: preamble).associate { i ->
            val m = PROPERTY.matchEntire(lines[i].text)!!
            m.groupValues[1].lowercase() to m.groupValues[2].trim()
        }
        val missing = desired.filterKeys { it.lowercase() !in existing }
        val differing = desired.filter { (k, v) -> existing[k.lowercase()]?.let { it != v.trim() } == true }.keys.toList()
        if (missing.isEmpty()) return PropPlan(null, emptyList(), differing)
        return when {
            bullet != null -> {
                val indent = lines[bullet.line + 1].text.takeWhile { it == ' ' || it == '\t' }
                PropPlan(Edit(bullet.end, Int.MAX_VALUE, MergeRenderer.propertyLines(missing, indent)), missing.keys.toList(), differing)
            }
            preamble.isNotEmpty() -> PropPlan(null, emptyList(), differing + missing.keys)
            else -> PropPlan(Edit(-1, Int.MAX_VALUE, MergeRenderer.propertyBlockLines(missing)), missing.keys.toList(), differing)
        }
    }

    /** Edits at the same line go deeper-first so a parent's new child lands after its last child's new child. */
    private fun assemble(lines: List<Line>, edits: List<Edit>, eol: String): String {
        val byLine = edits.groupBy { it.afterLine }.mapValues { (_, es) -> es.sortedByDescending { it.order } }
        val sb = StringBuilder()
        fun emit(afterLine: Int, lastTermMissing: Boolean) {
            val group = byLine[afterLine] ?: return
            if (lastTermMissing) sb.append(eol)
            val all = group.flatMap { it.lines }
            all.forEachIndexed { i, text ->
                sb.append(text)
                if (!(lastTermMissing && i == all.lastIndex)) sb.append(eol)
            }
        }
        emit(-1, false)
        lines.forEachIndexed { i, l ->
            sb.append(l.text).append(l.term)
            emit(i, l.term.isEmpty())
        }
        return sb.toString()
    }
}
