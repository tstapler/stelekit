// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.Settings
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStalenessStoreTest {

    private class MapSettings : Settings {
        val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    @Test
    fun `a fresh graph has nothing recorded and an unknown behind count`() {
        val s = SyncStalenessStore(MapSettings()).read("g")
        assertNull(s.lastMergedAtMs)
        assertNull(s.behindCount)
        assertEquals(emptyList(), s.history)
    }

    @Test
    fun `history is capped at 10 and keeps the newest in order`() {
        val store = SyncStalenessStore(MapSettings())
        for (i in 1..15) store.recordFetched("g", i * 1000L, i)

        val history = store.read("g").history
        assertEquals(10, history.size)
        assertEquals((6..15).map { it * 1000L }, history.map { it.atMs })
        assertEquals(15, store.read("g").behindCount)
    }

    @Test
    fun `a merge zeroes the behind count and a fetch error keeps the last known one`() {
        val store = SyncStalenessStore(MapSettings())
        store.recordFetched("g", 1_000, 4)
        store.recordError("g", 2_000, "RemoteBranchNotFound")
        assertEquals(4, store.read("g").behindCount)
        assertEquals(BackgroundOutcomeKind.Error, store.read("g").history.last().kind)

        store.recordMerged("g", 3_000)
        val s = store.read("g")
        assertEquals(0, s.behindCount)
        assertEquals(3_000L, s.lastMergedAtMs)
        assertEquals(1_000L, s.firstSeenAtMs, "first seen is the first write and never moves")
    }

    @Test
    fun `fetch-only reason is stored and cleared`() {
        val store = SyncStalenessStore(MapSettings())
        store.setFetchOnlyReason("g", FetchOnlyReason.Saf)
        assertEquals(FetchOnlyReason.Saf, store.read("g").fetchOnlyReason)
        store.setFetchOnlyReason("g", null)
        assertNull(store.read("g").fetchOnlyReason)
    }

    @Test
    fun `graphs are independent`() {
        val store = SyncStalenessStore(MapSettings())
        store.recordMerged("a", 5)
        assertNull(store.read("b").lastMergedAtMs)
    }

    @Test
    fun `a corrupt stored value reads as empty instead of failing`() {
        val settings = MapSettings()
        settings.putString("git_sync_staleness_g", "m=banana;h=1:Nope:x|;;;===")
        val s = SyncStalenessStore(settings).read("g")
        assertNull(s.lastMergedAtMs)
        assertEquals(emptyList(), s.history)
    }

    @Test
    fun `encode then decode is the identity for any recorded history`() = runTest {
        checkAll(Arb.long(1L..1_000_000L), Arb.int(0..500), Arb.string(0..80)) { at, behind, detail ->
            val store = SyncStalenessStore(MapSettings())
            store.recordFetched("g", at, behind)
            store.recordError("g", at + 1, detail)
            val read = store.read("g")
            assertEquals(read, SyncStalenessStore.decode(SyncStalenessStore.encode(read)))
            assertTrue(read.history.last().detail.none { it in ",;=|:\n" }, "details never carry separators")
        }
    }
}
