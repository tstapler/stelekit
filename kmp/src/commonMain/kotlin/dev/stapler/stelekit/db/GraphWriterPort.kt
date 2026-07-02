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
     *
     * @return `Right(Unit)` if the write actually landed on disk, `Left` with the underlying
     *   [DomainError] otherwise — [GraphWriter]'s implementation has several genuine failure
     *   paths (AAD/graphPath guard mismatch, decrypt failure, saga step failure) that must not
     *   be reported as success (see adversarial-review finding C3). Callers that previously
     *   ignored the (formerly `Unit`) result may keep doing so — the change is source-compatible
     *   for fire-and-forget call sites since Kotlin discards a non-`Unit` last expression when
     *   the enclosing function/lambda itself returns `Unit` — but any caller that needs to
     *   surface failures to the user (e.g. [dev.stapler.stelekit.llm.LlmSuggestionWriter]) must
     *   now check it.
     */
    suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String): Either<DomainError, Unit>

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
