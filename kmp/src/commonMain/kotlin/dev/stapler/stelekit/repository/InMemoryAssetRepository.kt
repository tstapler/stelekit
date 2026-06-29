package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetSortOrder
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

@OptIn(DirectRepositoryWrite::class)
class InMemoryAssetRepository : AssetRepository {
    private val store = MutableStateFlow<Map<String, AssetEntry>>(emptyMap())

    override fun getAssetByUuid(uuid: AssetUuid): Flow<Either<DomainError, AssetEntry?>> =
        store.map { it[uuid.value].right() }

    @Suppress("InMemoryPagination")
    override fun getAssets(limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>> =
        store.map { map ->
            map.values
                .sortedByDescending { it.importedAtMs }
                .drop(offset)
                .take(limit)
                .right()
        }

    @Suppress("InMemoryPagination")
    override fun getAssetsByMediaType(
        mediaType: AssetMediaType,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> =
        store.map { map ->
            map.values
                .filter { it.mediaType == mediaType }
                .sortedByDescending { it.importedAtMs }
                .drop(offset)
                .take(limit)
                .right()
        }

    @Suppress("InMemoryPagination")
    override fun searchAssets(
        query: String,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> =
        store.map { map ->
            val q = query.lowercase()
            map.values
                .filter { asset ->
                    asset.filePath.lowercase().contains(q) ||
                        asset.tags.any { it.lowercase().contains(q) } ||
                        asset.autoLabels.any { it.lowercase().contains(q) } ||
                        asset.ocrText?.lowercase()?.contains(q) == true
                }
                .sortedByDescending { it.importedAtMs }
                .drop(offset)
                .take(limit)
                .right()
        }

    @Suppress("InMemoryPagination")
    override fun getUnprocessedAssets(
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> =
        store.map { map ->
            map.values
                .filter { !it.mlProcessed && !it.mlFailed }
                .sortedBy { it.importedAtMs }
                .drop(offset)
                .take(limit)
                .right()
        }

    override suspend fun countUnprocessedAssets(): Either<DomainError, Long> =
        store.value.values.count { !it.mlProcessed && !it.mlFailed }.toLong().right()

    override suspend fun countAssets(): Either<DomainError, Long> =
        store.value.size.toLong().right()

    override suspend fun saveAsset(asset: AssetEntry): Either<DomainError, Unit> {
        store.value = store.value + (asset.uuid.value to asset)
        return Unit.right()
    }

    override suspend fun updateFilePath(
        uuid: AssetUuid,
        filePath: String,
        relativePath: String,
    ): Either<DomainError, Unit> {
        val asset = store.value[uuid.value]
            ?: return DomainError.DatabaseError.ReadFailed("Asset not found: ${uuid.value}").left()
        store.value = store.value + (uuid.value to asset.copy(filePath = filePath, relativePath = relativePath))
        return Unit.right()
    }

    override suspend fun updateTags(uuid: AssetUuid, tags: List<String>): Either<DomainError, Unit> {
        val asset = store.value[uuid.value]
            ?: return DomainError.DatabaseError.ReadFailed("Asset not found: ${uuid.value}").left()
        store.value = store.value + (uuid.value to asset.copy(tags = tags))
        return Unit.right()
    }

    override suspend fun updateAutoLabels(
        uuid: AssetUuid,
        autoLabels: List<String>,
        mlTagsSource: String,
    ): Either<DomainError, Unit> {
        val asset = store.value[uuid.value]
            ?: return DomainError.DatabaseError.ReadFailed("Asset not found: ${uuid.value}").left()
        store.value = store.value + (uuid.value to asset.copy(autoLabels = autoLabels, mlTagsSource = mlTagsSource))
        return Unit.right()
    }

    override suspend fun updateOcrText(uuid: AssetUuid, ocrText: String?): Either<DomainError, Unit> {
        val asset = store.value[uuid.value]
            ?: return DomainError.DatabaseError.ReadFailed("Asset not found: ${uuid.value}").left()
        store.value = store.value + (uuid.value to asset.copy(ocrText = ocrText))
        return Unit.right()
    }

    override suspend fun updateCloudDescription(
        uuid: AssetUuid,
        cloudDescription: String?,
        mlTagsSource: String,
    ): Either<DomainError, Unit> {
        val asset = store.value[uuid.value]
            ?: return DomainError.DatabaseError.ReadFailed("Asset not found: ${uuid.value}").left()
        store.value = store.value + (uuid.value to asset.copy(cloudDescription = cloudDescription, mlTagsSource = mlTagsSource))
        return Unit.right()
    }

    override suspend fun markMlProcessed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit> {
        val asset = store.value[uuid.value]
            ?: return DomainError.DatabaseError.ReadFailed("Asset not found: ${uuid.value}").left()
        store.value = store.value + (uuid.value to asset.copy(mlProcessed = true, mlAttemptedAt = attemptedAtMs))
        return Unit.right()
    }

    override suspend fun markMlFailed(uuid: AssetUuid, attemptedAtMs: Long): Either<DomainError, Unit> {
        val asset = store.value[uuid.value]
            ?: return DomainError.DatabaseError.ReadFailed("Asset not found: ${uuid.value}").left()
        store.value = store.value + (uuid.value to asset.copy(mlFailed = true, mlAttemptedAt = attemptedAtMs))
        return Unit.right()
    }

    override suspend fun updatePageUuids(uuid: AssetUuid, pageUuids: List<String>): Either<DomainError, Unit> {
        val asset = store.value[uuid.value]
            ?: return DomainError.DatabaseError.ReadFailed("Asset not found: ${uuid.value}").left()
        store.value = store.value + (uuid.value to asset.copy(pageUuids = pageUuids))
        return Unit.right()
    }

    override suspend fun deleteAsset(uuid: AssetUuid): Either<DomainError, Unit> {
        store.value = store.value - uuid.value
        return Unit.right()
    }

    @Suppress("InMemoryPagination")
    override fun getAssetPage(
        mediaType: AssetMediaType?,
        searchQuery: String,
        sortOrder: AssetSortOrder,
        cursorMs: Long?,
        cursorName: String?,
        cursorSize: Long?,
        cursorUuid: String?,
        limit: Int,
    ): Flow<Either<DomainError, List<AssetEntry>>> = store.map { map ->
        var list = map.values.toList()

        if (mediaType != null) list = list.filter { it.mediaType == mediaType }

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            list = list.filter { asset ->
                asset.filePath.lowercase().contains(q) ||
                    asset.tags.any { it.lowercase().contains(q) } ||
                    asset.autoLabels.any { it.lowercase().contains(q) } ||
                    asset.ocrText?.lowercase()?.contains(q) == true
            }
        }

        list = when (sortOrder) {
            AssetSortOrder.BY_DATE_ADDED ->
                list.sortedWith(compareByDescending<AssetEntry> { it.importedAtMs }.thenByDescending { it.uuid.value })
            AssetSortOrder.BY_NAME ->
                list.sortedWith(compareBy<AssetEntry> { it.filePath }.thenBy { it.uuid.value })
            AssetSortOrder.BY_SIZE ->
                list.sortedWith(compareByDescending<AssetEntry> { it.sizeBytes }.thenBy { it.uuid.value })
        }

        // Apply keyset cursor — mirrors the COALESCE sentinel logic in the SQL queries.
        list = when (sortOrder) {
            AssetSortOrder.BY_DATE_ADDED -> {
                val cursor = cursorMs ?: Long.MAX_VALUE
                list.filter { it.importedAtMs < cursor }
            }
            AssetSortOrder.BY_NAME -> {
                val cName = cursorName ?: ""
                val cUuid = cursorUuid ?: ""
                // COALESCE('') ensures file_path > '' is true for all real paths (first page).
                list.filter { asset ->
                    asset.filePath > cName || (asset.filePath == cName && asset.uuid.value > cUuid)
                }
            }
            AssetSortOrder.BY_SIZE -> {
                val cSize = cursorSize ?: Long.MAX_VALUE
                val cUuid = cursorUuid ?: ""
                list.filter { asset ->
                    asset.sizeBytes < cSize || (asset.sizeBytes == cSize && asset.uuid.value > cUuid)
                }
            }
        }

        list.take(limit).right()
    }

    @Suppress("InMemoryPagination")
    override fun getOrphanedAssets(cursorMs: Long?, cursorUuid: String?, limit: Int): Flow<Either<DomainError, List<AssetEntry>>> =
        store.map { map ->
            val cursor = cursorMs ?: Long.MAX_VALUE
            map.values
                .filter { it.isOrphan &&
                    (it.importedAtMs < cursor ||
                     (it.importedAtMs == cursor && cursorUuid != null && it.uuid.value < cursorUuid)) }
                .sortedWith(compareByDescending<AssetEntry> { it.importedAtMs }.thenByDescending { it.uuid.value })
                .take(limit)
                .right()
        }

    override suspend fun countOrphanedAssets(): Either<DomainError, Long> =
        store.value.values.count { it.isOrphan }.toLong().right()

    override suspend fun getDistinctTags(): Either<DomainError, List<String>> =
        store.value.values
            .flatMap { it.tags }
            .distinct()
            .sorted()
            .right()
}
