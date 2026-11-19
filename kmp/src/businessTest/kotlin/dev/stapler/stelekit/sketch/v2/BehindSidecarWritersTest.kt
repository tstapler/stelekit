// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.SketchBackupRestore
import dev.stapler.stelekit.sketch.v2.runtime.split.SheetSplitter
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitFailure
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitOutcome
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Review finding B1: a pull the reconciler has not merged yet must never be reverted by Convert, Split or Restore. */
class BehindSidecarWritersTest {
    private val f = ConversionFixtures
    private val s1 = SheetUuid(ConversionFixtures.S1)
    private val created = SheetUuid("p1-split")
    private val modelPath get() = f.file(SidecarCodec.MODEL_PATH)

    /** Another device renamed the project and pushed; this device pulled but has not reconciled. */
    private fun pull(d: Device): ByteArray {
        val pulled = d.fs.readFileBytes(modelPath)!!.decodeToString().replace("Kitchen", "Pulled").encodeToByteArray()
        d.fs.seed(modelPath, pulled)
        return pulled
    }

    private suspend fun convertedDevice(): Device {
        val d = f.device()
        assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
        return d
    }

    @Test
    fun convert_should_Defer_AndLeaveThePulledModel_WhenTheSidecarIsBehind() = runBlocking<Unit> {
        val d = f.device()
        val pulled = pull(d)
        val outcome = f.converter(d).convert(SketchTestSupport.P, s1)
        assertEquals(ConversionOutcome.Deferred, outcome)
        assertContentEquals(pulled, d.fs.readFileBytes(modelPath), "the pulled model survives")
        assertEquals(mapOf(f.S1 to 1L, f.S2 to 1L), f.schemas(d))
    }

    @Test
    fun split_should_Defer_AndLeaveThePulledModel_WhenTheSidecarIsBehind() = runBlocking<Unit> {
        val d = convertedDevice()
        val doc = SheetCodecV2.decode(d.store.readV2SheetFile(f.slug, s1.value)!!).getOrNull()!!
        val pulled = pull(d)
        val outcome = SheetSplitter(d.repos, d.store, SidecarSlugLock())
            .split(SketchTestSupport.P, s1, doc, doc.scene.elementsInOrder.take(2).map { it.id }.toSet(), created)
        assertEquals(SplitOutcome.Failed(SplitFailure.Deferred), outcome)
        assertContentEquals(pulled, d.fs.readFileBytes(modelPath), "the pulled model survives")
    }

    @Test
    fun split_should_RefuseWhenTheSourceFileIsNotTheBytesTheEditorLoaded() = runBlocking<Unit> {
        val d = convertedDevice()
        val doc = SheetCodecV2.decode(d.store.readV2SheetFile(f.slug, s1.value)!!).getOrNull()!!
        val outcome = SheetSplitter(d.repos, d.store, SidecarSlugLock()).split(
            SketchTestSupport.P, s1, doc, doc.scene.elementsInOrder.take(2).map { it.id }.toSet(), created,
            sourceSha = ContentHasher.sha256("other".encodeToByteArray()),
        )
        assertEquals(SplitOutcome.Failed(SplitFailure.SourceChanged), outcome)
    }

    @Test
    fun restore_should_Defer_AndLeaveThePulledModel_WhenTheSidecarIsBehind() = runBlocking<Unit> {
        val d = convertedDevice()
        val pulled = pull(d)
        val outcome = SketchBackupRestore(d.repos, d.store, SidecarSlugLock()).restore(f.slug, s1)
        assertEquals(RestoreOutcome.Deferred, outcome)
        assertContentEquals(pulled, d.fs.readFileBytes(modelPath), "the pulled model survives")
    }
}
