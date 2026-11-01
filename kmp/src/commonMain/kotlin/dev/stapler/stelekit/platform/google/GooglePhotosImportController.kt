// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.MediaAttachmentService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the "From Google Photos" toolbar entry shows (UW-82). */
sealed interface GooglePhotosImportState {
    data object Idle : GooglePhotosImportState
    data object Opening : GooglePhotosImportState
    data object Importing : GooglePhotosImportState

    /** [message] is shown with a Retry action. */
    data class Failed(val message: String) : GooglePhotosImportState
}

/**
 * Imports the photos the owner picks in Google Photos: each is saved through the supplied
 * [MediaAttachmentService] (the asset-indexing decorator in the shipped root, so every import is
 * registered by `AssetIndexService`) and the resulting attachments are returned for insertion.
 *
 * Holds no scope: callers launch [import] from their own coroutine, and a second call while one
 * is running returns immediately.
 */
class GooglePhotosImportController(private val picker: PhotosPicker) {
    private val logger = Logger("GooglePhotosImportController")
    private val _state = MutableStateFlow<GooglePhotosImportState>(GooglePhotosImportState.Idle)
    val state: StateFlow<GooglePhotosImportState> = _state.asStateFlow()

    suspend fun import(
        attachments: MediaAttachmentService,
        notesPath: EffectiveNotesPath,
    ): List<AttachmentResult> {
        if (!tryBegin()) return emptyList()
        try {
            val picked = picker.launchPicker().getOrNull()
            if (picked == null) {
                _state.value = GooglePhotosImportState.Failed("Couldn't open Google Photos - Retry")
                return emptyList()
            }
            _state.value = GooglePhotosImportState.Importing
            val saved = ArrayList<AttachmentResult>(picked.photos.size)
            var failed = picked.failedDownloads
            for (photo in picked.photos) {
                val attached = attachments.attachBytes(photo.bytes, suggestedName = fileNameFor(photo), notesPath = notesPath)
                val result = attached?.getOrNull()
                if (result == null) {
                    failed++
                    logger.warn("Google Photos import: could not save a picked photo (${attached?.leftOrNull()?.message ?: "unsupported"})")
                } else {
                    saved.add(result)
                }
            }
            _state.value =
                if (failed == 0) GooglePhotosImportState.Idle
                else GooglePhotosImportState.Failed("Couldn't import $failed ${if (failed == 1) "photo" else "photos"} - Retry")
            return saved
        } finally {
            // A cancelled import must not leave the toolbar stuck on "Opening…" / "Importing…".
            _state.update {
                if (it is GooglePhotosImportState.Opening || it is GooglePhotosImportState.Importing) GooglePhotosImportState.Idle else it
            }
        }
    }

    /** Returns the toolbar to its resting state, e.g. when the owner dismisses a failure. */
    fun dismiss() {
        _state.value = GooglePhotosImportState.Idle
    }

    /** Atomic double-tap guard: false while a run is already opening or importing. */
    private fun tryBegin(): Boolean {
        while (true) {
            val current = _state.value
            if (current is GooglePhotosImportState.Opening || current is GooglePhotosImportState.Importing) return false
            if (_state.compareAndSet(current, GooglePhotosImportState.Opening)) return true
        }
    }

    private fun fileNameFor(photo: PickedPhoto): String {
        val id = photo.mediaItemId.filter { it.isLetterOrDigit() }.take(12).ifEmpty { "photo" }
        return "google-photos-$id.jpg"
    }
}
