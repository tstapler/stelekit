package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.vault.CryptoLayer

/**
 * Port interface for [GraphWriter] used by [dev.stapler.stelekit.ui.StelekitViewModel].
 *
 * Defines only the members accessed by the ViewModel, enabling unit-testing the ViewModel
 * without a real filesystem (Dependency Inversion Principle).
 */
interface GraphWriterPort {
    /**
     * Sets the CryptoLayer used to encrypt files in paranoid mode.
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
     * Start the auto-save processor. The writer owns its internal scope.
     */
    fun startAutoSave(debounceMs: Long = 500L)

    /**
     * Stop the auto-save processor.
     */
    fun stopAutoSave()

    /**
     * Flush all pending saves to disk immediately (e.g. on app pause/shutdown).
     */
    suspend fun flush()

    /**
     * Rename a page file: moves the on-disk file from its current path to the new name's path.
     * Returns true if successful, false otherwise.
     */
    suspend fun renamePage(page: Page, newName: String, graphPath: String): Boolean

    /**
     * Immediately save a page and its blocks to disk (bypasses debouncing).
     */
    suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String)

    /**
     * Delete a page file from disk.
     * Returns true if successful, false otherwise.
     */
    suspend fun deletePage(page: Page): Boolean

    /**
     * Move a page's on-disk file to the directory defined by [newPathPrefix] and return the
     * updated [Page] with [sectionId] and [filePath] updated. The caller is responsible for
     * persisting the returned page to the repository.
     */
    suspend fun movePageToSection(
        page: Page,
        newSectionId: String,
        newPathPrefix: String,
    ): Either<DomainError, Page>
}
