package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IndentationTest {

    private val parser = MarkdownParser()

    @Test
    fun `test indentation levels`() {
        val input = """
- Level 0
 - Level 1 (1 space)
  - Level 1 (2 spaces)
   - Level 1 (3 spaces)
    - Level 2 (4 spaces)
        """.trimIndent()
        
        // Let's see what the preprocessor does
        val normalized = MarkdownPreprocessor.normalize(input)
        val lines = normalized.lines()
        
        println("Normalized:\n$normalized")
        
        // Expectation with current logic (spaces / 2):
        // 0 / 2 = 0
        // 1 / 2 = 0 -> FAILURE? Should this be level 1?
        // 2 / 2 = 1 -> Correct
        // 3 / 2 = 1 -> Correct
        // 4 / 2 = 2 -> Correct
        
        // If the user has 1 space indentation, we flatten it. This might be the bug.
    }
}
