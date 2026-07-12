@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.transfer.qrcode

import arrow.core.left
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Story 3.2.1 acceptance criteria: [QrImportService] parses [import]'s `markdown` via
 * [GraphLoader.importMarkdownString] and writes via [DatabaseWriteActor] typed methods only — with
 * mandatory compensating rollback when `saveBlocks` fails after `savePage` succeeds. `markdown` is
 * a plain [String], not a [VerifiedTransferPayload]: the CRC32 proof gate + envelope unwrap
 * ([TransferPayloadEnvelope]) both already ran, one layer up in [QrTransferCoordinator], before
 * [QrImportService]'s only production caller ever calls [import] — see [QrImportService]'s own
 * class KDoc for why this service can't accept a [VerifiedTransferPayload] here without either
 * weakening its `internal` constructor invariant or re-minting a second one.
 */
class QrImportServiceTest {

    /** No-op [FileSystem] — [GraphLoader.importMarkdownString] never touches the filesystem. */
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

    /** Delegates every [BlockRepository] call except [saveBlocks], which always fails. */
    private class FailingSaveBlocksRepository(
        private val delegate: BlockRepository,
    ) : BlockRepository by delegate {
        override suspend fun saveBlocks(blocks: List<Block>) =
            DomainError.DatabaseError.WriteFailed("boom").left()
    }

    private suspend fun buildService(
        blockRepository: BlockRepository = InMemoryBlockRepository(),
    ): Triple<QrImportService, InMemoryPageRepository, DatabaseWriteActor> {
        val pageRepo = InMemoryPageRepository()
        val actor = DatabaseWriteActor(blockRepository, pageRepo)
        val graphLoader = GraphLoader(
            fileSystem = NoOpFileSystem(),
            pageRepository = pageRepo,
            blockRepository = blockRepository,
            externalWriteActor = actor,
        )
        val service = QrImportService(graphLoader, pageRepo, actor)
        return Triple(service, pageRepo, actor)
    }

    @Test
    fun import_should_ReturnRightPageName_When_NoCollisionAndValidMarkdown() = runBlocking {
        val (service, pageRepo, _) = buildService()

        val result = service.import("- root block\n\t- child block\n", PageName("Meeting Notes"))

        assertTrue(result.isRight())
        assertEquals("Meeting Notes", result.getOrNull()?.value)
        val saved = pageRepo.getPageByName("Meeting Notes").first().getOrNull()
        assertEquals("Meeting Notes", saved?.name)
    }

    @Test
    fun import_should_ReturnMarkdownParseFailed_When_OutlinerPipelineCannotParse() = runBlocking {
        // The block content contains a null byte, which Block's own Validation.validateContent
        // rejects during GraphLoader.importMarkdownString's block-construction tail. Caught there
        // and surfaced as a distinct terminal Left — never treated as success.
        val (service, _, _) = buildService()

        val result = service.import("- bad\u0000content\n", PageName("Bad Content Page"))

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(
            error is DomainError.QrTransferError.MarkdownParseFailed,
            "expected MarkdownParseFailed, got $error",
        )
    }

    @Test
    fun import_should_RejectDirectoryTraversalName_When_PageNameContainsDotDotSegment() = runBlocking {
        // A page name containing a directory-traversal segment fails Page's own
        // Validation.validateName before any write occurs — never used to construct a raw path.
        val (service, _, _) = buildService()

        val result = service.import("- some content\n", PageName("../etc"))

        assertTrue(result.isLeft())
    }

    @Test
    fun import_should_DeleteOrphanedPage_When_SaveBlocksFailsAfterSavePageSucceeds() = runBlocking {
        val innerBlockRepo = InMemoryBlockRepository()
        val (service, pageRepo, _) = buildService(blockRepository = FailingSaveBlocksRepository(innerBlockRepo))

        val result = service.import("- root block\n\t- child block\n", PageName("Orphan Test"))

        assertTrue(result.isLeft())
        // No orphaned zero-block page survives — savePage's write was rolled back.
        val saved = pageRepo.getPageByName("Orphan Test").first().getOrNull()
        assertNull(saved, "orphaned page must be deleted after saveBlocks failure")
    }

    @Test
    fun import_should_WriteOnlyThroughDatabaseWriteActor_When_PageNameContainsPathTraversalCharacters() = runBlocking {
        // Real DatabaseWriteActor/DB write path (integration row) — a name with a leading slash
        // is validated/normalized and never used to construct a raw filesystem path; the only sink
        // is a DB row via DatabaseWriteActor.
        val (service, pageRepo, _) = buildService()

        val result = service.import("- content\n", PageName("/etc/passwd"))

        // Validation.validateName does not reject a bare leading slash (only ".." segments and
        // backslashes) — the name is stored as a DB row value, never as a raw file path, so this
        // is safe either way. Assert the write succeeded and landed as a normal DB row.
        assertTrue(result.isRight())
        val savedName = result.getOrNull()!!.value
        val saved = pageRepo.getPageByName(savedName).first().getOrNull()
        assertEquals(savedName, saved?.name)
    }

    @Test
    fun import_should_DisambiguateName_When_CollisionAndKeepBothChosen() = runBlocking {
        val (service, _, _) = buildService()
        val first = service.import("- v1\n", PageName("Duplicate"))
        assertTrue(first.isRight())

        val second = service.import(
            "- v2\n",
            PageName("Duplicate"),
            QrImportService.CollisionChoice.KEEP_BOTH,
        )

        assertTrue(second.isRight())
        assertEquals("Duplicate (2)", second.getOrNull()?.value)
    }

    @Test
    fun import_should_ReplaceExistingPage_When_CollisionAndOverwriteChosen() = runBlocking {
        val (service, pageRepo, _) = buildService()
        val first = service.import("- v1\n", PageName("Overwrite Me"))
        assertTrue(first.isRight())
        val firstUuid = pageRepo.getPageByName("Overwrite Me").first().getOrNull()?.uuid

        val second = service.import(
            "- v2 new content\n",
            PageName("Overwrite Me"),
            QrImportService.CollisionChoice.OVERWRITE,
        )

        assertTrue(second.isRight())
        assertEquals("Overwrite Me", second.getOrNull()?.value)
        val afterUuid = pageRepo.getPageByName("Overwrite Me").first().getOrNull()?.uuid
        assertEquals(firstUuid, afterUuid, "overwrite must reuse the existing page's UUID, not create a duplicate")
    }
}
