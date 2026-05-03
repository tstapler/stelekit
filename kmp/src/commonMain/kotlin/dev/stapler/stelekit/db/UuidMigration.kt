// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.left
import kotlinx.coroutines.CancellationException
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.util.UuidGenerator

/**
 * One-shot migration from content-derived block UUIDs to position-derived block UUIDs.
 *
 * The old scheme seeded `generateUuid` with filePath + parentUuid + siblingIndex + **content**,
 * meaning any content edit changed the UUID and reset version history. The new scheme drops
 * content from the seed, making UUIDs stable across edits.
 *
 * This migration runs once per graph (guarded by a `metadata` record). It re-derives every
 * block's UUID from position and updates all FK columns that reference block UUIDs:
 * - `blocks.parent_uuid`
 * - `blocks.left_uuid`
 * - `block_references.from_block_uuid`
 * - `block_references.to_block_uuid`
 * - `properties.block_uuid`
 *
 * The old seed formula was: "$filePath:${parentUuid ?: "root"}:$position:$content"
 * The new seed formula is:  "$filePath:${parentUuid ?: "root"}:$position"
 *
 * All data needed to reconstruct both formulas is stored in the DB (file_path via pages,
 * parent_uuid, position, and content are all block columns), so no markdown re-parsing
 * is required.
 */
class UuidMigration(
    private val writeActor: DatabaseWriteActor,
) {
    private val logger = Logger("UuidMigration")

    /**
     * Runs the migration if it hasn't run yet for this graph.
     * Safe to call on every app start — checks the metadata guard first.
     */
    suspend fun runIfNeeded(db: SteleDatabase) {
        val alreadyDone = db.steleDatabaseQueries.selectMetadata("uuid_migration_v1")
            .executeAsOneOrNull()
        if (alreadyDone == "done") {
            logger.info("UUID migration already applied — skipping")
            return
        }

        logger.info("Running UUID migration: position-only UUID scheme...")
        migrate(db)
        logger.info("UUID migration complete")
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun migrate(db: SteleDatabase) {
        val restricted = RestrictedDatabaseQueries(db.steleDatabaseQueries)

        // Load all blocks joined with their page file_path in one query.
        val allBlockRows = db.steleDatabaseQueries
            .selectAllBlocksWithPagePath()
            .executeAsList()

        if (allBlockRows.isEmpty()) {
            logger.info("UUID migration: no blocks found — marking as done")
            restricted.upsertMetadata("uuid_migration_v1", "done")
            return
        }

        logger.info("UUID migration: examining ${allBlockRows.size} blocks...")

        // oldUuid -> newUuid for blocks whose UUID needs to change
        val uuidRemap = mutableMapOf<String, String>()

        for (row in allBlockRows) {
            val filePath = row.file_path ?: continue
            val parentUuid = row.parent_uuid
            val position = row.position.toInt()
            val content = row.content
            val existingUuid = row.uuid

            // Reconstruct the old content-derived UUID
            val oldSeed = "$filePath:${parentUuid ?: "root"}:$position:$content"
            val derivedOldUuid = UuidGenerator.generateDeterministic(oldSeed)

            // Only remap blocks whose current UUID matches the old (content-derived) formula.
            // Blocks with an explicit `id` property or already position-derived UUIDs are left
            // untouched.
            if (existingUuid != derivedOldUuid) continue

            // Derive the new position-only UUID
            val newSeed = "$filePath:${parentUuid ?: "root"}:$position"
            val newUuid = UuidGenerator.generateDeterministic(newSeed)

            if (existingUuid != newUuid) {
                uuidRemap[existingUuid] = newUuid
            }
        }

        if (uuidRemap.isEmpty()) {
            logger.info("UUID migration: all UUIDs already correct — no changes needed")
            restricted.upsertMetadata("uuid_migration_v1", "done")
            return
        }

        logger.info("UUID migration: remapping ${uuidRemap.size} block UUIDs...")

        // Pre-check for UNIQUE constraint collisions before entering the transaction.
        // Remove any entries where the new UUID already belongs to a different block.
        val safeRemap = uuidRemap.filterTo(mutableMapOf()) { (oldUuid, newUuid) ->
            val existingCount = db.steleDatabaseQueries.existsBlockByUuid(newUuid).executeAsOne()
            if (existingCount > 0L) {
                logger.warn("UUID migration: skipping $oldUuid → $newUuid (new UUID already exists)")
                false
            } else {
                true
            }
        }

        if (safeRemap.isEmpty()) {
            logger.info("UUID migration: no safe remaps remain after collision check — marking done")
            restricted.upsertMetadata("uuid_migration_v1", "done")
            return
        }

        writeActor.execute {
            try {
                restricted.transaction {
                    for ((oldUuid, newUuid) in safeRemap) {
                        // Update the block's own UUID
                        restricted.updateBlockUuidForMigration(newUuid, oldUuid)
                    }

                    // Update FK columns pointing to remapped UUIDs.
                    for ((oldUuid, newUuid) in safeRemap) {
                        restricted.updateParentUuidForMigration(newUuid, oldUuid)
                        restricted.updateLeftUuidForMigration(newUuid, oldUuid)
                        restricted.updateBlockReferencesFromForMigration(newUuid, oldUuid)
                        restricted.updateBlockReferencesToForMigration(newUuid, oldUuid)
                        restricted.updatePropertiesBlockUuidForMigration(newUuid, oldUuid)
                    }
                }
                restricted.upsertMetadata("uuid_migration_v1", "done")
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("UUID migration transaction failed", e)
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }
}
