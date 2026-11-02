package dev.stapler.stelekit.asset

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.AssetRepository
import kotlinx.coroutines.flow.first

/** Which column matched and a bounded excerpt around the match (<= 240 characters). */
data class AssetSearchPreview(val matchedColumn: String, val excerpt: String) {
    /** Label for text that did not come from the owner: cloud text is an AI description, OCR text is text found in the image. */
    val sourceLabel: String? get() = when (matchedColumn) {
        COLUMN_CLOUD_DESCRIPTION -> "AI description"
        COLUMN_OCR_TEXT -> "Text found in image"
        else -> null
    }

    companion object {
        const val COLUMN_OCR_TEXT = "ocr_text"
        const val COLUMN_CLOUD_DESCRIPTION = "cloud_description"
    }
}

data class AssetSearchHit(val asset: AssetEntry, val preview: AssetSearchPreview?)

/**
 * Searches indexed assets through the projected `searchAssetsLight` query (no `ocr_text`/`auto_labels`
 * in list rows) and attaches bounded snippets for the returned uuids only. One service backs both the
 * Assets bar and the global-search section; callers differ only in `limit`.
 */
class AssetSearchService(private val repository: AssetRepository) {
    suspend fun search(query: String, limit: Int): Either<DomainError, List<AssetSearchHit>> {
        if (query.isBlank()) return emptyList<AssetSearchHit>().right()
        val capped = limit.coerceIn(1, MAX_RESULTS)
        return repository.searchAssets(query, capped, 0).first().flatMap { assets ->
            repository.getSearchSnippets(query, assets.map { it.uuid }).map { previews ->
                assets.map { AssetSearchHit(it, previews[it.uuid]) }
            }
        }
    }

    companion object {
        const val MAX_RESULTS = 50
        const val GLOBAL_SECTION_LIMIT = 5
        const val MIN_GLOBAL_QUERY_LENGTH = 3

        /** The global section only queries once the query is long enough; the leading `%` LIKE is a full scan. */
        fun shouldQueryGlobal(query: String): Boolean = query.trim().length >= MIN_GLOBAL_QUERY_LENGTH
    }
}
