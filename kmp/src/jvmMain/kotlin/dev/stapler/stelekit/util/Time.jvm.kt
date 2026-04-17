package dev.stapler.stelekit.util

actual object PlatformTime {
    actual fun now(): Long = System.nanoTime()
    actual fun currentThreadName(): String = Thread.currentThread().name
}
