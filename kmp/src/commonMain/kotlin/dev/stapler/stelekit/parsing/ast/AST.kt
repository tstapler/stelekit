package dev.stapler.stelekit.parsing.ast

/**
 * Base class for all AST nodes in the Logseq graph parser.
 *
 * Subclasses are split across focused files in this package:
 *   - BlockNodes.kt  — all BlockNode subclasses
 *   - InlineNodes.kt — all InlineNode subclasses
 *
 * Kotlin sealed classes allow subclasses in any file within the same package (since 1.5),
 * so `when` exhaustiveness checks and pattern matching work across files automatically.
 */
sealed class ASTNode

/**
 * Root node representing a parsed document (Page or Journal).
 */
data class DocumentNode(
    val children: List<BlockNode>
) : ASTNode()

/**
 * Base for all block-level constructs (bullets, headings, code fences, blockquotes, etc.).
 * Concrete subclasses live in BlockNodes.kt.
 */
sealed class BlockNode : ASTNode() {
    abstract val content: List<InlineNode>
    abstract val children: List<BlockNode>
    abstract val properties: Map<String, String>
}

/**
 * Base for all inline constructs (text, bold, links, tags, macros, etc.).
 * Concrete subclasses live in InlineNodes.kt.
 */
sealed class InlineNode : ASTNode()
