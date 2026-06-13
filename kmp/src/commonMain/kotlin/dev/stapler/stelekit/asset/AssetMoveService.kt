package dev.stapler.stelekit.asset

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.AssetRepository
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates moving an asset file from one subfolder to another with crash recovery.
 *
 * Safety model:
 *  1. Insert a WAL row BEFORE the file operation (via [RestrictedDatabaseQueries]).
 *  2. Rename the file on disk.
 *  3. Rewrite markdown references (best-effort).
 *  4. Update the DB row for the asset.
 *  5. Delete the WAL row.
 *
 * On app restart, [replayPendingMoves] re-drives any WAL rows left by a crash.
 *
 * WAL writes are performed via [RestrictedDatabaseQueries] with [DirectSqlWrite] opt-in
 * at the method level (same pattern as [dev.stapler.stelekit.db.MigrationRunner]).
 */
@OptIn(DirectSqlWrite::class)
class AssetMoveService(
    private val assetRepository: AssetRepository,
    private val restrictedQueries: RestrictedDatabaseQueries?,
) {
    private val logger = Logger("AssetMoveService")
    private val mutexMap = mutableMapOf<String, Mutex>()
    private val mapMutex = Mutex()

    private suspend fun mutexFor(uuid: String): Mutex = mapMutex.withLock {
        mutexMap.getOrPut(uuid) { Mutex() }
    }

    suspend fun moveAsset(
        asset: AssetEntry,
        newSubfolder: String,
        graphRoot: String,
        fileSystem: FileSystem,
        graphWriter: GraphWriter,
        writeActor: DatabaseWriteActor?,
    ): Either<DomainError, Unit> {
        val mutex = mutexFor(asset.uuid.value)
        return mutex.withLock {
            moveAssetInternal(asset, newSubfolder, graphRoot, fileSystem, graphWriter, writeActor)
        }
    }

    private suspend fun moveAssetInternal(
        asset: AssetEntry,
        newSubfolder: String,
        graphRoot: String,
        fileSystem: FileSystem,
        graphWriter: GraphWriter,
        writeActor: DatabaseWriteActor?,
    ): Either<DomainError, Unit> {
        val filename = asset.filePath.substringAfterLast('/')
        val newFilePath = AssetStoragePathResolver.resolvePath(graphRoot, newSubfolder, filename)
        val newRelativePath = AssetStoragePathResolver.relativeMarkdownPath(newSubfolder, filename)

        if (asset.filePath == newFilePath) return Unit.right()

        val targetDir = "$graphRoot/assets/$newSubfolder"
        try {
            fileSystem.createDirectory(targetDir)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { /* directory may already exist */ }

        // 1. Insert WAL entry before any file operation
        val walId = insertWalEntry(
            assetUuid = asset.uuid.value,
            oldFilePath = asset.filePath,
            newFilePath = newFilePath,
            oldRelativePath = asset.relativePath,
            newRelativePath = newRelativePath,
        )

        // 2. Rename file
        val renamed = try {
            fileSystem.renameFile(asset.filePath, newFilePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DomainError.DatabaseError.WriteFailed("File rename failed: ${e.message}").left()
        }
        if (!renamed) {
            return DomainError.DatabaseError.WriteFailed("Failed to rename ${asset.filePath} to $newFilePath").left()
        }

        // 3. Rewrite markdown references (best-effort)
        try {
            graphWriter.rewriteAssetReference(asset.relativePath, newRelativePath, graphRoot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Reference rewrite failed (non-fatal): ${e.message}")
        }

        // 4. Update DB
        val dbResult = if (writeActor != null) {
            writeActor.execute { assetRepository.updateFilePath(asset.uuid, newFilePath, newRelativePath) }
        } else {
            assetRepository.updateFilePath(asset.uuid, newFilePath, newRelativePath)
        }

        // 5. Delete WAL entry
        walId?.let { deleteWalEntry(it) }

        return dbResult
    }

    /**
     * Replay any pending moves from the WAL table on graph open.
     * Idempotent — safe to call multiple times.
     */
    suspend fun replayPendingMoves(
        graphRoot: String,
        fileSystem: FileSystem,
        graphWriter: GraphWriter,
        writeActor: DatabaseWriteActor?,
    ) {
        val queries = restrictedQueries ?: return
        val pending = try {
            queries.rawQueries.selectAllPendingMoves().executeAsList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to read pending moves: ${e.message}")
            return
        }

        for (row in pending) {
            try {
                replayMove(
                    id = row.id,
                    assetUuid = row.asset_uuid,
                    oldFilePath = row.old_file_path,
                    newFilePath = row.new_file_path,
                    oldRelativePath = row.old_relative_path,
                    newRelativePath = row.new_relative_path,
                    graphRoot = graphRoot,
                    fileSystem = fileSystem,
                    graphWriter = graphWriter,
                    writeActor = writeActor,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("WAL replay failed for move id=${row.id}: ${e.message}")
            }
        }
    }

    private suspend fun replayMove(
        id: Long,
        assetUuid: String,
        oldFilePath: String,
        newFilePath: String,
        oldRelativePath: String,
        newRelativePath: String,
        graphRoot: String,
        fileSystem: FileSystem,
        graphWriter: GraphWriter,
        writeActor: DatabaseWriteActor?,
    ) {
        val oldExists = try { fileSystem.fileExists(oldFilePath) } catch (_: Exception) { false }
        val newExists = try { fileSystem.fileExists(newFilePath) } catch (_: Exception) { false }

        when {
            oldExists && !newExists -> {
                // Incomplete move — complete it
                try { fileSystem.renameFile(oldFilePath, newFilePath) } catch (_: Exception) {}
                try { graphWriter.rewriteAssetReference(oldRelativePath, newRelativePath, graphRoot) } catch (_: Exception) {}
                val uuid = AssetUuid(assetUuid)
                if (writeActor != null) {
                    writeActor.execute { assetRepository.updateFilePath(uuid, newFilePath, newRelativePath) }
                } else {
                    assetRepository.updateFilePath(uuid, newFilePath, newRelativePath)
                }
                deleteWalEntry(id)
            }
            !oldExists && newExists -> {
                // Move already completed; just update DB and clear WAL
                val uuid = AssetUuid(assetUuid)
                if (writeActor != null) {
                    writeActor.execute { assetRepository.updateFilePath(uuid, newFilePath, newRelativePath) }
                } else {
                    assetRepository.updateFilePath(uuid, newFilePath, newRelativePath)
                }
                deleteWalEntry(id)
            }
            oldExists && newExists -> {
                // Ambiguous — log warning, do NOT delete WAL row
                logger.warn("WAL replay: both old='$oldFilePath' and new='$newFilePath' exist; skipping row id=$id")
            }
            else -> {
                // Both absent — file gone; clean up WAL row
                logger.warn("WAL replay: neither old nor new file exists for move id=$id; cleaning WAL row")
                deleteWalEntry(id)
            }
        }
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun insertWalEntry(
        assetUuid: String,
        oldFilePath: String,
        newFilePath: String,
        oldRelativePath: String,
        newRelativePath: String,
    ): Long? {
        val queries = restrictedQueries ?: return null
        return try {
            queries.insertPendingMove(
                asset_uuid = assetUuid,
                old_file_path = oldFilePath,
                new_file_path = newFilePath,
                old_relative_path = oldRelativePath,
                new_relative_path = newRelativePath,
                created_at_ms = Clock.System.now().toEpochMilliseconds(),
            )
            queries.rawQueries.selectAllPendingMoves().executeAsList().lastOrNull()?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to insert WAL entry: ${e.message}")
            null
        }
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun deleteWalEntry(id: Long) {
        val queries = restrictedQueries ?: return
        try {
            queries.deletePendingMove(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to delete WAL entry id=$id: ${e.message}")
        }
    }
}
