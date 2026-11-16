// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.annotate.performanceNowMs
import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Task 5.1.1f: the importer on the REAL single wasm thread. The body runs on `Dispatchers.Default` with real
 * timers (`kotlinx-coroutines-test` virtual time would skip the event loop this test is about), so a
 * `setTimeout(0)` scheduled before the parse can only fire if the parse yields. The binary check is that the
 * callback fires before the parse finishes. The wall-clock figures are RECORDED (one `SKETCH-IMPORT-RESULT` line,
 * copied into `benchmarks/budgets/sketch-engine-budgets.json`), not asserted: they depend on the host. If the worst
 * single-span decode exceeds [DECODE_REVIEW_MS], `maxImportSpanBytes` is lowered in `SceneLimits` before release.
 */
class JsonSpanScannerWasmTest {
    private class TimingDecoder : SpanDecoder {
        var worstMsPerMb = 0.0
        override fun decode(text: String): JsonElement {
            val t0 = performanceNowMs()
            val tree = Json.parseToJsonElement(text)
            val ms = performanceNowMs() - t0
            if (text.length >= MEASURE_FROM_CHARS) worstMsPerMb = maxOf(worstMsPerMb, ms / (text.length.toDouble() / BYTES_PER_MB))
            return tree
        }
    }

    private class Observation(val callbackYieldCount: Int, val finalYieldCount: Int, val maxGapMs: Double, val worstDecodeMsPerMb: Double, val ok: Boolean)

    private suspend fun observe(run: suspend (ParseBudget, TimingDecoder) -> Boolean): Observation = withContext(Dispatchers.Default) {
        val decoder = TimingDecoder()
        var lastNow = performanceNowMs()
        var worstGap = 0.0
        val budget = ParseBudget({ performanceNowMs().toLong() }, Long.MAX_VALUE, yielder = {
            val now = performanceNowMs()
            worstGap = maxOf(worstGap, now - lastNow)
            kotlinx.coroutines.yield()
            lastNow = performanceNowMs()
        })
        var yieldsWhenCallbackFired = -1
        window.setTimeout({
            yieldsWhenCallbackFired = budget.yieldCount
            null
        }, 0)
        val ok = run(budget, decoder)
        Observation(yieldsWhenCallbackFired, budget.yieldCount, worstGap, decoder.worstMsPerMb, ok)
    }

    private fun report(kind: String, o: Observation) {
        println(
            "SKETCH-IMPORT-RESULT {\"kind\":\"$kind\",\"sketch.import_max_yield_gap_ms\":${o.maxGapMs},\"sketch.import_span_decode_ms_per_mb\":${o.worstDecodeMsPerMb}," +
                "\"yields\":${o.finalYieldCount},\"callback_fired_at_yield\":${o.callbackYieldCount},\"lower_maxImportSpanBytes\":${o.worstDecodeMsPerMb * (SceneLimits.Default.maxImportSpanBytes / BYTES_PER_MB) > DECODE_REVIEW_MS}}",
        )
    }

    private fun assertEventLoopWasNotStarved(o: Observation) {
        assertTrue(o.ok, "the import itself failed")
        assertTrue(o.finalYieldCount > 0, "the parse never yielded")
        assertTrue(o.callbackYieldCount >= 0, "the setTimeout(0) callback never fired during the parse")
        assertTrue(o.callbackYieldCount < o.finalYieldCount, "the callback fired only after the parse finished (${o.callbackYieldCount} of ${o.finalYieldCount} yields)")
    }

    @Test
    fun import_should_LetATimerFire_When_ParsingAnImageBearingTwentyMegabyteFile(): TestResult = runTest(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        val elements = (0 until ELEMENTS).map { element("rectangle", "e$it", extra = ""","customData":{"pad":"${"z".repeat(PAD)}"}""") }
        val image = ExcalidrawTestData.pngBytes(IMAGE_SIDE, IMAGE_SIDE, IMAGE_BYTES)
        val text = ExcalidrawTestData.scene(elements, ExcalidrawTestData.files(ExcalidrawTestData.fileEntry("big", "image/png", image)))
        val bytes = text.encodeToByteArray()
        val o = observe { budget, decoder ->
            val result = ExcalidrawParser(decoder = decoder).parse(ImportSource.ofBytes(bytes), budget)
            result is Either.Right && result.value.scene.elements.size == ELEMENTS && result.value.scene.files.single().bytes.size == image.size
        }
        report("scene-20mb", o)
        assertEventLoopWasNotStarved(o)
    }

    @Test
    fun import_should_LetATimerFire_When_ParsingAnineMegabyteLibraryOfOneHundredTwentyItems(): TestResult = runTest(timeout = TEST_TIMEOUT_SECONDS.seconds) {
        val items = (0 until LIBRARY_ITEMS).map { i ->
            """{"id":"i$i","status":"published","created":1,"elements":[${element("rectangle", "r$i")}],"pad":"${"p".repeat(ITEM_PAD)}"}"""
        }
        val text = """{"type":"excalidrawlib","version":2,"source":"x","libraryItems":[${items.joinToString(",")}]}"""
        val bytes = text.encodeToByteArray()
        val o = observe { budget, decoder ->
            val result = ExcalidrawParser(decoder = decoder).parseLibrary(ImportSource.ofBytes(bytes), budget)
            result is Either.Right && result.value.items.size == LIBRARY_ITEMS
        }
        report("library-9mb", o)
        assertEventLoopWasNotStarved(o)
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 120
        const val ELEMENTS = 4_000
        const val PAD = 700
        const val IMAGE_SIDE = 100
        const val IMAGE_BYTES = 11_250_000
        const val LIBRARY_ITEMS = 120
        const val ITEM_PAD = 75_000
        const val MEASURE_FROM_CHARS = 10_000
        const val BYTES_PER_MB = 1_048_576.0
        const val DECODE_REVIEW_MS = 100
    }
}
