// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.ProjectSourceCollector
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.export.SketchCsvWriter
import dev.stapler.stelekit.sketch.export.SketchSvgWriter
import dev.stapler.stelekit.sketch.export.exportDimensions
import dev.stapler.stelekit.ui.sketch.SketchDocumentSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 1.4.5: fusion, CSV, SVG and the digest's geometry read give the same bytes for a project whose sheet A was
 * converted to schema 2 as for the same project unconverted, and a converted sheet never reads its old v1 rows.
 */
class MixedProjectFusionGoldenTest {
    private val slug = MixedProjectFixtures.slug

    /** CSV, SVG and the geometry observations the digest path reads from that SVG, per sheet. */
    private data class Outputs(val csv: String, val svg: String, val observations: String)

    private suspend fun outputs(d: Device, documents: SketchDocumentSource): Map<String, Outputs> {
        val sheets = d.repos.sheets.getSheets(SketchTestSupport.P).first().getOrNull()!!.items
        val collector = ProjectSourceCollector(DimensionRegistry(dimensions = emptyList()))
        return sheets.associate { sheet ->
            val doc = assertNotNull(documents.load(slug, sheet).getOrNull(), "${sheet.uuid.value} did not load").document
            val svg = SketchSvgWriter.write(doc)
            sheet.uuid.value to Outputs(
                csv = SketchCsvWriter.write(doc.exportDimensions()),
                svg = svg,
                observations = collector.extractGeometry(svg).observations.toString(),
            )
        }
    }

    @Test
    fun mixedProject_should_ExportTheSameBytes_AsTheUnconvertedProject() = runBlocking<Unit> {
        val plain = MixedProjectFixtures.device(convertA = false)
        val mixed = MixedProjectFixtures.device(convertA = true)

        val before = outputs(plain, MixedProjectFixtures.documents(plain))
        val after = outputs(mixed, MixedProjectFixtures.documents(mixed))

        assertEquals(setOf(MixedProjectFixtures.SHEET_A, MixedProjectFixtures.SHEET_B), after.keys)
        assertTrue(before.getValue(MixedProjectFixtures.SHEET_A).svg.isNotEmpty())
        assertEquals(before, after)
    }

    @Test
    fun convertedSheet_should_IgnoreMutatedV1Rows() = runBlocking<Unit> {
        val plain = MixedProjectFixtures.device(convertA = false)
        val mixed = MixedProjectFixtures.device(convertA = true)
        val token = mixed.repos.projects.loadProject(SketchTestSupport.P).getOrNull()!!.token
        val stale = SketchTestSupport.primitive("stale-1", MixedProjectFixtures.SHEET_A).copy(x2 = 99_999)
        mixed.repos.sheets.savePrimitives(listOf(stale), token).getOrNull()
        val staleRows = mixed.repos.sheets.getPrimitives(SheetUuid(MixedProjectFixtures.SHEET_A)).first().getOrNull().orEmpty()
        assertEquals(1, staleRows.size, "the converted sheet now holds a stale v1 row")

        assertEquals(outputs(plain, MixedProjectFixtures.documents(plain)), outputs(mixed, MixedProjectFixtures.documents(mixed)))
    }

    @Test
    fun projection_should_WriteNoSketchPrimitiveRows() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true)
        suspend fun rows() = listOf(MixedProjectFixtures.SHEET_A, MixedProjectFixtures.SHEET_B)
            .associateWith { d.repos.sheets.getPrimitives(SheetUuid(it)).first().getOrNull().orEmpty() }
        val rowsBefore = rows()
        val writesBefore = d.fs.writeCount

        outputs(d, MixedProjectFixtures.documents(d))

        assertEquals(emptyList(), rowsBefore.getValue(MixedProjectFixtures.SHEET_A), "a converted sheet has no v1 rows")
        assertEquals(rowsBefore, rows())
        assertEquals(writesBefore, d.fs.writeCount, "reading a projection must write no file either")
    }
}
