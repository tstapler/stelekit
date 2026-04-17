package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownPreprocessorTest {

    @Test
    fun `test loose indentation normalization`() {
        // Logseq often uses 2 spaces for indentation, which CommonMark might treat as same level
        // or not a proper sublist depending on the parent.
        // We want to ensure it parses as a tree.
        
        val input = """
            - Parent
              - Child (2 spaces)
                - Grandchild (4 spaces)
        """.trimIndent()
        
        // If we strictly enforce 4 spaces per level for CommonMark compatibility:
        val expected = """
            - Parent
                - Child (2 spaces)
                    - Grandchild (4 spaces)
        """.trimIndent()

        val result = MarkdownPreprocessor.normalize(input)
        assertEquals(expected, result)
    }
}
