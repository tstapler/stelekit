package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerfExporterPickerTest {

    // ── Minimal fakes ────────────────────────────────────────────────────────

    @OptIn(DirectRepositoryWrite::class)
    private class FakeSpanRepository : SpanRepository {
        private val spans = listOf(
            SerializedSpan(
                spanId = "s1", traceId = "t1", parentSpanId = "",
                name = "test.op", startEpochMs = 1000L, endEpochMs = 1050L,
                durationMs = 50L, statusCode = "OK", attributes = emptyMap()
            )
        )

        override fun getRecentSpans(limit: Int): Flow<List<SerializedSpan>> = flowOf(spans)
        @DirectRepositoryWrite override suspend fun insertSpan(span: SerializedSpan) = Unit
        @DirectRepositoryWrite override suspend fun deleteSpansOlderThan(cutoffEpochMs: Long) = Unit
        @DirectRepositoryWrite override suspend fun deleteExcessSpans(maxCount: Int) = Unit
        @DirectRepositoryWrite override suspend fun clear() = Unit
    }

    private class FakeFileSystem(
        private val pickResult: String? = null,
        private val fakeDownloadsPath: String = "/downloads",
        private val writeFileFails: Boolean = false,
    ) : FileSystem {
        val written = mutableMapOf<String, String>()

        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = null
        override fun writeFile(path: String, content: String): Boolean {
            if (writeFileFails) return false
            written[path] = content
            return true
        }
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun getDownloadsPath() = fakeDownloadsPath
        override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String) = pickResult
    }

    private fun realHistogramWriter(): HistogramWriter {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val scope = CoroutineScope(Dispatchers.IO)
        return HistogramWriter(database, scope)
    }

    private fun makePerfExporter(fs: FakeFileSystem) = PerfExporter(
        spanRepository = FakeSpanRepository(),
        histogramWriter = realHistogramWriter(),
        fileSystem = fs,
        appVersion = "test",
        platform = "jvm-test",
    )

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `exportWithPicker writes to the path returned by pickSaveFileAsync`() = runBlocking {
        val pickedPath = "/custom/chosen.json"
        val fs = FakeFileSystem(pickResult = pickedPath)
        val exporter = makePerfExporter(fs)

        val result = exporter.exportWithPicker()

        assertTrue(fs.written.containsKey(pickedPath),
            "File must be written to the picked path")
        assertTrue(result == pickedPath, "Result must be the picked path")
    }

    @Test
    fun `exportWithPicker written content is valid JSON with required fields`() = runBlocking {
        val pickedPath = "/out/report.json"
        val fs = FakeFileSystem(pickResult = pickedPath)
        val exporter = makePerfExporter(fs)

        exporter.exportWithPicker()

        val json = fs.written[pickedPath] ?: error("Nothing written to $pickedPath")
        assertTrue(json.contains("\"spans\""), "JSON must contain a spans field")
        assertTrue(json.contains("test.op"), "JSON must include the recorded span name")
        assertTrue(json.contains("\"platform\":\"jvm-test\""), "JSON must include the platform")
        assertTrue(json.contains("\"appVersion\":\"test\""), "JSON must include appVersion")
    }

    @Test
    fun `exportWithPicker falls back to Downloads when picker returns null`() = runBlocking {
        val fs = FakeFileSystem(pickResult = null, fakeDownloadsPath = "/downloads")
        val exporter = makePerfExporter(fs)

        val result = exporter.exportWithPicker()

        assertNotNull(result, "Should return a path even when picker is unavailable")
        assertTrue(result!!.startsWith("/downloads/"),
            "Fallback path must be inside the Downloads directory; got: $result")
        assertTrue(fs.written.containsKey(result), "File must be written to the fallback path")
    }

    @Test
    fun `export writes to custom directory when provided`() = runBlocking {
        val fs = FakeFileSystem()
        val exporter = makePerfExporter(fs)

        val result = exporter.export(directory = "/custom/dir")

        assertTrue(result.startsWith("/custom/dir/"), "Export path must use the provided directory")
        assertTrue(fs.written.containsKey(result), "File must be written to the export path")
    }

    @Test
    fun `export falls back to Downloads when directory is null`() = runBlocking {
        val fs = FakeFileSystem()
        val exporter = makePerfExporter(fs)

        val result = exporter.export(directory = null)

        assertTrue(result.startsWith("/downloads/"), "Null directory must fall back to Downloads")
        assertTrue(fs.written.containsKey(result), "File must be written")
    }

    @Test
    fun `exportWithPicker throws when writeFile returns false for the picked path`() = runBlocking {
        val pickedPath = "/chosen/report.json"
        val fs = FakeFileSystem(pickResult = pickedPath, writeFileFails = true)
        val exporter = makePerfExporter(fs)

        val threw = try { exporter.exportWithPicker(); false } catch (_: IllegalStateException) { true }
        assertTrue(threw, "exportWithPicker must throw when writeFile returns false")
    }

    @Test
    fun `pickSaveFileAsync returns null on base FileSystem default implementation`() = runBlocking {
        val fs = object : FileSystem {
            override fun getDefaultGraphPath() = "/graph"
            override fun expandTilde(path: String) = path
            override fun readFile(path: String) = null
            override fun writeFile(path: String, content: String) = false
            override fun listFiles(path: String) = emptyList<String>()
            override fun listDirectories(path: String) = emptyList<String>()
            override fun fileExists(path: String) = false
            override fun directoryExists(path: String) = false
            override fun createDirectory(path: String) = false
            override fun deleteFile(path: String) = false
            override fun pickDirectory() = null
            override fun getLastModifiedTime(path: String): Long? = null
        }
        assertNull(fs.pickSaveFileAsync("test.json", "application/json"),
            "Default FileSystem.pickSaveFileAsync must return null")
    }
}
