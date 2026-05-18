// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.drive

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.google.DriveFile
import dev.stapler.stelekit.platform.google.GoogleApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state for [DriveFileBrowserScreen].
 */
data class DriveFileBrowserState(
    val files: List<DriveFile> = emptyList(),
    /** Stack of (folderId, folderName) pairs representing the navigation history. */
    val folderStack: List<Pair<String, String>> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** File ID of a file currently being downloaded. */
    val downloadingFileId: String? = null,
) {
    /** Breadcrumb path for display in the header. */
    val breadcrumb: String
        get() = if (folderStack.isEmpty()) {
            "My Drive"
        } else {
            "My Drive / " + folderStack.joinToString(" / ") { it.second }
        }

    /** Current folder ID (null = root). */
    val currentFolderId: String?
        get() = folderStack.lastOrNull()?.first
}

/**
 * ViewModel for [DriveFileBrowserScreen].
 *
 * CRITICAL: owns its [CoroutineScope] internally — never accepts an externally supplied scope.
 */
class DriveFileBrowserViewModel(
    private val apiClient: GoogleApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger("DriveFileBrowserViewModel")

    private val _state = MutableStateFlow(DriveFileBrowserState())
    val state: StateFlow<DriveFileBrowserState> = _state.asStateFlow()

    /**
     * Load files from the given [folderId] (null = Drive root).
     * Replaces the current file list; preserves folder navigation stack.
     */
    fun loadFiles(folderId: String?) {
        _state.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            apiClient.listFiles(folderId).fold(
                ifLeft = { err ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Cannot load Drive files: ${err.message}",
                        )
                    }
                    logger.error("Drive listFiles failed: ${err.message}")
                },
                ifRight = { files ->
                    _state.update { it.copy(isLoading = false, files = files, error = null) }
                },
            )
        }
    }

    /** Navigate into a [folder], pushing it onto the navigation stack. */
    fun navigateInto(folder: DriveFile) {
        require(folder.isFolder) { "navigateInto called on a non-folder: ${folder.id}" }
        _state.update {
            it.copy(folderStack = it.folderStack + Pair(folder.id, folder.name))
        }
        loadFiles(folder.id)
    }

    /** Navigate up one level in the folder hierarchy. No-op at root. */
    fun navigateUp() {
        val current = _state.value
        if (current.folderStack.isEmpty()) return
        val newStack = current.folderStack.dropLast(1)
        _state.update { it.copy(folderStack = newStack) }
        loadFiles(newStack.lastOrNull()?.first)
    }

    /** Re-load the current folder. */
    fun reload() {
        loadFiles(_state.value.currentFolderId)
    }

    /**
     * Download [file] bytes from Drive and return them via [onSuccess].
     *
     * Shows a loading indicator on the file row during download.
     * On error, updates [DriveFileBrowserState.error] with a human-readable message.
     */
    fun downloadFile(
        file: DriveFile,
        onSuccess: (ByteArray) -> Unit,
    ) {
        _state.update { it.copy(downloadingFileId = file.id) }
        scope.launch {
            apiClient.downloadFile(file.id).fold(
                ifLeft = { err ->
                    _state.update {
                        it.copy(
                            downloadingFileId = null,
                            error = "Download failed: ${err.message}",
                        )
                    }
                },
                ifRight = { bytes ->
                    _state.update { it.copy(downloadingFileId = null) }
                    onSuccess(bytes)
                },
            )
        }
    }

    /** Cancel the internal scope. Call when the screen leaves composition permanently. */
    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
