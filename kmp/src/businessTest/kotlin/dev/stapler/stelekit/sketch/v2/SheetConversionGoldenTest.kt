// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.export.SketchCsvWriter
import dev.stapler.stelekit.sketch.export.SketchSvgWriter
import dev.stapler.stelekit.sketch.export.exportDimensions
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Story 1.4.2: the converter's outcome gates and its golden results (atomic, idempotent, unchanged exports). */
class SheetConversionGoldenTest {
    private val s1 = SheetUuid(ConversionFixtures.S1)
    private val f = ConversionFixtures

    private fun modelOf(d: Device): JsonObject =
        Json.parseToJsonElement(d.fs.readFileBytes(f.file(SidecarCodec.MODEL_PATH))!!.decodeToString()).jsonObject

    private fun sheetRef(d: Device, sheet: String): JsonObject =
        modelOf(d).getValue("sheets").jsonArray.map { it.jsonObject }.first { it.getValue("uuid").jsonPrimitive.content == sheet }

    private data class Exports(val svg: String, val csv: String)

    private suspend fun exports(d: Device): Map<String, Exports> {
        val documents = f.documents(d)
        return d.repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.associate { sheet ->
            val doc = assertNotNull(documents.load(f.slug, sheet).getOrNull(), "${sheet.uuid.value} did not load").document
            sheet.uuid.value to Exports(SketchSvgWriter.write(doc), SketchCsvWriter.write(doc.exportDimensions()))
        }
    }

    @Test
    fun convert_should_BeAtomicAndIdempotent() = runBlocking<Unit> {
        val d = f.device()
        val v1Bytes = d.fs.readFileBytes(f.file("sheets/${f.S1}.json"))!!
        val ids = d.repos.sheets.getPrimitives(s1).first().getOrNull()!!.map { it.uuid }
        assertEquals(f.S1_PRIMITIVES, ids.size)

        val first = f.converter(d).convert(SketchTestSupport.P, s1)

        assertEquals(ConversionOutcome.Converted(s1, f.S1_PRIMITIVES), first)
        assertContentEquals(v1Bytes, d.fs.readFileBytes(f.file("backup/${f.S1}.v1.json")))
        assertFalse(d.fs.fileExists(f.file("sheets/${f.S1}.json")), "the v1 file is removed")
        val ref = sheetRef(d, f.S1)
        assertEquals("sheets/${f.S1}.v2.json", ref.getValue("file").jsonPrimitive.content)
        assertEquals("2", ref.getValue("schema").jsonPrimitive.content)
        val doc = SheetCodecV2.decode(d.fs.readFileBytes(f.file("sheets/${f.S1}.v2.json"))!!).getOrNull()!!
        assertEquals(ids, doc.scene.elementsInOrder.map { it.id.value }, "the 12 elements carry the v1 ids in paint order")
        assertEquals(mapOf(f.S1 to 2L, f.S2 to 1L), f.schemas(d))
        assertIs<SidecarRead.Present>(d.store.read(f.slug))

        val writes = d.fs.writeCount
        assertEquals(ConversionOutcome.AlreadyConverted, f.converter(d).convert(SketchTestSupport.P, s1))
        assertEquals(writes, d.fs.writeCount, "the second run changes no file")
    }

    @Test
    fun convertedProject_should_ExportTheSameBytes_AsBefore() = runBlocking<Unit> {
        val d = f.device()
        val before = exports(d)

        assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
        assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, SheetUuid(f.S2)))

        assertTrue(before.getValue(f.S1).svg.isNotEmpty())
        assertEquals(before, exports(d))
    }

    @Test
    fun backup_should_SurviveLaterWrites_AndStayOutOfFilesAndHash() = runBlocking<Unit> {
        val d = f.device()
        val v1Bytes = d.fs.readFileBytes(f.file("sheets/${f.S1}.json"))!!
        f.converter(d).convert(SketchTestSupport.P, s1)
        val files = assertIs<SidecarRead.Present>(d.store.read(f.slug)).files
        val hash = SidecarCodec.bundleHash(files)

        d.store.write(f.slug, files)

        val after = assertIs<SidecarRead.Present>(d.store.read(f.slug)).files
        assertContentEquals(v1Bytes, d.fs.readFileBytes(f.file("backup/${f.S1}.v1.json")))
        assertTrue(after.keys.none { it.startsWith("backup/") })
        assertEquals(hash, SidecarCodec.bundleHash(after))
    }

    @Test
    fun convert_should_RefuseWhileConversionIsNotReady_AndWriteNothing() = runBlocking<Unit> {
        val d = f.device()
        val writes = d.fs.writeCount

        val outcome = f.converter(d, ready = false).convert(SketchTestSupport.P, s1)

        assertEquals(ConversionOutcome.NotReady, outcome)
        assertEquals(writes, d.fs.writeCount)
        assertFalse(d.fs.fileExists(f.file("backup/${f.S1}.v1.json")))
        assertEquals(mapOf(f.S1 to 1L, f.S2 to 1L), f.schemas(d))
    }

    @Test
    fun convert_should_AskForConfirmation_WhenTheProjectHasNoAcknowledgement() = runBlocking<Unit> {
        val d = f.device()
        val writes = d.fs.writeCount

        val outcome = f.converter(d, acknowledged = false).convert(SketchTestSupport.P, s1)

        assertEquals(ConversionOutcome.RequiresConfirmation(f.slug), outcome)
        assertEquals(writes, d.fs.writeCount)
        assertFalse(d.fs.fileExists(f.file("backup/${f.S1}.v1.json")))
    }

    @Test
    fun convert_should_ReturnNotFound_ForAnUnknownSheet() = runBlocking<Unit> {
        val d = f.device()

        val outcome = f.converter(d).convert(SketchTestSupport.P, SheetUuid("nope"))

        assertEquals(ConversionOutcome.Failed(ConversionFailure.NotFound, "sheet"), outcome)
    }

    @Test
    fun convert_should_LeaveTheOtherSheetAndItsFileUntouched() = runBlocking<Unit> {
        val d = f.device()
        val s2Bytes = d.fs.readFileBytes(f.file("sheets/${f.S2}.json"))!!

        f.converter(d).convert(SketchTestSupport.P, s1)

        assertContentEquals(s2Bytes, d.fs.readFileBytes(f.file("sheets/${f.S2}.json")))
        assertEquals(JsonArray(emptyList()), modelOf(d).getValue("dimensions"))
    }

    @Test
    fun convertedProject_should_BePulledByAnotherDevice_ThroughTheSheetRefFile() = runBlocking<Unit> {
        val a = f.device()
        f.converter(a).convert(SketchTestSupport.P, s1)
        val b = f.device()

        b.pullFrom(a)
        b.fs.deleteFile(f.file("sheets/${f.S1}.json"))
        val project = b.repos.projects.loadProject(SketchTestSupport.P).getOrNull()!!.project
        val outcome = b.reconciler.reconcile(project)

        assertEquals(dev.stapler.stelekit.projectbridge.ReconcileOutcome.Pulled(f.slug), outcome)
        assertEquals(mapOf(f.S1 to 2L, f.S2 to 1L), f.schemas(b))
        assertEquals(exports(a), exports(b))
    }
}
