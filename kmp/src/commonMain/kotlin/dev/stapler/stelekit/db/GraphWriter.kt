package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import kotlin.concurrent.Volatile
import arrow.fx.coroutines.resource
import arrow.resilience.saga
import arrow.resilience.transact
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
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
    /** Initial CryptoLayer value — use [setCryptoLayer] to change at runtime. */
    initialCryptoLayer: CryptoLayer? = null,
    /** Graph root path — required to compute graph-root-relative AAD paths for encryption. */
    @Volatile var graphPath: String = "",
    /**
     * Called immediately before writing a file to disk. Allows the caller to pre-mark the
     * file as a pending own-write in [FileRegistry] so the watcher never sees our write as
     * an external change. Must be paired with [onClearPendingWrite] for saga compensation.
     */
    private val onPreWrite: (suspend (filePath: String) -> Unit)? = null,
    /**
     * Called in saga compensation when a file write fails. Must clear the pending-write
     * sentinel set by [onPreWrite] so the file is not permanently suppressed from
     * external-change detection.
     */
    private val onClearPendingWrite: (suspend (filePath: String) -> Unit)? = null,
    /**
     * Called before each write with the file path and current on-disk content.
     * Returns true if the write should be blocked because the file was externally changed
     * since it was last written by the app. Null disables the check (all writes proceed).
     * Only applies to plaintext (.md) files; encrypted files use the modTime sentinel guard.
     */
    private val checkPreWriteConflict: (suspend (filePath: String, diskContent: String) -> Boolean)? = null,
    /**
     * Called when [checkPreWriteConflict] returns true. Receives [filePath], the content
     * that was about to be written ([pendingContent]), and the current on-disk content
     * ([diskContent]). The write has already been aborted when this is called.
     */
    private val onPreWriteConflict: (suspend (filePath: String, pendingContent: String, diskContent: String) -> Unit)? = null,
) : GraphWriterPort {
    /**
     * Backing field for the CryptoLayer used to encrypt files in paranoid mode.
     * Volatile so changes published by [setCryptoLayer] are visible across coroutine threads.
     */
    @Volatile private var cryptoLayer: CryptoLayer? = initialCryptoLayer

    override fun setCryptoLayer(layer: CryptoLayer?) { cryptoLayer = layer }

    override fun closeAndClearCryptoLayer() { cryptoLayer?.close(); cryptoLayer = null }

    private val logger = Logger("GraphWriter")
    private val saveMutex = Mutex()

    data class SaveRequest(
        val page: Page,
        val blocks: List<Block>,
        val graphPath: String
    )

    // Per-page debounce: pageUuid → pending Job + latest request
    private val pendingByPage = mutableMapOf<PageUuid, Pair<Job, SaveRequest>>()
    private val pendingMutex = Mutex()
    // Owned internal scope — callers must not inject a rememberCoroutineScope().
    private val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scope: CoroutineScope? = null
    private var debounceMs: Long = 500L

    // Safety check: if more than this many blocks are deleted, require confirmation
    private val largeDeletionThreshold = 50

    /**
     * Start the auto-save processor. Uses the writer's owned internal scope.
     * Call this once when the application starts.
     */
    override fun startAutoSave(debounceMs: Long) {
        this.debounceMs = debounceMs
        this.scope = ownedScope
    }

    /**
     * Start the auto-save processor with an externally-supplied scope.
     * Intended for tests only — production code should use [startAutoSave] without args.
     */
    fun startAutoSave(scope: CoroutineScope, _debounceMs: Long = 500L) {
        this.scope = scope
    }

    /**
     * Flush all pending saves to disk immediately (e.g. on app pause/shutdown).
     */
    override suspend fun flush() {
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
     * Permanently stops the auto-save processor and cancels the owned scope.
     * This is a terminal shutdown — do not call startAutoSave() after this.
     * Intended to be called once during graph teardown.
     */
    override fun stopAutoSave() {
        scope = null
        ownedScope.cancel()
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

    /** Returns true if a debounced save is queued for [pageUuid] but has not yet fired. */
    suspend fun hasPendingForPage(pageUuid: PageUuid): Boolean =
        pendingMutex.withLock { pendingByPage.containsKey(pageUuid) }

    /** Cancels the pending debounced save for [pageUuid] without writing to disk. */
    suspend fun cancelPendingForPage(pageUuid: PageUuid) {
        pendingMutex.withLock {
            pendingByPage.remove(pageUuid)?.first?.cancel()
        }
    }

    /**
     * Immediately save a page (bypasses debouncing).
     */
    override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String) {
        savePageInternal(page, blocks, graphPath)
    }

    /**
     * Rename a page file.
     * Calculates the new file path based on the new name and moves the file.
     * Returns true if successful, false otherwise.
     */
    override suspend fun renamePage(page: Page, newName: String, graphPath: String): Boolean = saveMutex.withLock {
        // IO boundary: all fileSystem calls must run on PlatformDispatcher.IO on Android.
        withContext(PlatformDispatcher.IO) {
        val oldPath = page.filePath
        if (oldPath.isNullOrBlank()) {
            logger.error("Cannot rename page with no file path: ${page.name}")
            return@withContext false
        }

        // Guard: cannot rename pages in the hidden-volume reserve area
        val renameLayer = cryptoLayer
        val renameGraphPath = this@GraphWriter.graphPath
        if (renameLayer != null && renameGraphPath.isEmpty()) {
            logger.error("renamePage aborted — cryptoLayer is set but graphPath is empty (AAD would be wrong)")
            return@withContext false
        }
        if (renameLayer != null) {
            if (renameLayer.checkNotHiddenReserve(relativeFilePath(oldPath, renameGraphPath)).isLeft()) {
                logger.error("Rename blocked — restricted path: $oldPath")
                return@withContext false
            }
        }

        // Calculate new path using the already-captured renameLayer snapshot
        val newPath = getPageFilePath(page.copy(name = newName), graphPath, renameLayer)

        // If paths are same, nothing to do (except maybe case change on some FS)
        if (oldPath == newPath) return@withContext true

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
                return@withContext false
            }
            val oldRelPath = relativeFilePath(oldPath, renameGraphPath)
            val newRelPath = relativeFilePath(newPath, renameGraphPath)
            val plaintext = when (val result = cryptoLayerNow.decrypt(oldRelPath, bytes)) {
                is Either.Right -> result.value
                is Either.Left -> {
                    logger.error("Failed to decrypt file for rename: $oldPath (${result.value})")
                    return@withContext false
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
                return@withContext false
            }
            val ok = fileSystem.writeFile(newPath, content)
            if (ok) fileSystem.updateShadow(newPath, content)
            ok
        }

        if (writeOk) {
            onFileWritten?.invoke(oldPath)   // mark old path as our own deletion
            if (fileSystem.deleteFile(oldPath)) {
                // Notify the file watcher so it registers the new path and does not treat
                // the newly-created file as an external change on the next poll tick.
                onFileWritten?.invoke(newPath)
                logger.debug("Renamed page from $oldPath to $newPath")
                return@withContext true
            } else {
                logger.error("Failed to delete old file after copy: $oldPath")
                return@withContext false
            }
        } else {
            logger.error("Failed to write new file during rename: $newPath")
            return@withContext false
        }
        } // end withContext(PlatformDispatcher.IO)
    }

    /**
     * Delete a page file.
     */
    override suspend fun deletePage(page: Page): Boolean = saveMutex.withLock {
        // IO boundary: all fileSystem calls must run on PlatformDispatcher.IO on Android.
        withContext(PlatformDispatcher.IO) {
        val path = page.filePath
        if (path.isNullOrBlank()) {
            logger.error("Cannot delete page with no file path: ${page.name}")
            return@withContext false
        }

        // Guard: cannot delete pages in the hidden-volume reserve area
        val deleteLayer = cryptoLayer
        val deleteGraphPath = this@GraphWriter.graphPath
        if (deleteLayer != null && deleteLayer.checkNotHiddenReserve(relativeFilePath(path, deleteGraphPath)).isLeft()) {
            logger.error("Delete blocked — restricted path: $path")
            return@withContext false
        }

        onFileWritten?.invoke(path)   // pre-register deletion so file watcher ignores own-delete event
        val success = fileSystem.deleteFile(path)
        if (success) {
            logger.debug("Deleted page file: $path")
        } else {
            logger.error("Failed to delete page file: $path")
        }
        success
        } // end withContext(PlatformDispatcher.IO)
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
            // IO BOUNDARY: All filesystem calls below this line run on PlatformDispatcher.IO.
            // Adding any fileSystem.* call outside this withContext block will cause SAF Binder IPC
            // to block a Default dispatcher thread, reintroducing the Android insert lag.
            withContext(PlatformDispatcher.IO) {
            // Capture cryptoLayer and graphPath once at lock entry — also used by getPageFilePath so
            // the file extension (.md.stek vs .md) is consistent with all subsequent encrypt/decrypt calls.
            val capturedCryptoLayer = cryptoLayer
            val capturedGraphPath = this@GraphWriter.graphPath
            // GAP-3: fail fast if encryption is active but the graph path hasn't been set yet.
            // relativeFilePath() with empty graphPath strips only the leading "/" from absolute paths,
            // producing the wrong AAD string and making the file permanently unreadable.
            if (capturedCryptoLayer != null && capturedGraphPath.isEmpty()) {
                logger.error("savePageInternal aborted — cryptoLayer is set but graphPath is empty (AAD would be wrong)")
                return@withContext false
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
                    return@withContext false
                }
            }

            // 0. Safety Check for Large Deletions
            // Shadow-first: zero SAF Binder IPC for warm shadow (all saves after the first).
            // For cold shadow (new file / first launch): falls back to SAF only for plaintext;
            // for encrypted cold shadow: reads ciphertext and decrypts (same as before).
            val oldContentForSafetyCheck = fileSystem.readShadowOnly(filePath)
                ?: if (capturedCryptoLayer != null) {
                    if (fileSystem.fileExists(filePath)) {
                        val rawBytes = fileSystem.readFileBytes(filePath)
                        rawBytes?.let { bytes ->
                            (capturedCryptoLayer.decrypt(relativeFilePath(filePath, capturedGraphPath), bytes) as? Either.Right)
                                ?.value?.decodeToString()
                        }
                    } else null
                } else {
                    fileSystem.readFile(filePath)
                }
            if (oldContentForSafetyCheck != null) {
                val oldBlockCount = oldContentForSafetyCheck.lines().count { it.trim().startsWith("- ") }
                if (oldBlockCount > largeDeletionThreshold && blocks.size < oldBlockCount / 2) {
                    logger.error(
                        "Safety check triggered: Attempting to delete more than 50% of blocks on page " +
                            "'${page.name}' ($oldBlockCount -> ${blocks.size}). Save aborted."
                    )
                    return@withContext false
                }
            }

            val content = buildMarkdown(page, blocks)

            // Pre-write conflict check: abort if the on-disk content changed since we last
            // wrote it. Reuses the content already read for the large-deletion safety check
            // (no extra disk read). Skipped for encrypted files (.md.stek) — those rely on
            // the modTime sentinel guard in FileRegistry.
            if (checkPreWriteConflict != null && oldContentForSafetyCheck != null
                && !filePath.endsWith(".md.stek")) {
                if (checkPreWriteConflict.invoke(filePath, oldContentForSafetyCheck)) {
                    logger.warn("Pre-write hash mismatch for '${page.name}' — external change detected, write aborted")
                    onPreWriteConflict?.invoke(filePath, content, oldContentForSafetyCheck)
                    return@withContext false
                }
            }

            // Run the saga — transact() throws on failure; runCatching logs and swallows.
            // The saga { } builder annotates the block with @SagaDSLMarker so inner saga()
            // calls resolve correctly via SagaScope. .transact() executes the pipeline.
            var succeeded = false
            runCatching {
                saga {
                    // Step 0: pre-mark pending write to close the watcher race window.
                    // Must be called before the file write so detectChanges never sees the
                    // new mtime without knowing we wrote it. Compensation clears the sentinel
                    // so the file is not permanently suppressed if the write fails.
                    saga(
                        action = { onPreWrite?.invoke(filePath) },
                        compensation = { _ ->
                            try {
                                // Clear the sentinel so subsequent external edits are still detected.
                                // Without this, a failed write leaves Long.MAX_VALUE in modTimes
                                // and the file is never detected as externally changed again.
                                onClearPendingWrite?.invoke(filePath)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error("Saga compensation: failed to clear pending write for $filePath", e)
                            }
                        }
                    )

                    // Step 1: write markdown file — rollback restores previous content
                    val cryptoLayerNow = capturedCryptoLayer
                    // Compensation data — shadow-first for plaintext (zero IPC when warm).
                    // Encrypted still needs ciphertext bytes; use shadowExists() to skip fileExists() IPC.
                    val oldRawBytes = if (cryptoLayerNow != null) {
                        if (fileSystem.shadowExists(filePath) || fileSystem.fileExists(filePath)) {
                            fileSystem.readFileBytes(filePath)
                        } else null
                    } else null
                    val oldContent = if (cryptoLayerNow == null) {
                        fileSystem.readShadowOnly(filePath) ?: fileSystem.readFile(filePath)
                    } else null
                    saga(
                        action = {
                            if (cryptoLayerNow != null) {
                                val relPath = relativeFilePath(filePath, capturedGraphPath)
                                val encryptedBytes = cryptoLayerNow.encrypt(relPath, content.encodeToByteArray())
                                if (!fileSystem.writeFileBytes(filePath, encryptedBytes)) {
                                    error("writeFileBytes returned false for: $filePath")
                                }
                                fileSystem.updateShadow(filePath, content)
                            } else {
                                // Try write-behind first (zero Binder IPC on Android); falls back to direct SAF write.
                                val wroteViaShadow = fileSystem.markDirty(filePath, content)
                                if (!wroteViaShadow) {
                                    if (!fileSystem.writeFile(filePath, content)) {
                                        error("writeFile returned false for: $filePath")
                                    }
                                    // Keep shadow in sync after a direct SAF write
                                    fileSystem.updateShadow(filePath, content)
                                }
                            }
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
            } // end withContext(PlatformDispatcher.IO)
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

                writeBlocks(block.uuid.value)
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
            onPreWrite: (suspend (String) -> Unit)? = null,
            onClearPendingWrite: (suspend (String) -> Unit)? = null,
            checkPreWriteConflict: (suspend (String, String) -> Boolean)? = null,
            onPreWriteConflict: (suspend (String, String, String) -> Unit)? = null,
        ): Resource<GraphWriter> = resource {
            val writer = GraphWriter(
                fileSystem = fileSystem,
                writeActor = writeActor,
                onFileWritten = onFileWritten,
                pageRepository = pageRepository,
                sidecarManager = sidecarManager,
                initialCryptoLayer = cryptoLayer,
                graphPath = graphPath,
                onPreWrite = onPreWrite,
                onClearPendingWrite = onClearPendingWrite,
                checkPreWriteConflict = checkPreWriteConflict,
                onPreWriteConflict = onPreWriteConflict,
            )
            onRelease {
                try { writer.flush() } catch (_: Exception) { /* best-effort flush */ }
                writer.stopAutoSave()
            }
            writer
        }
    }
}
