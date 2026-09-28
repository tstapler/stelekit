package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetSortOrder
import dev.stapler.stelekit.asset.AssetUuid
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * SQLDelight-backed [AssetRepository].
 *
 * All reads use [PlatformDispatcher.DB] via [asDbFlowList]/[asDbFlowOrNull].
 * All writes use [withContext] with [PlatformDispatcher.DB].
 *
 * Follows the same pattern as [SqlDelightImageAnnotationRepository]: calls steleDatabaseQueries
 * directly (not RestrictedDatabaseQueries, which is the actor-internal gate).
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightAssetRepository(
    private val database: SteleDatabase,
) : AssetRepository {

    private val queries = database.steleDatabaseQueries
    private val json = Json { ignoreUnknownKeys = true }

    private fun List<String>.toJson(): String =
        json.encodeToString(ListSerializer(String.serializer()), this)

    private fun String.fromJsonList(): List<String> =
        try { json.decodeFromString(ListSerializer(String.serializer()), this) }
        catch (_: Exception) { emptyList() }

    private fun dev.stapler.stelekit.db.Asset_index.toModel(): AssetEntry = AssetEntry(
        uuid = AssetUuid(uuid),
        filePath = file_path,
        relativePath = relative_path,
        mediaType = AssetMediaType.valueOf(media_type),
        subfolder = subfolder,
        tags = tags.fromJsonList(),
        autoLabels = auto_labels.fromJsonList(),
        ocrText = ocr_text,
        cloudDescription = cloud_description,
        pageUuids = page_uuids.fromJsonList(),
        sizeBytes = size_bytes,
        importedAtMs = imported_at_ms,
        mlProcessed = ml_processed != 0L,
        mlAttemptedAt = ml_attempted_at,
        mlFailed = ml_failed != 0L,
        contentHash = content_hash,
        isOrphan = is_orphan != 0L,
        mlTagsSource = ml_tags_source,
    )

    override fun getAssetByUuid(uuid: AssetUuid): Flow<Either<DomainError, AssetEntry?>> =
        queries.selectAssetByUuid(uuid.value)
            .asDbFlowOrNull(PlatformDispatcher.DB) { it.toModel() }

    override fun getAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>> =
        queries.selectAssets(limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getAssetsByMediaType(
        mediaType: AssetMediaType,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> =
        queries.selectAssetsByMediaType(mediaType.name, limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun searchAssets(
        query: String,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return queries.searchAssets("%$escaped%", limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }
    }

    override fun getUnprocessedAssets(
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> =
        queries.selectUnprocessedAssets(limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override suspend fun countUnprocessedAssets(): Either<DomainError, Long> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.countUnprocessedAssets().asFlow().mapToOne(PlatformDispatcher.DB).first().right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun countAssets(): Either<DomainError, Long> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.countAssets().asFlow().mapToOne(PlatformDispatcher.DB).first().right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun saveAsset(asset: AssetEntry): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.insertAssetOrIgnore(
                    uuid = asset.uuid.value,
                    file_path = asset.filePath,
                    relative_path = asset.relativePath,
                    media_type = asset.mediaType.name,
                    subfolder = asset.subfolder,
                    tags = asset.tags.toJson(),
                    auto_labels = asset.autoLabels.toJson(),
                    ocr_text = asset.ocrText,
                    cloud_description = asset.cloudDescription,
                    page_uuids = asset.pageUuids.toJson(),
                    size_bytes = asset.sizeBytes,
                    imported_at_ms = asset.importedAtMs,
                    content_hash = asset.contentHash,
                )
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun updateFilePath(
        uuid: AssetUuid,
        filePath: String,
        relativePath: String,
    ): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.updateAssetFilePath(filePath, relativePath, uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun updateTags(uuid: AssetUuid, tags: List<String>): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.updateAssetTags(tags.toJson(), uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun updateAutoLabels(
        uuid: AssetUuid,
        autoLabels: List<String>,
        mlTagsSource: String,
    ): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.updateAssetAutoLabels(autoLabels.toJson(), mlTagsSource, uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun updateOcrText(uuid: AssetUuid, ocrText: String?): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.updateAssetOcrText(ocrText, uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun updateCloudDescription(
        uuid: AssetUuid,
        cloudDescription: String?,
        mlTagsSource: String,
    ): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.updateAssetCloudDescription(cloudDescription, mlTagsSource, uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun markMlProcessed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.markAssetMlProcessed(attemptedAtMs, uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun markMlFailed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.markAssetMlFailed(attemptedAtMs, uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun updatePageUuids(uuid: AssetUuid, pageUuids: List<String>): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.updateAssetPageUuids(pageUuids.toJson(), uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    @DirectRepositoryWrite
    override suspend fun deleteAsset(uuid: AssetUuid): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.deleteAsset(uuid.value)
                Unit.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    override fun getAssetPage(
        mediaType: AssetMediaType?,
        searchQuery: String,
        sortOrder: AssetSortOrder,
        cursorMs: Long?,
        cursorName: String?,
        cursorSize: Long?,
        cursorUuid: String?,
        limit: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> {
        val lim = limit.toLong()
        return when {
            searchQuery.isNotBlank() -> {
                val escaped = searchQuery
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                queries.selectAssetsBySearchByDateKeyset("%$escaped%", cursorMs, cursorUuid, lim)
            }
            mediaType != null -> when (sortOrder) {
                AssetSortOrder.BY_DATE_ADDED ->
                    queries.selectAssetsByMediaTypeByDateKeyset(mediaType.name, cursorMs, cursorUuid, lim)
                AssetSortOrder.BY_NAME ->
                    queries.selectAssetsByMediaTypeByNameKeyset(mediaType.name, cursorName, cursorUuid, lim)
                AssetSortOrder.BY_SIZE ->
                    queries.selectAssetsByMediaTypeBySizeKeyset(mediaType.name, cursorSize, cursorUuid, lim)
            }
            else -> when (sortOrder) {
                AssetSortOrder.BY_DATE_ADDED -> queries.selectAssetsByDateKeyset(cursorMs, cursorUuid, lim)
                AssetSortOrder.BY_NAME -> queries.selectAssetsByNameKeyset(cursorName, cursorUuid, lim)
                AssetSortOrder.BY_SIZE -> queries.selectAssetsBySizeKeyset(cursorSize, cursorUuid, lim)
            }
        }.asDbFlowList(PlatformDispatcher.DB) { it.toModel() }
    }

    override fun getOrphanedAssets(cursorMs: Long?, cursorUuid: String?, limit: Int): Flow<Either<DomainError, List<AssetEntry>>> =
        queries.selectOrphanedAssets(cursorMs, cursorUuid, limit.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override suspend fun countOrphanedAssets(): Either<DomainError, Long> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.countOrphanedAssets().asFlow().mapToOne(PlatformDispatcher.DB).first().right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun getDistinctTags(): Either<DomainError, List<String>> =
        withContext(PlatformDispatcher.DB) {
            try {
                val rawList = queries.selectAllTagsJson().asFlow().mapToList(PlatformDispatcher.DB).first()
                val tags = rawList
                    .flatMap { tagsJson -> tagsJson.fromJsonList() }
                    .distinct()
                    .sorted()
                tags.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
            }
        }
}
