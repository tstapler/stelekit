// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pre-mortem P1 #3 (Story 1.4.4): a file replaced under the open sheet (a git pull) is never overwritten. Both versions end
 * up on disk: the other device's file in `sheets/`, this device's in-memory scene in `conflicts/`.
 */
class SheetSaverConflictTest {
    private val sheetId = MixedProjectFixtures.SHEET_A

    /** Device A and device B start from the same v2 project; B saves an edit, then A pulls B's files. */
    private suspend fun pulledFromB(context: kotlin.coroutines.CoroutineContext): Triple<SaverRig, SaverRig, ByteArray> {
        val a = SaverRig(MixedProjectFixtures.device(convertA = true), context)
        val b = SaverRig(MixedProjectFixtures.device(convertA = true), context)
        val theirs = SaverRig.withRect(b.loadedDoc(), "theirs", 700)
        b.saver.submit(theirs)
        assertEquals(SaveState.Saved, b.saver.flush())
        val theirBytes = b.diskBytes()
        a.device.pullFrom(b.device)
        b.saver.close()
        return Triple(a, b, theirBytes)
    }

    @Test
    fun pulledFile_should_NeverBeOverwrittenAndMineShouldBeKeptAsACopy() = runBlocking<Unit> {
        val (a, _, theirBytes) = pulledFromB(coroutineContext)
        val mine = SaverRig.withRect(a.loadedDoc(), "mine", 300)

        a.saver.submit(mine)
        val state = a.saver.flush()

        val conflict = assertIs<SaveState.Conflict>(state)
        assertContentEquals(theirBytes, a.diskBytes(), "sheets/ keeps the other device's version")
        val copy = assertNotNull(conflict.copyPath)
        assertTrue(copy.contains("/conflicts/$sheetId.v2.conflict-"), copy)
        val mineBytes = SheetCodecV2.encode(mine).getOrNull()!!
        assertContentEquals(mineBytes, a.device.fs.readFileBytes(copy), "the copy holds the in-memory scene")
        assertTrue(copy.contains(ContentHasher.sha256(mineBytes).take(HASH8)), "hash8 is the scene's own sha256")
        assertEquals(listOf(BannerRowId.SaveConflict), a.rows())
        assertEquals(listOf("sketch.save.conflict sheet=$sheetId"), a.logLines)
        a.saver.close()
    }

    @Test
    fun saveConflict_should_CountOnceInTheLocalQualitySignals() = runBlocking<Unit> {
        val signals = QualitySignals(MapSettings())
        val a = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext, signals = signals)
        val b = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        b.saver.submit(SaverRig.withRect(b.loadedDoc(), "theirs", 700))
        assertEquals(SaveState.Saved, b.saver.flush())
        a.device.pullFrom(b.device)
        b.saver.close()

        a.saver.submit(SaverRig.withRect(a.loadedDoc(), "mine", 300))
        assertIs<SaveState.Conflict>(a.saver.flush())

