package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.PageFileError
import dev.stapler.stelekit.db.PageFileResolver
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock

/** Why an [ActiveTargetWriter] write wrote nothing but may succeed later; carried by [DomainError.MergeError.Retryable]. */
enum class WriteRetryReason(val message: String) {
    /** The write channel is closed or the actor scope was cancelled (graph switched or shut down mid-write). */
    GraphClosed("The graph was closed while copying; try again."),

    /** The page has edits that are not saved yet; copying now would clobber them. */
    PageBeingEdited("This page has unsaved edits; try again once they are saved."),
}

/**
 * Writer for the open graph (ADR-001 rev. 3): updates the DB through [DatabaseWriteActor] and the
 * file through [GraphWriter.savePage], like an editor save. It re-renders the whole page, so the
 * result is compared with [MarkdownTargetWriter] on block data, not bytes. The router holds the
 * `GraphWriteLock`; this class does not.
 *
 * Every inserted block that has a uuid gets `properties["id"]`, because the serializer emits only
 * `block.properties` and an unlabeled reload would derive a different positional uuid (Spike 0.1.3).
 * Unlabeled blocks stay unlabeled (a null uuid means "positional"), as in the markdown writer.
 *
 * Before any save, no inserted uuid may already belong to a block on another page (INSERT OR REPLACE
 * would move it and cascade-delete its children, Spike 0.1.3). Check-then-write is not atomic, which
 * is acceptable because uuid' values are content-derived hashes, so a collision means corrupt input.
 *
 * @param isPageDirty true when the page has unsaved or debounced edits; the write is then refused
 *   as [WriteRetryReason.PageBeingEdited]. Wire the editor in with
 *   `{ id -> id.value in bsm.dirtyPageUuids.value || bsm.hasPendingDiskWrite(id.value) }`; the
 *   default only sees [GraphWriter]'s own debounce window.
 */
