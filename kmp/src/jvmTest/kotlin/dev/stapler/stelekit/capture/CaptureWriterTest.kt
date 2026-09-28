// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import arrow.core.Either
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositoryFactoryImpl
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Coverage for [CaptureWriter] — the shared commonMain write path extracted from
 * `CaptureViewModel.performSave()` (androidApp). Uses the `IN_MEMORY` repository backend
 * (see `RepositoryFlowResilienceTest` for the same construction pattern) so no real SQLite
 * file is involved.
 */
class CaptureWriterTest {

    /** A live [RepositorySet] backed by in-memory repositories with a real [DatabaseWriteActor]. */
    private fun newInMemoryRepositorySetWithActor(): Pair<RepositorySet, CoroutineScope> {
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite::memory:")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repoSet = runBlocking { factory.createRepositorySet(GraphBackend.IN_MEMORY, scope) }
        return repoSet to scope
    }

    @Test
    fun writeCapture_should_AppendBlockToTodayJournal_And_ReturnSaved_When_TextIsNonBlank() = runBlocking {
        val (repoSet, scope) = newInMemoryRepositorySetWithActor()
        val graphPath = Files.createTempDirectory("capture-writer-test").toString()
        val fileSystem = PlatformFileSystem().apply { registerGraphRoot(graphPath) }
        try {
            requireNotNull(repoSet.writeActor) { "expected a live writeActor for this AC" }

            val result = CaptureWriter.writeCapture(repoSet, fileSystem, graphPath, "Buy milk")

            val saved = assertIs<CaptureResult.Saved>(result)
            assertTrue(saved.page.isJournal)
            // ensureTodayJournal() seeds a fresh page with one empty block, so a brand-new
            // journal has that seed block plus the appended capture block — assert on content
            // presence rather than an exact count.
            val blocks = repoSet.blockRepository.getBlocksForPage(saved.page.uuid).first().getOrNull().orEmpty()
            assertEquals(1, blocks.count { it.content == "Buy milk" }, "expected exactly one appended capture block")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun writeCapture_should_ReturnFailed_When_SaveBlockThrows() = runBlocking {
        // Pre-populate today's journal so ensureTodayJournal() finds it and never itself
        // calls blockRepository.saveBlock() — isolating the throw to CaptureWriter's own write.
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val now = Clock.System.now()
        val todaysJournal = Page(
            uuid = PageUuid("journal-today"),
            name = "journal-today",
            createdAt = now,
            updatedAt = now,
            isJournal = true,
            journalDate = today,
        )
        val pageRepository = FakePageRepository(initialPages = listOf(todaysJournal))
        val blockRepository = ThrowingBlockRepository()
        val repoSet = RepositorySet(
            blockRepository = blockRepository,
            pageRepository = pageRepository,
            propertyRepository = InMemoryPropertyRepository(),
            referenceRepository = InMemoryReferenceRepository(),
            searchRepository = InMemorySearchRepository(pageRepository, blockRepository),
            journalService = JournalService(pageRepository, blockRepository, writeActor = null),
        )

        // The throw happens before GraphWriter is ever reached, so a real PlatformFileSystem
        // (required by writeCapture's signature) is never actually touched here.
        val result = CaptureWriter.writeCapture(repoSet, PlatformFileSystem(), "/tmp/graph", "Buy milk")

        val failed = assertIs<CaptureResult.Failed>(result)
        assertTrue(failed.message.contains("boom"), "expected the thrown message to surface, got: ${failed.message}")
    }

    @Test
    fun resolveCaptureAvailability_should_ReturnNoActiveGraph_When_NoRepositorySetActive() {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = FakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )

        assertEquals(CaptureResult.NoActiveGraph, CaptureWriter.resolveCaptureAvailability(graphManager))
    }

    @Test
    fun resolveCaptureAvailability_should_ReturnGraphLocked_When_ActiveGraphIsParanoidMode() = runBlocking {
        // FakeFileSystem.fileExists() always returns true, so GraphManager.addGraph's vault-marker
        // check (VaultManager.vaultFilePath) marks any graph added through it as paranoid-mode.
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = FakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val id = graphManager.addGraph("/vault-graph")
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        assertEquals(true, graphManager.getActiveGraphInfo()?.isParanoidMode)
        assertEquals(CaptureResult.GraphLocked, CaptureWriter.resolveCaptureAvailability(graphManager))
    }

    @Test
    fun writeCapture_should_ResolveToSingleBlock_When_CalledTwiceWithSameCaptureId() = runBlocking {
        val (repoSet, scope) = newInMemoryRepositorySetWithActor()
        val graphPath = Files.createTempDirectory("capture-writer-test").toString()
        val fileSystem = PlatformFileSystem().apply { registerGraphRoot(graphPath) }
        try {
            val captureId = "capture-fixed-id"

            val first = CaptureWriter.writeCapture(repoSet, fileSystem, graphPath, "First", captureId)
            val second = CaptureWriter.writeCapture(
                repoSet, fileSystem, graphPath, "First edited", captureId,
            )

            assertIs<CaptureResult.Saved>(first)
            val savedPage = assertIs<CaptureResult.Saved>(second).page
            val blocks = repoSet.blockRepository.getBlocksForPage(savedPage.uuid).first().getOrNull().orEmpty()
            val matching = blocks.filter { it.uuid == BlockUuid(captureId) }
            assertEquals(1, matching.size, "expected replay with the same captureId to resolve to one block")
            assertEquals("First edited", matching.single().content)
        } finally {
            scope.cancel()
        }
    }

    private class ThrowingBlockRepository : FakeBlockRepository() {
        @OptIn(DirectRepositoryWrite::class)
        override suspend fun saveBlock(block: Block): Either<DomainError, Unit> = throw RuntimeException("boom")
    }
}
