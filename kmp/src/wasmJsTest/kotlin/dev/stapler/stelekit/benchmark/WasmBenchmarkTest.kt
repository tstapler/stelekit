package dev.stapler.stelekit.benchmark

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
        val names = BenchmarkRegistry.all.map { it.name }.toSet()
        assertEquals(setOf("GraphLoad", "WriteConcurrency", "NavigationLatency", "UserSession"), names)
        assertEquals(5, BenchmarkRegistry.all.size)
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
        assertTrue(json.contains("\"platform\""), "JSON must contain 'platform' key")
        assertTrue(json.contains("\"scenario\""), "JSON must contain 'scenario' key")
        assertTrue(json.contains("wasmjs"), "JSON must contain platform value")
        assertTrue(json.contains("GraphLoad"), "JSON must contain scenario value")
    }
}
