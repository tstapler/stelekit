package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.EffectiveNotesPath
import arrow.core.Either
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.db.sidecar.ImageSidecarIndexer
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.db.sidecar.SidecarFile
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(DirectRepositoryWrite::class)
class ImageSidecarManagerTest {

    private fun annotation(uuid: String = "ann-001") = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/graph",
        filePath = "/graph/assets/images/test.jpg",
    )

    private fun measurement(uuid: String = "meas-001", imageUuid: String = "ann-001") = MeasurementAnnotation(
        uuid = uuid,
        imageUuid = imageUuid,
        annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.9, 0.8)),
        valueMeters = 2.5,
    )

    @Test
    fun writeThenRead_should_matchOriginal_when_fileSystemSucceeds() {
        val fs = FakeFileSystem()
        val manager = ImageSidecarManager(fs)
        val ann = annotation()
        val measurements = listOf(measurement())

        val writeResult = manager.writeSidecar(ann, measurements)
        assertIs<Either.Right<Unit>>(writeResult)

        val readResult = manager.readSidecar(EffectiveNotesPath("/graph"), ann.uuid)
        assertIs<Either.Right<SidecarFile?>>(readResult)
        val sidecar = readResult.value
        assertNotNull(sidecar)
        assertEquals(ann.uuid, sidecar.imageAnnotation.uuid)
        assertEquals(1, sidecar.measurements.size)
        assertEquals("meas-001", sidecar.measurements[0].uuid)
        assertEquals(2.5, sidecar.measurements[0].valueMeters)
    }

    @Test
    fun writeSidecar_should_returnLeft_when_fileSystemThrows() {
        val fs = FailingFileSystem()
        val manager = ImageSidecarManager(fs)
        val ann = annotation()

        val writeResult = manager.writeSidecar(ann, emptyList())
        assertIs<Either.Left<*>>(writeResult)
    }

    @Test
    fun readSidecar_should_returnNullRight_when_fileDoesNotExist() {
        val fs = FakeFileSystem()
        val manager = ImageSidecarManager(fs)
        val readResult = manager.readSidecar(EffectiveNotesPath("/graph"), "non-existent-uuid")
        assertIs<Either.Right<SidecarFile?>>(readResult)
        assertNull(readResult.value)
    }
}

/** A FileSystem that always throws on writeFile — used to test failure paths. */
private class FailingFileSystem : FakeFileSystem() {
    override fun writeFile(path: String, content: String): Boolean =
        throw java.io.IOException("Simulated I/O failure: disk full")
}
