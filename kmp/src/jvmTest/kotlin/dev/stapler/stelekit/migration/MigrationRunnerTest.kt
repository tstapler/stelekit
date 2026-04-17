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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Tests for [MigrationRunner] covering the four key behaviours specified in Task 3.3:
 * happy path, idempotency, tamper detection, and interrupted-migration detection.
 */
class MigrationRunnerTest {

    @BeforeTest
    fun setup() {
        MigrationRegistry.clear()
    }

    @AfterTest
    fun teardown() {
        MigrationRegistry.clear()
    }

    // ── Test fixtures ──────────────────────────────────────────────────────────

    /**
     * Creates an isolated in-memory SQLite database and a [ChangelogRepository] backed by it.
     * The database is shared so the runner and the assertions query the same state.
     */
    private fun buildTestDb(): SteleDatabase {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        return SteleDatabase(driver)
    }

    /**
     * Builds a simple [RepositorySet] backed by in-memory repositories and an actor
     * so [ChangeApplier] can execute writes.
     */
    private fun buildRepoSet(): Pair<RepositorySet, DatabaseWriteActor> {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val scope = CoroutineScope(SupervisorJob())
        val actor = DatabaseWriteActor(blockRepo, pageRepo, scope)
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

    /**
     * Creates a fresh [MigrationRegistry] (isolated from the global object) and a
     * [MigrationRunner] wired together for a single test.
     */
    private fun buildRunner(
        db: SteleDatabase,
        repoSet: RepositorySet,
        actor: DatabaseWriteActor,
        registry: MigrationRegistry = MigrationRegistry,
    ): MigrationRunner {
        val changelogRepo = ChangelogRepository(db)
        val evaluator = DslEvaluator(repoSet)
        val applier = ChangeApplier(actor, opLogger = null)
        return MigrationRunner(
            registry = registry,
            changelogRepo = changelogRepo,
            evaluator = evaluator,
            applier = applier,
            flusher = null,
        )
    }

    // ── Test 1: happy path ─────────────────────────────────────────────────────

    @Test
    fun happy_path_applies_all_pending_migrations() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "Add status property"
            checksumBody = "v001-body"
            apply { /* no-op: no pages/blocks in test graph */ }
        }
        val m2 = migration("V002") {
            description = "Add type property"
            checksumBody = "v002-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.registerAll(m1, m2)

        val runner = buildRunner(db, repoSet, actor)
        val result = runner.runPending("graph-test", repoSet, "/tmp/test-graph")

        assertEquals(2, result.applied, "Expected both migrations to be applied")
        assertEquals(0, result.skipped, "Expected no skipped migrations on first run")

        val appliedIds = ChangelogRepository(db).appliedIds("graph-test")
        assertNotNull(appliedIds["V001"], "V001 should be in changelog")
        assertNotNull(appliedIds["V002"], "V002 should be in changelog")

        actor.close()
    }

    // ── Test 2: idempotency ────────────────────────────────────────────────────

    @Test
    fun idempotency_second_run_applies_nothing() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "Migration one"
            checksumBody = "idempotent-body-1"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(m1)

        val runner = buildRunner(db, repoSet, actor)

        // First run — applies m1
        val firstResult = runner.runPending("graph-idem", repoSet, "/tmp/test-graph")
        assertEquals(1, firstResult.applied)

        // Second run — nothing pending
        val secondResult = runner.runPending("graph-idem", repoSet, "/tmp/test-graph")
        assertEquals(0, secondResult.applied, "Second run should apply nothing")
        assertEquals(1, secondResult.skipped, "Second run should report 1 already-applied")

        actor.close()
    }

    // ── Test 3: tampered migration throws ──────────────────────────────────────

    @Test
    fun tampered_migration_throws() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        // Register and apply V001 with its original checksumBody.
        val original = migration("V001") {
            description = "Original"
            checksumBody = "original-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(original)

        val runner = buildRunner(db, repoSet, actor)
        runner.runPending("graph-tamper", repoSet, "/tmp/test-graph")

        // Now simulate tampering: re-register V001 with a different checksumBody.
        // @BeforeTest/@AfterTest only guard between tests — within a test we clear manually
        // when intentionally swapping registrations.
        MigrationRegistry.clear()
        val tampered = migration("V001") {
            description = "Tampered"
            checksumBody = "TAMPERED-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(tampered)

        // Second run should detect the checksum mismatch.
        assertFailsWith<MigrationTamperedError> {
            runner.runPending("graph-tamper", repoSet, "/tmp/test-graph")
        }

        actor.close()
    }

    // ── Test 4: interrupted migration throws ──────────────────────────────────

    @Test
    fun interrupted_migration_throws() = runBlocking {
        val db = buildTestDb()
        val (repoSet, actor) = buildRepoSet()

        val m1 = migration("V001") {
            description = "Will be interrupted"
            checksumBody = "interrupted-body"
            apply { /* no-op */ }
        }
        MigrationRegistry.register(m1)

        // Manually seed a RUNNING row to simulate a previously interrupted run.
        val changelogRepo = ChangelogRepository(db)
        val checksum = MigrationChecksumComputer.compute(m1.checksumBody)
        changelogRepo.markRunning(
            id = "V001",
            graphId = "graph-interrupted",
            order = 0,
            checksum = checksum,
            description = m1.description,
        )

        val runner = buildRunner(db, repoSet, actor)

        // Runner should detect the RUNNING row and throw.
        assertFailsWith<InterruptedMigrationException> {
            runner.runPending("graph-interrupted", repoSet, "/tmp/test-graph")
        }

        actor.close()
    }
}
