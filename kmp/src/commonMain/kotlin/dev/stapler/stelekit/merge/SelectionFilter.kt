package dev.stapler.stelekit.merge

import dev.stapler.stelekit.model.Page
import kotlinx.datetime.LocalDate

/**
 * Which pages the copy picker lists. SQL implements this directly; [matches] is the
 * pure oracle for the in-memory repository and for tests of the SQL.
 *
 * - [journals] = false excludes journal pages.
 * - A date range keeps only journal pages dated within it (bounds inclusive).
 * - [namespace] is a case-insensitive (ASCII) name prefix, e.g. "work/".
 * - [tag] matches a token of the page's `tags` / `tag` property.
 */
data class SelectionFilter(
    val journals: Boolean = true,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val namespace: String? = null,
    val tag: String? = null,
) {
    val namePrefix: String? get() = namespace?.takeIf { it.isNotEmpty() }
    val tagToken: String? get() = tag?.let(::normalizeTagToken)?.takeIf { it.isNotEmpty() }

    /** Pure predicate; SQL must agree with it (see SelectionFilterTest / repository oracle tests). */
    fun matches(page: Page): Boolean = matchesWithoutTag(page) && matchesTag(page.properties)

    fun matchesWithoutTag(page: Page): Boolean {
        if (!journals && page.isJournal) return false
        if (dateFrom != null || dateTo != null) {
            val d = page.journalDate
            if (!page.isJournal || d == null) return false
            if (dateFrom != null && d < dateFrom) return false
            if (dateTo != null && d > dateTo) return false
        }
        val prefix = namePrefix
        if (prefix != null && !page.name.asciiLower().startsWith(prefix.asciiLower())) return false
        return true
    }

    fun matchesTag(properties: Map<String, String>): Boolean {
        val wanted = tagToken ?: return true
        return TAG_KEYS.any { key ->
            properties[key]?.split(',')?.any { normalizeTagToken(it) == wanted } == true
        }
    }

    companion object {
        val TAG_KEYS = listOf("tags", "tag")

        fun normalizeTagToken(raw: String): String =
            raw.trim().removePrefix("#").removePrefix("[[").removeSuffix("]]").trim().asciiLower()
    }
}

// SQLite NOCASE folds ASCII only; the oracle must fold the same way.
internal fun String.asciiLower(): String =
    buildString(length) { for (c in this@asciiLower) append(if (c in 'A'..'Z') c + 32 else c) }

/** Oracle ordering used by in-memory repositories; matches SQL `ORDER BY name, section_id` (uuid only as a last tiebreak). */
fun Collection<Page>.filteredAndSorted(filter: SelectionFilter): List<Page> =
    filter(filter::matches).sortedWith(compareBy({ it.name.asciiLower() }, { it.sectionId.toDbString() }, { it.uuid.value }))

/** Bind values for the `*PagesFiltered*` queries in SteleDatabase.sq. */
internal data class SelectionSqlArgs(
    val nameLo: String,
    val nameHi: String,
    val includeJournals: Long,
    val dateFrom: String?,
    val dateTo: String?,
    val tagLike: String?,
)

// U+10FFFF: sorts after every other name in SQLite's UTF-8 byte order.
private const val MAX_NAME = "􏿿"

internal fun SelectionFilter.toSqlArgs(): SelectionSqlArgs {
    val prefix = namePrefix?.asciiLower()
    return SelectionSqlArgs(
        nameLo = prefix ?: "",
        nameHi = prefix?.let(::prefixUpperBound) ?: MAX_NAME,
        includeJournals = if (journals) 1L else 0L,
        dateFrom = dateFrom?.toString(),
        dateTo = dateTo?.toString(),
        tagLike = tagToken?.let { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" },
    )
}

/**
 * Smallest string above every string starting with [lowerPrefix] under NOCASE order, where
 * A-Z fold down to a-z (so the code point after '@' is '[', not 'A').
 */
internal fun prefixUpperBound(lowerPrefix: String): String {
    val last = lowerPrefix.last()
    if (last == '￿') return MAX_NAME
    var next = last + 1
    if (next in 'A'..'Z') next = '['
    return lowerPrefix.dropLast(1) + next
}
