// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [MigrationRunner.repair], [MigrationRunner.baseline], and
 * [MigrationRunner.recalculateChecksums] covering Task 4.2 validation criteria.
 */
class RepairTest {

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

    // ── repair ─────────────────────────────────────────────────────────────────

    @Test
    fun repair_deletes_failed_rows() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "Will fail"
            checksumBody = "repair-fail-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(m1)

        // Seed a FAILED row directly via ChangelogRepository
        val changelogRepo = ChangelogRepository(db)
        val checksum = MigrationChecksumComputer.compute(m1.checksumBody)
        changelogRepo.markRunning("V001", "graph-repair", 0, checksum, m1.description)
        changelogRepo.markFailed("V001", "graph-repair", "simulated failure")

        val runner = buildRunner(db, repoSet, actor)
        val result = runner.repair("graph-repair")

        assertEquals(1, result.deletedCount, "Expected 1 FAILED row deleted")
        assertTrue("V001" in result.deletedIds, "V001 should be in deletedIds")

        // Verify the row is gone
        val appliedAfter = changelogRepo.appliedIds("graph-repair")
        assertTrue("V001" !in appliedAfter, "V001 should no longer be in changelog after repair")

        actor.close()
    }

    @Test
    fun repair_throws_when_running_exists() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "Interrupted"
            checksumBody = "repair-running-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(m1)

        // Seed a RUNNING row
        val changelogRepo = ChangelogRepository(db)
        val checksum = MigrationChecksumComputer.compute(m1.checksumBody)
        changelogRepo.markRunning("V001", "graph-repair-running", 0, checksum, m1.description)

        val runner = buildRunner(db, repoSet, actor)

        assertFailsWith<InterruptedMigrationException> {
            runner.repair("graph-repair-running")
        }

        actor.close()
    }

    // ── baseline ───────────────────────────────────────────────────────────────

    @Test
    fun baseline_marks_migrations_as_applied() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "Baseline migration"
            checksumBody = "baseline-body-1"
            apply { /* no-op */ }
        }
        val m2 = migration("V002") {
            description = "Post-baseline migration"
            checksumBody = "baseline-body-2"
            apply { /* no-op */ }
        }
        MigrationRegistry.registerAll(m1, m2)

        val runner = buildRunner(db, repoSet, actor)
        val result = runner.baseline("graph-baseline", "V001")

        assertEquals(1, result.baselined, "Expected 1 migration to be baselined")

        val changelogRepo = ChangelogRepository(db)
        val appliedIds = changelogRepo.appliedIds("graph-baseline")
        assertTrue("V001" in appliedIds, "V001 should be in changelog after baseline")
        assertTrue("V002" !in appliedIds, "V002 should not be in changelog — it's after the baseline point")

        actor.close()
    }

    @Test
    fun baseline_throws_when_migration_already_applied() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "Already applied"
            checksumBody = "baseline-existing-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(m1)

        // Apply V001 normally first
        val runner = buildRunner(db, repoSet, actor)
        runner.runPending("graph-baseline-exists", repoSet, "/tmp/test-graph")

        // Trying to baseline V001 again should throw
        assertFailsWith<IllegalStateException> {
            runner.baseline("graph-baseline-exists", "V001")
        }

        actor.close()
    }

    // ── recalculateChecksums ───────────────────────────────────────────────────

    @Test
    fun recalculate_checksums_updates_stored_value() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "Checksum update test"
            checksumBody = "original-checksum-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(m1)

        val runner = buildRunner(db, repoSet, actor)
        runner.runPending("graph-checksum", repoSet, "/tmp/test-graph")

        val changelogRepo = ChangelogRepository(db)
        val originalChecksum = changelogRepo.appliedIds("graph-checksum")["V001"]!!

        // Simulate a legitimate checksumBody change by re-registering with different body
        MigrationRegistry.clear()
        val m1Updated = migration("V001") {
            description = "Checksum update test"
            checksumBody = "updated-checksum-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(m1Updated)

        val updatedIds = runner.recalculateChecksums("graph-checksum")

        assertTrue("V001" in updatedIds, "V001 should be in the list of updated checksums")

        val newChecksum = changelogRepo.appliedIds("graph-checksum")["V001"]!!
        val expectedChecksum = MigrationChecksumComputer.compute("updated-checksum-body")
        assertEquals(expectedChecksum, newChecksum, "Stored checksum should match recomputed value")
        assertTrue(newChecksum != originalChecksum, "New checksum should differ from the original")

        actor.close()
    }
}
