package dev.stapler.stelekit.util

private val MINOR_WORDS = setOf(
    "a", "an", "the", "and", "but", "or", "for", "nor",
    "of", "in", "on", "at", "to", "with", "by", "from",
)

/**
 * Converts this string to title case: capitalizes the first letter of each word,
 * keeping common articles and prepositions lowercase unless they appear first.
 */
fun String.toTitleCase(): String {
    if (isEmpty()) return this
    return split(" ").mapIndexed { index, word ->
        when {
            word.isEmpty() -> word
            index > 0 && word.lowercase() in MINOR_WORDS -> word.lowercase()
            else -> word[0].uppercaseChar() + word.substring(1)
        }
    }.joinToString(" ")
}
