package dev.stapler.stelekit.repository

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 6.1: tags round-trip through real SQLDelight with quotes, backslashes, unicode and commas. */
@OptIn(DirectRepositoryWrite::class)
class ImageAnnotationTagEscapingTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: SteleDatabase
    private lateinit var repo: SqlDelightImageAnnotationRepository

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        database = SteleDatabase(driver)
        repo = SqlDelightImageAnnotationRepository(database)
    }

    @AfterTest
    fun teardown() = driver.close()

    private fun annotation(uuid: String, tags: List<String>) = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk-$uuid",
        pageUuid = "page-1",
        graphPath = "/graph",
        filePath = "/graph/assets/$uuid.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 200.0, 100),
        unit = MeasurementUnit.METERS,
        tags = tags,
    )

    private fun byTag(tag: String): List<String> = runBlocking {
        repo.getImageAnnotationsByTag(tag).first().getOrNull().orEmpty().map { it.uuid }.sorted()
    }

    private fun stored(uuid: String): List<String> = runBlocking {
        repo.getImageAnnotationByUuid(uuid).first().getOrNull()!!.tags
    }

    private val nasty = listOf(
        "a\"b\\c",
        "plain",
        "with,comma",
        "x\",\"y",
        "café 日本語 😀",
        "back\\",
        "100%_done",
        "line\nbreak\ttab",
        "",
    )

    @Test
    fun tags_should_RoundTripExactly_When_TheyContainQuotesBackslashesUnicodeOrCommas() = runBlocking<Unit> {
        repo.saveImageAnnotation(annotation("all", nasty))
        assertEquals(nasty, stored("all"))
    }

    @Test
    fun getByTag_should_MatchEachNastyTagExactly_When_AnnotationsShareNoOtherTag() = runBlocking<Unit> {
        nasty.forEachIndexed { i, tag -> repo.saveImageAnnotation(annotation("n$i", listOf(tag))) }
        nasty.forEachIndexed { i, tag -> assertEquals(listOf("n$i"), byTag(tag), "tag [$tag]") }
    }

    @Test
    fun getByTag_should_NotCollide_When_OneTagContainsAnotherAsQuotedText() = runBlocking<Unit> {
        repo.saveImageAnnotation(annotation("joined", listOf("x\",\"foo")))
        repo.saveImageAnnotation(annotation("real", listOf("foo")))
        assertEquals(listOf("real"), byTag("foo"))
        assertEquals(listOf("joined"), byTag("x\",\"foo"))
    }

    @Test
    fun getByTag_should_TreatLikeWildcardsLiterally_When_TagHasPercentOrUnderscore() = runBlocking<Unit> {
        repo.saveImageAnnotation(annotation("w", listOf("100%_done")))
        repo.saveImageAnnotation(annotation("other", listOf("100xxdone")))
        assertEquals(listOf("w"), byTag("100%_done"))
        assertEquals(emptyList(), byTag("%"))
    }

    @Test
    fun decodeTags_should_SalvageLegacyRows_When_OldWriterLeftQuotesUnescaped() {
        assertEquals(listOf("a\"b\\c", "plain"), decodeTags("[\"a\"b\\c\",\"plain\"]"))
        assertEquals(listOf("one", "two"), decodeTags("[\"one\",\"two\"]"))
        assertEquals(emptyList(), decodeTags("[]"))
        assertEquals(emptyList(), decodeTags("garbage"))
    }
}
