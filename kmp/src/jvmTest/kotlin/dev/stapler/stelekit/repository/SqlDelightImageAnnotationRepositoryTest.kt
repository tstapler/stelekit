package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [SqlDelightImageAnnotationRepository] using an in-memory SQLite database.
 *
 * Covers TC-015, TC-016, TC-017, TC-018.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightImageAnnotationRepositoryTest {

    private lateinit var database: SteleDatabase
    private lateinit var imageRepo: SqlDelightImageAnnotationRepository
    private lateinit var measurementRepo: SqlDelightMeasurementAnnotationRepository

    @BeforeTest
    fun setUp() {
        // DriverFactory.createDriver runs MigrationRunner, so all migrations (including 4.sqm)
        // are applied before SteleDatabase wraps the driver.
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        database = SteleDatabase(driver)
        imageRepo = SqlDelightImageAnnotationRepository(database)
        measurementRepo = SqlDelightMeasurementAnnotationRepository(database)
    }

    @AfterTest
    fun tearDown() {
        // In-memory SQLite — no explicit close needed
    }

    private fun annotation(
        uuid: String = "ann-001",
        blockUuid: String = "blk-001",
        pageUuid: String = "page-001",
        tags: List<String> = emptyList(),
    ) = ImageAnnotation(
        uuid = uuid,
        blockUuid = blockUuid,
        pageUuid = pageUuid,
        graphPath = "/graph",
        filePath = "/graph/assets/images/test.jpg",
        calibration = Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = 200.0,
            confidencePercent = 100,
        ),
        unit = MeasurementUnit.METERS,
        tags = tags,
    )

    @Test
    fun insertAndSelect_should_roundTrip_when_usingInMemorySqlite() = runBlocking {
        val ann = annotation()
        val saveResult = imageRepo.saveImageAnnotation(ann)
        assertIs<Either.Right<Unit>>(saveResult)

        val fetched = imageRepo.getImageAnnotationByUuid(ann.uuid).first()
        assertIs<Either.Right<ImageAnnotation?>>(fetched)
        val loaded = fetched.value
        assertNotNull(loaded)
        assertEquals(ann.uuid, loaded.uuid)
        assertEquals(ann.blockUuid, loaded.blockUuid)
        assertEquals(ann.pageUuid, loaded.pageUuid)
        assertEquals(ann.calibration.method, loaded.calibration.method)
        assertEquals(ann.calibration.pixelsPerMeter, loaded.calibration.pixelsPerMeter, 0.0001)
        assertEquals(ann.unit, loaded.unit)
    }

    @Test
    fun selectByPage_should_returnOnlyMatchingRows_when_multipleAnnotationsExist() = runBlocking {
        imageRepo.saveImageAnnotation(annotation("ann-001", pageUuid = "page-A"))
        imageRepo.saveImageAnnotation(annotation("ann-002", pageUuid = "page-B"))
        imageRepo.saveImageAnnotation(annotation("ann-003", pageUuid = "page-A"))

        val pageA = imageRepo.getImageAnnotationsByPage("page-A").first()
        assertIs<Either.Right<List<ImageAnnotation>>>(pageA)
        assertEquals(2, pageA.value.size)
        assertTrue(pageA.value.all { it.pageUuid == "page-A" })

        val pageB = imageRepo.getImageAnnotationsByPage("page-B").first()
        assertIs<Either.Right<List<ImageAnnotation>>>(pageB)
        assertEquals(1, pageB.value.size)
    }

    @Test
    fun selectByTag_should_filterCorrectly_when_tagsJsonColumnQueried() = runBlocking {
        imageRepo.saveImageAnnotation(annotation("ann-001", tags = listOf("site-A")))
        imageRepo.saveImageAnnotation(annotation("ann-002", tags = listOf("site-B")))
        imageRepo.saveImageAnnotation(annotation("ann-003", tags = listOf("site-A", "indoor")))

        val siteA = imageRepo.getImageAnnotationsByTag("site-A").first()
        assertIs<Either.Right<List<ImageAnnotation>>>(siteA)
        assertEquals(2, siteA.value.size)

        val siteB = imageRepo.getImageAnnotationsByTag("site-B").first()
        assertIs<Either.Right<List<ImageAnnotation>>>(siteB)
        assertEquals(1, siteB.value.size)
    }

    @Test
    fun delete_should_removeAnnotation_when_uuidExists() = runBlocking {
        val ann = annotation()
        imageRepo.saveImageAnnotation(ann)
        imageRepo.deleteImageAnnotation(ann.uuid)

        val fetched = imageRepo.getImageAnnotationByUuid(ann.uuid).first()
        assertIs<Either.Right<ImageAnnotation?>>(fetched)
        assertNull(fetched.value)
    }

    @Test
    fun getAllImageAnnotations_should_returnAllInserted() = runBlocking {
        imageRepo.saveImageAnnotation(annotation("ann-001"))
        imageRepo.saveImageAnnotation(annotation("ann-002"))
        imageRepo.saveImageAnnotation(annotation("ann-003"))

        val all = imageRepo.getAllImageAnnotations().first()
        assertIs<Either.Right<List<ImageAnnotation>>>(all)
        assertEquals(3, all.value.size)
    }
}

