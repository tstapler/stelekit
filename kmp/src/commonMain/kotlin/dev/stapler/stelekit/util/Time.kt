package dev.stapler.stelekit.util

expect object PlatformTime {
    fun now(): Long
    fun currentThreadName(): String
}
