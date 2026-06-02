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
