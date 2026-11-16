// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 1.4.3r: losing the writer lease makes the saver read-only, never `SaveFailed`. */
class WriterLeaseSaverTest {
    @Test
    fun refusal_should_BecomeReadOnlyNotSaveFailed() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val rig = SaverRig(MixedProjectFixtures.device(convertA = true, lease = lease), coroutineContext)
        val before = rig.diskBytes()
        lease.lose()

        rig.saver.submit(SaverRig.withRect(rig.loadedDoc(), "mine", 300))
        val state = rig.saver.flush()

        assertEquals(SaveState.LeaseConflict, state)
        assertContentEquals(before, rig.diskBytes(), "nothing was written")
        assertEquals(listOf(BannerRowId.LeaseConflict), rig.rows())
        assertTrue(rig.logLines.none { it.contains("persist.failed") }, "no SaveFailed line: ${rig.logLines}")
        rig.saver.close()
    }

    @Test
    fun leaseConflict_should_StopTheDebounce_SoLaterEditsStayReadOnly() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val rig = SaverRig(MixedProjectFixtures.device(convertA = true, lease = lease), coroutineContext)
        lease.lose()
        rig.saver.submit(SaverRig.withRect(rig.loadedDoc(), "one", 100))
        rig.saver.flush()
        lease.reacquire()

        rig.saver.submit(SaverRig.withRect(rig.loadedDoc(), "two", 200))

        assertEquals(SaveState.LeaseConflict, rig.saver.flush())
        assertTrue(rig.saver.state.value.hasUnsavedWork)
        rig.saver.close()
    }
}
