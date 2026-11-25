// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetMerge
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E14 (ADR-008) through the real saver and store: a file that changed under the saver is merged with the open scene instead of
 * becoming a conflict copy, and no ordering of saves, failures or editor adoption lets a stale scene write over the merge.
 */
class SheetSaverMergeTest {
    private val sheetId = MixedProjectFixtures.SHEET_A

    private class Merging(val rig: SaverRig, val saver: SheetSaver) {
        fun disk(): SketchDocumentV2 = SheetCodecV2.decode(rig.diskBytes()).getOrNull()!!
        fun ids(): Set<String> = disk().scene.elementsInOrder.map { it.id.value }.toSet()
        fun close() {
            saver.close()
            rig.saver.close()
        }
    }

    private suspend fun merging(
        context: CoroutineContext,
        withBase: Boolean = true,
        storeOf: (ProjectSidecarStore) -> ProjectSidecarStore = { it },
        merge: Boolean = true,
    ): Merging {
        val rig = SaverRig(MixedProjectFixtures.device(convertA = true), context)
        val saver = SheetSaver(
            SheetTarget(MixedProjectFixtures.slug, SketchTestSupport.P, rig.sheet, ContentHasher.sha256(rig.loadedBytes), loadedDoc = rig.loadedDoc().takeIf { withBase }),
            SheetSaverPorts(rig.device.repos, storeOf(rig.device.store), rig.slugLock, rig.diagnostics, mergeOnConflict = merge),
            context = context,
            encodeContext = context,
        )
        return Merging(rig, saver)
    }

    /** Device B saves one more rectangle and A pulls it; returns A's saver ready to save its own. */
    private suspend fun pulledFromB(context: CoroutineContext, storeOf: (ProjectSidecarStore) -> ProjectSidecarStore = { it }, withBase: Boolean = true): Merging {
        val a = merging(context, withBase, storeOf)
        val b = SaverRig(MixedProjectFixtures.device(convertA = true), context)
        b.saver.submit(SaverRig.withRect(b.loadedDoc(), "theirs", 700))
        assertEquals(SaveState.Saved, b.saver.flush())
        a.rig.device.pullFrom(b.device)
        b.saver.close()
        return a
    }

    @Test
    fun changedFile_should_BeMergedNotCopied_When_BothDevicesAddedElements() = runBlocking<Unit> {
        val a = pulledFromB(coroutineContext)

        a.saver.submit(SaverRig.withRect(a.rig.loadedDoc(), "mine", 300))
        val state = a.saver.flush()

        assertEquals(SaveState.Saved, state)
        assertTrue(a.ids().containsAll(setOf("mine", "theirs")), "the file holds both devices' rectangles: ${a.ids()}")
        assertTrue(a.rig.rows().isEmpty(), "no conflict banner")
        val merge = assertNotNull(a.saver.merges.value)
        assertEquals(listOf("theirs"), merge.report.added.map { it.value })
        a.close()
    }

    @Test
    fun staleEditorScene_should_NeverWriteOverTheMerge_When_SubmittedBeforeTheEditorTookIt() = runBlocking<Unit> {
        val a = pulledFromB(coroutineContext)
        val mine = SaverRig.withRect(a.rig.loadedDoc(), "mine", 300)
        a.saver.submit(mine)
        assertEquals(SaveState.Saved, a.saver.flush())

        a.saver.submit(SaverRig.withRect(mine, "later", 400))
        assertEquals(SaveState.Saved, a.saver.flush())

        assertTrue(a.ids().containsAll(setOf("mine", "theirs", "later")), "the later edit saved with theirs folded in: ${a.ids()}")
        a.close()
    }

    @Test
    fun acknowledgedMerge_should_LetTheEditorsOwnSceneThrough() = runBlocking<Unit> {
        val a = pulledFromB(coroutineContext)
        a.saver.submit(SaverRig.withRect(a.rig.loadedDoc(), "mine", 300))
        a.saver.flush()
        val merge: SheetMerge = assertNotNull(a.saver.merges.value)

        val adopted = a.disk()
        a.saver.submit(adopted)
        a.saver.acknowledgeMerge(merge)
        assertNull(a.saver.merges.value)
        val without = adopted.copy(scene = adopted.scene.remove(adopted.scene.elementsInOrder.first { it.id.value == "mine" }.id).getOrNull()!!)
        a.saver.submit(without)

        assertEquals(SaveState.Saved, a.saver.flush())
        assertTrue("mine" !in a.ids(), "after the acknowledgement an editor delete is the editor's own")
        a.close()
    }

