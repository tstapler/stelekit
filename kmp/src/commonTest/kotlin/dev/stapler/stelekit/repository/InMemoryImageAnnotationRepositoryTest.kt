package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.model.ImageAnnotation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(DirectRepositoryWrite::class)
class InMemoryImageAnnotationRepositoryTest {

    private fun repo() = InMemoryImageAnnotationRepository()

    private fun annotation(uuid: String = "ann-001") = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/graph",
        filePath = "/graph/assets/images/test.jpg",
    )

    @Test
    fun save_should_returnRight_when_annotationIsValid() = runTest {
        val r = repo()
        val result = r.saveImageAnnotation(annotation())
        assertIs<Either.Right<Unit>>(result)
        Unit
    }

    @Test
    fun getByUuid_should_returnAnnotation_when_saved() = runTest {
        val r = repo()
        val ann = annotation()
        r.saveImageAnnotation(ann)
        val found = r.getImageAnnotationByUuid(ann.uuid).first()
        assertIs<Either.Right<ImageAnnotation?>>(found)
        assertEquals(ann, found.value)
    }

    @Test
    fun save_should_replaceExisting_when_uuidAlreadyExists() = runTest {
        val r = repo()
        val original = annotation().copy(filePath = "/graph/assets/images/original.jpg")
        val updated = annotation().copy(filePath = "/graph/assets/images/updated.jpg")
        r.saveImageAnnotation(original)
        val second = r.saveImageAnnotation(updated)
        assertIs<Either.Right<Unit>>(second)
        val found = r.getImageAnnotationByUuid(original.uuid).first()
        assertIs<Either.Right<ImageAnnotation?>>(found)
        assertEquals(updated, found.value)
        Unit
    }

    @Test
    fun delete_should_removeAnnotation_when_uuidExists() = runTest {
        val r = repo()
        val ann = annotation()
        r.saveImageAnnotation(ann)
        r.deleteImageAnnotation(ann.uuid)
        val found = r.getImageAnnotationByUuid(ann.uuid).first()
        assertIs<Either.Right<ImageAnnotation?>>(found)
        assertNull(found.value)
    }

    @Test
    fun getByPage_should_returnOnlyMatchingAnnotations() = runTest {
        val r = repo()
        r.saveImageAnnotation(annotation("ann-001").copy(pageUuid = "page-A"))
        r.upsert(annotation("ann-002").copy(pageUuid = "page-B"))
        r.upsert(annotation("ann-003").copy(pageUuid = "page-A"))

        val pageA = r.getImageAnnotationsByPage("page-A").first()
        assertIs<Either.Right<List<ImageAnnotation>>>(pageA)
        assertEquals(2, pageA.value.size)
        assertTrue(pageA.value.all { it.pageUuid == "page-A" })
    }

    @Test
    fun getByTag_should_returnOnlyTaggedAnnotations() = runTest {
        val r = repo()
        r.upsert(annotation("ann-001").copy(tags = listOf("site-A", "indoor")))
        r.upsert(annotation("ann-002").copy(tags = listOf("site-B")))
        r.upsert(annotation("ann-003").copy(tags = listOf("site-A")))

        val siteA = r.getImageAnnotationsByTag("site-A").first()
        assertIs<Either.Right<List<ImageAnnotation>>>(siteA)
        assertEquals(2, siteA.value.size)
    }
}
