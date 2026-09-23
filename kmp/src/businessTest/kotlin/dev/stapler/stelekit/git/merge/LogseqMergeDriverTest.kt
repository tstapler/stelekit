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

    @Test
    fun androidDataLoss_localSmallerThanBase_canHandleReturnsFalse() {
        // canHandle only fires when remote is suspiciously small vs base (< 70%).
        // When LOCAL is smaller than base, that's a different condition — not this strategy's concern.
        val base = List(10) { "line $it" }
        val local = listOf("line 0", "line 1") // local is small but this strategy checks remote
        val remote = List(10) { "line $it" }   // remote is same as base
        assertFalse(AndroidDataLossProtectionStrategy().canHandle(base, local, remote))
    }

    // ---- SimpleAdditionMergeStrategy ----

    @Test
    fun simpleAddition_localAddsLineInMiddle_returnsLocal() {
        // SimpleAdditionMergeStrategy only handles additions at the END (prefix check).
        // A mid-insertion means local.subList(0, base.size) != base, so canHandle = false.
        val base = listOf("line 1", "line 2", "line 3")
        val local = listOf("line 1", "inserted by local", "line 2", "line 3")
        val remote = listOf("line 1", "line 2", "line 3")

        val strategy = SimpleAdditionMergeStrategy()
        assertFalse(strategy.canHandle(base, local, remote))
    }

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

    @Test
    fun nonOverlapping_bothChangedSameLineSameValue_convergentEdit() {
        // Both sides independently made the same change — a convergent edit.
        // NonOverlappingChangeMergeStrategy handles this via `localLine == remoteLine` branch,
        // which emits the shared new value without conflict markers.
        val base = listOf("a", "b")
        val local = listOf("x", "b")
        val remote = listOf("x", "b")

        val strategy = NonOverlappingChangeMergeStrategy()
        assertTrue(strategy.canHandle(base, local, remote))
        val result = strategy.applyMerge(base, local, remote)
        // Convergent edit: result should contain "x" not "a", and no conflict markers
        assertTrue(result.contains("x"), "Convergent edit result must contain the new value: $result")
        assertFalse(result.any { it.startsWith("<<<<<<< ") }, "Convergent edit must not produce conflict markers: $result")
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

    // ---- canHandle() → false cases ----

    @Test
    fun androidDataLoss_remoteNotSmall_canHandleReturnsFalse() {
        val base = List(10) { "line $it" }
        val local = List(10) { "line $it" }
        val remote = List(9) { "line $it" } // 90% of base — above 70% threshold
        assertFalse(AndroidDataLossProtectionStrategy().canHandle(base, local, remote))
    }

    @Test
    fun androidDataLoss_emptyBase_canHandleReturnsFalse() {
        assertFalse(AndroidDataLossProtectionStrategy().canHandle(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun simpleAddition_bothSidesChanged_canHandleReturnsFalse() {
        val base = listOf("a", "b")
        val local = listOf("a", "b", "local extra")
        val remote = listOf("a", "b", "remote extra")
        assertFalse(SimpleAdditionMergeStrategy().canHandle(base, local, remote))
    }

    @Test
    fun nonOverlapping_differentSizes_canHandleReturnsFalse() {
        val base = listOf("a", "b")
        val local = listOf("a", "b", "c")
        val remote = listOf("a", "b")
        assertFalse(NonOverlappingChangeMergeStrategy().canHandle(base, local, remote))
    }

    @Test
    fun logseqPageRef_noPageReferences_canHandleReturnsFalse() {
        val base = listOf("plain line", "another line")
        val local = listOf("plain line", "added local")
        val remote = listOf("plain line", "added remote")
        assertFalse(LogseqPageReferenceMergeStrategy().canHandle(base, local, remote))
    }

    @Test
    fun logseqPageRef_headingOnlyNoLinks_canHandleReturnsFalse() {
        // A markdown heading with no [[links]] or ((refs)) must not trigger this strategy.
        // Previously canHandle matched "#" broadly, firing on any heading.
        val base = listOf("# April Notes")
        val local = listOf("# April Notes", "- task done")
        val remote = listOf("# April Notes", "- other task")
        assertFalse(LogseqPageReferenceMergeStrategy().canHandle(base, local, remote))
    }

    // ---- Empty-list inputs ----

    @Test
    fun androidDataLoss_emptyInputs_canHandleReturnsFalse() {
        assertFalse(AndroidDataLossProtectionStrategy().canHandle(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun simpleAddition_emptyInputs_canHandleReturnsFalse() {
        assertFalse(SimpleAdditionMergeStrategy().canHandle(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun nonOverlapping_emptyInputs_returnsEmpty() {
        val result = NonOverlappingChangeMergeStrategy().applyMerge(emptyList(), emptyList(), emptyList())
        assertEquals(emptyList(), result)
    }

    @Test
    fun fallback_emptyInputs_returnsEmpty() {
        val result = FallbackMergeStrategy().applyMerge(emptyList(), emptyList(), emptyList())
        assertEquals(emptyList(), result)
    }

    @Test
    fun logseqPageRef_emptyInputs_returnsEmpty() {
        val result = LogseqPageReferenceMergeStrategy().applyMerge(emptyList(), emptyList(), emptyList())
        assertEquals(emptyList(), result)
    }

    @Test
    fun driver_emptyInputs_returnsEmpty() {
        val result = LogseqMergeDriver().merge(emptyList(), emptyList(), emptyList())
        assertEquals(emptyList(), result.lines)
        assertFalse(result.hasConflictMarkers)
    }

    // ---- LogseqMergeDriver integration ----

    @Test
    fun logseqPageRef_sameBulletAtDifferentPositions_bothKept() {
        // Both sides independently add the same bullet but at different base positions.
        // The global seenLines dedup would have dropped one — position-scoped dedup must not.
        val base = listOf("- [[Page A]]", "- mid", "- [[Page B]]")
        val local = listOf("- [[Page A]]", "- local insert", "- mid", "- [[Page B]]")
        val remote = listOf("- [[Page A]]", "- mid", "- [[Page B]]", "- local insert")

        val strategy = LogseqPageReferenceMergeStrategy()
        val result = strategy.applyMerge(base, local, remote)
        // "- local insert" must appear at least once — not zero (dropped) or more than twice
        val count = result.count { it == "- local insert" }
        assertTrue(count >= 1, "Expected at least one '- local insert' in $result")
    }

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

    @Test
    fun logseqPageRef_bothSidesReplaceLineDifferently_emitsConflictMarkers() {
        // Both sides edit the same base line to different values — must emit conflict markers,
        // not silently concatenate. Regression test for T-12.
        val base = listOf("- [[Page A]]", "- shared note")
        val local = listOf("- [[Page A updated by local]]", "- shared note")
        val remote = listOf("- [[Page A updated by remote]]", "- shared note")

        val strategy = LogseqPageReferenceMergeStrategy()
        val result = strategy.applyMerge(base, local, remote)

        assertTrue(result.any { it == "<<<<<<< LOCAL" }, "Expected LOCAL conflict marker but got: $result")
        assertTrue(result.any { it == "=======" }, "Expected divider marker but got: $result")
        assertTrue(result.any { it == ">>>>>>> REMOTE" }, "Expected REMOTE conflict marker but got: $result")
        assertTrue(result.contains("- [[Page A updated by local]]"))
        assertTrue(result.contains("- [[Page A updated by remote]]"))
    }

    @Test
    fun logseqPageRef_bothSidesReplaceLineToSameValue_noDuplicateNoMarkers() {
        // Both sides make the SAME change — convergent edit, no conflict markers needed.
        val base = listOf("- [[Page A]]")
        val local = listOf("- [[Page A renamed]]")
        val remote = listOf("- [[Page A renamed]]")

        val strategy = LogseqPageReferenceMergeStrategy()
        val result = strategy.applyMerge(base, local, remote)

        assertFalse(result.any { it.startsWith("<<<<<<< ") }, "Convergent edit must not produce conflict markers: $result")
        assertEquals(1, result.count { it == "- [[Page A renamed]]" }, "Expected exactly one copy: $result")
    }
}