        assertEquals(1L, signals.counts().conflicts)
        assertEquals(0L, signals.counts().solves)
        a.saver.close()
    }

    @Test
    fun conflictCopy_should_NeverBeNamedLikeASheetOrScannedAsOne() = runBlocking<Unit> {
        val (a, _, _) = pulledFromB(coroutineContext)
        a.saver.submit(SaverRig.withRect(a.loadedDoc(), "mine", 300))
        val copy = assertIs<SaveState.Conflict>(a.saver.flush()).copyPath!!

        assertFalse(copy.contains("/sheets/"))
        val read = a.device.store.read(MixedProjectFixtures.slug)
        assertTrue(read is dev.stapler.stelekit.projectbridge.SidecarRead.Present, "the sidecar still reads as a valid bundle")
        a.saver.close()
    }

    @Test
    fun reload_should_AdoptTheOtherVersionAfterTheCopyExists() = runBlocking<Unit> {
        val (a, _, theirBytes) = pulledFromB(coroutineContext)
        a.saver.submit(SaverRig.withRect(a.loadedDoc(), "mine", 300))
        assertIs<SaveState.Conflict>(a.saver.flush())

        val adopted = a.saver.reload().getOrNull()

        assertContentEquals(theirBytes, SheetCodecV2.encode(assertNotNull(adopted)).getOrNull())
        assertEquals(SaveState.Saved, a.saver.state.value)
        assertTrue(a.rows().isEmpty())
        a.saver.submit(adopted)
        assertEquals(SaveState.Saved, a.saver.flush())
        assertContentEquals(theirBytes, a.diskBytes(), "re-submitting the adopted scene writes nothing new")
        a.saver.close()
    }

    @Test
    fun keepMine_should_RebaseAndWriteOverTheOtherVersion() = runBlocking<Unit> {
        val (a, _, theirBytes) = pulledFromB(coroutineContext)
        val mine = SaverRig.withRect(a.loadedDoc(), "mine", 300)
        a.saver.submit(mine)
        val copy = assertIs<SaveState.Conflict>(a.saver.flush()).copyPath!!

        a.saver.keepMine()
        val after = a.saver.flush()

        assertEquals(SaveState.Saved, after)
        assertContentEquals(SheetCodecV2.encode(mine).getOrNull(), a.diskBytes())
        assertTrue(a.device.fs.fileExists(copy), "the copy of mine stays")
        assertTrue(!a.diskBytes().contentEquals(theirBytes))
        assertTrue(a.rows().isEmpty())
        a.saver.close()
    }

    @Test
    fun matchingBase_should_SaveAndAdvanceItSoTheNextSaveAlsoMatches() = runBlocking<Unit> {
        val a = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        val first = SaverRig.withRect(a.loadedDoc(), "e1", 100)
        val second = SaverRig.withRect(first, "e2", 200)

        a.saver.submit(first)
        assertEquals(SaveState.Saved, a.saver.flush())
        assertContentEquals(SheetCodecV2.encode(first).getOrNull(), a.diskBytes())
        a.saver.submit(second)
        assertEquals(SaveState.Saved, a.saver.flush())

        assertContentEquals(SheetCodecV2.encode(second).getOrNull(), a.diskBytes())
        assertTrue(a.logLines.isEmpty())
        a.saver.close()
    }

    @Test
    fun sheetOnlyReplace_should_BeAConflictToo() = runBlocking<Unit> {
        val a = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        val theirs = SheetCodecV2.encode(SaverRig.withRect(a.loadedDoc(), "theirs", 700)).getOrNull()!!
        a.device.fs.seed(MixedProjectFixtures.sidecarFile(a.path), theirs)

        a.saver.submit(SaverRig.withRect(a.loadedDoc(), "mine", 300))
        val state = a.saver.flush()

        assertIs<SaveState.Conflict>(state)
        assertContentEquals(theirs, a.diskBytes())
        a.saver.close()
    }

    @Test
    fun pullEvent_should_PromptReloadWithoutWaitingForASave() = runBlocking<Unit> {
        val pulls = MutableSharedFlow<Unit>()
        val a = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext, externalChanges = pulls)
        val b = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        b.saver.submit(SaverRig.withRect(b.loadedDoc(), "theirs", 700))
        b.saver.flush()
        a.device.pullFrom(b.device)
        b.saver.close()
        yield()

        pulls.emit(Unit)
        repeat(YIELDS) { yield() }

        assertEquals(SaveState.Conflict(null), a.saver.state.value)
        assertEquals(listOf(BannerRowId.SaveConflict), a.rows())
        a.saver.close()
    }

    @Test
    fun pullEvent_should_BeIgnoredWhenTheFileIsStillTheOneTheSaverKnows() = runBlocking<Unit> {
        val pulls = MutableSharedFlow<Unit>()
        val a = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext, externalChanges = pulls)
        yield()

        pulls.emit(Unit)
        repeat(YIELDS) { yield() }

        assertEquals(SaveState.Saved, a.saver.state.value)
        assertTrue(a.rows().isEmpty())
        a.saver.close()
    }

    private companion object {
        const val HASH8 = 8
        const val YIELDS = 30
    }
}
