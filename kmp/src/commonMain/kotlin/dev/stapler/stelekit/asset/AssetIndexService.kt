package dev.stapler.stelekit.asset
import dev.stapler.stelekit.model.EffectiveNotesPath

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.UuidGenerator
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Progress of the index walk: files found vs files processed (not the plugin drain's backlog). */
sealed interface AssetIndexProgress {
    data object Idle : AssetIndexProgress
    data object Counting : AssetIndexProgress
    data class Indexing(val done: Int, val total: Int) : AssetIndexProgress
    data class Finished(val total: Int) : AssetIndexProgress
}

/**
 * Populates `asset_index` from the files under `<notesPath>/assets/` and from newly attached files.
 *
 * Memory bounds: the backfill walks the tree lazily (one directory listing at a time, never a list
 * of every path), processes at most [BACKFILL_BATCH_SIZE] files per batch, looks up already-indexed
 * paths with one bounded `IN` projection per batch, and reads at most [MAX_HASHED_FILE_BYTES] of any
 * single file (larger files are indexed by name and size only). All writes go through the
 * [DatabaseWriteActor].
 */
class AssetIndexService(
    private val assetRepository: AssetRepository,
) {
    private val logger = Logger("AssetIndexService")
    private val scope = CoroutineScope(
        SupervisorJob() + PlatformDispatcher.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error("AssetIndexService uncaught: ${throwable.message}")
            }
    )
    private var backfillJob: Job? = null

    private val _progress = MutableStateFlow<AssetIndexProgress>(AssetIndexProgress.Idle)
    val progress: StateFlow<AssetIndexProgress> = _progress.asStateFlow()

    /**
     * Registers one attached file. Idempotent on content hash: attaching bytes that are already
     * indexed returns the existing row instead of adding a second one.
     */
    suspend fun registerAsset(
        filePath: String,
        notesPath: EffectiveNotesPath,
        mimeHint: String? = null,
        pageUuid: String? = null,
        writeActor: DatabaseWriteActor?,
        fileSystem: FileSystem,
    ): Either<DomainError, AssetEntry> {
        val entry = buildAssetEntry(filePath, notesPath, mimeHint, pageUuid, fileSystem)
        val hash = entry.contentHash
        if (hash != null) {
            val existing = assetRepository.findUuidByContentHash(hash).getOrNull()
            if (existing != null) {
                val row = assetRepository.getAssetByUuidOnce(existing)
                if (row != null) return row.right()
            }
        }
        val result = if (writeActor != null) {
            writeActor.execute { @OptIn(DirectRepositoryWrite::class) assetRepository.saveAsset(entry) }
        } else {
            @OptIn(DirectRepositoryWrite::class) assetRepository.saveAsset(entry)
        }
        return result.map { entry }
    }

    private fun buildAssetEntry(
        filePath: String,
        notesPath: EffectiveNotesPath,
        mimeHint: String? = null,
        pageUuid: String? = null,
        fileSystem: FileSystem,
    ): AssetEntry {
        val filename = filePath.substringAfterLast('/')
        val knownSize = try { fileSystem.getFileSize(filePath) } catch (_: Exception) { null }
        val fileBytes = if (knownSize != null && knownSize > MAX_HASHED_FILE_BYTES) {
            null
        } else {
            try { fileSystem.readFileBytes(filePath) } catch (_: Exception) { null }
        }
        val head = fileBytes?.copyOf(minOf(MIME_SNIFF_BYTES, fileBytes.size)) ?: ByteArray(0)
        val mime = mimeHint ?: MimeTypeDetector.detect(head, filename)
        val mediaType = AssetMediaType.fromMimeType(mime)
        val subfolder = AssetStoragePathResolver.resolveSubfolder(mime)
        // The row mirrors where the file really is (attachments land flat in assets/), so a later
        // move rewrites the reference the pages actually contain.
        val notesPrefix = notesPath.directory() + "/"
        val relativePath = if (filePath.startsWith(notesPrefix)) "../" + filePath.removePrefix(notesPrefix)
        else AssetStoragePathResolver.relativeMarkdownPath(subfolder, filename)
        return AssetEntry(
            uuid = AssetUuid(UuidGenerator.generateV7()),
            filePath = filePath,
            relativePath = relativePath,
            mediaType = mediaType,
            subfolder = subfolder,
            tags = emptyList(),
            autoLabels = emptyList(),
            ocrText = null,
            cloudDescription = null,
            pageUuids = listOfNotNull(pageUuid),
            sizeBytes = fileBytes?.size?.toLong() ?: knownSize ?: 0L,
            importedAtMs = Clock.System.now().toEpochMilliseconds(),
            mlProcessed = false,
            mlAttemptedAt = null,
            mlFailed = false,
            contentHash = fileBytes?.let { ContentHasher.sha256(it) },
            isOrphan = false,
            mlTagsSource = "NONE",
        )
    }

    /**
     * Scans `<notesPath>/assets/` recursively in bounded batches (<= [BACKFILL_BATCH_SIZE] per
     * batch), skipping already-indexed files and registering new ones. Non-blocking; a second call
     * replaces a run still in flight.
     */
    fun startBackfill(
        notesPath: EffectiveNotesPath,
        writeActor: DatabaseWriteActor?,
        fileSystem: FileSystem,
    ) {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            try {
                backfillGraph(notesPath, writeActor, fileSystem)
            } catch (e: CancellationException) {
                _progress.value = AssetIndexProgress.Idle
                throw e
            } catch (e: Throwable) {
                logger.error("Backfill failed: ${e.message}")
                _progress.value = AssetIndexProgress.Idle
            }
        }
    }

    /** Cancels a backfill in flight; already-saved batches stay. */
    fun stopBackfill() {
        backfillJob?.cancel()
        backfillJob = null
    }

    /** Cancels everything and releases the scope; the service is unusable afterwards. */
    fun close() {
        scope.cancel()
    }

    private suspend fun backfillGraph(
        notesPath: EffectiveNotesPath,
        writeActor: DatabaseWriteActor?,
        fileSystem: FileSystem,
    ) {
        val assetsDir = notesPath.child("assets")
        if (!fileSystem.directoryExists(assetsDir)) return

        _progress.value = AssetIndexProgress.Counting
        val total = walkFiles(assetsDir, fileSystem).count()
        var done = 0
        _progress.value = AssetIndexProgress.Indexing(done, total)
        for (batch in walkFiles(assetsDir, fileSystem).chunked(BACKFILL_BATCH_SIZE)) {
            processBatch(batch, notesPath, writeActor, fileSystem)
            done += batch.size
            _progress.value = AssetIndexProgress.Indexing(done, maxOf(total, done))
            yield()
        }
        _progress.value = AssetIndexProgress.Finished(done)
    }

    private suspend fun processBatch(
        filePaths: List<String>,
        notesPath: EffectiveNotesPath,
        writeActor: DatabaseWriteActor?,
        fileSystem: FileSystem,
    ) {
        val indexed = assetRepository.getIndexedFilePaths(filePaths).getOrNull().orEmpty()
        // Build all entries first (file IO + mime detection), then save in one actor round-trip
        val entries = mutableListOf<AssetEntry>()
        for (filePath in filePaths.filter { it !in indexed }) {
            try {
                entries.add(buildAssetEntry(filePath, notesPath, null, null, fileSystem))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Failed to build asset entry for '$filePath': ${e.message}")
            }
        }
        if (entries.isEmpty()) return
        if (writeActor != null) {
            writeActor.execute {
                @OptIn(DirectRepositoryWrite::class)
                for (entry in entries) assetRepository.saveAsset(entry)
                Unit.right()
            }.onLeft { e -> logger.error("Batch asset save failed (${entries.size} entries): ${e.message}") }
        } else {
            for (entry in entries) {
                @OptIn(DirectRepositoryWrite::class)
                assetRepository.saveAsset(entry)
            }
        }
    }

    companion object {
        const val BACKFILL_BATCH_SIZE = 50
        private const val MIME_SNIFF_BYTES = 16

        /** Files above this are indexed by name and size only (no content read, no hash). */
        const val MAX_HASHED_FILE_BYTES = 8L * 1024 * 1024

        /**
         * Lazy depth-first walk: holds one directory's entry names at a time plus the stack of
         * unvisited sibling directories, never the whole tree's file list.
         */
        internal fun walkFiles(root: String, fileSystem: FileSystem): Sequence<String> = sequence {
            val pending = ArrayDeque<String>()
            pending.addLast(root)
            while (pending.isNotEmpty()) {
                val dir = pending.removeLast()
                val files = try { fileSystem.listFiles(dir) } catch (_: Exception) { emptyList() }
                for (file in files) yield("$dir/$file")
                val subDirs = try { fileSystem.listDirectories(dir) } catch (_: Exception) { emptyList() }
                for (subDir in subDirs) pending.addLast("$dir/$subDir")
            }
        }
    }
}

private suspend fun AssetRepository.getAssetByUuidOnce(uuid: AssetUuid): AssetEntry? =
    getAssetByUuid(uuid).first().getOrNull()
