// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class UrlLinkRepairServiceTest {

    private val now = Clock.System.now()

    private fun page(uuid: String, name: String, path: String) =
        Page(uuid = PageUuid(uuid), name = name, createdAt = now, updatedAt = now, filePath = path)

    private fun block(uuid: String, pageUuid: String, content: String, pos: String = "a0") = Block(
        uuid = BlockUuid(uuid), pageUuid = PageUuid(pageUuid), content = content, position = pos, createdAt = now, updatedAt = now,
    )

    @Test
    fun repairsCorruptedBlocksInDbAndFile_andIsIdempotent(): Unit = runBlocking {
        val tempDir = kotlin.io.path.createTempDirectory(kotlin.io.path.Path(System.getProperty("user.home")), "stelekit_url_repair_").toFile()
        try {
            val pagesDir = File(tempDir, "pages").also { it.mkdirs() }
            val file = File(pagesDir, "Reading.md")
            file.writeText("- https://[[GitHub]].com/lni/dragonboat\n- clean https://github.com/x [[Treacle]]\n")

            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()
            pageRepo.savePage(page("p1", "Reading", file.absolutePath))
            blockRepo.saveBlock(block("b1", "p1", "https://[[GitHub]].com/lni/dragonboat", "a0"))
            blockRepo.saveBlock(block("b2", "p1", "clean https://github.com/x [[Treacle]]", "a1"))
            pageRepo.savePage(page("p2", "Other", File(pagesDir, "Other.md").absolutePath))
            blockRepo.saveBlock(block("b3", "p2", "no urls, just [[Treacle]]", "a0"))
            blockRepo.saveBlock(block("b4", "p2", "www.[[GitHub]].com/y", "a1"))

            val path = EffectiveNotesPath(tempDir.absolutePath)
            val writer = GraphWriter(PlatformFileSystem.withRoot(tempDir.absolutePath)).also {
                it.currentEpoch = GraphEpoch(GraphId("url-repair-test"), graphPath = path, sequence = 1L)
            }
            val service = UrlLinkRepairService(blockRepo, pageRepo, DatabaseWriteActor(blockRepo, pageRepo), writer, batchSize = 1)

            val first = service.run(path).getOrNull()!!
            assertEquals(2, first.blocksRepaired)
            assertEquals(2, first.pagesRewritten)
            assertEquals("www.github.com/y", blockRepo.getBlockByUuid(BlockUuid("b4")).first().getOrNull()!!.content)
            assertTrue(first.clean)
            assertEquals("https://github.com/lni/dragonboat", blockRepo.getBlockByUuid(BlockUuid("b1")).first().getOrNull()!!.content)
            assertEquals("clean https://github.com/x [[Treacle]]", blockRepo.getBlockByUuid(BlockUuid("b2")).first().getOrNull()!!.content)
            val onDisk = file.readText()
            assertTrue("https://github.com/lni/dragonboat" in onDisk && "[[GitHub]]" !in onDisk, "file not repaired: $onDisk")

            val second = service.run(path).getOrNull()!!
            assertEquals(0, second.blocksRepaired)
            assertEquals(0, second.pagesRewritten)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
