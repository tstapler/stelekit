package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Page
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
    suspend fun loadPageByName(pageName: String): Page?

    /**
     * Cancels any in-flight background indexing immediately.
     */
    fun cancelBackgroundWork()

    /**
     * Parses [content] and saves the resulting page and blocks to the database.
     */
    suspend fun parseAndSavePage(
        filePath: String,
        content: String,
        mode: ParseMode = ParseMode.FULL,
        priority: DatabaseWriteActor.Priority = DatabaseWriteActor.Priority.HIGH,
    )
}
