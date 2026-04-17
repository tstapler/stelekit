package dev.stapler.stelekit.util

import kotlin.js.Date

actual object PlatformTime {
    actual fun now(): Long {
        val perf = js("typeof performance !== 'undefined' ? performance : null")
        return if (perf != null) {
            (perf.now() * 1_000_000).toLong()
        } else {
            (Date.now() * 1_000_000).toLong()
        }
    }

    actual fun currentThreadName(): String = "JS-Main"
}
