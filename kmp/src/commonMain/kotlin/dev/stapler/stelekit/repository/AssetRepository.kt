package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetSortOrder
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun getAssetByUuid(uuid: AssetUuid): Flow<Either<DomainError, AssetEntry?>>
    fun getAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    fun getAssetsByMediaType(mediaType: AssetMediaType, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    fun searchAssets(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    /** Bounded snippets (<= 50 uuids, <= 240 characters each) for rows returned by [searchAssets]. */
    suspend fun getSearchSnippets(
        query: String,
        uuids: List<AssetUuid>,
    ): Either<DomainError, Map<AssetUuid, dev.stapler.stelekit.asset.AssetSearchPreview>>

    fun getUnprocessedAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    suspend fun countUnprocessedAssets(): Either<DomainError, Long>

    /** Bounded batches of ML-processed images with no cloud description, oldest first (the IncludeExisting backfill). */
    fun getAssetsNeedingCloudDescription(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>> =
        kotlinx.coroutines.flow.flowOf(Either.Right(emptyList()))

    suspend fun countAssetsNeedingCloudDescription(): Either<DomainError, Long> = Either.Right(0L)

    /** Assets holding an AI (cloud) description; the "Delete AI descriptions" confirm shows this number. */
    suspend fun countCloudDescriptions(): Either<DomainError, Long> = Either.Right(0L)

    /** Clears every cloud description in one statement; OCR text and on-device labels stay. */
    @DirectRepositoryWrite
    suspend fun clearCloudDescriptions(): Either<DomainError, Unit> = Either.Right(Unit)
    suspend fun countAssets(): Either<DomainError, Long>

    // Keyset pagination — pass all cursor fields null for the first page.
    fun getAssetPage(
        mediaType: AssetMediaType?,
        searchQuery: String,
        sortOrder: AssetSortOrder,
        cursorMs: Long?,
        cursorName: String?,
        cursorSize: Long?,
        cursorUuid: String?,
        limit: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>>

    fun getOrphanedAssets(cursorMs: Long?, cursorUuid: String?, limit: Int): Flow<Either<DomainError, List<AssetEntry>>>
    suspend fun countOrphanedAssets(): Either<DomainError, Long>
    suspend fun getDistinctTags(): Either<DomainError, List<String>>

    /** Subset of [filePaths] that already have a row. Callers chunk to at most 500 paths. */
    suspend fun getIndexedFilePaths(filePaths: List<String>): Either<DomainError, Set<String>>

    /** Uuid of an indexed asset whose content hash equals [contentHash], or null. */
    suspend fun findUuidByContentHash(contentHash: String): Either<DomainError, AssetUuid?>

    @DirectRepositoryWrite
    suspend fun saveAsset(asset: AssetEntry): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateFilePath(uuid: AssetUuid, filePath: String, relativePath: String): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateTags(uuid: AssetUuid, tags: List<String>): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateAutoLabels(uuid: AssetUuid, autoLabels: List<String>, mlTagsSource: String): Either<DomainError, Unit>
    /** Re-classifies an indexed row (the hash-dedupe upgrade of an `IMAGE` row to `DRAWING`). */
    @DirectRepositoryWrite
    suspend fun updateMediaType(uuid: AssetUuid, mediaType: AssetMediaType): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateOcrText(uuid: AssetUuid, ocrText: String?): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateCloudDescription(uuid: AssetUuid, cloudDescription: String?, mlTagsSource: String): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun markMlProcessed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun markMlFailed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit>
    /** Clears the failed flag on every failed row so the next drain tries them again. */
    @DirectRepositoryWrite
    suspend fun resetFailedAssets(): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updatePageUuids(uuid: AssetUuid, pageUuids: List<String>): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun deleteAsset(uuid: AssetUuid): Either<DomainError, Unit>
}
