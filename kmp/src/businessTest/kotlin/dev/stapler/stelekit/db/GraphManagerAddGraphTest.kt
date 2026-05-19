package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * Regression test for the addGraph ANR bug:
 * addGraph was non-suspend and called fileSystem.fileExists/readFile/displayNameForPath
 * on the caller's dispatcher (main thread in LaunchedEffect). This caused ANR on real
 * Android hardware. addGraph must now be suspend and dispatch I/O to PlatformDispatcher.IO.
 */
class GraphManagerAddGraphTest {

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
    }

    /**
     * Minimal FileSystem stub — all operations are no-ops / return safe defaults.
     * Subclassed in tests that need to observe behaviour.
     */
    private open class StubFileSystem : FileSystem {
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

    @Test
    fun `addGraph dispatches fileExists off the caller thread`() = runTest {
        val callerThreadId = Thread.currentThread().id
        var fileExistsThreadId: Long = callerThreadId  // pre-initialize to caller to make the assertion fail if never called

        val recordingFs = object : StubFileSystem() {
            override fun fileExists(path: String): Boolean {
                fileExistsThreadId = Thread.currentThread().id
                return false
            }
        }

        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = recordingFs,
            defaultBackend = GraphBackend.IN_MEMORY,
        )

        graphManager.addGraph("/test/graph")

        assertNotNull(fileExistsThreadId)
        assertNotEquals(
            callerThreadId,
            fileExistsThreadId,
            "addGraph must dispatch fileExists to PlatformDispatcher.IO, not the caller thread. " +
                "Caller thread id=${callerThreadId}, fileExists ran on id=${fileExistsThreadId}. " +
                "Regression: calling fileExists on the main thread causes ANR on Android.",
        )
    }

    @Test
    fun `addGraph dispatches readFile off the caller thread`() = runTest {
        val callerThreadId = Thread.currentThread().id
        var readFileThreadId: Long = callerThreadId

        val recordingFs = object : StubFileSystem() {
            override fun fileExists(path: String) = path.endsWith(".gitignore")  // trigger readFile call
            override fun readFile(path: String): String? {
                readFileThreadId = Thread.currentThread().id
                return null
            }
        }

        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = recordingFs,
            defaultBackend = GraphBackend.IN_MEMORY,
        )

        graphManager.addGraph("/test/graph")

        assertNotEquals(
            callerThreadId,
            readFileThreadId,
            "addGraph must dispatch readFile to PlatformDispatcher.IO, not the caller thread. " +
                "Regression: calling readFile on the main thread causes ANR on Android.",
        )
    }
}
