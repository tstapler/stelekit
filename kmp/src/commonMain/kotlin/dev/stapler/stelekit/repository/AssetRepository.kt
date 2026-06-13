package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
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

    suspend fun saveAsset(asset: AssetEntry): Either<DomainError, Unit>
    suspend fun updateFilePath(uuid: AssetUuid, filePath: String, relativePath: String): Either<DomainError, Unit>
    suspend fun updateTags(uuid: AssetUuid, tags: List<String>): Either<DomainError, Unit>
    suspend fun updateAutoLabels(uuid: AssetUuid, autoLabels: List<String>, mlTagsSource: String): Either<DomainError, Unit>
    suspend fun updateOcrText(uuid: AssetUuid, ocrText: String?): Either<DomainError, Unit>
    suspend fun updateCloudDescription(uuid: AssetUuid, cloudDescription: String?, mlTagsSource: String): Either<DomainError, Unit>
    suspend fun markMlProcessed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit>
    suspend fun markMlFailed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit>
    suspend fun updatePageUuids(uuid: AssetUuid, pageUuids: List<String>): Either<DomainError, Unit>
    suspend fun deleteAsset(uuid: AssetUuid): Either<DomainError, Unit>
}
