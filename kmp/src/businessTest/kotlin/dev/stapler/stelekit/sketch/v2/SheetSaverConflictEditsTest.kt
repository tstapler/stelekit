// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Review finding B2: edits made after a conflict are never discarded without a conflict copy. */
class SheetSaverConflictEditsTest {
    private suspend fun conflicted(context: CoroutineContext, external: MutableSharedFlow<Unit>? = null): SaverRig {
        val a = SaverRig(MixedProjectFixtures.device(convertA = true), context, externalChanges = external ?: kotlinx.coroutines.flow.emptyFlow())
        val b = SaverRig(MixedProjectFixtures.device(convertA = true), context)
        b.saver.submit(SaverRig.withRect(b.loadedDoc(), "theirs", 700))
        assertEquals(SaveState.Saved, b.saver.flush())
        a.device.pullFrom(b.device)
        b.saver.close()
        return a
    }

    private fun copies(a: SaverRig): List<ByteArray> =
        a.device.fs.allFilePaths().filter { it.contains("/conflicts/") && it.endsWith(".json") }.map { a.device.fs.readFileBytes(it)!! }

    private fun holdsRect(bytes: ByteArray, id: String): Boolean =
        SheetCodecV2.decode(bytes).getOrNull()?.scene?.elementsInOrder?.any { it.id.value == id } == true

    @Test
    fun editsAfterTheConflict_should_BeKeptAsACopy_BeforeUseTheirsDiscardsThem() = runBlocking<Unit> {
        val a = conflicted(coroutineContext)
        val mine = SaverRig.withRect(a.loadedDoc(), "mine", 300)
        a.saver.submit(mine)
        assertIs<SaveState.Conflict>(a.saver.flush())
        a.saver.submit(SaverRig.withRect(mine, "later", 400))

        assertNotNull(a.saver.reload().getOrNull())

        assertTrue(copies(a).any { holdsRect(it, "later") }, "the edit made after the conflict survives as a conflict copy")
        a.saver.close()
    }

    @Test
    fun editsAfterTheConflict_should_ReachACopyEvenWithoutReload() = runBlocking<Unit> {
        val a = conflicted(coroutineContext)
        val mine = SaverRig.withRect(a.loadedDoc(), "mine", 300)
        a.saver.submit(mine)
        assertIs<SaveState.Conflict>(a.saver.flush())
        a.saver.submit(SaverRig.withRect(mine, "later", 400))

        val state = a.saver.flush()

        assertIs<SaveState.Conflict>(state)
        assertTrue(copies(a).any { holdsRect(it, "later") })
        a.saver.close()
    }

    @Test
    fun externalChangeConflict_should_KeepLaterEditsBeforeReload() = runBlocking<Unit> {
        val external = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val a = conflicted(coroutineContext, external)
        yield()
        external.tryEmit(Unit)
        repeat(20) { yield() }
        assertIs<SaveState.Conflict>(a.saver.state.value)
        a.saver.submit(SaverRig.withRect(a.loadedDoc(), "later", 400))

        assertNotNull(a.saver.reload().getOrNull())

        assertTrue(copies(a).any { holdsRect(it, "later") })
        a.saver.close()
    }
}
