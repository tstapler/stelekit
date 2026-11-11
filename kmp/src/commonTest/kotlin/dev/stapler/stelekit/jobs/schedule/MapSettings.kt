// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.platform.Settings
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** In-memory [Settings] fixture with switchable read/write failures (no @Test methods). */
class MapSettings : Settings {
    val store = mutableMapOf<String, String>()
    var failReads = false
    var failWrites = false

    private fun checkRead() { if (failReads) throw IllegalStateException("read failed") }
    private fun checkWrite() { if (failWrites) throw IllegalStateException("write failed") }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean { checkRead(); return store[key]?.toBoolean() ?: defaultValue }
    override fun putBoolean(key: String, value: Boolean) { checkWrite(); store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String): String { checkRead(); return store[key] ?: defaultValue }
    override fun putString(key: String, value: String) { checkWrite(); store[key] = value }
    override fun containsKey(key: String): Boolean { checkRead(); return store.containsKey(key) }
}

/**
 * Widens the read-modify-write window: every read returns the value it saw, then busy-waits [gapMs] before
 * returning it, so two unserialised editors both act on the same stale snapshot (no @Test methods).
 */
class SlowReadSettings(private val inner: Settings, private val gapMs: Long = 40) : Settings by inner {
    override fun getString(key: String, defaultValue: String): String {
        val seen = inner.getString(key, defaultValue)
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < gapMs.milliseconds) { /* spin: a real thread, not a virtual-time delay */ }
        return seen
    }
}
