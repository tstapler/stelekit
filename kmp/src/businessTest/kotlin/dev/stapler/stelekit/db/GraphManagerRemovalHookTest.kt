// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `onGraphRemoved` fires only on the success path of `removeGraph`, after the registry is saved, and never changes the result. */
class GraphManagerRemovalHookTest {

    private class MemSettings : Settings {
        val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    private val settings = MemSettings()

    private fun newManager() = GraphManager(
        platformSettings = settings,
        driverFactory = DriverFactory(),
        fileSystem = StubFileSystem(),
        defaultBackend = GraphBackend.IN_MEMORY,
    )

    @Test
    fun removeGraph_should_InvokeHookAfterRegistrySaved_When_RemovalSucceeds() = runTest {
        val gm = newManager()
        val a = gm.addGraph("/test/graph-a")
        val b = gm.addGraph("/test/graph-b")
        gm.switchGraph(a)
        gm.awaitPendingMigration()
        val calls = mutableListOf<GraphId>()
        var registryGraphCountAtCall = -1
        var persistedHasRemovedAtCall = true
        gm.onGraphRemoved = { id ->
            calls += id
            registryGraphCountAtCall = gm.graphRegistry.value.graphs.count { it.id == b }
            persistedHasRemovedAtCall = settings.getString("graph_registry", "").contains(b.value)
        }

        assertTrue(gm.removeGraph(b))

        assertEquals(listOf(b), calls)
        assertEquals(0, registryGraphCountAtCall)
        assertFalse(persistedHasRemovedAtCall, "the registry must already be saved without the graph")
    }

    @Test
    fun removeGraph_should_NotInvokeHook_When_GraphNotFound() = runTest {
        val gm = newManager()
        var called = false
        gm.onGraphRemoved = { called = true }

        assertFalse(gm.removeGraph(GraphId("does-not-exist")))
        assertFalse(called)
    }

    @Test
    fun removeGraph_should_NotInvokeHook_When_ActiveGraphIsNotTheLastRealGraph() = runTest {
        val gm = newManager()
        val a = gm.addGraph("/test/graph-a")
        gm.addGraph("/test/graph-b")
        gm.switchGraph(a)
        gm.awaitPendingMigration()
        var called = false
        gm.onGraphRemoved = { called = true }

        assertFalse(gm.removeGraph(a))
        assertFalse(called)
    }

    @Test
    fun removeGraph_should_NotInvokeHook_When_GraphIsDemo() = runTest {
        val gm = newManager()
        val demo = gm.graphRegistry.value.graphs.firstOrNull { it.isDemo }?.id ?: gm.addDemoGraph()
        var called = false
        gm.onGraphRemoved = { called = true }

        assertFalse(gm.removeGraph(demo))
        assertFalse(called)
    }

    @Test
    fun removeGraph_should_StillReturnTrueAndKeepRegistrySaved_When_HookThrows() = runTest {
        val gm = newManager()
        val a = gm.addGraph("/test/graph-a")
        val b = gm.addGraph("/test/graph-b")
        gm.switchGraph(a)
        gm.awaitPendingMigration()
        gm.onGraphRemoved = { throw IllegalStateException("boom") }

        assertTrue(gm.removeGraph(b))

        assertTrue(gm.graphRegistry.value.graphs.none { it.id == b })
        assertFalse(settings.getString("graph_registry", "").contains(b.value))
    }
}
