package dev.stapler.stelekit.model

/**
 * Where a graph's markdown content actually lives, per ADR-001. This is the durable, queryable
 * fact that `GitShadowWorktree.sweepOrphans()` and the relocate/link flows consult instead of
 * inferring storage backend ad hoc from platform-specific state.
 */
sealed interface StorageLocation {
    val graphId: String

    /** Content lives entirely inside the app's own private storage; no external grant exists. */
    data class AppOwned(override val graphId: String) : StorageLocation

    /** Content lives in an Android Storage Access Framework folder, addressed by its tree URI. */
    data class SafFolder(override val graphId: String, val treeUri: String) : StorageLocation

    /** Content lives at a real filesystem path reached via Android's MANAGE_EXTERNAL_STORAGE. */
    data class DirectAccessFolder(override val graphId: String, val realPath: String) : StorageLocation

    /** Content lives in a folder connected via the File System Access API (Web). */
    data class HostFolder(override val graphId: String, val displayName: String) : StorageLocation
}
