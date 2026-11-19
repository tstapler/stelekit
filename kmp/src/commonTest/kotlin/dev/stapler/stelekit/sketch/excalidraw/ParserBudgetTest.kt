// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.scene
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 5.1.1d: the cooperative `ParseBudget` (a time cap that works on a single-threaded wasmJs). */
class ParserBudgetTest {
    private val limits = SceneLimits.Default

    private fun manyElements(n: Int) = scene((0 until n).map { element("rectangle", "e$it") })

    @Test
    fun parse_should_ReturnImportTimeout_When_ADeadlinePassesWithinFiveYields() = runTest {
        var now = 0L
        val budget = ParseBudget({ now }, limits.maxParseMillis, yielder = { now += ONE_SECOND; yield() })
        val source = ImportSource.ofBytes(manyElements(TEN_THOUSAND).encodeToByteArray())

        val result = ExcalidrawParser().parse(source, budget)

        val left = (result as Either.Left).value
        assertEquals(ImportReason.ImportTimeout, left.reason)
        assertTrue(budget.yieldCount <= FIVE, "yielded ${budget.yieldCount} times before giving up")
        assertTrue(budget.maxBytesBetweenYields <= ParseBudget.YIELD_BYTES + 1, "slice was ${budget.maxBytesBetweenYields} bytes")
    }

    @Test
    fun parse_should_NeverRunMoreThanOneElementSlice_BetweenYields() = runTest {
        val budget = ParseBudget({ 0L }, Long.MAX_VALUE)
        val result = ExcalidrawParser().parse(ImportSource.ofBytes(manyElements(limits.maxElements).encodeToByteArray()), budget)
        assertTrue(result is Either.Right)
        assertTrue(budget.maxElementsBetweenYields <= ParseBudget.YIELD_ELEMENTS, "slice was ${budget.maxElementsBetweenYields} elements")
        assertTrue(budget.yieldCount > 0)
    }

    @Test
    fun parse_should_StopWithinOneSlice_When_TheCallerCancels() = runTest {
        var yields = 0
        var outcome: Any? = null
        val job = launch {
            val budget = ParseBudget({ 0L }, Long.MAX_VALUE, yielder = {
                yields++
                if (yields == 2) currentCoroutineContext().job.cancel()
                yield()
            })
            outcome = ExcalidrawParser().parse(ImportSource.ofBytes(manyElements(TEN_THOUSAND).encodeToByteArray()), budget)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, yields)
        assertEquals(null, outcome, "a cancelled parse must not return a result")
    }

    @Test
    fun parse_should_StayInsideTheDefaultBudget_When_TheClockIsReal() = runTest {
        val result = ExcalidrawParser().parse(ImportSource.ofBytes(ExcalidrawTestData.mixedScene().encodeToByteArray()))
        assertTrue(result is Either.Right)
    }

    private companion object {
        const val ONE_SECOND = 1_000L
        const val FIVE = 5
        const val TEN_THOUSAND = 10_000
    }
}
