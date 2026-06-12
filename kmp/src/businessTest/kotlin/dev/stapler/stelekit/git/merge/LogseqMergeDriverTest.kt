package dev.stapler.stelekit.git.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogseqMergeDriverTest {

    // ---- AndroidDataLossProtectionStrategy ----

    @Test
    fun androidDataLoss_remoteSmallLocalLarge_returnsLocal() {
        val base = List(10) { "line $it" }
        val local = List(10) { "line $it" }
        val remote = listOf("line 0", "line 1", "line 2") // < 70% of base

        val strategy = AndroidDataLossProtectionStrategy()
        assertTrue(strategy.canHandle(base, local, remote))

        val result = strategy.applyMerge(base, local, remote)
        assertEquals(local, result)
    }

    @Test
    fun androidDataLoss_remoteHasUniqueLines_appendsToLocal() {
        val base = List(10) { "line $it" }
        val local = List(10) { "line $it" }
        val remote = listOf("line 0", "only in remote")

        val strategy = AndroidDataLossProtectionStrategy()
        assertTrue(strategy.canHandle(base, local, remote))

        val result = strategy.applyMerge(base, local, remote)
        assertTrue(result.contains("only in remote"))
        assertTrue(result.contains(""))
        // local lines come first
        assertEquals(local, result.subList(0, local.size))
    }

    // ---- SimpleAdditionMergeStrategy ----

    @Test
    fun simpleAddition_localAddsLine_returnsLocal() {
        val base = listOf("line 1", "line 2")
        val local = listOf("line 1", "line 2", "new line")
        val remote = listOf("line 1", "line 2")

        val strategy = SimpleAdditionMergeStrategy()
        assertTrue(strategy.canHandle(base, local, remote))

        val result = strategy.applyMerge(base, local, remote)
        assertEquals(local, result)
    }

    @Test
    fun simpleAddition_remoteAddsLine_returnsRemote() {
        val base = listOf("line 1", "line 2")
        val local = listOf("line 1", "line 2")
        val remote = listOf("line 1", "line 2", "new line")

        val strategy = SimpleAdditionMergeStrategy()
        assertTrue(strategy.canHandle(base, local, remote))

        val result = strategy.applyMerge(base, local, remote)
        assertEquals(remote, result)
    }

    // ---- NonOverlappingChangeMergeStrategy ----

    @Test
    fun nonOverlapping_differentLines_mergesCorrectly() {
        val base = listOf("a", "b")
        val local = listOf("x", "b")
        val remote = listOf("a", "y")

        val strategy = NonOverlappingChangeMergeStrategy()
        assertTrue(strategy.canHandle(base, local, remote))

        val result = strategy.applyMerge(base, local, remote)
        assertEquals(listOf("x", "y"), result)
    }

    @Test
    fun nonOverlapping_bothChangedSameLine_hasConflictMarker() {
        val base = listOf("a", "b")
        val local = listOf("x", "b")
        val remote = listOf("z", "b")

        val strategy = NonOverlappingChangeMergeStrategy()
        assertTrue(strategy.canHandle(base, local, remote))

        val result = strategy.applyMerge(base, local, remote)
        assertTrue(result.any { it.startsWith("<<<<<<< LOCAL") })
        assertTrue(result.contains("x"))
        assertTrue(result.contains("z"))
    }

    // ---- LogseqPageReferenceMergeStrategy ----

    @Test
    fun logseqPageRef_bothAddDifferentLines_bothInOutput() {
        val base = listOf("- [[Page A]]", "- existing note")
        val local = listOf("- [[Page A]]", "- existing note", "- local addition")
        val remote = listOf("- [[Page A]]", "- existing note", "- remote addition")

        val strategy = LogseqPageReferenceMergeStrategy()
        assertTrue(strategy.canHandle(base, local, remote))

        val result = strategy.applyMerge(base, local, remote)
        assertTrue(result.contains("- local addition"), "Expected local addition in: $result")
        assertTrue(result.contains("- remote addition"), "Expected remote addition in: $result")
        // No duplicates of base lines
        assertEquals(1, result.count { it == "- [[Page A]]" })
        assertEquals(1, result.count { it == "- existing note" })
    }

    // ---- FallbackMergeStrategy ----

    @Test
    fun fallback_remoteHasNewLine_appendsRemoteOnlyLine() {
        val base = listOf("line 1", "line 2")
        val local = listOf("line 1", "line 2")
        val remote = listOf("line 1", "line 2", "new remote line")

        val strategy = FallbackMergeStrategy()
        assertTrue(strategy.canHandle(base, local, remote))

        val result = strategy.applyMerge(base, local, remote)
        assertTrue(result.contains("new remote line"), "Expected new remote line in: $result")
    }

    // ---- LogseqMergeDriver integration ----

    @Test
    fun driver_bothDevicesAddedSameBullet_singleCopy() {
        val base = listOf("- morning note")
        val local = listOf("- morning note", "- both added this")
        val remote = listOf("- morning note", "- both added this")

        val driver = LogseqMergeDriver()
        val result = driver.merge(base, local, remote)

        val count = result.lines.count { it == "- both added this" }
        assertEquals(1, count, "Expected exactly one copy but got: ${result.lines}")
        assertFalse(result.hasConflictMarkers)
    }

    @Test
    fun driver_conflictProducesMarkers_hasConflictMarkersTrue() {
        val base = listOf("a", "b")
        val local = listOf("x", "b")
        val remote = listOf("z", "b")

        val driver = LogseqMergeDriver()
        val result = driver.merge(base, local, remote)

        assertTrue(result.hasConflictMarkers, "Expected conflict markers")
    }
}
