package dev.stapler.stelekit.performance

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.createTelemetryDatabaseInMemory
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the private `detectRegressions` through [PerfExporter.export] with spans tagged by
 * `app.version`, then reads the `regressions` array back out of the written report.
 */
class PerfExporterRegressionTest {

    private val current = "1.1.0"
    private val previous = "1.0.0"

    @OptIn(DirectRepositoryWrite::class)
    private class FakeSpanRepository(private val spans: List<SerializedSpan>) : SpanRepository {
        override fun getRecentSpans(limit: Int): Flow<Either<DomainError, List<SerializedSpan>>> = flowOf(spans.right())
        @DirectRepositoryWrite override suspend fun insertSpan(span: SerializedSpan) = Unit
        @DirectRepositoryWrite override suspend fun insertSpans(spans: List<SerializedSpan>) = Unit
        @DirectRepositoryWrite override suspend fun deleteSpansOlderThan(cutoffEpochMs: Long) = Unit
        @DirectRepositoryWrite override suspend fun deleteExcessSpans(maxCount: Int) = Unit
        @DirectRepositoryWrite override suspend fun clear() = Unit
    }

    private class CapturingFileSystem : FileSystem {
        var bytes: ByteArray? = null
        var text: String? = null
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean { text = content; return true }
        override fun writeFileBytes(path: String, data: ByteArray): Boolean { bytes = data; return true }
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun getDownloadsPath() = "/downloads"
        override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? = null

        fun reportJson(): String = bytes
            ?.let { java.util.zip.GZIPInputStream(it.inputStream()).bufferedReader().readText() }
            ?: checkNotNull(text) { "nothing written" }
    }

    private fun spansFor(version: String, durations: List<Long>, name: String = "op") =
        durations.mapIndexed { i, d ->
            SerializedSpan(
                name = name, startEpochMs = 0, endEpochMs = d, durationMs = d,
                attributes = mapOf("app.version" to version), spanId = "$version-$name-$i",
            )
        }

    private fun regressions(spans: List<SerializedSpan>): JsonArray = runBlocking {
        val fs = CapturingFileSystem()
        PerfExporter(
            spanRepository = FakeSpanRepository(spans),
            histogramWriter = HistogramWriter(createTelemetryDatabaseInMemory(), CoroutineScope(Dispatchers.IO)),
            fileSystem = fs,
            appVersion = current,
            platform = "jvm-test",
        ).export("/out")
        Json.parseToJsonElement(fs.reportJson()).jsonObject.getValue("regressions").jsonArray
    }

    @Test
    fun `no regressions when there is no prior version`() {
        assertEquals(0, regressions(spansFor(current, listOf(500, 500, 500))).size)
    }

    @Test
    fun `no regressions when current version is the oldest known`() {
        assertEquals(0, regressions(spansFor(current, listOf(500, 500, 500)) + spansFor("1.2.0", listOf(1, 1, 1))).size)
    }

    @Test
    fun `skipped when the baseline has fewer than MIN_REGRESSION_SAMPLES`() {
        val spans = spansFor(previous, listOf(100, 100)) + spansFor(current, listOf(900, 900, 900))
        assertEquals(0, regressions(spans).size)
    }

    @Test
    fun `skipped when the current version has fewer than MIN_REGRESSION_SAMPLES`() {
        val spans = spansFor(previous, listOf(100, 100, 100)) + spansFor(current, listOf(900, 900))
        assertEquals(0, regressions(spans).size)
    }

    @Test
    fun `skipped when the absolute p95 increase is at or below 100ms`() {
        // +100ms exactly (a 100% change) is still not enough — the abs gate is strict >.
        val spans = spansFor(previous, listOf(100, 100, 100)) + spansFor(current, listOf(200, 200, 200))
        assertEquals(0, regressions(spans).size)
    }

    @Test
    fun `skipped when the relative increase is at or below 50 percent`() {
        // +150ms passes the abs gate but is only 15% of a 1000ms baseline.
        val spans = spansFor(previous, listOf(1000, 1000, 1000)) + spansFor(current, listOf(1150, 1150, 1150))
        assertEquals(0, regressions(spans).size)
    }

    @Test
    fun `zero baseline p95 does not divide by zero and raises no alert`() {
        val spans = spansFor(previous, listOf(0, 0, 0)) + spansFor(current, listOf(900, 900, 900))
        assertEquals(0, regressions(spans).size)
    }

    @Test
    fun `operations missing from the baseline are ignored`() {
        val spans = spansFor(previous, listOf(100, 100, 100), name = "other") +
            spansFor(current, listOf(900, 900, 900), name = "op")
        assertEquals(0, regressions(spans).size)
    }

    @Test
    fun `genuine regression is reported and ordered by severity`() {
        val spans = spansFor(previous, listOf(100, 100, 100), "mild") + spansFor(current, listOf(300, 300, 300), "mild") +
            spansFor(previous, listOf(100, 100, 100), "severe") + spansFor(current, listOf(1100, 1100, 1100), "severe")
        val alerts = regressions(spans)
        assertEquals(listOf("severe", "mild"), alerts.map { it.jsonObject.getValue("operation").jsonPrimitive.content })
        val severe = alerts[0].jsonObject
        assertEquals(previous, severe.getValue("baselineVersion").jsonPrimitive.content)
        assertEquals(current, severe.getValue("currentVersion").jsonPrimitive.content)
        assertEquals(1000, severe.getValue("changePercent").jsonPrimitive.int)
        assertTrue(alerts[1].jsonObject.getValue("changePercent").jsonPrimitive.int == 200)
    }

    @Test
    fun `spans without a version tag never form a baseline`() {
        val untagged = (1..3).map {
            SerializedSpan(name = "op", startEpochMs = 0, endEpochMs = 1, durationMs = 100, spanId = "u$it")
        }
        assertEquals(0, regressions(untagged + spansFor(current, listOf(900, 900, 900))).size)
    }

    @Test
    fun `semver ordering compares numerically not lexically`() {
        // Lexically "1.10.0" < "1.9.0"; numerically 1.9.0 is the baseline for 1.10.0.
        val exporterVersion = "1.10.0"
        val spans = spansFor("1.9.0", listOf(100, 100, 100)) + spansFor(exporterVersion, listOf(900, 900, 900))
        val fs = CapturingFileSystem()
        runBlocking {
            PerfExporter(
                FakeSpanRepository(spans),
                HistogramWriter(createTelemetryDatabaseInMemory(), CoroutineScope(Dispatchers.IO)),
                fs, exporterVersion, "jvm-test",
            ).export("/out")
        }
        val alerts: List<JsonObject> = Json.parseToJsonElement(fs.reportJson()).jsonObject
            .getValue("regressions").jsonArray.map { it.jsonObject }
        assertEquals("1.9.0", alerts.single().getValue("baselineVersion").jsonPrimitive.content)
    }
}
