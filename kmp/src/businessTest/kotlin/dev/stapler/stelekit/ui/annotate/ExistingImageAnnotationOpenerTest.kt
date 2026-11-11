// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(DirectRepositoryWrite::class)
class ExistingImageAnnotationOpenerTest {
    private val annotations = InMemoryImageAnnotationRepository()
    private val blocks = InMemoryBlockRepository()
    private var fileThere = true

    private fun opener() = ExistingImageAnnotationOpener(
        imageAnnotationRepository = annotations,
        blockRepository = blocks,
        writeActor = null,
        graphPath = "/graph/",
        fileExists = { fileThere },
    )

    private fun inlineBlock() = Block(
        uuid = BlockUuid("block-inline"),
        pageUuid = PageUuid("page-001"),
        content = "![](../assets/photo.jpg)",
        position = "a0",
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
    )

    @Test
    fun resolveImageFilePath_mapsGraphRelativeReferenceToAbsolutePath() {
        assertEquals("/graph/assets/photo.jpg", resolveImageFilePath("/graph/", "../assets/photo.jpg"))
        assertEquals("/graph/assets/photo.jpg", resolveImageFilePath("/graph", "/graph/assets/photo.jpg"))
        assertEquals("content://x/1", resolveImageFilePath("/graph", "content://x/1"))
    }

    @Test
    fun existingAnnotation_isReusedWithoutCreatingBlocks() = runTest {
        annotations.saveImageAnnotation(
            ImageAnnotation(uuid = "ann-1", blockUuid = "b1", pageUuid = "page-001", graphPath = "/graph", filePath = "/graph/assets/photo.jpg"),
        )

        val result = opener().open(ExistingImageRequest("page-001", "../assets/photo.jpg"))

        assertEquals("ann-1", result.getOrNull())
        assertTrue(blocks.getBlocksForPage(PageUuid("page-001")).first().getOrNull().orEmpty().isEmpty())
    }

    @Test
    fun imageWithoutAnnotation_getsFreshRecordAndAppendedBlock() = runTest {
        val uuid = opener().open(ExistingImageRequest("page-001", "../assets/photo.jpg", tags = listOf("t"))).getOrNull()

        assertNotNull(uuid)
        val saved = annotations.getImageAnnotationByUuid(uuid).first().getOrNull()
        assertNotNull(saved)
        assertEquals("/graph/assets/photo.jpg", saved.filePath)
        assertEquals(listOf("t"), saved.tags)
        val pageBlocks = blocks.getBlocksForPage(PageUuid("page-001")).first().getOrNull().orEmpty()
        assertEquals(listOf(saved.blockUuid), pageBlocks.map { it.uuid.value })
        assertEquals(BlockType.ImageAnnotation, pageBlocks.single().blockType)
    }

    @Test
    fun inlineSourceBlock_isConvertedInPlaceWithoutDuplicate() = runTest {
        val source = inlineBlock()
        blocks.saveBlock(source)

        val uuid = opener().open(ExistingImageRequest("page-001", "../assets/photo.jpg", sourceBlock = source)).getOrNull()

        assertNotNull(uuid)
        assertEquals("block-inline", annotations.getImageAnnotationByUuid(uuid).first().getOrNull()?.blockUuid)
        val pageBlocks = blocks.getBlocksForPage(PageUuid("page-001")).first().getOrNull().orEmpty()
        assertEquals(1, pageBlocks.size)
        assertEquals(BlockType.ImageAnnotation, pageBlocks.single().blockType)
        assertEquals(uuid, pageBlocks.single().properties["image-id"])
    }

    @Test
    fun missingFile_isAnHonestErrorAndWritesNothing() = runTest {
        fileThere = false

        val result = opener().open(ExistingImageRequest("page-001", "../assets/photo.jpg"))

        assertIs<OpenForAnnotationError.FileMissing>(result.leftOrNull())
        assertTrue(annotations.getImageAnnotationsByPage("page-001").first().getOrNull().orEmpty().isEmpty())
        assertTrue(blocks.getBlocksForPage(PageUuid("page-001")).first().getOrNull().orEmpty().isEmpty())
    }

    @Test
    fun imageWithoutPage_isReportedNotCrashed() = runTest {
        assertIs<OpenForAnnotationError.NoPage>(opener().open(ExistingImageRequest(null, "../assets/photo.jpg")).leftOrNull())
    }

    @Test
    fun contentUri_skipsFileProbe() = runTest {
        fileThere = false

        val result = opener().open(ExistingImageRequest("page-001", "content://media/1"))

        assertTrue(result.isRight())
    }
}
