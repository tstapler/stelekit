package dev.stapler.stelekit.cache

import java.util.concurrent.locks.ReentrantLock

internal actual class PlatformLock {
    private val lock = ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
