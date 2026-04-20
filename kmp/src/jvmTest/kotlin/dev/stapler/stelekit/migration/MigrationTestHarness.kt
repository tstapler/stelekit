// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.OperationLogger
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
/**
 * Reusable test harness that wires up a complete in-memory migration environment.
 *
 * Creates an isolated in-memory SQLite database (for [ChangelogRepository] and
 * [OperationLogger]) and in-memory repository implementations (for block/page storage)
 * so each test gets a pristine, independent environment.
 *
 * Usage:
 * ```kotlin
 * val harness = MigrationTestHarness()
 * val runner = harness.buildRunner()
 * runner.runPending("test-graph", harness.repoSet, "/tmp/test")
 * harness.close()
 * ```
 */
class MigrationTestHarness {

    private val driver: SqlDriver = DriverFactory().createDriver("jdbc:sqlite::memory:")

    /** In-memory SQLite database used for [ChangelogRepository] and [OperationLogger]. */
    val db: SteleDatabase = SteleDatabase(driver)

    private val blockRepo = InMemoryBlockRepository()
    private val pageRepo = InMemoryPageRepository()

    /** Write actor serialising all DB mutations to avoid SQLITE_BUSY contention. */
    val writeActor: DatabaseWriteActor = DatabaseWriteActor(blockRepo, pageRepo)

    /** Operation logger backed by the in-memory SQLite DB. */
    val opLogger: OperationLogger = OperationLogger(db, sessionId = "test-session")

    /** Changelog backed by the same in-memory SQLite DB as [opLogger]. */
    val changelogRepo: ChangelogRepository = ChangelogRepository(db)

    /**
     * Full in-memory [RepositorySet] used by [DslEvaluator] and [ChangeApplier].
     *
     * Seed pages and blocks before running a migration:
     * ```kotlin
     * runBlocking {
     *     harness.repoSet.pageRepository.savePage(myPage)
     *     harness.repoSet.blockRepository.saveBlock(myBlock)
     * }
     * ```
     */
    val repoSet: RepositorySet = RepositorySet(
        blockRepository = blockRepo,
        pageRepository = pageRepo,
        propertyRepository = InMemoryPropertyRepository(),
        referenceRepository = InMemoryReferenceRepository(),
        searchRepository = InMemorySearchRepository(pageRepo, blockRepo),
        journalService = JournalService(pageRepo, blockRepo),
        writeActor = writeActor,
    )

    /** [DslEvaluator] wired to [repoSet]. */
    val evaluator: DslEvaluator = DslEvaluator(repoSet)

    /** [ChangeApplier] wired to [writeActor] and [opLogger]. */
    val applier: ChangeApplier = ChangeApplier(writeActor, opLogger)

    /**
     * Creates a [MigrationRunner] using [registry] (defaults to the global
     * [MigrationRegistry]) with all dependencies wired from this harness.
     *
     * The caller is responsible for populating [registry] before calling
     * [MigrationRunner.runPending].
     */
    fun buildRunner(registry: MigrationRegistry = MigrationRegistry): MigrationRunner =
        MigrationRunner(
            registry = registry,
            changelogRepo = changelogRepo,
            evaluator = evaluator,
            applier = applier,
            flusher = null,
        )

    /**
     * Closes the [writeActor] coroutine channel and releases the in-memory SQLite
     * driver. Call this in `@AfterTest` to prevent resource leaks between tests.
     */
    fun close() {
        writeActor.close()
        driver.close()
    }
}
