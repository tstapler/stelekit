package dev.stapler.stelekit.benchmark

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lightweight wasmJs tests that verify shared benchmark types compile and behave correctly
 * on the wasmJs target.
 *
 * No database, no FileSystem, no browser APIs — pure data structure tests.
 * These run in headless Chromium via wasmJsBrowserTest (puppeteer-backed).
 */
class WasmBenchmarkTest {

    @Test
    fun benchmarkRegistry_containsExpectedScenarios() {
        assertEquals(5, BenchmarkRegistry.all.size)
        assertEquals(
            listOf(
                "GraphLoad" to "TINY",
                "GraphLoad" to "SMALL",
                "WriteConcurrency" to "TINY",
                "NavigationLatency" to "SMALL",
                "UserSession" to "SMALL",
            ),
            BenchmarkRegistry.all.map { it.name to it.preset.name },
        )
    }

    @Test
    fun syntheticGraphContent_tiny_hasExpectedShape() {
        val content = SyntheticGraphContentFactory.tiny()
        assertTrue(content.pages.isNotEmpty(), "TINY preset must produce pages")
        assertTrue(content.journals.isNotEmpty(), "TINY preset must produce journals")
        assertEquals(50, content.pages.size)
        assertEquals(14, content.journals.size)
    }

    @Test
    fun scenarioDescriptor_wasmjsNotInUserSession() {
        val userSession = BenchmarkRegistry.all.first { it.name == "UserSession" }
        assertFalse(
            "wasmjs" in userSession.supportedPlatforms,
            "UserSession must not list wasmjs as a supported platform (requires real graph)",
        )
    }

    @Test
    fun benchmarkResult_serializesToJson() {
        val result = BenchmarkResult(
            platform = "wasmjs",
            scenario = "GraphLoad",
            graphConfig = "TINY",
            runAtEpochMs = 1748736000000L,
            gitSha = "abc1234",
            metrics = mapOf("totalMs" to 1234.0, "phase1TtiMs" to 500.0),
        )
        val json = Json.encodeToString(result)
        val decoded = Json.decodeFromString<BenchmarkResult>(json)
        assertEquals("wasmjs", decoded.platform)
        assertEquals("GraphLoad", decoded.scenario)
        assertEquals(1234.0, decoded.metrics["totalMs"])
        assertEquals(500.0, decoded.metrics["phase1TtiMs"])
    }
}
