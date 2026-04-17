package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.FileUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles writing page and block changes back to markdown files.
 * Supports debounced auto-save to avoid excessive disk writes.
 *
 * Updated to use UUID-native storage and safety checks for large deletions.
 */
class GraphWriter(
    private val fileSystem: PlatformFileSystem,
    private val writeActor: DatabaseWriteActor? = null,
    private val onFileWritten: ((filePath: String) -> Unit)? = null,
    @Deprecated("Use writeActor instead", level = DeprecationLevel.WARNING)
    private val pageRepository: PageRepository? = null,
    private val sidecarManager: SidecarManager? = null,
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
            try {
                savePageInternal(request.page, request.blocks, request.graphPath)
                logger.info("Flushed page: ${request.page.name}")
            } catch (e: Exception) {
                logger.error("Failed to flush page: ${request.page.name}", e)
            }
        }
    }

    private suspend fun saveImmediately(request: SaveRequest) {
        try {
            savePageInternal(request.page, request.blocks, request.graphPath)
            logger.info("Saved page: ${request.page.name}")
        } catch (e: Exception) {
            logger.error("Failed to save page: ${request.page.name}", e)
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

        // Calculate new path
        val newPath = getPageFilePath(page.copy(name = newName), graphPath)

        // If paths are same, nothing to do (except maybe case change on some FS)
        if (oldPath == newPath) return true

        val content = fileSystem.readFile(oldPath)
        if (content == null) {
            logger.error("Failed to read file for rename: $oldPath")
            return false
        }
        
        if (fileSystem.writeFile(newPath, content)) {
            if (fileSystem.deleteFile(oldPath)) {
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

        val success = fileSystem.deleteFile(path)
        if (success) {
            logger.debug("Deleted page file: $path")
        } else {
            logger.error("Failed to delete page file: $path")
        }
        return success
    }

    private suspend fun savePageInternal(page: Page, blocks: List<Block>, graphPath: String) = saveMutex.withLock {
        // 3. Path Resolution
        val filePath = if (!page.filePath.isNullOrBlank()) {
            page.filePath
        } else {
            getPageFilePath(page, graphPath)
        }

        // 0. Safety Check for Large Deletions
        if (fileSystem.fileExists(filePath)) {
            val oldContent = fileSystem.readFile(filePath) ?: ""
            // Count old blocks by looking for "- " lines (simplistic but works for md)
            val oldBlockCount = oldContent.lines().count { it.trim().startsWith("- ") }
            val newBlockCount = blocks.size
            
            if (oldBlockCount > largeDeletionThreshold && newBlockCount < oldBlockCount / 2) {
                logger.error("Safety check triggered: Attempting to delete more than 50% of blocks on page '${page.name}' ($oldBlockCount -> $newBlockCount). Save aborted.")
                // In a real app, we would trigger a UI confirmation here.
                // For this headless implementation, we abort to be safe.
                return@withLock
            }
        }

        val content = buildString {
            // 1. Page Properties
            if (page.properties.isNotEmpty()) {
                page.properties.forEach { (key, value) ->
                    appendLine("$key:: $value")
                }
            }

            // 2. Blocks
            // Group blocks by parentUuid for tree reconstruction
            val blocksByParent = blocks.groupBy { it.parentUuid }

            // Recursive function to write blocks
            fun writeBlocks(parentUuid: String?) {
                val siblings = blocksByParent[parentUuid] ?: return
                val sortedSiblings = siblings.sortedBy { it.position }

                sortedSiblings.forEach { block ->
                    // Indentation: tab per level (standard Logseq format)
                    val indent = "\t".repeat(block.level)
                    append(indent)
                    append("- ")
                    appendLine(block.content)

                    // Block Properties
                    if (block.properties.isNotEmpty()) {
                        val propIndent = indent + "\t"
                        block.properties.forEach { (key, value) ->
                            append(propIndent)
                            appendLine("$key:: $value")
                        }
                    }

                    // Recursively write children
                    writeBlocks(block.uuid)
                }
            }

            // Start with root blocks (parentUuid = null)
            writeBlocks(null)
        }

        val success = fileSystem.writeFile(filePath, content)
        if (success) {
            logger.debug("Saved page to: $filePath")
            // Notify GraphLoader so it suppresses treating this as an external change
            onFileWritten?.invoke(filePath)
            // Write sidecar so GraphLoader can recover stable UUIDs after a git pull
            if (sidecarManager != null) {
                val pageSlug = FileUtils.sanitizeFileName(page.name)
                try {
                    sidecarManager.write(pageSlug, blocks)
                } catch (e: Exception) {
                    logger.error("Failed to write sidecar for page '${page.name}'", e)
                }
            }
            // Update filePath in DB for new pages
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
        } else {
            logger.error("Failed to write file: $filePath")
        }
    }
    
    private fun getPageFilePath(page: Page, graphPath: String): String {
        val safeName = FileUtils.sanitizeFileName(page.name)
        val basePath = if (graphPath.endsWith("/")) graphPath else "$graphPath/"
        
        val folder = if (page.isJournal) "journals" else "pages"
        return "${basePath}$folder/$safeName.md"
    }
}
