// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.projectbridge.ReconcileOutcome
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.4.4: saver, database row and the reconciler agree (review C2, C6), and the slug lock orders them. */
class SheetSaverReconcileIntegrationTest {
    private val slug = MixedProjectFixtures.slug

    private suspend fun settled(rig: SaverRig) {
        assertIs<ReconcileOutcome.Unchanged>(rig.reconciler.reconcileAll().outcomes.single())
    }

    @Test
    fun layerEdit_should_WriteFileModelAndRowTogetherAndReconcileUnchanged() = runBlocking<Unit> {
        val rig = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        settled(rig)
        val layered = rig.loadedDoc().let { doc ->
            doc.copy(
                scene = doc.scene.withLayers(
                    listOf(Layer.DefaultLayer, Layer(LayerId("v1:Base"), "Base", order = 1), Layer(LayerId("v1:Walls"), "Walls", order = 2)),
                ),
            )
        }

        rig.saver.submit(layered)
        assertEquals(SaveState.Saved, rig.saver.flush())

        val bytes = SheetCodecV2.encode(layered).getOrNull()!!
        assertContentEquals(bytes, rig.diskBytes())
        val row = rig.device.repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.first { it.uuid == rig.sheet }
        assertEquals(listOf("Base", "Walls"), row.layers)
        assertEquals(2L, row.schemaVersion)
        val model = rig.device.fs.readFileBytes(MixedProjectFixtures.sidecarFile(SidecarCodec.MODEL_PATH))!!
        assertEquals(ContentHasher.sha256(bytes), SidecarCodec.v2SheetShas(model)!!.getValue(rig.path))
        val writes = rig.device.fs.writeCount

        assertIs<ReconcileOutcome.Unchanged>(rig.reconciler.reconcileAll().outcomes.single())

        assertEquals(writes, rig.device.fs.writeCount, "the reconcile after a save writes nothing")
        rig.saver.close()
    }

    @Test
    fun saveThenReconcile_should_StayUnchanged_WhenTheBaseMovedWithTheWrite() = runBlocking<Unit> {
        val a = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        settled(a)
        a.saver.submit(SaverRig.withRect(a.loadedDoc(), "mine", 300))
        a.saver.flush()

        // Nothing changed on disk since the saver's own write: the base moved with it, so a later pull is not a conflict.
        assertIs<ReconcileOutcome.Unchanged>(a.reconciler.reconcileAll().outcomes.single())
        a.saver.close()
    }

    @Test
    fun reconcileHoldingTheSlugLock_should_DelayTheSaverInsteadOfBeingOverwrittenByIt() = runBlocking<Unit> {
        val rig = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        val before = rig.diskBytes()
        val gate = CompletableDeferred<Unit>()
        val holder = launch { rig.slugLock.withSlug(slug) { gate.await() } }
        yield()
        val edited = SaverRig.withRect(rig.loadedDoc(), "e1", 100)
        rig.saver.submit(edited)

        val flushing = async { rig.saver.flush() }
        repeat(YIELDS) { yield() }

        assertContentEquals(before, rig.diskBytes(), "the saver waits for the slug lock")
        assertTrue(flushing.isActive)
        gate.complete(Unit)
        holder.join()
        assertEquals(SaveState.Saved, flushing.await())
        assertContentEquals(SheetCodecV2.encode(edited).getOrNull(), rig.diskBytes())
        rig.saver.close()
    }

    private companion object {
        const val YIELDS = 30
    }
}
