package dev.stapler.stelekit.db

/**
 * An external (non-app-initiated) change detected by the file watcher.
 *
 * @param filePath  Absolute path of the file that changed.
 * @param content   New content read from disk.
 * @param suppress  Call this to tell GraphLoader to skip automatic re-import for
 *                  this event.  Must be called synchronously during the collector's
 *                  handling of the event (before the next watcher tick).
 */
data class ExternalFileChange(
    val filePath: String,
    val content: String,
    val suppress: () -> Unit
)

/**
 * Emitted when a database write fails persistently (i.e. even the per-request individual
 * retry in [DatabaseWriteActor] could not save the data). Consumers (e.g. the ViewModel)
 * should surface this to the user and offer a retry action.
 *
 * @param filePath   Source markdown file path, or page UUID if a file path is unavailable.
 * @param blockCount Number of blocks that could not be saved (0 for page-level failures).
 * @param cause      The domain error.
 */
data class WriteError(
    val filePath: String,
    val blockCount: Int,
    val cause: dev.stapler.stelekit.error.DomainError,
)
