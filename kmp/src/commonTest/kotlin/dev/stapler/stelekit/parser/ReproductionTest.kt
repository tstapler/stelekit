package dev.stapler.stelekit.parser

import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser as JetbrainsMarkdownParser
import kotlin.test.Test

class ReproductionTest {
    @Test
    fun `reproduce detached child with wikilink`() {
        // The user scenario
        val input = """
- Listening to [[The Knowledge...]]
 - [[Rediscovering Paper]]
        """.trimIndent()
        
        val normalized = MarkdownPreprocessor.normalize(input)
        println("Original:\n$input")
        println("Normalized:\n$normalized")
        
        val flavour = CommonMarkFlavourDescriptor()
        val parser = JetbrainsMarkdownParser(flavour)
        val tree = parser.buildMarkdownTreeFromString(normalized)
        
        println("AST Structure:")
        printNode(tree, "", normalized)
    }
    
    private fun printNode(node: org.intellij.markdown.ast.ASTNode, indent: String, content: String) {
        val text = if (node.type.toString().contains("TEXT") || node.type.toString().contains("PARAGRAPH")) {
            " -> '${content.subSequence(node.startOffset, node.endOffset).toString().replace("\n", "\n")}'"
        } else ""
        
        println("$indent${node.type}$text")
        
        for (child in node.children) {
            printNode(child, "$indent  ", content)
        }
    }
}
