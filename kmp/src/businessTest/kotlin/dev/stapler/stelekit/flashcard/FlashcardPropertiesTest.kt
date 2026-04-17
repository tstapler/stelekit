package dev.stapler.stelekit.flashcard

import dev.stapler.stelekit.parser.PropertiesParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that card properties round-trip through markdown serialization and parsing.
 *
 * GraphWriter serializes Block.properties as inline `key:: value` lines.
 * PropertiesParser parses those lines back into a properties map.
 */
class FlashcardPropertiesTest {

    // Card property keys used by the flashcard feature
    private val cardProperties = mapOf(
        "card" to "true",
        "card-next-review" to "2026-04-23",
        "card-ease" to "2.50",
        "card-interval" to "3"
    )

    /**
     * Reproduce GraphWriter's block-property serialization:
     * each entry is written as `key:: value` on its own line
     * with a leading tab (for block-level properties).
     */
    private fun serializeBlockProperties(properties: Map<String, String>): String =
        buildString {
            properties.forEach { (key, value) ->
                appendLine("\t$key:: $value")
            }
        }

    @Test
    fun `card properties are serialized as key-colon-colon-value lines`() {
        val markdown = serializeBlockProperties(cardProperties)

        assertTrue(
            markdown.contains("\tcard:: true"),
            "Expected 'card:: true' in serialized output but got:\n$markdown"
        )
        assertTrue(
            markdown.contains("\tcard-next-review:: 2026-04-23"),
            "Expected 'card-next-review:: 2026-04-23' but got:\n$markdown"
        )
        assertTrue(
            markdown.contains("\tcard-ease:: 2.50"),
            "Expected 'card-ease:: 2.50' but got:\n$markdown"
        )
        assertTrue(
            markdown.contains("\tcard-interval:: 3"),
            "Expected 'card-interval:: 3' but got:\n$markdown"
        )
    }

    @Test
    fun `PropertiesParser round-trips card properties`() {
        // Produce the same inline format GraphWriter emits (indented key:: value)
        val markdown = buildString {
            cardProperties.forEach { (key, value) ->
                appendLine("$key:: $value")
            }
        }

        val result = PropertiesParser.parse(markdown)

        assertEquals("true", result.properties["card"])
        assertEquals("2026-04-23", result.properties["card-next-review"])
        assertEquals("2.50", result.properties["card-ease"])
        assertEquals("3", result.properties["card-interval"])
    }

    @Test
    fun `PropertiesParser parses card=true from a two-property string`() {
        val markdown = "card:: true\ncard-next-review:: 2026-04-23"
        val result = PropertiesParser.parse(markdown)

        assertEquals("true", result.properties["card"])
        assertEquals("2026-04-23", result.properties["card-next-review"])
    }

    @Test
    fun `PropertiesParser preserves non-property content when card properties are present`() {
        val markdown = "What is the capital of France?\ncard:: true\ncard-next-review:: 2026-04-23"
        val result = PropertiesParser.parse(markdown)

        assertEquals("true", result.properties["card"])
        assertEquals("2026-04-23", result.properties["card-next-review"])
        assertTrue(
            result.content.contains("What is the capital of France?"),
            "Non-property content should be preserved, got: '${result.content}'"
        )
    }

    @Test
    fun `card-ease value round-trips with two decimal places`() {
        // GraphWriter formats ease as "%.2f" so "2.50" must survive the round-trip
        val markdown = "card-ease:: 2.50"
        val result = PropertiesParser.parse(markdown)
        assertEquals("2.50", result.properties["card-ease"])
    }

    @Test
    fun `all four card property keys are recovered after serialization and parsing`() {
        val markdown = buildString {
            cardProperties.forEach { (key, value) -> appendLine("$key:: $value") }
        }
        val result = PropertiesParser.parse(markdown)
        val expectedKeys = setOf("card", "card-next-review", "card-ease", "card-interval")
        assertTrue(
            result.properties.keys.containsAll(expectedKeys),
            "Expected keys $expectedKeys but got ${result.properties.keys}"
        )
    }
}
