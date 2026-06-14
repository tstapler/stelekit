package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.model.BlockUuid

/**
 * Bundles platform-provided attachment callbacks for the editor toolbar.
 *
 * Passing a single [EditorCapabilities] instead of individual nullable lambdas ensures
 * every screen that hosts an editor toolbar receives the complete set of capabilities
 * atomically — forgetting one is a compile error, not a silent omission.
 */
data class EditorCapabilities(
    /**
     * Opens a file picker, copies the chosen file to `<graphRoot>/assets/`, and inserts
     * `![alt](relativePath)` at the cursor of the supplied block UUID.
     * Null hides the attach-image toolbar button.
     */
    val onAttachImage: ((editingBlockUuid: BlockUuid?) -> Unit)? = null,
    /**
     * Handles files drag-and-dropped onto the page area. Each entry is a platform file handle
     * (opaque [Any] in commonMain; a [java.io.File] on JVM). Null disables drop handling.
     */
    val onFileDrop: ((List<Any>) -> Unit)? = null,
    /**
     * Handles Ctrl/Cmd+V when the clipboard contains an image. Returns true if consumed.
     * Null disables clipboard-image paste.
     */
    val onPasteImage: ((editingBlockUuid: BlockUuid?) -> Boolean)? = null,
    /**
     * Triggers a camera capture and inserts the resulting IMAGE_ANNOTATION block on the
     * current page below the editing cursor. Null hides the camera toolbar button.
     * Only non-null on Android when SensorModule.cameraProvider.isAvailable is true.
     */
    val onCaptureImage: (() -> Unit)? = null,
)
