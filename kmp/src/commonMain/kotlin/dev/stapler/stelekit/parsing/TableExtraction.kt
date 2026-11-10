package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.ast.BlockNode
import dev.stapler.stelekit.parsing.ast.TableBlockNode
import dev.stapler.stelekit.parsing.ast.TextNode

/** A pipe table plus the property keys of every block that encloses it (nearest block included). */
data class ParsedTable(val table: TableBlockNode, val enclosingPropertyKeys: Set<String>)

/**
 * Public entry point for reading pipe tables out of block or page markdown. [BlockParser] keeps
 * its table parsing private and only recognises a table that starts a line; a table written as
 * `- | a | b |` arrives as the bullet's text, so this re-parses such text with [BlockParser].
 * Cell text is returned as [BlockParser] splits it: `\|` is not an escape, so it splits the cell.
 */
object TableExtraction {
    private val PROPERTY_LINE = Regex("""^\s*([A-Za-z0-9_-]+)::(?:\s.*)?$""")

    /** Every table in [blockContent], document order, with the property keys of its enclosing blocks. */
    fun parseTables(blockContent: String): List<ParsedTable> {
        val out = ArrayList<ParsedTable>()
        for (node in BlockParser(blockContent).parse().children) collect(node, emptySet(), out)
        return out
    }

    /**
     * Keys of the page's first block: `key:: value` lines at the top of the content, or the
     * properties of the first parsed block. The Active writer may demote page properties there.
     */
    fun firstBlockPropertyKeys(blockContent: String): Set<String> {
        val first = BlockParser(blockContent).parse().children.firstOrNull() ?: return emptySet()
        val lines = textOf(first).lineSequence().map { PROPERTY_LINE.find(it)?.groupValues?.get(1) }
        return first.properties.keys + lines.filterNotNull().toSet()
    }

    private fun collect(node: BlockNode, inherited: Set<String>, out: MutableList<ParsedTable>) {
        val keys = inherited + node.properties.keys
        if (node is TableBlockNode) {
            out.add(ParsedTable(node, keys))
        } else {
            val text = textOf(node)
            if (text.trimStart().startsWith("|")) {
                BlockParser(text).parse().children.filterIsInstance<TableBlockNode>().forEach {
                    out.add(ParsedTable(it, keys + it.properties.keys))
                }
            }
        }
        for (child in node.children) collect(child, keys, out)
    }

    private fun textOf(node: BlockNode): String =
        node.content.filterIsInstance<TextNode>().joinToString("") { it.content }
}
