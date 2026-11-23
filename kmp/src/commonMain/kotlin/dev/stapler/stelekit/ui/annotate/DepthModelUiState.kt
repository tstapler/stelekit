package dev.stapler.stelekit.ui.annotate

// Lives in the devices module (not ui_sketch) so the depth-estimator port can name it without importing the UI layer.

/**
 * Platform-independent mirror of [DepthModelDownloader.ModelState] for common UI.
 *
 * Populated via [AnnotationEditorViewModel.updateDepthModelUiState] from the Android
 * platform layer which observes [DepthModelDownloader.modelState].
 */
sealed interface DepthModelUiState {
    /** Model not downloaded. Show "Download depth model (~100MB)" button. */
    data object Absent : DepthModelUiState

    /** Download in progress. [progress] is 0–100 or -1 if indeterminate. [notice] is an optional one-line caveat. */
    data class Downloading(val progress: Int, val notice: String? = null) : DepthModelUiState

    /**
     * Download queued but not running (waiting for an unmetered network, offline, or backing off). [canDownloadAnyway]
     * offers "Download now anyway" for the metered case.
     */
    data class Waiting(val message: String, val canDownloadAnyway: Boolean, val notice: String? = null) : DepthModelUiState

    /** Model ready — show "Estimate depth (AI)" button. */
    data object Ready : DepthModelUiState

    /** Download cancelled — show "Download cancelled." with a "Download again" action. */
    data object Cancelled : DepthModelUiState

    /**
     * Download failed. Show "Download failed — tap to retry", or [reason] followed by
     * "Tap to retry." when set (e.g. a stall-timeout transition surfaces plain-language copy
     * instead of the generic message).
     */
    data class Failed(val reason: String? = null) : DepthModelUiState
}
