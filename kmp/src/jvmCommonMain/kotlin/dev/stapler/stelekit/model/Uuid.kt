package dev.stapler.stelekit.model

@JvmInline
actual value class PageUuid(actual val value: String) {
    actual override fun toString(): String = value
}

@JvmInline
actual value class BlockUuid(actual val value: String) {
    actual override fun toString(): String = value
}

@JvmInline
actual value class FilePath(actual val value: String) {
    actual override fun toString(): String = value
}

@JvmInline
actual value class PageName(actual val value: String) {
    actual override fun toString(): String = value
}
