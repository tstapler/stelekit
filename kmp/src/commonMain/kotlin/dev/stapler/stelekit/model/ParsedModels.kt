package dev.stapler.stelekit.model

/**
 * Intermediate representation of a parsed Markdown page.
 * This structure mirrors the file content before it is normalized into the database schema.
 */
data class ParsedPage(
    val title: String?,
    val properties: Map<String, String>,
    val blocks: List<ParsedBlock>
)

/**
 * Discriminated union representing the structural type of a parsed block.
 * Mirrors the block node subtypes from the AST layer.
 */
sealed class BlockType {
    object Bullet : BlockType()
    object Paragraph : BlockType()
    data class Heading(val level: Int) : BlockType()
    data class CodeFence(val language: String) : BlockType()
    object Blockquote : BlockType()
    data class OrderedListItem(val number: Int) : BlockType()
    object ThematicBreak : BlockType()
    object Table : BlockType()
    object RawHtml : BlockType()
}

/**
 * Intermediate representation of a parsed Markdown block.
 */
data class ParsedBlock(
    val content: String,
    val properties: Map<String, String>,
    val level: Int,
    // Children are optional here depending on whether the parser produces a flat list or tree.
    // For now, we'll assume the parser might preserve hierarchy if convenient,
    // but the main usage in GraphLoader might just iterate a flat list.
    // Let's include it for flexibility.
    val children: List<ParsedBlock> = emptyList(),
    // Extracted references (WikiLinks [[...]] and Block Refs ((...)))
    val references: List<String> = emptyList(),
    // Metadata
    val scheduled: String? = null,
    val deadline: String? = null,
    // Block structural type — defaults to Bullet to preserve backward compatibility
    val blockType: BlockType = BlockType.Bullet
)
