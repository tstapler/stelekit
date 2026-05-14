package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import kotlin.concurrent.Volatile
import arrow.fx.coroutines.resource
import arrow.resilience.saga
import arrow.resilience.transact
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.FileUtils
import dev.stapler.stelekit.vault.CryptoLayer
import dev.stapler.stelekit.vault.VaultError
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles writing page and block changes back to markdown files.
 * Supports debounced auto-save to avoid excessive disk writes.
 *
 * Updated to use UUID-native storage and safety checks for large deletions.
 *
 * The multi-step save pipeline is modelled as an Arrow Saga for transactional rollback:
 * if the DB update fails after the file has been written, the file content is restored.
 *
 * Paranoid-mode invariant: [cryptoLayer] must be set to a [CryptoLayer] initialized with the
 * current DEK before any write that should be encrypted. Setting it to null switches the writer
 * back to plaintext mode (used when the vault is locked or the graph is not paranoid-mode).
 * The caller (typically [GraphManager]) is responsible for keeping [cryptoLayer] in sync with
 * the vault's lock/unlock lifecycle — stale [CryptoLayer] references after a DEK rotation will
 * silently produce ciphertext the new key cannot decrypt.
 */
class GraphWriter(
    private val fileSystem: FileSystem,
    private val writeActor: DatabaseWriteActor? = null,
    private val onFileWritten: (suspend (filePath: String) -> Unit)? = null,
    @Deprecated("Use writeActor instead", level = DeprecationLevel.WARNING)
    private val pageRepository: PageRepository? = null,
    private val sidecarManager: SidecarManager? = null,
    /** When non-null, all file writes are encrypted via paranoid-mode before hitting disk. */
    @Volatile var cryptoLayer: CryptoLayer? = null,
    /** Graph root path — required to compute graph-root-relative AAD paths for encryption. */
    @Volatile var graphPath: String = "",
) {
    private val logger = Logger("GraphWriter")
    private val saveMutex = Mutex()

    data class SaveRequest(
        val page: Page,
        val blocks: List<Block>,
        val graphPath: String
    )

    // Per-page debounce: pageUuid → pending Job + latest request
    private val pendingByPage = mutableMapOf<String, Pair<Job, SaveRequest>>()
    private val pendingMutex = Mutex()
    private var scope: CoroutineScope? = null
    private val debounceMs: Long = 500L

    // Safety check: if more than this many blocks are deleted, require confirmation
    private val largeDeletionThreshold = 50

    /**
     * Start the auto-save processor.
     * Call this once when the application starts.
     */
    fun startAutoSave(scope: CoroutineScope, debounceMs: Long = this.debounceMs) {
        this.scope = scope
    }

    /**
     * Flush all pending saves to disk immediately (e.g. on app pause/shutdown).
     */
    suspend fun flush() {
        val pending = pendingMutex.withLock {
            val snapshot = pendingByPage.values.map { (job, req) -> job to req }
            pendingByPage.values.forEach { (job, _) -> job.cancel() }
            pendingByPage.clear()
            snapshot.map { it.second }
        }
        pending.forEach { request ->
            val success = savePageInternal(request.page, request.blocks, request.graphPath)
            if (success) {
                logger.info("Flushed page: ${request.page.name}")
            }
        }
    }

    private suspend fun saveImmediately(request: SaveRequest) {
        val success = savePageInternal(request.page, request.blocks, request.graphPath)
        if (success) {
            logger.info("Saved page: ${request.page.name}")
        }
    }

    /**
     * Stop the auto-save processor and flush remaining saves.
     */
    fun stopAutoSave() {
        scope = null
    }

    /**
     * Queue a page for debounced saving. Per-page debounce: only the latest
     * save request for a given page fires, preventing cross-page save drops.
     */
    suspend fun queueSave(page: Page, blocks: List<Block>, graphPath: String) {
        val currentScope = scope ?: return
        val request = SaveRequest(page, blocks, graphPath)
        pendingMutex.withLock {
            pendingByPage[page.uuid]?.first?.cancel()
            val job = currentScope.launch {
                delay(debounceMs)
                pendingMutex.withLock { pendingByPage.remove(page.uuid) }
                saveImmediately(request)
            }
            pendingByPage[page.uuid] = job to request
        }
    }

    /**
     * Immediately save a page (bypasses debouncing).
     */
    suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String) {
        savePageInternal(page, blocks, graphPath)
    }

    /**
     * Rename a page file.
     * Calculates the new file path based on the new name and moves the file.
     * Returns true if successful, false otherwise.
     */
    suspend fun renamePage(page: Page, newName: String, graphPath: String): Boolean = saveMutex.withLock {
        val oldPath = page.filePath
        if (oldPath.isNullOrBlank()) {
            logger.error("Cannot rename page with no file path: ${page.name}")
            return@withLock false
        }

        // Guard: cannot rename pages in the hidden-volume reserve area
        val renameLayer = cryptoLayer
        val renameGraphPath = this.graphPath
        if (renameLayer != null && renameGraphPath.isEmpty()) {
            logger.error("renamePage aborted — cryptoLayer is set but graphPath is empty (AAD would be wrong)")
            return@withLock false
        }
        if (renameLayer != null) {
            if (renameLayer.checkNotHiddenReserve(relativeFilePath(oldPath, renameGraphPath)).isLeft()) {
                logger.error("Rename blocked — restricted path: $oldPath")
                return@withLock false
            }
        }

        // Calculate new path using the already-captured renameLayer snapshot
        val newPath = getPageFilePath(page.copy(name = newName), graphPath, renameLayer)

        // If paths are same, nothing to do (except maybe case change on some FS)
        if (oldPath == newPath) return true

        // When encryption is active, re-encrypt with the new path as AAD rather than copying
        // raw ciphertext — the AEAD tag binds the old path, so a verbatim copy would be
        // permanently unreadable at the new location.
        // Use the already-captured renameLayer snapshot — re-reading cryptoLayer here could
        // observe null if the vault is locked between the guard check and this point.
        val cryptoLayerNow = renameLayer
        val writeOk = if (cryptoLayerNow != null) {
            val bytes = fileSystem.readFileBytes(oldPath)
            if (bytes == null) {
                logger.error("Failed to read file bytes for rename: $oldPath")
                return false
            }
            val oldRelPath = relativeFilePath(oldPath, renameGraphPath)
            val newRelPath = relativeFilePath(newPath, renameGraphPath)
            val plaintext = when (val result = cryptoLayerNow.decrypt(oldRelPath, bytes)) {
                is Either.Right -> result.value
                is Either.Left -> {
                    logger.error("Failed to decrypt file for rename: $oldPath (${result.value})")
                    return false
                }
            }
            try {
                fileSystem.writeFileBytes(newPath, cryptoLayerNow.encrypt(newRelPath, plaintext))
            } finally {
                plaintext.fill(0)
            }
        } else {
            val content = fileSystem.readFile(oldPath)
            if (content == null) {
                logger.error("Failed to read file for rename: $oldPath")
                return false
            }
            fileSystem.writeFile(newPath, content)
        }

        if (writeOk) {
            onFileWritten?.invoke(oldPath)   // mark old path as our own deletion
            if (fileSystem.deleteFile(oldPath)) {
                // Notify the file watcher so it registers the new path and does not treat
                // the newly-created file as an external change on the next poll tick.
                onFileWritten?.invoke(newPath)
                logger.debug("Renamed page from $oldPath to $newPath")
                return true
            } else {
                logger.error("Failed to delete old file after copy: $oldPath")
                return false
            }
        } else {
            logger.error("Failed to write new file during rename: $newPath")
            return false
        }
    }

    /**
     * Delete a page file.
     */
    suspend fun deletePage(page: Page): Boolean = saveMutex.withLock {
        val path = page.filePath
        if (path.isNullOrBlank()) {
            logger.error("Cannot delete page with no file path: ${page.name}")
            return false
        }

        // Guard: cannot delete pages in the hidden-volume reserve area
        val deleteLayer = cryptoLayer
        val deleteGraphPath = this.graphPath
        if (deleteLayer != null && deleteLayer.checkNotHiddenReserve(relativeFilePath(path, deleteGraphPath)).isLeft()) {
            logger.error("Delete blocked — restricted path: $path")
            return false
        }

        onFileWritten?.invoke(path)   // pre-register deletion so file watcher ignores own-delete event
        val success = fileSystem.deleteFile(path)
        if (success) {
            logger.debug("Deleted page file: $path")
        } else {
            logger.error("Failed to delete page file: $path")
        }
        return success
    }

    /**
     * Internal save pipeline implemented as an Arrow Saga.
     *
     * Steps and their compensations:
     * 1. Write markdown to disk — compensation: restore old content (or delete if new file)
     * 2. Notify file-written observer — compensation: no-op (idempotent)
     * 3. Write sidecar — compensation: no-op (non-critical)
     * 4. Update DB filePath for new pages — compensation: no-op (best-effort)
     *
     * On any step failure the saga runs compensations in reverse order, ensuring the
     * on-disk state is rolled back before the error propagates.
     *
     * **Atomicity**: The platform [FileSystem.writeFileBytes] implementation MUST be atomic
     * (temp-file + rename). A partial write of an .md.stek ciphertext cannot be recovered —
     * the AEAD tag will fail to verify and the page will be permanently unreadable.
     */
    private suspend fun savePageInternal(page: Page, blocks: List<Block>, graphPath: String): Boolean =
        saveMutex.withLock {
            // Capture cryptoLayer and graphPath once at lock entry — also used by getPageFilePath so
            // the file extension (.md.stek vs .md) is consistent with all subsequent encrypt/decrypt calls.
            val capturedCryptoLayer = cryptoLayer
            val capturedGraphPath = this.graphPath
            // GAP-3: fail fast if encryption is active but the graph path hasn't been set yet.
            // relativeFilePath() with empty graphPath strips only the leading "/" from absolute paths,
            // producing the wrong AAD string and making the file permanently unreadable.
            if (capturedCryptoLayer != null && capturedGraphPath.isEmpty()) {
                logger.error("savePageInternal aborted — cryptoLayer is set but graphPath is empty (AAD would be wrong)")
                return@withLock false
            }

            val filePath = if (!page.filePath.isNullOrBlank()) {
                page.filePath
            } else {
                getPageFilePath(page, graphPath, capturedCryptoLayer)
            }

            // Guard: outer graph cannot write to the hidden volume reserve area
            if (capturedCryptoLayer != null) {
                val relPath = relativeFilePath(filePath, capturedGraphPath)
                val guard = capturedCryptoLayer.checkNotHiddenReserve(relPath)
                if (guard.isLeft()) {
                    logger.error("Write blocked — restricted path: $filePath")
                    return@withLock false
                }
            }

            // 0. Safety Check for Large Deletions
            if (fileSystem.fileExists(filePath)) {
                val oldContent = if (capturedCryptoLayer != null) {
                    val rawBytes = fileSystem.readFileBytes(filePath)
                    if (rawBytes != null) {
                        when (val r = capturedCryptoLayer.decrypt(relativeFilePath(filePath, capturedGraphPath), rawBytes)) {
                            is Either.Right -> r.value.decodeToString()
                            is Either.Left -> null  // decrypt failed — skip guard conservatively
                        }
                    } else null
                } else {
                    fileSystem.readFile(filePath) ?: ""
                }
                if (oldContent != null) {
                    val oldBlockCount = oldContent.lines().count { it.trim().startsWith("- ") }
                    val newBlockCount = blocks.size
                    if (oldBlockCount > largeDeletionThreshold && newBlockCount < oldBlockCount / 2) {
                        logger.error(
                            "Safety check triggered: Attempting to delete more than 50% of blocks on page " +
                                "'${page.name}' ($oldBlockCount -> $newBlockCount). Save aborted."
                        )
                        return@withLock false
                    }
                }
            }

            val content = buildMarkdown(page, blocks)

            // Run the saga — transact() throws on failure; runCatching logs and swallows.
            // The saga { } builder annotates the block with @SagaDSLMarker so inner saga()
            // calls resolve correctly via SagaScope. .transact() executes the pipeline.
            var succeeded = false
            runCatching {
                saga {
                    // Step 1: write markdown file — rollback restores previous content
                    val cryptoLayerNow = capturedCryptoLayer
                    val oldRawBytes = if (cryptoLayerNow != null && fileSystem.fileExists(filePath)) fileSystem.readFileBytes(filePath) else null
                    val oldContent = if (cryptoLayerNow == null && fileSystem.fileExists(filePath)) fileSystem.readFile(filePath) else null
                    saga(
                        action = {
                            if (cryptoLayerNow != null) {
                                val relPath = relativeFilePath(filePath, capturedGraphPath)
                                val encryptedBytes = cryptoLayerNow.encrypt(relPath, content.encodeToByteArray())
                                if (!fileSystem.writeFileBytes(filePath, encryptedBytes)) {
                                    error("writeFileBytes returned false for: $filePath")
                                }
                            } else {
                                if (!fileSystem.writeFile(filePath, content)) {
                                    error("writeFile returned false for: $filePath")
                                }
                            }
                            fileSystem.updateShadow(filePath, content)
                        },
                        compensation = { _ ->
                            try {
                                if (cryptoLayerNow != null) {
                                    if (oldRawBytes != null) fileSystem.writeFileBytes(filePath, oldRawBytes)
                                    else fileSystem.deleteFile(filePath)
                                } else {
                                    if (oldContent != null) fileSystem.writeFile(filePath, oldContent)
                                    else fileSystem.deleteFile(filePath)
                                }
                                fileSystem.invalidateShadow(filePath)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error("Saga compensation: failed to restore $filePath", e)
                            }
                        }
                    )

                    // Step 2: notify observer (suppresses external-change detection in GraphLoader)
                    saga(
                        action = { onFileWritten?.invoke(filePath) },
                        compensation = { _ -> /* idempotent — no-op */ }
                    )

                    // Step 3: write sidecar (non-critical — failure is logged but not propagated)
                    saga(
                        action = {
                            if (sidecarManager != null) {
                                val pageSlug = FileUtils.sanitizeFileName(page.name)
                                try {
                                    sidecarManager.write(pageSlug, blocks)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logger.error("Failed to write sidecar for page '${page.name}'", e)
                                }
                            }
                        },
                        compensation = { _ -> /* sidecar is non-critical — no rollback */ }
                    )

                    // Step 4: update filePath in DB for new pages (fire-and-forget via actor)
                    saga(
                        action = {
                            if (page.filePath.isNullOrBlank()) {
                                val updatedPage = page.copy(filePath = filePath)
                                val currentScope = scope
                                if (writeActor != null && currentScope != null) {
                                    currentScope.launch { writeActor.savePage(updatedPage) }
                                } else {
                                    @Suppress("DEPRECATION")
                                    @OptIn(DirectRepositoryWrite::class)
                                    pageRepository?.savePage(updatedPage)
                                }
                            }
                        },
                        compensation = { _ -> /* DB update is best-effort — no rollback needed */ }
                    )

                    logger.debug("Saved page to: $filePath")
                }.transact()
                succeeded = true
            }.onFailure { e ->
                logger.error("Failed to write file: $filePath", e)
            }
            succeeded
        }

    private fun buildMarkdown(page: Page, blocks: List<Block>): String = buildString {
        // 1. Page Properties
        if (page.properties.isNotEmpty()) {
            page.properties.forEach { (key, value) ->
                appendLine("$key:: $value")
            }
        }

        // 2. Blocks — reconstruct tree from flat list
        val blocksByParent = blocks.groupBy { it.parentUuid }

        fun writeBlocks(parentUuid: String?) {
            val siblings = blocksByParent[parentUuid] ?: return
            val sortedSiblings = siblings.sortedBy { it.position }

            sortedSiblings.forEach { block ->
                val indent = "\t".repeat(block.level)
                append(indent)
                append("- ")
                appendLine(block.content)

                if (block.properties.isNotEmpty()) {
                    val propIndent = indent + "\t"
                    block.properties.forEach { (key, value) ->
                        append(propIndent)
                        appendLine("$key:: $value")
                    }
                }

                writeBlocks(block.uuid)
            }
        }

        writeBlocks(null)
    }

    private fun getPageFilePath(page: Page, graphPath: String, layer: CryptoLayer? = cryptoLayer): String {
        val safeName = FileUtils.sanitizeFileName(page.name)
        val basePath = if (graphPath.endsWith("/")) graphPath else "$graphPath/"
        val folder = if (page.isJournal) "journals" else "pages"
        val extension = if (layer != null) ".md.stek" else ".md"
        return "${basePath}$folder/$safeName$extension"
    }

    /** Compute the graph-root-relative path used as AAD for file encryption. */
    private fun relativeFilePath(absoluteFilePath: String, base: String = graphPath): String {
        val baseWithSlash = if (base.endsWith("/")) base else "$base/"
        return if (absoluteFilePath.startsWith(baseWithSlash)) {
            absoluteFilePath.removePrefix(baseWithSlash)
        } else {
            if (base.isNotEmpty()) {
                logger.error("relativeFilePath: '$absoluteFilePath' is outside graph root '$base' — AAD may be non-portable")
            }
            absoluteFilePath
        }
    }

    companion object {
        /**
         * Creates a [Resource]-managed [GraphWriter].
         * On release, flushes pending debounced saves and stops the auto-save scope,
         * guaranteed even under [kotlinx.coroutines.CancellationException].
         */
        fun resource(
            fileSystem: FileSystem,
            writeActor: DatabaseWriteActor? = null,
            onFileWritten: (suspend (String) -> Unit)? = null,
            pageRepository: PageRepository? = null,
            sidecarManager: SidecarManager? = null,
            cryptoLayer: CryptoLayer? = null,
            graphPath: String = "",
        ): Resource<GraphWriter> = resource {
            val writer = GraphWriter(
                fileSystem = fileSystem,
                writeActor = writeActor,
                onFileWritten = onFileWritten,
                pageRepository = pageRepository,
                sidecarManager = sidecarManager,
                cryptoLayer = cryptoLayer,
                graphPath = graphPath,
            )
            onRelease {
                try { writer.flush() } catch (_: Exception) { /* best-effort flush */ }
                writer.stopAutoSave()
            }
            writer
        }
    }
}