/**
 * Integration tests for [SqlDelightMeasurementAnnotationRepository].
 *
 * Covers TC-018.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightMeasurementAnnotationRepositoryTest {

    private lateinit var database: SteleDatabase
    private lateinit var imageRepo: SqlDelightImageAnnotationRepository
    private lateinit var measurementRepo: SqlDelightMeasurementAnnotationRepository

    @BeforeTest
    fun setUp() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        database = SteleDatabase(driver)
        imageRepo = SqlDelightImageAnnotationRepository(database)
        measurementRepo = SqlDelightMeasurementAnnotationRepository(database)
    }

    private fun baseAnnotation(uuid: String = "img-001") = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/graph",
        filePath = "/graph/assets/images/test.jpg",
    )

    private fun measurement(uuid: String = "meas-001", imageUuid: String = "img-001") = MeasurementAnnotation(
        uuid = uuid,
        imageUuid = imageUuid,
        annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.8, 0.9)),
        valueMeters = 3.5,
        valueDisplay = "3.5 m",
    )

    @Test
    fun insertAndCascadeDelete_should_removeChildRows_when_parentDeleted() = runBlocking {
        imageRepo.saveImageAnnotation(baseAnnotation())
        val measurements = (1..5).map { measurement("meas-00$it") }
        measurementRepo.saveMeasurements("img-001", measurements)

        // Verify 5 measurements were inserted
        val before = measurementRepo.getMeasurementsForImage("img-001").first()
        assertIs<Either.Right<List<MeasurementAnnotation>>>(before)
        assertEquals(5, before.value.size)

        // Delete the parent image annotation → cascade deletes measurements
        imageRepo.deleteImageAnnotation("img-001")

        // Measurements should be empty now
        val after = measurementRepo.getMeasurementsForImage("img-001").first()
        assertIs<Either.Right<List<MeasurementAnnotation>>>(after)
        assertTrue(after.value.isEmpty())
    }

    @Test
    fun saveMeasurements_should_roundTrip_all_fields() = runBlocking {
        imageRepo.saveImageAnnotation(baseAnnotation())
        val m = measurement().copy(
            annotationType = AnnotationType.AREA,
            valueMeters = 6.28,
            label = "Room A",
            colorHex = "#00FF00",
        )
        measurementRepo.saveMeasurementAnnotation(m)

        val fetched = measurementRepo.getMeasurementsForImage("img-001").first()
        assertIs<Either.Right<List<MeasurementAnnotation>>>(fetched)
        val loaded = fetched.value.firstOrNull()
        assertNotNull(loaded)
        assertEquals(AnnotationType.AREA, loaded.annotationType)
        assertEquals(6.28, loaded.valueMeters ?: 0.0, 0.0001)
        assertEquals("Room A", loaded.label)
    }
}
