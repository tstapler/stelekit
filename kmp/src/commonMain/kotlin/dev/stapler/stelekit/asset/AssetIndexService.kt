package dev.stapler.stelekit.asset

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.util.UuidGenerator
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class AssetIndexService(
    private val assetRepository: AssetRepository,
) {
    private val logger = Logger("AssetIndexService")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error("AssetIndexService uncaught: ${throwable.message}")
            }
    )

    @Suppress("UnusedParameter")
    @OptIn(DirectRepositoryWrite::class)
    suspend fun registerAsset(
        filePath: String,
        graphRoot: String,
        mimeHint: String? = null,
        pageUuid: String? = null,
        writeActor: DatabaseWriteActor?,
        fileSystem: FileSystem,
    ): Either<DomainError, AssetEntry> {
        val filename = filePath.substringAfterLast('/')
        val fileBytes = try {
            fileSystem.readFileBytes(filePath)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val bytes = fileBytes?.take(16)?.toByteArray() ?: ByteArray(0)
        val mime = mimeHint ?: MimeTypeDetector.detect(bytes, filename)
        val mediaType = AssetMediaType.fromMimeType(mime)
        val subfolder = AssetStoragePathResolver.resolveSubfolder(mime)
        val relativePath = AssetStoragePathResolver.relativeMarkdownPath(subfolder, filename)
        val sizeBytes = fileBytes?.size?.toLong() ?: 0L
        val uuid = AssetUuid(UuidGenerator.generateV7())
        val entry = AssetEntry(
            uuid = uuid,
            filePath = filePath,
            relativePath = relativePath,
            mediaType = mediaType,
            subfolder = subfolder,
            tags = emptyList(),
            autoLabels = emptyList(),
            ocrText = null,
            cloudDescription = null,
            pageUuids = listOfNotNull(pageUuid),
            sizeBytes = sizeBytes,
            importedAtMs = Clock.System.now().toEpochMilliseconds(),
            mlProcessed = false,
            mlAttemptedAt = null,
            mlFailed = false,
            contentHash = null,
            isOrphan = false,
            mlTagsSource = "NONE",
        )
        val result = if (writeActor != null) {
            writeActor.execute { assetRepository.saveAsset(entry) }
        } else {
            assetRepository.saveAsset(entry)
        }
        return result.map { entry }
    }

    /**
     * Scans `<graphRoot>/assets/` recursively in bounded batches (<= 50 per batch),
     * skipping already-indexed files, registering new ones.
     * Launched as a non-blocking background job.
     */
    fun startBackfill(
        graphRoot: String,
        writeActor: DatabaseWriteActor?,
        fileSystem: FileSystem,
    ) {
        scope.launch {
            try {
                backfillGraph(graphRoot, writeActor, fileSystem)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Backfill failed: ${e.message}")
            }
        }
    }

    private suspend fun backfillGraph(
        graphRoot: String,
        writeActor: DatabaseWriteActor?,
        fileSystem: FileSystem,
    ) {
        val assetsDir = "$graphRoot/assets"
        if (!fileSystem.directoryExists(assetsDir)) return

        val allFiles = collectFilesRecursively(assetsDir, fileSystem)

        var offset = 0
        while (offset < allFiles.size) {
            val batch = allFiles.subList(offset, minOf(offset + BACKFILL_BATCH_SIZE, allFiles.size))
            processBatch(batch, graphRoot, writeActor, fileSystem)
            offset += BACKFILL_BATCH_SIZE
            yield()
        }
    }

    private fun collectFilesRecursively(dir: String, fileSystem: FileSystem): List<String> {
        val result = mutableListOf<String>()
        try {
            val files = fileSystem.listFiles(dir)
            for (file in files) {
                result.add("$dir/$file")
            }
            val subDirs = fileSystem.listDirectories(dir)
            for (subDir in subDirs) {
                result.addAll(collectFilesRecursively("$dir/$subDir", fileSystem))
            }
        } catch (_: Exception) {}
        if (result.size > 1000) {
            logger.warn("collectFilesRecursively: found ${result.size} files in '$dir' — large trees load entirely into memory before batching")
        }
        return result
    }

    private suspend fun processBatch(
        filePaths: List<String>,
        graphRoot: String,
        writeActor: DatabaseWriteActor?,
        fileSystem: FileSystem,
    ) {
        for (filePath in filePaths) {
            try {
                registerAsset(
                    filePath = filePath,
                    graphRoot = graphRoot,
                    mimeHint = null,
                    pageUuid = null,
                    writeActor = writeActor,
                    fileSystem = fileSystem,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Failed to index asset '$filePath': ${e.message}")
            }
        }
    }

    companion object {
        const val BACKFILL_BATCH_SIZE = 50
    }
}
