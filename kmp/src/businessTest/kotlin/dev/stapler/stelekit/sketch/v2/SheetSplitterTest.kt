// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.split.SheetSplitter
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitCrashPoint
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitFailure
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitOutcome
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitStep
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 2.2.4, Task 2.2.4a: the two-sheet split with its one model write, its crash boundaries and its refusals. */
class SheetSplitterTest {
    private val source = SheetUuid(ConversionFixtures.S1)
    private val created = SheetUuid("p1-split")
    private val slug = ConversionFixtures.slug

    private class Crash(step: SplitStep) : RuntimeException("crash after $step")

    private suspend fun convertedDevice(): Device {
        val d = ConversionFixtures.device()
        assertIs<ConversionOutcome.Converted>(ConversionFixtures.converter(d).convert(SketchTestSupport.P, source))
        return d
    }

    private suspend fun sourceDocument(d: Device): SketchDocumentV2 =
        SheetCodecV2.decode(d.store.readV2SheetFile(slug, source.value)!!).getOrNull()!!

    private fun splitter(d: Device, crashAt: SplitStep? = null) = SheetSplitter(
        d.repos, d.store, SidecarSlugLock(),
        crashPoint = SplitCrashPoint { if (it == crashAt) throw Crash(it) },
    )

    private fun pick(doc: SketchDocumentV2, count: Int): Set<ElementId> = doc.scene.elementsInOrder.take(count).map { it.id }.toSet()

    private suspend fun fileOf(d: Device, sheet: SheetUuid): ByteArray? = d.store.readV2SheetFile(slug, sheet.value)

    @Test
    fun split_should_MoveExactlyTheSelection_IntoANewV2Sheet_And_ReadPresentAfterwards() = runBlocking<Unit> {
        val d = convertedDevice()
        val doc = sourceDocument(d)
        val total = doc.scene.elementsInOrder.size
        val selected = pick(doc, 4)
        val outcome = splitter(d).split(SketchTestSupport.P, source, doc, selected, created)
        assertIs<SplitOutcome.Split>(outcome)
        assertEquals(4, outcome.moved)
        val sourceAfter = SheetCodecV2.decode(fileOf(d, source)!!).getOrNull()!!
        val newDoc = SheetCodecV2.decode(fileOf(d, created)!!).getOrNull()!!
        assertEquals(total - 4, sourceAfter.scene.elementsInOrder.size)
        assertEquals(selected, newDoc.scene.elementsInOrder.map { it.id }.toSet(), "ids are kept")
        assertEquals(mapOf(source.value to 2L, ConversionFixtures.S2 to 1L, created.value to 2L), ConversionFixtures.schemas(d))
        assertIs<SidecarRead.Present>(d.store.read(slug))
    }

    @Test
    fun split_should_LeaveTheSourceHistoryUndoable_ByReturningTheDeleteAsOneCommand() = runBlocking<Unit> {
        val d = convertedDevice()
        val doc = sourceDocument(d)
        val plan = dev.stapler.stelekit.sketch.v2.runtime.split.SplitPlanner.plan(doc, pick(doc, 3), created.value).getOrNull()!!
        assertEquals(3, plan.deleteMoved.ids.size)
        assertEquals(doc.scene.elementsInOrder.size - 3, plan.sourceAfter.scene.elementsInOrder.size)
    }

    @Test
    fun crashAfterStagingTheNewSheet_should_LeaveTheSourceAndTheModelUnchanged() = runBlocking<Unit> {
        val d = convertedDevice()
        val doc = sourceDocument(d)
        val before = fileOf(d, source)!!.toList()
        runCatching { splitter(d, SplitStep.NewSheetStaged).split(SketchTestSupport.P, source, doc, pick(doc, 4), created) }
        assertEquals(before, fileOf(d, source)!!.toList(), "the source file is unchanged")
        assertEquals(mapOf(source.value to 2L, ConversionFixtures.S2 to 1L), ConversionFixtures.schemas(d))
        assertTrue(fileOf(d, created) != null, "the staged file exists, unreferenced")
        assertIs<SidecarRead.Present>(d.store.read(slug))
    }

    @Test
    fun crashAfterTheModelWrite_should_LeaveBothSheetsPresent_And_TheRetryAfterRowsRepairsNothingNew() = runBlocking<Unit> {
        val d = convertedDevice()
        val doc = sourceDocument(d)
        runCatching { splitter(d, SplitStep.SourceAndModelWritten).split(SketchTestSupport.P, source, doc, pick(doc, 4), created) }
        val present = d.store.read(slug)
        assertIs<SidecarRead.Present>(present)
        assertTrue(SheetPaths.pathFor(created, SheetPaths.SCHEMA_V2) in present.files)
        val newDoc = SheetCodecV2.decode(fileOf(d, created)!!).getOrNull()!!
        assertEquals(4, newDoc.scene.elementsInOrder.size)
        assertEquals(doc.scene.elementsInOrder.size - 4, SheetCodecV2.decode(fileOf(d, source)!!).getOrNull()!!.scene.elementsInOrder.size)
    }

    @Test
    fun crashAfterTheRowWrites_should_LeaveACompleteSplit() = runBlocking<Unit> {
        val d = convertedDevice()
        val doc = sourceDocument(d)
        runCatching { splitter(d, SplitStep.RowsWritten).split(SketchTestSupport.P, source, doc, pick(doc, 4), created) }
        assertEquals(mapOf(source.value to 2L, ConversionFixtures.S2 to 1L, created.value to 2L), ConversionFixtures.schemas(d))
    }

    @Test
    fun split_should_RefuseAndWriteNothing_When_NothingIsSelectedOrTheElementIsUnknown() = runBlocking<Unit> {
        val d = convertedDevice()
        val doc = sourceDocument(d)
        val disk = ConversionFixtures.disk(d)
        val none = splitter(d).split(SketchTestSupport.P, source, doc, emptySet(), created)
        assertEquals(SplitOutcome.Failed(SplitFailure.NothingSelected), none)
        val unknown = splitter(d).split(SketchTestSupport.P, source, doc, setOf(ElementId("missing")), created)
        assertEquals(SplitOutcome.Failed(SplitFailure.UnknownElement), unknown)
        assertEquals(disk, ConversionFixtures.disk(d))
    }

    @Test
    fun split_should_RefuseAV1SourceSheet_AndWriteNothing() = runBlocking<Unit> {
        val d = ConversionFixtures.device()
        val doc = SketchDocumentV2(sheetUuid = ConversionFixtures.S2)
        val disk = ConversionFixtures.disk(d)
        val outcome = splitter(d).split(SketchTestSupport.P, SheetUuid(ConversionFixtures.S2), doc, setOf(ElementId("x")), created)
        assertTrue(outcome is SplitOutcome.Failed)
        assertEquals(disk, ConversionFixtures.disk(d))
    }
}
