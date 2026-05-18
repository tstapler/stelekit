package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.model.ImageAnnotation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    fun save_should_returnRight_when_annotationIsValid() = runBlocking {
        val r = repo()
        val result = r.saveImageAnnotation(annotation())
        assertIs<Either.Right<Unit>>(result)
        Unit
    }

    @Test
    fun getByUuid_should_returnAnnotation_when_saved() = runBlocking {
        val r = repo()
        val ann = annotation()
        r.saveImageAnnotation(ann)
        val found = r.getImageAnnotationByUuid(ann.uuid).first()
        assertIs<Either.Right<ImageAnnotation?>>(found)
        assertEquals(ann, found.value)
    }

    @Test
    fun save_should_returnLeft_when_uuidAlreadyExists() = runBlocking {
        val r = repo()
        r.saveImageAnnotation(annotation())
        val second = r.saveImageAnnotation(annotation())
        assertIs<Either.Left<*>>(second)
        Unit
    }

    @Test
    fun delete_should_removeAnnotation_when_uuidExists() = runBlocking {
        val r = repo()
        val ann = annotation()
        r.saveImageAnnotation(ann)
        r.deleteImageAnnotation(ann.uuid)
        val found = r.getImageAnnotationByUuid(ann.uuid).first()
        assertIs<Either.Right<ImageAnnotation?>>(found)
        assertNull(found.value)
    }

    @Test
    fun getByPage_should_returnOnlyMatchingAnnotations() = runBlocking {
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
    fun getByTag_should_returnOnlyTaggedAnnotations() = runBlocking {
        val r = repo()
        r.upsert(annotation("ann-001").copy(tags = listOf("site-A", "indoor")))
        r.upsert(annotation("ann-002").copy(tags = listOf("site-B")))
        r.upsert(annotation("ann-003").copy(tags = listOf("site-A")))

        val siteA = r.getImageAnnotationsByTag("site-A").first()
        assertIs<Either.Right<List<ImageAnnotation>>>(siteA)
        assertEquals(2, siteA.value.size)
    }
}
