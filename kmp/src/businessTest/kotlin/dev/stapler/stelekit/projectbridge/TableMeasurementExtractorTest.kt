package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.FC
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.fusion.SourceOrigin
import dev.stapler.stelekit.units.LengthQualifier
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableMeasurementExtractorTest {
    private val refs = RefTable()
    private val extractor = TableMeasurementExtractor(ClosetTableRegistry.registry, refs)

    private fun List<Observation>.byDim() = associateBy { it.dimension }

    @Test
    fun `closet fixture tables yield the zone widths heights and opening`() {
        val result = extractor.extract(closetTablesMarkdown(), "closet page")
        val dims = result.observations.byDim()
        assertEquals(FC.opening, dims.getValue("opening").value)
        assertEquals(FC.cabinetHeightB, dims.getValue("height.rear").value)
        assertEquals(FC.cabinetHeightA, dims.getValue("height.front").value)
        assertEquals(FC.zoneLeftLong, dims.getValue("zone.left-long.width").value)
        assertEquals(FC.zoneLeftShort, dims.getValue("zone.left-short.width").value)
        assertEquals(FC.zoneShelves, dims.getValue("zone.shelves.width").value)
        assertEquals(FC.zoneRightShort, dims.getValue("zone.right-short.width").value)
        assertEquals(FC.zoneRightLong, dims.getValue("zone.right-long.width").value)
        assertEquals(Rational(38), dims.getValue("rod.left-long").value)
        assertEquals(Rational(38), dims.getValue("rod.right-long").value)
        assertEquals(11, result.observations.size)
        assertTrue(result.observations.all { it.source.kind == SourceKind.TABLE })
    }

    @Test
    fun `bold is stripped and the feet-inches form agrees with the inches`() {
        val dims = extractor.extract(closetTablesMarkdown(), "p").observations.byDim()
        assertEquals(Rational.ZERO, dims.getValue("opening").precision)
    }

    @Test
    fun `tilde value carries approx and others do not`() {
        val dims = extractor.extract(closetTablesMarkdown(), "p").observations.byDim()
        assertEquals(Rational(24), dims.getValue("depth.usable").value)
        assertTrue(LengthQualifier.APPROX in dims.getValue("depth.usable").qualifiers)
        assertFalse(LengthQualifier.APPROX in dims.getValue("opening").qualifiers)
    }

    @Test
    fun `unmapped table reports its columns and never guesses a dimension`() {
        val result = extractor.extract(closetTablesMarkdown(), "p")
        // short-hang rod heights have no alias; the headerless hang-math table has none either
        assertEquals(listOf(NeedsMapping(listOf("Rod height")), NeedsMapping(listOf(""))), result.needsMapping)
        assertTrue(result.observations.none { it.value == FC.hangA })
    }

    @Test
    fun `text and dash cells yield nothing`() {
        val md = "| Zone | Width | Contents |\n|---|---|---|\n| Shelves (shared) | — | 4 shelves (8.2\" gaps) |\n"
        val result = extractor.extract(md, "p")
        assertTrue(result.observations.isEmpty())
        assertTrue(result.needsMapping.isEmpty())
    }

    @Test
    fun `width in inches header resolves through the unit-stripped alias`() {
        val md = "| Zone | Width (in) |\n|---|---|\n| Left - short hang | 22 1/2 |\n"
        assertEquals(FC.zoneLeftShort, extractor.extract(md, "p").observations.single().value)
    }

    @Test
    fun `lossy decimals keep their rounding precision`() {
        val md = "| Measurement | Value |\n|---|---|\n| Height at rear | 40.9\" |\n"
        val o = extractor.extract(md, "p").observations.single()
        assertEquals(Rational(409, 10), o.value)
        assertEquals(Rational(1, 20), o.precision)
    }

    @Test
    fun `count dimension reads a count`() {
        val md = "| Item | Qty |\n|---|---|\n| Brackets | 6 |\n"
        val o = extractor.extract(md, "p").observations.single()
        assertEquals(Rational(6), o.value)
    }

    @Test
    fun `table indented under a bullet is read`() {
        val md = "- Notes\n  - | Zone | Width |\n    |---|---|\n    | Left - short hang | 22 1/2\" |\n"
        assertEquals(1, extractor.extract(md, "p").observations.size)
    }

    @Test
    fun `refs are opaque and distinct per cell and keep the locator only in the ref table`() {
        val result = extractor.extract(closetTablesMarkdown(), "Secret Page Title")
        val ids = result.observations.map { it.source.ref }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { "secret" in it.normalised.lowercase() })
        assertTrue(refs.describe(ids.first())!!.startsWith("Secret Page Title"))
    }

    @Test
    fun `page property marker skips the whole page`() {
        val result = extractor.extract(closetTablesMarkdown(), "p", SourceOrigin(pagePropertyKeys = setOf("stelekit-digest")))
        assertTrue(result.observations.isEmpty())
        assertEquals(3, result.skippedSelf)
    }

    @Test
    fun `first block marker skips the whole page`() {
        val md = "stelekit-digest:: true\n\n" + closetTablesMarkdown()
        val result = extractor.extract(md, "p")
        assertTrue(result.observations.isEmpty())
        assertEquals(3, result.skippedSelf)
    }

    @Test
    fun `first block marker passed by the caller skips the page`() {
        val result = extractor.extract(closetTablesMarkdown(), "p", SourceOrigin(firstBlockPropertyKeys = setOf("stelekit-digest::")))
        assertTrue(result.observations.isEmpty())
    }

    @Test
    fun `block carrying the marker is skipped but its sibling is read`() {
        val md = "- Intro\n- Digest\n  stelekit-digest:: x\n  - | Zone | Width |\n    |---|---|\n    | Left - short hang | 30\" |\n" +
            "- Mine\n  - | Zone | Width |\n    |---|---|\n    | Left - short hang | 22 1/2\" |\n"
        val result = extractor.extract(md, "p")
        assertEquals(FC.zoneLeftShort, result.observations.single().value)
        assertEquals(1, result.skippedSelf)
    }

    @Test
    fun `marker on the table block itself skips it`() {
        val md = "- | Zone | Width |\n  |---|---|\n  | Left - short hang | 30\" |\n  stelekit-digest:: x\n"
        val result = extractor.extract(md, "p")
        assertNull(result.observations.firstOrNull())
        assertEquals(1, result.skippedSelf)
    }

    @Test
    fun `collector table stage runs the extractor over page text`() {
        val collector = ProjectSourceCollector(ClosetTableRegistry.registry, RefTable())
        val result = collector.extractTables(listOf(TablePageText("closet", closetTablesMarkdown())))
        assertEquals(11, result.observations.size)
    }
}