class ActiveTargetWriter(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val writeActor: DatabaseWriteActor,
    private val graphWriter: GraphWriter,
    private val fs: FileSystem,
    private val graphPath: String,
    private val encrypted: Boolean = false,
    private val isPageDirty: suspend (PageUuid) -> Boolean = { graphWriter.hasPendingForPage(it) },
) : TargetWriter {

    override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = guarded {
        val row = findPage(page).getOrReturn { return@guarded it.left() } ?: return@guarded null.right()
        val tree = loadTree(row).getOrReturn { return@guarded it.left() }
        if (tree.isEmpty() && !fileExists(row.filePath)) return@guarded null.right()
        MergePage(row.name, row.isJournal, row.journalDate, row.properties, tree.map { it.merge }).right()
    }

    override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> = guarded {
        val row = findPage(page).getOrReturn { return@guarded it.left() }
        val path = pathFor(page, row).getOrReturn { return@guarded it.left() }
        if (row != null && isPageDirty(row.uuid)) return@guarded retryable(WriteRetryReason.PageBeingEdited)

        val tree = row?.let { loadTree(it).getOrReturn { e -> return@guarded e.left() } }.orEmpty()
        val isNew = tree.isEmpty() && !fileExists(path)
        val now = Clock.System.now()
        val pageRow = row ?: ActiveWritePlanner.newPageRow(page, merged, path, now)
        val inserts = mutableListOf<Block>()
        if (!ActiveWritePlanner.plan(tree, merged.blocks, null, ActiveWritePlanner.Ctx(pageRow.uuid, row?.filePath ?: path, now), inserts)) {
            return@guarded refuse(NotRoundTrippable.ExistingContentChanged("merged page lost or altered an existing block")).left()
        }
        val propsChanged = row != null && merged.properties != row.properties
        if (isNew && MergeRenderer.renderNewPage(merged).isEmpty()) return@guarded WriteOutcome.Unchanged.right()
        if (!isNew && inserts.isEmpty() && !propsChanged) return@guarded WriteOutcome.Unchanged.right()

        findCollision(inserts, pageRow.uuid)?.let {
            return@guarded DomainError.DatabaseError.WriteFailed("uuid collision: block $it already exists on another page").left()
        }

        val saved = if (row == null || propsChanged) {
            writeActor.savePage(pageRow.copy(properties = merged.properties, updatedAt = now))
        } else Unit.right()
        saved.onLeft { return@guarded it.stopped().left() }
        writeActor.saveBlocksDiff(inserts, emptyList()).onLeft { e ->
            rollbackPage(row, pageRow, inserts)
            return@guarded e.stopped().left()
        }
        val allBlocks = tree.flatMap(ActiveWritePlanner::flatten) + inserts
        graphWriter.savePage(pageRow.copy(properties = merged.properties), allBlocks, graphPath).onLeft { e ->
            rollbackPage(row, pageRow, inserts)
            return@guarded e.left()
        }
        val hash = hashOf(path).getOrReturn { return@guarded it.left() } ?: ""
        if (isNew) WriteOutcome.Created(path, hash).right() else WriteOutcome.Updated(path, hash, inserts.size).right()
    }

    override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> = guarded {
        val row = findPage(page).getOrReturn { return@guarded it.left() }
        val path = pathFor(page, row).getOrReturn { return@guarded it.left() }
        val current = hashOf(path).getOrReturn { return@guarded it.left() } ?: return@guarded Unit.right()
        if (current != expectedHash) {
            return@guarded DomainError.ConflictError.ConcurrentWrite(path, "File changed since it was created; not deleting: $path").left()
        }
        if (row != null && isPageDirty(row.uuid)) return@guarded retryable(WriteRetryReason.PageBeingEdited)
        val target = row ?: return@guarded deleteFileOnly(path)
        if (!graphWriter.deletePage(target.copy(filePath = path))) {
            return@guarded DomainError.FileSystemError.DeleteFailed(path, "delete failed").left()
        }
        writeActor.deletePage(target.uuid).mapLeft { it.stopped() }
    }

    override suspend fun fileHash(page: PageKey): Either<DomainError, String?> = guarded {
        val row = findPage(page).getOrReturn { return@guarded it.left() }
        val path = pathFor(page, row).getOrReturn { return@guarded it.left() }
        hashOf(path)
    }

    override suspend fun removeBlocks(
        page: PageKey,
        uuids: Set<String>,
        expectedContentHashes: Map<String, String>,
    ): Either<DomainError, RemoveReport> = guarded {
        val row = findPage(page).getOrReturn { return@guarded it.left() }
            ?: return@guarded RemoveReport(emptySet(), emptySet(), uuids).right()
        val path = pathFor(page, row).getOrReturn { return@guarded it.left() }
        if (isPageDirty(row.uuid)) return@guarded retryable(WriteRetryReason.PageBeingEdited)
        val tree = loadTree(row).getOrReturn { return@guarded it.left() }

        val acc = ActiveWritePlanner.Removal()
        ActiveWritePlanner.prune(tree, uuids, expectedContentHashes, acc)
        val report = RemoveReport(acc.removed, acc.edited, uuids - acc.found)
        if (acc.doomed.isEmpty()) return@guarded report.right()

        val remaining = tree.flatMap(ActiveWritePlanner::flatten).filter { it.uuid !in acc.doomed }
        val deleted = tree.flatMap(ActiveWritePlanner::flatten).filter { it.uuid in acc.doomed }
        // Children before parents so no row is deleted under a live child.
        deleteBlocks(deleted).onLeft { return@guarded it.stopped().left() }
        graphWriter.savePage(row.copy(filePath = row.filePath ?: path), remaining, graphPath).onLeft { e ->
            writeActor.saveBlocksDiff(deleted, emptyList())
            return@guarded e.left()
        }
        report.right()
    }

    // region DB access

    private suspend fun findPage(key: PageKey): Either<DomainError, Page?> {
        val date = if (key.isJournal) JournalUtils.parseJournalDate(key.name) else null
        return (if (date != null) pageRepository.getJournalPageByDate(date) else pageRepository.getPageByName(key.name)).first()
    }

    private suspend fun loadTree(row: Page): Either<DomainError, List<ActiveWritePlanner.Node>> =
        blockRepository.getBlocksForPage(row.uuid).first().map(ActiveWritePlanner::buildTree)

    private suspend fun findCollision(inserts: List<Block>, pageUuid: PageUuid): String? {
        if (inserts.isEmpty()) return null
        val found = inserts.map { it.uuid }.chunked(COLLISION_CHUNK).flatMap { blockRepository.getBlocksByUuids(it).getOrNull().orEmpty() }
        return found.firstOrNull { it.pageUuid != pageUuid }?.uuid?.value
    }

    /** One actor round-trip; [blocks] are pre-order so deleting in reverse removes children before parents. */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun deleteBlocks(blocks: List<Block>): Either<DomainError, Unit> = writeActor.execute {
        blocks.asReversed().forEach { blockRepository.deleteBlock(it.uuid) }
        Unit.right()
    }

    /** Undoes a partly applied write: inserted blocks go, a page row created by this write goes, changed properties are restored. */
    private suspend fun rollbackPage(before: Page?, after: Page, inserts: List<Block>) {
        deleteBlocks(inserts)
        if (before == null) writeActor.deletePage(after.uuid) else writeActor.savePage(before)
    }

    // endregion

    // region paths and files

    private suspend fun pathFor(key: PageKey, row: Page?): Either<DomainError, String> {
        val stored = row?.filePath?.takeIf { it.isNotBlank() }
        val path = stored ?: resolveNew(key).getOrReturn { return it.left() }
        if (!PageFileResolver.isWithin(graphPath, path)) {
            return refuse(WriteRefusedReason.PathOutsideGraph(path)).left()
        }
        return path.right()
    }

    private suspend fun resolveNew(key: PageKey): Either<DomainError, String> = withContext(PlatformDispatcher.IO) {
        val date = if (key.isJournal) JournalUtils.parseJournalDate(key.name) else null
        val resolved = if (date != null) {
            val root = graphPath.trimEnd('/')
            val stems = fs.listFiles("$root/journals").map { it.substringBefore('.') }
            PageFileResolver.resolveJournal(date, graphPath, stems, fs.readFile("$root/logseq/config.edn"), encrypted)
        } else {
            PageFileResolver.resolve(key.name, key.isJournal, graphPath, encrypted)
        }
        resolved.fold({ refuse(it.toRefusal()).left() }, { it.right() })
    }

    private suspend fun fileExists(path: String?): Boolean =
        path != null && withContext(PlatformDispatcher.IO) { fs.fileExists(path) }

    private suspend fun hashOf(path: String): Either<DomainError, String?> = withContext(PlatformDispatcher.IO) {
        if (!fs.fileExists(path)) return@withContext null.right()
        val bytes = fs.readFileBytes(path)
            ?: return@withContext DomainError.FileSystemError.ReadFailed(path, "unreadable").left()
        bytes.toByteString().sha256().hex().right()
    }

    private suspend fun deleteFileOnly(path: String): Either<DomainError, Unit> = withContext(PlatformDispatcher.IO) {
        if (fs.deleteFile(path)) Unit.right() else DomainError.FileSystemError.DeleteFailed(path, "delete failed").left()
    }


    // endregion


    /** Maps a closed write channel or a cancelled actor scope (our own coroutine still active) to a retryable error. */
    private suspend fun <T> guarded(block: suspend () -> Either<DomainError, T>): Either<DomainError, T> = try {
        // A stopped actor means the graph is being switched away, so even reads may hit a closing driver:
        // report that as retryable (the router re-decides) instead of a permanent read failure.
        if (writeActor.isStopped) retryable(WriteRetryReason.GraphClosed) else block().retryIfStopped()
    } catch (e: ClosedSendChannelException) {
        retryable(WriteRetryReason.GraphClosed)
    } catch (e: CancellationException) {
        if (currentCoroutineContext().isActive) retryable(WriteRetryReason.GraphClosed) else throw e
    } catch (e: Exception) {
        DomainError.DatabaseError.WriteFailed(e.message ?: e::class.simpleName ?: "unknown").left()
    }

    private fun <T> Either<DomainError, T>.retryIfStopped(): Either<DomainError, T> =
        if (isLeft() && leftOrNull() !is DomainError.MergeError && writeActor.isStopped) retryable(WriteRetryReason.GraphClosed) else this

    /** A request queued when the actor's scope was cancelled fails with this marker; surface it as retryable. */
    private fun DomainError.stopped(): DomainError =
        if (this is DomainError.DatabaseError.WriteFailed && message == DatabaseWriteActor.STOPPED_MESSAGE) {
            DomainError.MergeError.Retryable(WriteRetryReason.GraphClosed.message)
        } else this

    private fun <T> retryable(reason: WriteRetryReason): Either<DomainError, T> =
        DomainError.MergeError.Retryable(reason.message).left()

    companion object {
        private const val COLLISION_CHUNK = 500

        /** Captures the open graph's repositories; [RepositorySet.writeActor] must be present. */
        fun forGraph(
            repos: RepositorySet,
            graphWriter: GraphWriter,
            fs: FileSystem,
            graphPath: String,
            encrypted: Boolean = false,
            isPageDirty: suspend (PageUuid) -> Boolean = { graphWriter.hasPendingForPage(it) },
        ): ActiveTargetWriter = ActiveTargetWriter(
            pageRepository = repos.pageRepository,
            blockRepository = repos.blockRepository,
            writeActor = requireNotNull(repos.writeActor) { "RepositorySet has no DatabaseWriteActor" },
            graphWriter = graphWriter,
            fs = fs,
            graphPath = graphPath,
            encrypted = encrypted,
            isPageDirty = isPageDirty,
        )
    }
}

private inline fun <L, R> Either<L, R>.getOrReturn(onLeft: (L) -> Nothing): R = when (this) {
    is Either.Left -> onLeft(value)
    is Either.Right -> value
}

private fun PageFileError.toRefusal(): WriteRefusedReason = when (this) {
    is PageFileError.InvalidPageName -> WriteRefusedReason.InvalidPageName(name)
    is PageFileError.PathOutsideGraph -> WriteRefusedReason.PathOutsideGraph(path)
    is PageFileError.JournalFormatUnsupported -> WriteRefusedReason.JournalFormatUnsupported(format)
}

private fun refuse(reason: NotRoundTrippable) = refuse(WriteRefusedReason.NotRoundTrippable(reason))

private fun refuse(reason: WriteRefusedReason) = DomainError.MergeError.WriteRefused(reason)
