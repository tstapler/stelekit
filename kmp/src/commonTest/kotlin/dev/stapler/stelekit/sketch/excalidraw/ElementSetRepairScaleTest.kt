// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Review fix C2: duplicate-id and `frameId` cycle repair stay linear at the importer's widened 50,000-element bound. */
class ElementSetRepairScaleTest {
    private fun element(id: String, frameId: String? = null) =
        ExcalidrawElement("rectangle", id, 0.0, 0.0, 1.0, 1.0, 0.0, emptyList(), frameId, null, false, null, false, JsonObject(emptyMap()))

    private fun budget(): ParseBudget {
        val mark = TimeSource.Monotonic.markNow()
        return ParseBudget({ mark.elapsedNow().inWholeMilliseconds }, BUDGET_MILLIS)
    }

    @Test
    fun repair_should_FinishInTime_When_FiftyThousandElementsFormOneFrameChain() = runTest {
        val elements = (0 until SCALE).map { element("e$it", frameId = "e${it + 1}") }
        val issues = ArrayList<ImportIssue>()
        val repaired = ElementSetRepair.repair(elements, issues, budget())
        assertEquals(SCALE, repaired.size)
        assertTrue(issues.isEmpty(), "a chain without a loop has nothing to repair")
    }

    @Test
    fun repair_should_ClearEveryFrameId_When_FiftyThousandElementsFormOneCycle() = runTest {
        val elements = (0 until SCALE).map { element("e$it", frameId = "e${(it + 1) % SCALE}") }
        val issues = ArrayList<ImportIssue>()
        val repaired = ElementSetRepair.repair(elements, issues, budget())
        assertTrue(repaired.all { it.frameId == null })
        assertEquals(SCALE, issues.count { it.reason == ImportReason.CyclicFrame })
    }

    @Test
    fun repair_should_KeepTheTail_When_AChainLeadsIntoASmallCycle() = runTest {
        val elements = listOf(element("a", "b"), element("b", "c"), element("c", "b"), element("d", null))
        val repaired = ElementSetRepair.repair(elements, ArrayList(), budget())
        assertEquals("b", repaired[0].frameId)
        assertNull(repaired[1].frameId)
        assertNull(repaired[2].frameId)
    }

    @Test
    fun repair_should_RenameEveryCopyUniquely_When_FiftyThousandElementsShareOneId() = runTest {
        val issues = ArrayList<ImportIssue>()
        val repaired = ElementSetRepair.repair((0 until SCALE).map { element("same") }, issues, budget())
        assertEquals(SCALE, repaired.map { it.id }.toSet().size)
        assertEquals(SCALE - 1, issues.count { it.reason == ImportReason.DuplicateId })
    }

    private companion object {
        const val SCALE = 50_000
        const val BUDGET_MILLIS = 20_000L
    }
}
