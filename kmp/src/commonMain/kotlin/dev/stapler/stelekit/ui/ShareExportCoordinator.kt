// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.DomainError.ExportError
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import dev.stapler.stelekit.platform.openInBrowser
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * Owns the share dialog and export pipeline: the dialog's format/scope/journal-range selections,
 * resolving a [ShareScope] to exportable text, clipboard export, Google Docs upload (including
 * the Google OAuth state it depends on), and the direct clipboard-export shortcuts
 * (`exportPage`/`exportSelectedBlocks`) used by the command palette and keyboard shortcuts.
 *
 * Extracted from [StelekitViewModel] (Phase 4 of the decomposition plan in
 * `project_plans/stelekit-viewmodel-decomposition/plan.md`), following the same
 * parameter-object-driven collaborator pattern [SectionManagementCoordinator] (Phase 1),
 * [LlmSuggestionCoordinator] (Phase 2), and [GitSyncCoordinator] (Phase 3) established. Unlike
 * those three groups, this one had **zero existing test coverage** before this extraction —
 * see `StelekitViewModelShareExportTest` (jvmTest), written and run green against the
 * pre-extraction implementation first, then re-verified unchanged against this class.
 *
 * Shares the ViewModel's [AppState] directly via [uiState] rather than owning a separate
 * `MutableStateFlow` of its own, for the same reason documented on [SectionManagementCoordinator]:
 * the fields this coordinator owns (`shareDialogVisible`/`shareFormat`/`shareScope`/
 * `shareIsGoogleAuthenticated`/`shareGoogleEmail`/`shareJournalFromDate`/`shareJournalToDate`/
 * `isExportingToDrive`/`isExporting`) are pre-existing `AppState` fields read directly by Compose
 * call sites (`ShareDialog`) via `uiState.value.xxx`. This also means `currentPage` — owned by
 * the Navigation group, which stays on [StelekitViewModel] — is read the same way
 * (`uiState.value.currentPage`) rather than through a separate provider lambda: it is already
 * part of the same shared `AppState`, so no extra wiring is needed to reach it.
 *
 * [blockStateManager] is a plain constructor-injected dependency (exactly like [pageRepository]/
 * [blockRepository]/[exportService]/[notificationManager]), not a callback into another
 * coordinator — `exportPage`/`exportSelectedBlocks` read `blockStateManager.blocksForPage(...)`/
 * `.selectedBlockUuids` directly, matching what the original `StelekitViewModel` methods did.
 *
 * Reuses the ViewModel's own [scope] rather than creating a new one, for the same lifecycle
 * reasons documented on [SectionManagementCoordinator]: it is cancelled in
 * [StelekitViewModel.close], and this coordinator is held as a `private val` field with the same
 * lifetime as the ViewModel itself.
 */
