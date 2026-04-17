// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Golden-file style integration test that validates the full end-to-end migration
 * pipeline against a representative in-memory graph.
 *
 * Each test:
 * 1. Seeds the graph with representative pages and blocks.
 * 2. Registers one or more migrations.
 * 3. Runs [MigrationRunner.runPending].
 * 4. Asserts the expected post-migration state in the repository.
 * 5. Verifies changelog records reflect the correct status.
 *
 * Idempotency is verified by running the same migration twice and asserting that
 * the second [RunResult.applied] count is zero.
 */
@OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
class MigrationRegressionTest {

    private lateinit var harness: MigrationTestHarness

    private val now = Clock.System.now()

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private fun makePage(uuid: String, name: String, properties: Map<String, String> = emptyMap()) =
        Page(uuid = uuid, name = name, createdAt = now, updatedAt = now, properties = properties)

    private fun makeBlock(
        uuid: String,
        pageUuid: String,
        content: String,
        position: Int = 0,
        properties: Map<String, String> = emptyMap(),
    ) = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = position,
        createdAt = now,
        updatedAt = now,
        properties = properties,
    )

    /** Seeds three pages and five blocks that cover both tagged and untagged cases. */
    private suspend fun seedRepresentativeGraph() {
        val repoSet = harness.repoSet

        // Pages
        val pageA = makePage("page-a", "Alpha")
        val pageB = makePage("page-b", "Beta")
        val pageC = makePage("page-c", "Gamma")
        repoSet.pageRepository.savePage(pageA)
        repoSet.pageRepository.savePage(pageB)
        repoSet.pageRepository.savePage(pageC)

        // Blocks — none have the "migrated" property yet
        repoSet.blockRepository.saveBlock(makeBlock("blk-1", "page-a", "First note", 0))
        repoSet.blockRepository.saveBlock(makeBlock("blk-2", "page-a", "Second note", 1))
        repoSet.blockRepository.saveBlock(makeBlock("blk-3", "page-b", "Beta note", 0))
        repoSet.blockRepository.saveBlock(makeBlock("blk-4", "page-b", "Another beta note", 1))
        repoSet.blockRepository.saveBlock(makeBlock("blk-5", "page-c", "Gamma note", 0))
    }

    // ── Test lifecycle ─────────────────────────────────────────────────────────

    @BeforeTest
    fun setup() {
        harness = MigrationTestHarness()
        MigrationRegistry.clear()
    }

    @AfterTest
    fun teardown() {
        MigrationRegistry.clear()
        harness.close()
    }

    // ── Test 1: full pipeline — applies and records in changelog ───────────────

    /**
     * Verifies that a migration that adds a property to all blocks:
     * - is applied exactly once across all five blocks,
     * - is recorded as APPLIED in the changelog, and
     * - leaves every block with the new property set to the expected value.
     */
    @Test
    fun full_migration_pipeline_applies_and_records_in_changelog(): Unit = runBlocking {
        seedRepresentativeGraph()

        // Register a migration that stamps every block with `migrated:: true`
        val stampMigration = migration("V001__stamp-migrated") {
            description = "Stamp all blocks with migrated:: true"
            checksumBody = "V001__stamp-migrated"
            apply {
                forBlocks(where = { it.properties["migrated"] == null }) {
                    setProperty("migrated", "true")
                }
            }
        }
        MigrationRegistry.register(stampMigration)

        val runner = harness.buildRunner()
        val result = runner.runPending("regression-graph", harness.repoSet, "/tmp/regression")

        // One migration was registered and must have been applied.
        assertEquals(1, result.applied, "Expected exactly one migration applied")
        assertEquals(0, result.skipped, "Expected zero skipped on first run")

        // All five blocks should now carry the property.
        val blockUuids = listOf("blk-1", "blk-2", "blk-3", "blk-4", "blk-5")
        for (uuid in blockUuids) {
            val block = harness.repoSet.blockRepository.getBlockByUuid(uuid).first().getOrNull()
            assertNotNull(block, "Block $uuid should exist")
            assertEquals(
                "true",
                block.properties["migrated"],
                "Block $uuid should have migrated:: true, properties were: ${block.properties}",
            )
        }

        // Changelog must record V001 as APPLIED (appliedIds returns id → checksum for APPLIED rows).
        val appliedIds = harness.changelogRepo.appliedIds("regression-graph")
        assertTrue(
            appliedIds.containsKey("V001__stamp-migrated"),
            "Changelog should contain V001__stamp-migrated as APPLIED, found keys: ${appliedIds.keys}",
        )
        assertNotNull(appliedIds["V001__stamp-migrated"], "Stored checksum must not be null")
    }

    // ── Test 2: idempotency ────────────────────────────────────────────────────

    /**
     * Verifies that running the same migration a second time on an already-migrated
     * graph produces zero applied changes.
     *
     * This is the core idempotency contract of the migration engine:
     * [RunResult.applied] == 0 on re-run because the evaluator detects that all
     * postconditions are already satisfied and emits no [BlockChange]s.
     */
    @Test
    fun migration_pipeline_is_idempotent(): Unit = runBlocking {
        seedRepresentativeGraph()

        val stampMigration = migration("V001__stamp-idempotent") {
            description = "Idempotency check — stamp all blocks"
            checksumBody = "V001__stamp-idempotent"
            apply {
                forBlocks(where = { it.properties["status"] == null }) {
                    setProperty("status", "active")
                }
            }
        }
        MigrationRegistry.register(stampMigration)

        val runner = harness.buildRunner()

        // First run — should apply to all five blocks.
        val firstResult = runner.runPending("idempotent-graph", harness.repoSet, "/tmp/idempotent")
        assertEquals(1, firstResult.applied, "First run should apply the migration once")

        // Second run — migration is already in changelog; evaluator finds all properties
        // already set so no BlockChanges are emitted.
        val secondResult = runner.runPending("idempotent-graph", harness.repoSet, "/tmp/idempotent")
        assertEquals(
            0,
            secondResult.applied,
            "Second run should apply zero migrations (idempotency violation)",
        )
        assertEquals(
            1,
            secondResult.skipped,
            "Second run should report one already-applied migration",
        )

        // Changelog should still contain exactly one APPLIED row.
        val appliedIds = harness.changelogRepo.appliedIds("idempotent-graph")
        assertEquals(1, appliedIds.size, "Changelog should have exactly one APPLIED row")
        assertTrue(appliedIds.containsKey("V001__stamp-idempotent"))
    }

    // ── Test 3: multiple migrations in declared order ──────────────────────────

    /**
     * Verifies that two migrations run in registry order and each is independently
     * recorded in the changelog.
     */
    @Test
    fun two_migrations_run_in_order_and_both_recorded(): Unit = runBlocking {
        seedRepresentativeGraph()

        val m1 = migration("V001__add-type") {
            description = "Add type property"
            checksumBody = "V001__add-type"
            apply {
                forBlocks(where = { it.properties["type"] == null }) {
                    setProperty("type", "note")
                }
            }
        }
        val m2 = migration("V002__add-reviewed") {
            description = "Add reviewed property"
            checksumBody = "V002__add-reviewed"
            apply {
                forBlocks(where = { it.properties["reviewed"] == null }) {
                    setProperty("reviewed", "false")
                }
            }
        }
        MigrationRegistry.registerAll(m1, m2)

        val runner = harness.buildRunner()
        val result = runner.runPending("two-migration-graph", harness.repoSet, "/tmp/two")

        assertEquals(2, result.applied, "Both migrations should be applied")
        assertEquals(0, result.skipped)

        // Spot-check a block for both properties.
        val block = harness.repoSet.blockRepository.getBlockByUuid("blk-1").first().getOrNull()
        assertNotNull(block)
        assertEquals("note", block.properties["type"])
        assertEquals("false", block.properties["reviewed"])

        // Both must appear in the changelog.
        val appliedIds = harness.changelogRepo.appliedIds("two-migration-graph")
        assertTrue(appliedIds.containsKey("V001__add-type"))
        assertTrue(appliedIds.containsKey("V002__add-reviewed"))
    }

    // ── Test 4: migration that matches no blocks writes nothing ────────────────

    /**
     * Verifies that a migration whose `where` predicate matches nothing records
     * itself as APPLIED with zero changes rather than being skipped or erroring.
     */
    @Test
    fun migration_with_no_matching_blocks_records_as_applied_with_zero_changes(): Unit = runBlocking {
        seedRepresentativeGraph()

        val noMatchMigration = migration("V001__no-match") {
            description = "Migration that never matches"
            checksumBody = "V001__no-match"
            apply {
                // Only targets blocks with an extremely specific property value that doesn't exist.
                forBlocks(where = { it.properties["nonexistent-sentinel"] == "xyz-never" }) {
                    setProperty("touched", "true")
                }
            }
        }
        MigrationRegistry.register(noMatchMigration)

        val runner = harness.buildRunner()
        val result = runner.runPending("no-match-graph", harness.repoSet, "/tmp/no-match")

        // The migration ran but touched nothing — still counts as applied.
        assertEquals(1, result.applied)

        // No block should have been modified.
        val block = harness.repoSet.blockRepository.getBlockByUuid("blk-1").first().getOrNull()
        assertNotNull(block)
        assertNull(block.properties["touched"], "Block should not have 'touched' property")

        // Changelog entry exists.
        val appliedIds = harness.changelogRepo.appliedIds("no-match-graph")
        assertTrue(appliedIds.containsKey("V001__no-match"))
    }
}
