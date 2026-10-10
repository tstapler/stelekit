package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Compiled patterns for "does this block reference page X" — shared by every backend. */
internal data class LinkPatterns(val wikiLink: Regex, val simpleHashtag: Regex)

internal fun compileLinkPatterns(pageName: String): LinkPatterns {
    val escaped = Regex.escape(pageName)
    return LinkPatterns(
        wikiLink = "\\[\\[$escaped(\\|[^\\]]*)?\\]\\]".toRegex(RegexOption.IGNORE_CASE),
        simpleHashtag = "#$escaped(?=[\\s,\\.!?;\"\\[\\]]|\$)".toRegex(),
    )
}

/**
 * Returns true if [content] contains a linked reference matching [patterns]:
 * `[[page]]`, `[[page|alias]]`, `#[[page]]`, or `#page` followed by whitespace/punctuation/end.
 */
internal fun isLinkedReference(content: String, patterns: LinkPatterns): Boolean =
    patterns.wikiLink.containsMatchIn(content) || patterns.simpleHashtag.containsMatchIn(content)

internal const val MAX_LINKED_REF_BATCH = 2_000
internal const val MAX_LINKED_REF_ITERATIONS = 50

/** One SQL window of candidate blocks; [exhausted] means the window came back short of its size. */
internal data class LinkBatch(val candidates: List<Block>, val exhausted: Boolean)

/**
 * Iterative overfetch: SQL `LIKE` is looser than [isLinkedReference], so a single `LIMIT` window can
 * under-return true matches. Widens the SQL window until `offset + limit` true matches are found or
 * the table is exhausted. Batch starts at 4x the need and doubles when yield is under 25%.
 */
internal suspend fun overfetchLinkedReferences(
    limit: Int,
    offset: Int,
    patterns: LinkPatterns,
    loadBatch: suspend (batchSize: Int, sqlOffset: Int) -> LinkBatch,
): List<Block> {
    val need = offset + limit
    val seen = mutableSetOf<String>()
    val accumulated = mutableListOf<Block>()
    var sqlOffset = 0
    var batchSize = maxOf(need * 4, 100)
    var iterations = 0

    while (accumulated.size < need && iterations++ < MAX_LINKED_REF_ITERATIONS) {
        val loaded = loadBatch(batchSize, sqlOffset)
        val batch = loaded.candidates
            .filter { seen.add(it.uuid.value) }
            .filter { isLinkedReference(it.content, patterns) }
        accumulated.addAll(batch)

        if (loaded.exhausted) break

        sqlOffset += batchSize
        if (batch.size < batchSize / 4) batchSize = minOf(batchSize * 2, MAX_LINKED_REF_BATCH)
    }
    // accumulated is bounded to roughly offset+limit by the loop above.
    return accumulated.drop(offset).take(limit)
}

/**
 * Default body for query-block reads on repositories that predate them (test stubs, wrappers).
 * Fails loud as `Left` rather than silently emitting an empty list.
 */
internal fun <T> unsupportedRead(method: String): Flow<Either<DomainError, T>> =
    flowOf(DomainError.DatabaseError.ReadFailed("$method is not supported by this repository").left())
