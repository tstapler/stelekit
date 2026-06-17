package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.vault.CryptoLayer
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Port interface for [GraphLoader] used by [dev.stapler.stelekit.ui.StelekitViewModel].
 *
 * Defines only the members accessed by the ViewModel, enabling unit-testing the ViewModel
 * without a real filesystem (Dependency Inversion Principle).
 */
interface GraphLoaderPort {
    /**
     * Sets the flow of page UUIDs currently open in an active edit session. When non-null,
     * background indexing skips these pages to avoid clobbering in-progress edits.
     */
    fun setActivePageUuids(uuids: StateFlow<Set<String>>?)

    /**
     * Sets the flow of page UUIDs that have unsaved block edits. The file watcher skips
     * auto-reload only for pages in this set — pages that are open but unedited (e.g. the
     * journals page being viewed) are still reloaded when an external change is detected.
     */
    fun setUnsavedPageUuids(uuids: StateFlow<Set<String>>?)

    /**
     * Emitted when the file watcher detects an external modification to a file.
     */
    val externalFileChanges: SharedFlow<ExternalFileChange>

    /**
     * Emitted when a DB write fails after all retries.
     */
    val writeErrors: SharedFlow<WriteError>

    /**
     * Sets the CryptoLayer used to decrypt/encrypt files in paranoid mode.
     * Pass null to switch to plaintext mode.
     */
    fun setCryptoLayer(layer: CryptoLayer?)

    /**
     * Calls [CryptoLayer.close] on the current layer (zeroing the DEK copy) then sets it to null.
     * Convenience for vault-lock flows where the close and null must be atomic from the caller's
     * perspective.
     */
    fun closeAndClearCryptoLayer()

    /**
     * Loads the graph progressively: Phase 1 loads recent journals immediately,
     * Phase 2 indexes remaining pages in the background.
     */
    suspend fun loadGraphProgressive(
        graphPath: String,
        immediateJournalCount: Int = 10,
        onProgress: (String) -> Unit,
        onPhase1Complete: () -> Unit,
        onFullyLoaded: () -> Unit,
    )

    /**
     * Finds and fully indexes all pages that were only partially loaded (METADATA_ONLY).
     */
    suspend fun indexRemainingPages(onProgress: (String) -> Unit)

    /**
     * Priority-loads a page by name directly from disk.
     */
    suspend fun loadPageByName(pageName: PageName): Page?

    /**
     * Loads or re-reads a page by UUID from disk into the database.
     * When [force] is true the mtime guard is bypassed and the file is always re-read,
     * which is the right behaviour for a user-triggered "reload from disk" action.
     */
    suspend fun loadFullPage(pageUuid: String, force: Boolean = false)

    /**
     * Cancels any in-flight background indexing immediately.
     */
    fun cancelBackgroundWork()

    /**
     * Parses [content] and saves the resulting page and blocks to the database.
     */
    suspend fun parseAndSavePage(
        filePath: FilePath,
        content: String,
        mode: ParseMode = ParseMode.FULL,
        priority: DatabaseWriteActor.Priority = DatabaseWriteActor.Priority.HIGH,
    )
}
