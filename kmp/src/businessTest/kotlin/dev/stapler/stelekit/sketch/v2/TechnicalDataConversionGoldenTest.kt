// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.ProjectSourceCollector
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.export.SketchCsvWriter
import dev.stapler.stelekit.sketch.export.SketchSvgWriter
import dev.stapler.stelekit.sketch.export.exportDimensions
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 1.4.2c: zones, dimensions and scale are carried verbatim through a conversion (proved by output equality, since
 * a dimension has no primitive reference), and with the refusal active nothing is written.
 */
class TechnicalDataConversionGoldenTest {
    private val sheetA = SheetUuid(MixedProjectFixtures.SHEET_A)
    private val slug = MixedProjectFixtures.slug

    private data class Outputs(val csv: String, val svg: String, val observations: String)

    private fun model(d: Device): JsonObject =
        Json.parseToJsonElement(d.fs.readFileBytes(MixedProjectFixtures.sidecarFile(SidecarCodec.MODEL_PATH))!!.decodeToString()).jsonObject

    /** Everything of the model a conversion must not touch: the top level except `sheets`, and each sheet ref except the file fields. */
    private fun carried(d: Device): Pair<Map<String, JsonElement>, List<Map<String, JsonElement>>> {
        val m = model(d)
        val sheets = m.getValue("sheets").jsonArray.map { ref -> ref.jsonObject.filterKeys { it !in CONVERSION_FIELDS } }
        return m.filterKeys { it != "sheets" } to sheets
    }

    private suspend fun outputs(d: Device): Map<String, Outputs> {
        val documents = MixedProjectFixtures.documents(d)
        val collector = ProjectSourceCollector(DimensionRegistry(dimensions = emptyList()))
        return d.repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items.associate { sheet ->
            val doc = assertNotNull(documents.load(slug, sheet).getOrNull(), "${sheet.uuid.value} did not load").document
            val svg = SketchSvgWriter.write(doc)
            sheet.uuid.value to Outputs(SketchCsvWriter.write(doc.exportDimensions()), svg, collector.extractGeometry(svg).observations.toString())
        }
    }

    @Test
    fun conversion_should_CarryZonesDimensionsAndScaleVerbatim_AgainstTheRealPolicy() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = false)
        val before = outputs(d)
        val carriedBefore = carried(d)
        assertTrue((carriedBefore.first.getValue("dimensions") as JsonArray).isNotEmpty(), "the fixture has dimensions")
        assertTrue(carriedBefore.second.any { it.getValue("zones").jsonArray.isNotEmpty() }, "the fixture has zones")

        val outcome = ConversionFixtures.converter(d).convert(SketchTestSupport.P, sheetA)

        assertIs<ConversionOutcome.Converted>(outcome)
        assertEquals(carriedBefore, carried(d), "zones, dimensions and scale bytes are unchanged")
        assertEquals(before, outputs(d), "fusion, CSV and digest geometry equal the pre-conversion outputs")
    }

    @Test
    fun conversion_should_RefuseAndWriteNothing_WhenTheTestOnlyOverrideRestoresTheRefusal() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = false)
        val writes = d.fs.writeCount

        val outcome = ConversionFixtures.converter(d, technicalDataEditable = false).convert(SketchTestSupport.P, sheetA)

        assertEquals(ConversionOutcome.Failed(ConversionFailure.HasTechnicalData), outcome)
        assertEquals(writes, d.fs.writeCount)
        assertFalse(d.fs.fileExists(MixedProjectFixtures.sidecarFile("backup/${MixedProjectFixtures.SHEET_A}.v1.json")))
        assertEquals(mapOf(MixedProjectFixtures.SHEET_A to 1L, MixedProjectFixtures.SHEET_B to 1L), ConversionFixtures.schemas(d))
    }

    @Test
    fun conversion_should_ReportNewerSchemaAndWriteNothing_WhenTheSidecarIsFromANewerBuild() = runBlocking<Unit> {
        val d = ConversionFixtures.device()
        d.fs.seed(ConversionFixtures.file("sheets/${ConversionFixtures.S1}.json"), "{\"schema\":3}".encodeToByteArray())
        val writes = d.fs.writeCount

        val outcome = ConversionFixtures.converter(d).convert(SketchTestSupport.P, SheetUuid(ConversionFixtures.S1))

        assertEquals(ConversionOutcome.Failed(ConversionFailure.NewerSchema), outcome)
        assertEquals(writes, d.fs.writeCount)
        assertFalse(d.fs.fileExists(ConversionFixtures.file("backup/${ConversionFixtures.S1}.v1.json")))
    }

    private companion object {
        val CONVERSION_FIELDS = setOf("file", "schema", "fileSha256", "layers")
    }
}
