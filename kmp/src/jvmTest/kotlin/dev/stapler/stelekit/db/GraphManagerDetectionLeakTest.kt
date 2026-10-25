// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(dev.stapler.stelekit.model.RegistryRootPath::class)

package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A graph's git/wiki detection runs fire-and-forget. Whatever it throws must stay in GraphManager:
 * an escaped exception reaches the JVM's uncaught-exception handler, where kotlinx-coroutines-test
 * reports it as `UncaughtExceptionsBeforeTest` on whichever unrelated test runs next.
 */
class GraphManagerDetectionLeakTest {
    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** Every folder exists, but looking for a notes folder's `pages/` fails, as a vanished SAF grant would. */
    private class ExplodingPagesFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String): Boolean {
            if (path.endsWith("/pages")) throw IllegalStateException("storage vanished: $path")
            return true
        }
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
        override fun renameFile(from: String, to: String) = true
    }

    private fun newManager(fs: FileSystem, backend: GraphBackend = GraphBackend.IN_MEMORY) = GraphManager(
        platformSettings = MapSettings(),
        driverFactory = DriverFactory(),
        fileSystem = fs,
        defaultBackend = backend,
    )

    private inline fun <T> recordingUncaught(block: (CopyOnWriteArrayList<Throwable>) -> T): T {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            return block(uncaught)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun addGraph_should_NotLeakUncaughtException_When_DetectionFails() = runTest {
        recordingUncaught { uncaught ->
            val manager = newManager(ExplodingPagesFileSystem())
            manager.addGraph("/graphs/a")
            manager.awaitBackgroundDetection()
            delay(300) // an unawaited job would throw about now
            manager.shutdown()
            assertEquals(emptyList(), uncaught.map { it.message })
        }
    }

    @Test
    fun updateGraphPath_should_NotLeakUncaughtException_When_DetectionFails() = runTest {
        recordingUncaught { uncaught ->
            val manager = newManager(ExplodingPagesFileSystem())
            val id = manager.addGraph("/graphs/a")
            manager.awaitBackgroundDetection()
            val result = manager.updateGraphPath(id, "/graphs/b")
            assertTrue(result is UpdateGraphPathResult.Success, "move should succeed: $result")
            manager.awaitBackgroundDetection()
            delay(300)
            manager.shutdown()
            assertEquals(emptyList(), uncaught.map { it.message })
        }
    }

    @Test
    fun createGitConfigRepository_should_ReturnNull_And_OpenNoDatabase_When_BackendIsInMemory() = runTest {
        val dataDir = File.createTempFile("stele-inmem", "").also { it.delete(); it.mkdirs() }
        val previous = System.getProperty("stelekit.devDataDir")
        System.setProperty("stelekit.devDataDir", dataDir.absolutePath)
        try {
            val manager = newManager(ExplodingPagesFileSystem().let { fs ->
                object : FileSystem by fs {
                    override fun directoryExists(path: String) = true
                }
            })
            val id = manager.addGraph("/graphs/a")
            manager.awaitBackgroundDetection()
            manager.switchGraph(id)
            manager.awaitPendingMigration()

            assertNull(manager.createGitConfigRepository(), "in-memory graphs have no git config table")
            assertEquals(
                emptyList(), dataDir.walkTopDown().filter { it.extension == "db" }.map { it.name }.toList(),
                "an in-memory graph must not create a SQLite file in the shared data directory",
            )
            manager.shutdown()
        } finally {
            if (previous != null) System.setProperty("stelekit.devDataDir", previous) else System.clearProperty("stelekit.devDataDir")
            dataDir.deleteRecursively()
        }
    }
}
