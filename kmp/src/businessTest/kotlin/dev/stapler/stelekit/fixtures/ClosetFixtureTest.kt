package dev.stapler.stelekit.fixtures

import dev.stapler.stelekit.units.Rational
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 1.5a: the sanitised closet fixtures are present, mutually consistent and carry the seeded conflicts. */
class ClosetFixtureTest {
    private fun fixture(name: String): String =
        checkNotNull(ClosetFixtureTest::class.java.classLoader.getResourceAsStream("home-sketch/closet/$name")) {
            "fixture $name missing from the test classpath"
        }.bufferedReader().use { it.readText() }

    private fun sum(vararg v: Rational): Rational = v.reduce { a, b -> a.add(b).getOrNull()!! }

    private fun count(haystack: String, needle: String): Int = haystack.windowed(needle.length).count { it == needle }

    private val manifest get() = fixture("fixture-manifest.json")

    @Test
    fun `every manifest file is on the classpath and non-empty`() {
        val files = Regex("\"files\"\\s*:\\s*\\[([^\\]]*)]").find(manifest)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        assertEquals(
            setOf("fixture-manifest.json", "closet.yaml", "closet-tables.md", "closet-elevation.svg", "closet-section.svg", "dimension-map.json"),
            files.toSet(),
        )
        files.forEach { assertTrue(fixture(it).isNotBlank(), "$it is blank") }
        assertTrue("\"project\": \"proj-closet-1\"" in manifest)
    }

    @Test
    fun `zone widths in the yaml sum to the opening exactly`() {
        val yaml = fixture("closet.yaml")
        val widths = Regex("(?m)^    width: ([0-9.]+)").findAll(yaml).map { it.groupValues[1] }.toList()
        assertEquals(listOf("33.4375", "22.5", "24.1875", "22.5", "33.375"), widths)
        val all = sum(FC.zoneLeftLong, FC.zoneLeftShort, FC.zoneShelves, FC.zoneRightShort, FC.zoneRightLong)
        assertEquals(FC.opening, all)
        assertEquals(FC.zoneMiddle, sum(FC.zoneLeftShort, FC.zoneShelves, FC.zoneRightShort))
        assertEquals(FC.opening, sum(FC.zoneLeftLong, FC.zoneMiddle, FC.zoneRightLong))
    }

    @Test
    fun `tables state the same zone widths and the feet-inches form`() {
        val tables = fixture("closet-tables.md")
        listOf("**136\" (11' 4\")**", "**33 7/16\"**", "22 1/2\"", "24 3/16\"", "**33 3/8\"**", "40 7/8\"", "43 5/16\"").forEach {
            assertTrue(it in tables, "tables missing $it")
        }
        assertEquals(Rational(136), Rational(11 * 12 + 4))
    }

    @Test
    fun `svg scales and label strings match the constants`() {
        val elevation = fixture("closet-elevation.svg")
        val section = fixture("closet-section.svg")
        assertTrue("scale(${FC.scaleElevation}, -${FC.scaleElevation})" in elevation)
        assertTrue("scale(${FC.scaleSection}, ${FC.scaleSection})" in section)
        listOf("Total Width: 136\"", "38\" Rod", "36\" Rod", "43.31\" (Front)", "40.88\" (Rear)").forEach {
            assertTrue(it in elevation, "elevation missing $it")
        }
        assertTrue("23.12\" Cabinet H" in section)
        assertTrue("<title" !in elevation && "<metadata" !in elevation && "<title" !in section && "<metadata" !in section)
    }

    @Test
    fun `negative control - lossy decimal labels do not conflict with their fractions`() {
        val precision = FC.labelPrecision.num.toDouble() / FC.labelPrecision.den
        assertTrue(abs(43.31 - FC.cabinetHeightA.num.toDouble() / FC.cabinetHeightA.den) <= precision)
        assertTrue(abs(23.12 - FC.usableCabinetHeight.num.toDouble() / FC.usableCabinetHeight.den) <= precision)
        val elevation = fixture("closet-elevation.svg")
        assertTrue("${FC.lossyDecimalA}\" (Front)" in elevation)
        assertTrue(FC.fractionA + "\"" in fixture("closet-tables.md"))
        assertTrue(FC.fractionB + "\"" in fixture("closet-tables.md"))
    }

    @Test
    fun `seeded mutations apply exactly once and produce the expected disagreement`() {
        val tables = fixture("closet-tables.md")
        assertEquals(1, count(tables, "**33 7/16\"**"))
        val mutated = tables.replace("**33 7/16\"**", "**33 3/8\"**")
        assertEquals(2, count(mutated, "**33 3/8\"**"))
        val sumWithMutation = sum(FC.zoneLeftLongTableMutated, FC.zoneLeftShort, FC.zoneShelves, FC.zoneRightShort, FC.zoneRightLong)
        assertEquals(Rational(2175, 16), sumWithMutation) // 135 15/16
        assertEquals(Rational(1, 16), FC.zoneLeftLong.subtract(FC.zoneLeftLongTableMutated).getOrNull())

        val elevation = fixture("closet-elevation.svg")
        assertEquals(1, count(elevation, ">38\" Rod<"))
        assertEquals(0, count(elevation, ">${FC.rodLabelMutated}<"))
        assertEquals(1, count(elevation.replace(">38\" Rod<", ">${FC.rodLabelMutated}<"), ">${FC.rodLabelMutated}<"))
    }

    @Test
    fun `real conflicts are present in the sources`() {
        val tables = fixture("closet-tables.md")
        val yaml = fixture("closet.yaml")
        assertTrue("1-1/16\" OD" in tables && "radius: 0.625" in yaml)
        assertTrue("gives 12 carriers" in tables && "10 rod carriers" in tables && "qty 8" in tables)
        assertEquals(FC.carrierCounts, listOf(8, 10, 12))
    }

    @Test
    fun `hang sum shelf pitch and stud list hold exactly`() {
        assertEquals(FC.hangSum, sum(FC.hangA, FC.usableCabinetHeight))
        assertEquals(FC.shelfPitch, Rational(8200, 1000))
        assertEquals(Rational(259, 2), FC.studs.last())
        val near = FC.studs[2].subtract(FC.zoneLeftLong).getOrNull()!!
        assertEquals(Rational(1, 16), near)
    }
}
