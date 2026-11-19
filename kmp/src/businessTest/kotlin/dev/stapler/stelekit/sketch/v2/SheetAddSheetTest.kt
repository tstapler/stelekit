// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.split.SheetSplitter
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitCrashPoint
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitFailure
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitOutcome
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitStep
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 5.1.5b: "Import into a new sketch" adds a v2 sheet beside the current one and never touches the current sheet. */
class SheetAddSheetTest {
    private val anchor = SheetUuid(ConversionFixtures.S1)
    private val created = SheetUuid("p1-import")
    private val slug = ConversionFixtures.slug

    private class Crash(step: SplitStep) : RuntimeException("crash after $step")

    private suspend fun convertedDevice(): Device {
        val d = ConversionFixtures.device()
        assertIs<ConversionOutcome.Converted>(ConversionFixtures.converter(d).convert(SketchTestSupport.P, anchor))
        return d
    }

    private fun splitter(d: Device, crashAt: SplitStep? = null) = SheetSplitter(
        d.repos, d.store, SidecarSlugLock(),
        crashPoint = SplitCrashPoint { if (it == crashAt) throw Crash(it) },
    )

    private fun content(count: Int): SketchDocumentV2 =
        Transaction.begin(SketchDocumentV2()).apply(ImportScene((1..count).map { RectElement(ElementBase(ElementId("n$it")), SceneUnit.ofInt(it * 20), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10)) })).fold({ error("$it") }, { it.doc })

    private suspend fun fileOf(d: Device, sheet: SheetUuid): ByteArray? = d.store.readV2SheetFile(slug, sheet.value)

    @Test
    fun addSheet_should_CreateAV2SheetWithTheContent_AndLeaveTheAnchorFileUntouched() = runBlocking<Unit> {
        val d = convertedDevice()
        val anchorBefore = fileOf(d, anchor)!!.copyOf()

        val outcome = splitter(d).addSheet(SketchTestSupport.P, anchor, content(3), created)

        assertIs<SplitOutcome.Split>(outcome)
        assertEquals(3, outcome.moved)
        assertContentEquals(anchorBefore, fileOf(d, anchor), "the current sheet is never touched")
        val written = SheetCodecV2.decode(fileOf(d, created)!!).getOrNull()!!
        assertEquals(3, written.scene.size)
        assertEquals(created.value, written.sheetUuid)
        assertEquals(mapOf(anchor.value to 2L, ConversionFixtures.S2 to 1L, created.value to 2L), ConversionFixtures.schemas(d))
        assertIs<SidecarRead.Present>(d.store.read(slug))
    }

    @Test
    fun crashAfterStaging_should_LeaveTheModelUnchanged_SoTheNewSheetIsNotReferenced() = runBlocking<Unit> {
        val d = convertedDevice()
        runCatching { splitter(d, SplitStep.NewSheetStaged).addSheet(SketchTestSupport.P, anchor, content(2), created) }
        assertEquals(mapOf(anchor.value to 2L, ConversionFixtures.S2 to 1L), ConversionFixtures.schemas(d))
        assertTrue(fileOf(d, created) != null, "the staged file exists, unreferenced")
    }

    @Test
    fun addSheet_should_RefuseAV1Anchor_AndWriteNothing() = runBlocking<Unit> {
        val d = ConversionFixtures.device()
        val disk = ConversionFixtures.disk(d)

        val outcome = splitter(d).addSheet(SketchTestSupport.P, SheetUuid(ConversionFixtures.S2), content(1), created)

        assertEquals(SplitOutcome.Failed(SplitFailure.NotV2), outcome)
        assertEquals(disk, ConversionFixtures.disk(d))
    }
}