    @Test
    fun secondChange_should_KeepAConflictCopy_When_TheEditorHasNotTakenTheFirstMerge() = runBlocking<Unit> {
        val a = pulledFromB(coroutineContext)
        a.saver.submit(SaverRig.withRect(a.rig.loadedDoc(), "mine", 300))
        assertEquals(SaveState.Saved, a.saver.flush())
        val third = SaverRig.withRect(a.disk(), "third", 800)
        val intruder = SheetCodecV2.encode(SaverRig.withRect(a.disk(), "other", 900)).getOrNull()!!
        a.rig.device.fs.seed(MixedProjectFixtures.sidecarFile(a.rig.path), intruder)

        a.saver.submit(third)
        val state = a.saver.flush()

        val conflict = assertIs<SaveState.Conflict>(state)
        assertNotNull(conflict.copyPath)
        assertContentEquals(intruder, a.rig.diskBytes(), "the file that arrived is never overwritten")
        a.close()
    }

    @Test
    fun withoutABase_should_KeepTheConflictCopyAsBefore() = runBlocking<Unit> {
        val a = pulledFromB(coroutineContext, withBase = false)

        a.saver.submit(SaverRig.withRect(a.rig.loadedDoc(), "mine", 300))

        assertIs<SaveState.Conflict>(a.saver.flush())
        assertEquals(listOf(BannerRowId.SaveConflict), a.rig.rows())
        assertTrue("mine" !in a.ids())
        a.close()
    }

    @Test
    fun mergeSwitchedOff_should_KeepTheConflictCopyAsBefore() = runBlocking<Unit> {
        val a = merging(coroutineContext, merge = false)
        val b = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        b.saver.submit(SaverRig.withRect(b.loadedDoc(), "theirs", 700))
        b.saver.flush()
        a.rig.device.pullFrom(b.device)
        b.saver.close()

        a.saver.submit(SaverRig.withRect(a.rig.loadedDoc(), "mine", 300))

        assertIs<SaveState.Conflict>(a.saver.flush())
        a.close()
    }

    @Test
    fun failedWrite_should_LeaveTheOtherDevicesFileIntactAndMergeOnRetry() = runBlocking<Unit> {
        var failures = 1
        val flaky = { real: ProjectSidecarStore ->
            object : ProjectSidecarStore by real {
                override suspend fun writeNow(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome =
                    if (failures-- > 0) SidecarWriteOutcome.Failed(DomainError.FileSystemError.WriteFailed(slug, "injected")) else real.writeNow(slug, files, opaque)
            }
        }
        val a = pulledFromB(coroutineContext, flaky)
        val theirs = a.rig.diskBytes()

        a.saver.submit(SaverRig.withRect(a.rig.loadedDoc(), "mine", 300))
        assertIs<SaveState.Failed>(a.saver.flush())
        assertContentEquals(theirs, a.rig.diskBytes(), "a failed merged write never leaves a partial file")
        assertNull(a.saver.merges.value, "a merge that was not written is not announced")

        a.saver.retry()
        assertEquals(SaveState.Saved, a.saver.flush())
        assertTrue(a.ids().containsAll(setOf("mine", "theirs")))
        a.close()
    }

    @Test
    fun staleOpaqueDuringTheMergedWrite_should_ReplanFromAFreshReadAndStillMerge() = runBlocking<Unit> {
        var stale = 1
        val racing = { real: ProjectSidecarStore ->
            object : ProjectSidecarStore by real {
                override suspend fun writeNow(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome =
                    if (stale-- > 0) SidecarWriteOutcome.StaleOpaque else real.writeNow(slug, files, opaque)
            }
        }
        val a = pulledFromB(coroutineContext, racing)

        a.saver.submit(SaverRig.withRect(a.rig.loadedDoc(), "mine", 300))

        assertEquals(SaveState.Saved, a.saver.flush())
        assertTrue(a.ids().containsAll(setOf("mine", "theirs")))
        a.close()
    }

    @Test
    fun mergedFile_should_BeByteIdenticalToTheEncodingOfTheReportedDocument() = runBlocking<Unit> {
        val a = pulledFromB(coroutineContext)
        a.saver.submit(SaverRig.withRect(a.rig.loadedDoc(), "mine", 300))
        a.saver.flush()

        val merge = assertNotNull(a.saver.merges.value)

        assertContentEquals(SheetCodecV2.encode(merge.merged).getOrNull(), a.rig.diskBytes())
        a.close()
    }

}
