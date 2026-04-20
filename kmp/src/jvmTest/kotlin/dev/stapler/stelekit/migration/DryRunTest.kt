// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests for [MigrationRunner.dryRun] covering Task 4.1 validation criteria:
 * - Returns plan with correct pending count.
 * - Does not modify repo state.
 * - Flags destructive changes as [MigrationPlanEntry.isDestructive].
 */
@OptIn(DirectRepositoryWrite::class)
class DryRunTest {

    private val now = Clock.System.now()

    @BeforeTest
    fun setup() {
        MigrationRegistry.clear()
    }

    @AfterTest
    fun teardown() {
        MigrationRegistry.clear()
    }

    private fun buildTestDb(): SteleDatabase {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        return SteleDatabase(driver)
    }

    private fun buildRepoSet(): Pair<RepositorySet, DatabaseWriteActor> {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val actor = DatabaseWriteActor(blockRepo, pageRepo)
        val repoSet = RepositorySet(
            blockRepository = blockRepo,
            pageRepository = pageRepo,
            propertyRepository = InMemoryPropertyRepository(),
            referenceRepository = InMemoryReferenceRepository(),
            searchRepository = InMemorySearchRepository(pageRepo, blockRepo),
            journalService = JournalService(pageRepo, blockRepo),
            writeActor = actor,
        )
        return repoSet to actor
    }

    private fun buildRunner(
        db: SteleDatabase,
        repoSet: RepositorySet,
        actor: DatabaseWriteActor,
    ): MigrationRunner {
        val changelogRepo = ChangelogRepository(db)
        val evaluator = DslEvaluator(repoSet)
        val applier = ChangeApplier(actor, opLogger = null)
        return MigrationRunner(
            registry = MigrationRegistry,
            changelogRepo = changelogRepo,
            evaluator = evaluator,
            applier = applier,
            flusher = null,
        )
    }

    private fun makePage(uuid: String, name: String) = Page(
        uuid = uuid,
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    private fun makeBlock(uuid: String, pageUuid: String, content: String, position: Int = 0) = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = position,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun dry_run_returns_correct_pending_count() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "First migration"
            checksumBody = "dry-run-body-1"
            apply { /* no-op */ }
        }
        val m2 = migration("V002") {
            description = "Second migration"
            checksumBody = "dry-run-body-2"
            apply { /* no-op */ }
        }
        MigrationRegistry.registerAll(m1, m2)

        val runner = buildRunner(db, repoSet, actor)
        val plan = runner.dryRun("graph-dry", repoSet)

        assertEquals(2, plan.wouldApply, "Expected 2 pending migrations in plan")
        assertEquals(2, plan.pendingMigrations.size)
        assertEquals(0, plan.alreadyApplied)
        assertEquals("V001", plan.pendingMigrations[0].migrationId)
        assertEquals("V002", plan.pendingMigrations[1].migrationId)

        actor.close()
    }

    @Test
    fun dry_run_does_not_modify_repo() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "No-op migration"
            checksumBody = "dry-run-no-op-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(m1)

        val runner = buildRunner(db, repoSet, actor)

        // Snapshot repo state before
        val pagesBefore = repoSet.pageRepository.getAllPages().first().getOrDefault(emptyList())
        val changelogBefore = ChangelogRepository(db).appliedIds("graph-dry-no-mod")

        runner.dryRun("graph-dry-no-mod", repoSet)

        // Snapshot repo state after
        val pagesAfter = repoSet.pageRepository.getAllPages().first().getOrDefault(emptyList())
        val changelogAfter = ChangelogRepository(db).appliedIds("graph-dry-no-mod")

        assertEquals(pagesBefore.size, pagesAfter.size, "Page count should be unchanged after dry run")
        assertEquals(changelogBefore, changelogAfter, "Changelog should be unchanged after dry run")

        actor.close()
    }

    @Test
    fun dry_run_flags_destructive_changes() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        // Seed a block so the migration can target it for deletion
        val pageRepo = repoSet.pageRepository
        val blockRepo = repoSet.blockRepository
        val page = makePage("page-uuid-destroy-1234", "TestPageDestroy")
        pageRepo.savePage(page)
        val block = makeBlock("block-uuid-destroy-1234", "page-uuid-destroy-1234", "doomed block")
        blockRepo.saveBlock(block)

        val m1 = migration("V001") {
            description = "Destructive migration"
            checksumBody = "dry-run-destructive-body"
            allowDestructive = true
            apply {
                forBlocks(where = { it.uuid == "block-uuid-destroy-1234" }) {
                    deleteBlock()
                }
            }
        }
        MigrationRegistry.register(m1)

        val runner = buildRunner(db, repoSet, actor)
        val plan = runner.dryRun("graph-dry-destructive", repoSet)

        assertEquals(1, plan.pendingMigrations.size)
        assertTrue(plan.pendingMigrations[0].isDestructive, "Migration with deleteBlock should be flagged as destructive")

        actor.close()
    }
}