class ShareExportCoordinator(
    private val exportService: ExportService?,
    private val notificationManager: NotificationManager?,
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val blockStateManager: BlockStateManager?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<AppState>,
) {

    // ===== Share Dialog =====

    /** Opens the share dialog. */
    fun showShareDialog() {
        uiState.update { it.copy(shareDialogVisible = true) }
    }

    /** Closes the share dialog. */
    fun hideShareDialog() {
        uiState.update { it.copy(shareDialogVisible = false) }
    }

    /** Updates the share format selection (persists across dialog invocations in the session). */
    fun setShareFormat(format: String) {
        uiState.update { it.copy(shareFormat = format) }
    }

    /** Updates the share scope selection (persists across dialog invocations in the session). */
    fun setShareScope(scope: ShareScope) {
        uiState.update { it.copy(shareScope = scope) }
    }

    /**
     * Resolve export content for any [ShareScope].
     *
     * Routes to the appropriate ExportService method based on scope.
     * Requires [journalFrom]/[journalTo] (non-null) when scope is [ShareScope.JournalRange].
     */
    suspend fun resolveExportContent(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        formatId: String,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
    ): Either<DomainError, String> {
        val svc = exportService
            ?: return ExportError.SerializationFailed("Export service unavailable").left()
        return when (shareScope) {
            ShareScope.CurrentPage -> svc.exportToString(page, allBlocks, formatId)
            ShareScope.SelectedBlocks -> svc.exportToString(
                page,
                svc.subtreeBlocks(allBlocks, selectedUuids),
                formatId,
            )
            ShareScope.PageAndLinks -> svc.exportPageWithLinks(
                page, allBlocks, formatId, pageRepository, blockRepository,
            )
            ShareScope.JournalRange -> {
                val from = journalFrom
                    ?: return ExportError.SerializationFailed("Start date not set for journal export").left()
                val to = journalTo
                    ?: return ExportError.SerializationFailed("End date not set for journal export").left()
                svc.exportJournalRange(from, to, formatId, pageRepository, blockRepository)
            }
        }
    }

    /** Exports the resolved scope content to the clipboard. Errors surface via [notificationManager]. */
    fun exportScopeToClipboard(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        formatId: String,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
        onDone: () -> Unit = {},
    ) {
        val svc = exportService ?: return
        scope.launch {
            try {
                when (shareScope) {
                    ShareScope.CurrentPage ->
                        svc.exportToClipboard(page, allBlocks, formatId)
                    ShareScope.SelectedBlocks ->
                        svc.exportToClipboard(
                            page,
                            svc.subtreeBlocks(allBlocks, selectedUuids),
                            formatId,
                        )
                    else -> {
                        val result = resolveExportContent(
                            shareScope, page, allBlocks, selectedUuids, formatId,
                            journalFrom, journalTo,
                        )
                        result.fold(
                            ifLeft = { err ->
                                withContext(Dispatchers.Main) {
                                    notificationManager?.show(
                                        "Export failed: ${err.message}",
                                        NotificationType.ERROR,
                                    )
                                }
                            },
                            ifRight = { content ->
                                if (formatId == "html") {
                                    val plainResult = resolveExportContent(
                                        shareScope, page, allBlocks, selectedUuids,
                                        "plain-text", journalFrom, journalTo,
                                    )
                                    svc.clipboard.writeHtml(content, plainResult.getOrNull() ?: content)
                                } else {
                                    svc.clipboard.writeText(content)
                                }
                            },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notificationManager?.show("Clipboard export failed: ${e.message}", NotificationType.ERROR)
                }
            } finally {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    /** Launches Google OAuth in the ViewModel scope, updates [AppState.shareIsGoogleAuthenticated] on completion. */
    fun launchGoogleAuth(manager: GoogleAuthManager) {
        scope.launch {
            val result = manager.authenticate()
            val authenticated = result.isRight()
            val email = if (authenticated) manager.getConnectedEmail() else null
            uiState.update { it.copy(
                shareIsGoogleAuthenticated = authenticated,
                shareGoogleEmail = email,
            ) }
            if (!authenticated) {
                withContext(Dispatchers.Main) {
                    notificationManager?.show(
                        "Google sign-in failed: ${result.fold({ it.message }, { "" })}",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    /** Re-queries auth state from [manager] and syncs to [AppState]. Call when the dialog opens. */
    fun refreshShareGoogleAuthState(manager: GoogleAuthManager) {
        scope.launch {
            val authenticated = manager.isAuthenticated()
            val email = if (authenticated) manager.getConnectedEmail() else null
            uiState.update { it.copy(
                shareIsGoogleAuthenticated = authenticated,
                shareGoogleEmail = email,
            ) }
        }
    }

    /** Sets the journal date range for a [ShareScope.JournalRange] export. */
    fun setShareJournalDateRange(from: LocalDate?, to: LocalDate?) {
        uiState.update { it.copy(shareJournalFromDate = from, shareJournalToDate = to) }
    }

    /**
     * Exports the current page as HTML and uploads it to Google Docs.
     * Runs on the ViewModel's own scope (never rememberCoroutineScope — that scope
     * is cancelled when the composable leaves composition).
     * On success: opens the created document in the browser.
     * On error: shows a snackbar notification.
     */
    fun shareToGoogleDocs(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        driveClient: DriveUploader,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
    ) {
        uiState.update { it.copy(isExportingToDrive = true) }
        scope.launch(Dispatchers.Default) {
            try {
                val htmlResult = resolveExportContent(
                    shareScope, page, allBlocks, selectedUuids, "html", journalFrom, journalTo,
                )
                htmlResult.fold(
                    ifLeft = { err ->
                        withContext(Dispatchers.Main) {
                            notificationManager?.show("Export failed: ${err.message}", NotificationType.ERROR)
                        }
                    },
                    ifRight = { html ->
                        val uploadResult = driveClient.uploadFile(
                            fileName = page.name,
                            mimeType = "application/vnd.google-apps.document",
                            bytes = html.encodeToByteArray(),
                            parentFolderId = null,
                        )
                        uploadResult.fold(
                            ifLeft = { err ->
                                withContext(Dispatchers.Main) {
                                    notificationManager?.show(
                                        "Google Docs upload failed: ${err.message}",
                                        NotificationType.ERROR,
                                    )
                                }
                            },
                            ifRight = { fileId ->
                                openInBrowser("https://docs.google.com/document/d/$fileId/edit")
                            }
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notificationManager?.show("Google Docs export failed: ${e.message}", NotificationType.ERROR)
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    uiState.update { it.copy(isExportingToDrive = false) }
                }
            }
        }
    }

    // ===== Export =====

    /**
     * Injects the platform-specific [ClipboardProvider] so export operations can write
     * to the system clipboard. Called once from the composable root after construction.
     */
    fun setClipboardProvider(provider: ClipboardProvider) {
        exportService?.clipboard = provider
    }

    /**
     * Exports the current page to [formatId] and copies the result to the clipboard.
     * No-op when there is no current page or no [ExportService] configured.
     */
    fun exportPage(formatId: String) {
        val page = uiState.value.currentPage ?: return
        val blocks = blockStateManager?.blocksForPage(page.uuid.value) ?: return
        val sortedBlocks = BlockSorter.sort(blocks)
        if (exportService == null) {
            notificationManager?.show("Export unavailable", NotificationType.ERROR)
            return
        }
        if (uiState.value.isExporting) return
        uiState.update { it.copy(isExporting = true) }
        scope.launch(Dispatchers.Default) {
            try {
                val result = exportService.exportToClipboard(page, sortedBlocks, formatId)
                withContext(Dispatchers.Main) {
                    result.onRight {
                        notificationManager?.show("Copied as ${formatDisplayName(formatId)}", NotificationType.SUCCESS)
                    }.onLeft { e ->
                        notificationManager?.show("Export failed: ${e.message}", NotificationType.ERROR)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    uiState.update { it.copy(isExporting = false) }
                }
            }
        }
    }

    /**
     * Exports the currently selected blocks (and their subtrees) to [formatId].
     * Falls back to [exportPage] when no blocks are selected.
     */
    fun exportSelectedBlocks(formatId: String) {
        val page = uiState.value.currentPage ?: return
        val selectedUuids = blockStateManager?.selectedBlockUuids?.value ?: emptySet()
        if (selectedUuids.isEmpty()) {
            exportPage(formatId)
            return
        }
        val allBlocks = blockStateManager?.blocksForPage(page.uuid.value) ?: return
        if (exportService == null) {
            notificationManager?.show("Export unavailable", NotificationType.ERROR)
            return
        }
        if (uiState.value.isExporting) return
        uiState.update { it.copy(isExporting = true) }
        scope.launch(Dispatchers.Default) {
            try {
                val subtreeBlocks = exportService.subtreeBlocks(allBlocks, selectedUuids)
                val result = exportService.exportToClipboard(page, subtreeBlocks, formatId)
                withContext(Dispatchers.Main) {
                    result.onRight {
                        notificationManager?.show("Copied as ${formatDisplayName(formatId)}", NotificationType.SUCCESS)
                    }.onLeft { e ->
                        notificationManager?.show("Export failed: ${e.message}", NotificationType.ERROR)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    uiState.update { it.copy(isExporting = false) }
                }
            }
        }
    }

    private fun formatDisplayName(formatId: String): String = when (formatId) {
        "markdown" -> "Markdown"
        "plain-text" -> "Plain Text"
        "html" -> "HTML"
        "json" -> "JSON"
        else -> formatId
    }
}
