@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.repository.SqlDelightImageAnnotationRepository
import dev.stapler.stelekit.repository.SqlDelightLibraryRepository
import dev.stapler.stelekit.repository.SqlDelightMeasurementAnnotationRepository

/** What to annotate an image asset with. [pages] > 1 creates that many annotation rows for the one file. */
data class AnnotationSpec(val tags: List<String> = emptyList(), val labels: List<String?> = emptyList(), val pages: Int = 1, val annotatedAtMs: Long = 0L)

/**
 * Real SQLDelight (in-memory SQLite) with the Library read path wired, plus the reference records built from
 * the same inserts so SQL results can be compared with [LibraryReference].
 */
class LibraryFixture {
    val driver: SqlDriver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    private val db = SteleDatabase(driver)
    val assets = SqlDelightAssetRepository(db)
    val annotations = SqlDelightImageAnnotationRepository(db)
    val measurements = SqlDelightMeasurementAnnotationRepository(db)
    val library = SqlDelightLibraryRepository(db, driver)
    val records = mutableListOf<LibraryRecord>()

    fun close() = driver.close()

    private fun id(prefix: String, n: Int) = "$prefix-${n.toString().padStart(6, '0')}"

    suspend fun add(
        n: Int,
        fileName: String,
        type: AssetMediaType,
        importedAtMs: Long,
        orphan: Boolean = false,
        annotation: AnnotationSpec? = null,
        sizeBytes: Long = 100L + n,
    ) {
        val uuid = id("asset", n)
        val path = "/g/assets/${type.name.lowercase()}/$fileName"
        assets.saveAsset(
            AssetEntry(
                uuid = AssetUuid(uuid), filePath = path, relativePath = "../assets/${type.name.lowercase()}/$fileName",
                mediaType = type, subfolder = "files", tags = emptyList(), autoLabels = emptyList(), ocrText = null,
                cloudDescription = null, pageUuids = if (orphan) emptyList() else listOf("page-1"), sizeBytes = sizeBytes,
                importedAtMs = importedAtMs, mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
                contentHash = null, isOrphan = false, mlTagsSource = "NONE",
            ),
        )
        if (orphan) {
            driver.execute(null, "UPDATE asset_index SET is_orphan = 1 WHERE uuid = ?", 1) { bindString(0, uuid) }.await()
        }
        var count = 0
        var lastAt: Long? = null
        val allTags = mutableListOf<String>()
        val allLabels = mutableListOf<String>()
        var firstAnnotation: String? = null
        if (annotation != null) {
            repeat(annotation.pages) { p ->
                val annUuid = id("ann", n * 10 + p)
                if (firstAnnotation == null || annUuid < firstAnnotation!!) firstAnnotation = annUuid
                annotations.saveImageAnnotation(
                    ImageAnnotation(
                        uuid = annUuid, blockUuid = id("blk", n * 10 + p), pageUuid = id("page", p + 1), graphPath = "/g",
                        filePath = path, importedAtMs = annotation.annotatedAtMs + p, tags = annotation.tags,
                    ),
                )
                lastAt = maxOf(lastAt ?: Long.MIN_VALUE, annotation.annotatedAtMs + p)
                allTags += annotation.tags
                annotation.labels.forEachIndexed { i, label ->
                    measurements.saveMeasurementAnnotation(
                        MeasurementAnnotation(
                            uuid = id("m", n * 100 + p * 10 + i), imageUuid = annUuid, annotationType = AnnotationType.DISTANCE,
                            normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.9, 0.9)), label = label,
                        ),
                    )
                    count++
                    if (label != null) allLabels += label
                }
            }
        }
        records += LibraryRecord(
            item = LibraryItem(
                id = uuid, filePath = path, fileName = fileName, mediaType = type.name, sizeBytes = sizeBytes,
                importedAtMs = importedAtMs, isOrphan = orphan, pageUuids = if (orphan) emptyList() else listOf("page-1"),
                annotated = annotation != null, measurementCount = count, lastAnnotatedAtMs = lastAt,
                annotationUuid = firstAnnotation, thumbnailPath = null, relativePath = "../assets/${type.name.lowercase()}/$fileName",
            ),
            annotationTags = allTags,
            measurementLabels = allLabels,
        )
    }
}
