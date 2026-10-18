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
class ImageSidecarIndexerTest {

    @Test
    fun rebuildFromSidecars_should_upsertAllRows_when_sidecarFilesPresent() = runBlocking {
        val fs = FakeFileSystem()
        // Write 3 sidecar files
        val manager = ImageSidecarManager(fs)
        for (i in 1..3) {
            val ann = ImageAnnotation(
                uuid = "ann-00$i",
                blockUuid = "blk-00$i",
                pageUuid = "page-001",
                graphPath = "/graph",
                filePath = "/graph/assets/images/img-00$i.jpg",
            )
            manager.writeSidecar(ann, emptyList())
        }

        val imageRepo = InMemoryImageAnnotationRepository()
        val measurementRepo = InMemoryMeasurementAnnotationRepository()
        val indexer = ImageSidecarIndexer(fs, imageRepo, measurementRepo)

        val result = indexer.rebuildFromSidecars(EffectiveNotesPath("/graph"))
        assertIs<Either.Right<Int>>(result)
        assertEquals(3, result.value)

        val all = imageRepo.getAllImageAnnotations().first()
        assertIs<Either.Right<List<ImageAnnotation>>>(all)
        assertEquals(3, all.value.size)
    }

    @Test
    fun rebuildFromSidecars_should_returnZero_when_noSidecarDirectory() = runBlocking {
        val fs = FakeFileSystem()
        val imageRepo = InMemoryImageAnnotationRepository()
        val measurementRepo = InMemoryMeasurementAnnotationRepository()
        val indexer = ImageSidecarIndexer(fs, imageRepo, measurementRepo)

        val result = indexer.rebuildFromSidecars(EffectiveNotesPath("/graph"))
        assertIs<Either.Right<Int>>(result)
        assertEquals(0, result.value)
    }
}
