@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
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
import kotlin.test.assertNotNull
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

    /**
     * Delegates every [BlockRepository] call except [saveBlocks], which succeeds on its first
     * invocation and fails on every subsequent one — models a pre-existing page being created
     * successfully, then a *later* overwrite import's `saveBlocks` failing.
     */
    private class FailSecondSaveBlocksRepository(
        private val delegate: BlockRepository,
    ) : BlockRepository by delegate {
        private var callCount = 0

        override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> {
            callCount += 1
            return if (callCount >= 2) {
                DomainError.DatabaseError.WriteFailed("boom").left()
            } else {
                delegate.saveBlocks(blocks)
            }
        }
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

    /**
     * Gate 2 BLOCKER B2 regression: before the fix, [GraphLoader.importMarkdownString] hardcoded
     * `pagePath = ""` for every QR import, so [dev.stapler.stelekit.db.MarkdownPageParser
     * .generateUuid]'s seed (`"$pagePath:${parentUuid ?: "root"}:$blockIndex"`) was
     * content/page-independent — any two QR imports with the same block-tree shape (e.g. both a
     * single root block) produced an IDENTICAL block UUID. Because `blocks.uuid` is `UNIQUE` and
     * `insertBlock` is `INSERT OR REPLACE`, the second import's write would silently hijack and
     * reparent the first page's block row onto the second page. This test imports two
     * structurally-identical, differently-named/-content pages and asserts the first page's block
     * survives the second import completely unchanged.
     */
    @Test
    fun import_should_PreserveDistinctBlocks_When_TwoPagesShareIdenticalBlockTreeShape() = runBlocking {
        val blockRepo = InMemoryBlockRepository()
        val (service, pageRepo, _) = buildService(blockRepository = blockRepo)

        val firstResult = service.import("- hello\n", PageName("Page One"))
        assertTrue(firstResult.isRight())
        val pageAUuid = pageRepo.getPageByName("Page One").first().getOrNull()?.uuid
        assertNotNull(pageAUuid, "page A must have been saved")
        val pageABlocksBefore = blockRepo.getBlocksForPage(pageAUuid).first().getOrNull()
        assertEquals(1, pageABlocksBefore?.size)
        assertEquals("hello", pageABlocksBefore?.first()?.content)

        // Same block-tree shape as page A (single root block, no children) but different content
        // and a different target page.
        val secondResult = service.import("- world\n", PageName("Page Two"))
        assertTrue(secondResult.isRight())

        val pageABlocksAfter = blockRepo.getBlocksForPage(pageAUuid).first().getOrNull()
        assertEquals(1, pageABlocksAfter?.size, "page A must still have exactly its own block")
        assertEquals("hello", pageABlocksAfter?.first()?.content, "page A's block content must be unchanged")
        assertEquals(
            pageABlocksBefore?.first()?.uuid,
            pageABlocksAfter?.first()?.uuid,
            "page A's block UUID must not be hijacked by an unrelated page import",
        )
    }

    /**
     * Gate 2 BLOCKER C1 regression: before the fix, a `saveBlocks` failure during an OVERWRITE
     * import (after [DatabaseWriteActor.deleteBlocksForPage] had already cleared the pre-existing
     * page's old blocks) triggered an unconditional `writeActor.deletePage(pageToSave.uuid)` —
     * since `pageToSave.uuid == existing.uuid` on the overwrite path, this deleted the user's
     * REAL pre-existing page row entirely, not just the failed new import. This is the inverse of
     * [import_should_DeleteOrphanedPage_When_SaveBlocksFailsAfterSavePageSucceeds], which correctly
     * covers the brand-new-page case.
     */
    @Test
    fun import_should_NotDeletePreExistingPage_When_SaveBlocksFailsDuringOverwrite() = runBlocking {
        val innerBlockRepo = InMemoryBlockRepository()
        val (service, pageRepo, _) = buildService(blockRepository = FailSecondSaveBlocksRepository(innerBlockRepo))

        // First import succeeds (call #1 to saveBlocks) — establishes the pre-existing page.
        val first = service.import("- v1\n", PageName("Overwrite Fail Test"))
        assertTrue(first.isRight())
        val preExistingUuid = pageRepo.getPageByName("Overwrite Fail Test").first().getOrNull()?.uuid
        assertNotNull(preExistingUuid, "pre-existing page must have been saved")

        // Second import overwrites the same page; its saveBlocks call (#2) is made to fail.
        val second = service.import(
            "- v2 new content\n",
            PageName("Overwrite Fail Test"),
            QrImportService.CollisionChoice.OVERWRITE,
        )

        assertTrue(second.isLeft())
        assertTrue(
            second.leftOrNull() is DomainError.QrTransferError.OverwriteFailedPreviousContentAffected,
            "expected a distinct overwrite-failure error, got ${second.leftOrNull()}",
        )
        // The pre-existing page ROW must still exist — even though its blocks were cleared before
        // the failed write, the page itself must never vanish from the database.
        val afterFailure = pageRepo.getPageByName("Overwrite Fail Test").first().getOrNull()
        assertNotNull(afterFailure, "pre-existing page must NOT be deleted on a failed overwrite")
        assertEquals(preExistingUuid, afterFailure.uuid)
    }
}
