package dev.stapler.stelekit.ui.library

/** Per-card actions (parity with the retired Assets screen's action menu). */
enum class LibraryAssetAction { Details, CopyLink, Delete }

object LibraryItemActions {
    /** Markdown a user can paste on a page: images and drawings embed, other files link. */
    fun markdownLink(item: LibraryItem): String {
        val target = item.relativePath.ifEmpty { item.fileName }
        return when (item.mediaClass) {
            LibraryMediaClass.IMAGES, LibraryMediaClass.SKETCHES -> "![${item.fileName}]($target)"
            else -> "[${item.fileName}]($target)"
        }
    }
}
