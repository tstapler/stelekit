package dev.stapler.stelekit.model

/**
 * Type-safe wrapper for page UUIDs. Erases to String on JVM/Android; zero runtime overhead.
 * Using distinct value classes for page and block UUIDs makes swapping them a compile error.
 */
@JvmInline value class PageUuid(val value: String) {
    override fun toString(): String = value
}

/**
 * Type-safe wrapper for block UUIDs. Erases to String on JVM/Android; zero runtime overhead.
 */
@JvmInline value class BlockUuid(val value: String) {
    override fun toString(): String = value
}

/**
 * Type-safe wrapper for filesystem paths. Prevents mixing file paths with page names
 * or UUIDs in the database and file-watcher layers.
 */
@JvmInline value class FilePath(val value: String) {
    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Logseq page names (the human-readable title, not the UUID).
 * Prevents mixing page names with file paths or UUIDs at key API boundaries.
 */
@JvmInline value class PageName(val value: String) {
    override fun toString(): String = value
}
