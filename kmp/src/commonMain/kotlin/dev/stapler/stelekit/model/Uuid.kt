package dev.stapler.stelekit.model

/** Type-safe wrapper for page UUIDs. Prevents mixing with block UUIDs at compile time. */
expect value class PageUuid(val value: String) {
    override fun toString(): String
}

/** Type-safe wrapper for block UUIDs. Prevents mixing with page UUIDs at compile time. */
expect value class BlockUuid(val value: String) {
    override fun toString(): String
}

/** Type-safe wrapper for filesystem paths. Prevents mixing paths with names or UUIDs. */
expect value class FilePath(val value: String) {
    override fun toString(): String
}

/** Type-safe wrapper for Logseq page names (human-readable title, not UUID). */
expect value class PageName(val value: String) {
    override fun toString(): String
}
