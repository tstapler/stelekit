package dev.stapler.stelekit.cache

internal expect class PlatformLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
