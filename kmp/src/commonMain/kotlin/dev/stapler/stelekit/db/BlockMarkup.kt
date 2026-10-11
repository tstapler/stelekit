package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.BlockType

/**
 * What the parser strips from a bullet's text and keeps beside it (heading marker, list number,
 * `SCHEDULED`/`DEADLINE` lines), put back so a block serializes to the markup it was read from.
 * Shared by [LogseqPageSerializer] and the cross-graph merge so both write the same text.
 *
 * Limit: the database stores only a heading's type, not its level (`blockTypeFromString` yields 1),
 * so a heading reloaded from SQLite is restored as `#`.
 */
object BlockMarkup {
    class Restored(val content: String, val properties: Map<String, String>)

    private val HEADING = Regex("^#{1,6}(\\s|$)")
    private val ORDERED = Regex("^\\d+\\.(\\s|$)")
    private val TIMESTAMP_VALUE = Regex("^\\d{4}-\\d{2}-\\d{2}[^<>\\n]*$")
    private val TIMESTAMP_KEYS = listOf("scheduled" to "SCHEDULED", "deadline" to "DEADLINE")

    fun restore(content: String, blockType: BlockType, properties: Map<String, String>): Restored {
        var text = when (blockType) {
            is BlockType.Heading ->
                if (HEADING.containsMatchIn(content)) content
                else "#".repeat(blockType.level.coerceIn(1, 6)) + if (content.isEmpty()) "" else " $content"
            is BlockType.OrderedListItem ->
                if (ORDERED.containsMatchIn(content)) content else "${blockType.number}. $content"
            else -> content
        }
        val rest = LinkedHashMap(properties)
        for ((key, label) in TIMESTAMP_KEYS) {
            val value = properties[key]?.trim() ?: continue
            if (!TIMESTAMP_VALUE.matches(value) || Regex("\\b$label:").containsMatchIn(text)) continue
            text = if (text.isEmpty()) "$label: <$value>" else "$text\n$label: <$value>"
            rest.remove(key)
        }
        return Restored(text, rest)
    }
}
