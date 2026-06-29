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
    fun getUnprocessedAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>>
    suspend fun countUnprocessedAssets(): Either<DomainError, Long>
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

    fun getOrphanedAssets(cursorMs: Long?, limit: Int): Flow<Either<DomainError, List<AssetEntry>>>
    suspend fun countOrphanedAssets(): Either<DomainError, Long>
    suspend fun getDistinctTags(): Either<DomainError, List<String>>

    @DirectRepositoryWrite
    suspend fun saveAsset(asset: AssetEntry): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateFilePath(uuid: AssetUuid, filePath: String, relativePath: String): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateTags(uuid: AssetUuid, tags: List<String>): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateAutoLabels(uuid: AssetUuid, autoLabels: List<String>, mlTagsSource: String): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateOcrText(uuid: AssetUuid, ocrText: String?): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updateCloudDescription(uuid: AssetUuid, cloudDescription: String?, mlTagsSource: String): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun markMlProcessed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun markMlFailed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun updatePageUuids(uuid: AssetUuid, pageUuids: List<String>): Either<DomainError, Unit>
    @DirectRepositoryWrite
    suspend fun deleteAsset(uuid: AssetUuid): Either<DomainError, Unit>
}
