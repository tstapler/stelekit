package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.BlockPropertyKeys

/** Whitespace conventions of the file being edited; new text must not introduce a second style. */
data class FileStyle(
    val eol: String = "\n",
    val indentUnit: String = "\t",
)

/**
 * Renders only the blocks a merge inserts. `LogseqPageSerializer` derives no `id::` from
 * `Block.uuid` (Spike 0.1.3), so every block with a uuid gets an explicit `id::` here; without
 * it the uuid' (and the `src-id` match for repeat copies) would not survive a re-parse.
 * A null uuid means "no explicit id::" and stays that way.
 */
object MergeRenderer {
    private const val PROPERTY_INDENT = "  "

    /** Lines (no terminators) for [blocks], each root prefixed with [baseIndent]. */
    fun renderBlocks(blocks: List<MergeBlock>, baseIndent: String = "", style: FileStyle = FileStyle()): List<String> =
        buildList { blocks.forEach { renderBlock(it, baseIndent, style, this) } }

    /** Whole-file text for a brand-new page: page-property block, then blocks, ending in a newline. */
    fun renderNewPage(page: MergePage, style: FileStyle = FileStyle()): String {
        val lines = propertyBlockLines(page.properties) + renderBlocks(page.blocks, "", style)
        return lines.joinToString("") { it + style.eol }
    }

    /**
     * Page properties as a leading property-only bullet. The loader recognises only this form as
     * page properties; a bare `key:: v` preamble parses as an ordinary first block.
     */
    fun propertyBlockLines(props: Map<String, String>): List<String> {
        val lines = propertyLines(props, PROPERTY_INDENT)
        return if (lines.isEmpty()) emptyList() else listOf("-") + lines
    }

    fun propertyLines(props: Map<String, String>, indent: String): List<String> =
        props.filterKeys { it != BlockPropertyKeys.ID }.map { (k, v) -> "$indent$k:: $v" }

    private fun renderBlock(b: MergeBlock, indent: String, style: FileStyle, out: MutableList<String>) {
        val contentLines = b.content.split("\n")
        val first = contentLines.first()
        out += if (first.isEmpty()) "$indent-" else "$indent- $first"
        val continuation = indent + PROPERTY_INDENT
        contentLines.drop(1).forEach { out += "$continuation$it" }
        val props = LinkedHashMap<String, String>()
        b.uuid?.let { props[BlockPropertyKeys.ID] = it }
        props.putAll(b.properties.filterKeys { it != BlockPropertyKeys.ID })
        props.forEach { (k, v) -> out += "$continuation$k:: $v" }
        b.children.forEach { renderBlock(it, indent + style.indentUnit, style, out) }
    }
}
