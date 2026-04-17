package dev.stapler.stelekit.parser

import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser as JetbrainsMarkdownParser
import kotlin.test.Ignore
import kotlin.test.Test

class ASTDebugTest {
    @Ignore("Debug utility - intentionally fails to print AST structure")
    @Test
    fun `debug AST structure`() {
        val input = """
- Parent
  - Child (2 spaces)
        """.trimIndent()
        
        val normalized = MarkdownPreprocessor.normalize(input)
        val sb = StringBuilder()
        sb.append("Normalized:\n$normalized\n")
        
        val flavour = CommonMarkFlavourDescriptor()
        val parser = JetbrainsMarkdownParser(flavour)
        val tree = parser.buildMarkdownTreeFromString(normalized)
        
        sb.append("AST Structure:\n")
        printNode(tree, "", normalized, sb)
        
        println(sb.toString())
        kotlin.test.fail("Forced failure to see stdout:\n$sb")
    }
    
    private fun printNode(node: org.intellij.markdown.ast.ASTNode, indent: String, content: String, sb: StringBuilder) {
        val text = if (node.type.toString().contains("TEXT") || node.type.toString().contains("PARAGRAPH")) {
            " -> '${content.subSequence(node.startOffset, node.endOffset).toString().replace("\n", "\\n")}'"
        } else ""
        
        sb.append("$indent${node.type}$text\n")
        
        for (child in node.children) {
            printNode(child, "$indent  ", content, sb)
        }
    }
}
