package dev.stapler.stelekit.transfer

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * [GraphMergeService] recovers pages missing from one graph by copying them in from another —
 * see its class KDoc for why this is a two-step snapshot/merge flow rather than a single call
 * (only one graph's [RepositorySet] is ever open at a time).
 */
class GraphMergeServiceTest {

    /** No-op — [GraphLoader.importMarkdownString] never touches the filesystem. */
    private class NoOpFileSystem : FileSystem {
        override fun getDefaultGraphPath() = ""
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private fun buildRepositorySet(): RepositorySet {
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        val writeActor = DatabaseWriteActor(blockRepository, pageRepository)
        return RepositorySet(
            blockRepository = blockRepository,
            pageRepository = pageRepository,
            propertyRepository = InMemoryPropertyRepository(),
            referenceRepository = InMemoryReferenceRepository(),
            searchRepository = InMemorySearchRepository(),
            journalService = JournalService(pageRepository, blockRepository, writeActor),
            writeActor = writeActor,
        )
    }

    private suspend fun seedPage(repos: RepositorySet, name: String, markdown: String) {
        val graphLoader = GraphLoader(
            fileSystem = NoOpFileSystem(),
            pageRepository = repos.pageRepository,
            blockRepository = repos.blockRepository,
            externalWriteActor = repos.writeActor,
        )
        val importer = QrImportService(graphLoader, repos.pageRepository, repos.writeActor!!)
        val result = importer.import(markdown, PageName(name))
        assertTrue(result.isRight(), "seedPage($name) failed: $result")
    }

    @Test
    fun merge_should_ImportAllPages_When_TargetIsEmpty() = runBlocking {
        val source = buildRepositorySet()
        seedPage(source, "Page A", "- content a\n")
        seedPage(source, "Page B", "- content b\n")
        val target = buildRepositorySet()
        val fileSystem = NoOpFileSystem()

        val service = GraphMergeService()
        service.snapshot(source)
        assertEquals(2, service.pendingPageCount.value)

        val result = service.merge(target, fileSystem)

        assertEquals(setOf("Page A", "Page B"), result.imported.toSet())
        assertTrue(result.skippedExisting.isEmpty())
        assertTrue(result.failed.isEmpty())
        assertEquals(0, service.pendingPageCount.value)
        assertEquals("Page A", target.pageRepository.getPageByName("Page A").first().getOrNull()?.name)
    }

    @Test
    fun merge_should_SkipExistingPages_When_TargetAlreadyHasSameName() = runBlocking {
        val source = buildRepositorySet()
        seedPage(source, "Shared Page", "- from source, should not overwrite\n")
        seedPage(source, "New Page", "- only in source\n")
        val target = buildRepositorySet()
        seedPage(target, "Shared Page", "- original target content\n")
        val fileSystem = NoOpFileSystem()

        val service = GraphMergeService()
        service.snapshot(source)
        val result = service.merge(target, fileSystem)

        assertEquals(listOf("New Page"), result.imported)
        assertEquals(listOf("Shared Page"), result.skippedExisting)
        assertTrue(result.failed.isEmpty())
        val targetBlocks = target.blockRepository
            .getBlocksForPage(target.pageRepository.getPageByName("Shared Page").first().getOrNull()!!.uuid)
            .first().getOrNull().orEmpty()
        assertTrue(
            targetBlocks.any { it.content.contains("original target content") },
            "merge() must not overwrite a page that already exists in the target",
        )
    }
}
