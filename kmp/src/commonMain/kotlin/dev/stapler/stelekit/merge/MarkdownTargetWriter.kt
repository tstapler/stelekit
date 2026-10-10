package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.PageFileError
import dev.stapler.stelekit.db.PageFileResolver
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString

/**
 * Off-graph writer (ADR-001): splices only the new blocks into the target page's original bytes,
 * then replaces the file via temp + rename. A brand-new page is the only whole render.
 *
 * Where [FileSystem.supportsAtomicReplace] the temp file is moved over the page in one step, so a
 * crash leaves the old or the new file complete. Otherwise `renameFile` (which never overwrites;
 * JVM returns true and leaves the source behind) forces delete-then-rename with restore-on-failure;
 * a crash in that gap leaves the complete new text in the `.tmp` file.
 *
 * @param canonicalize symlink-resolving path function; the identity default only checks lexically.
 *   Pass `File(p).canonicalPath` on JVM so a symlinked page file or folder cannot escape the root.
 */
class MarkdownTargetWriter(
    private val fs: FileSystem,
    private val target: OffGraphTarget,
    private val capabilities: TargetWriterCapabilities,
    private val canonicalize: (String) -> String = { it },
) : TargetWriter {

    override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = io {
        val path = resolveWritable(page).getOrReturn { return@io it.left() }
        val original = readText(path).getOrReturn { return@io it.left() } ?: return@io null.right()
        RoundTripGuard.probe(original, path, page.isJournal).onLeft { return@io refuse(it).left() }
        parse(original, path, page, JournalUtils.parseJournalDate(page.name)).map { it.mergePage }
    }

    override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> = io {
        val path = resolveWritable(page).getOrReturn { return@io it.left() }
        val original = readText(path).getOrReturn { return@io it.left() }
        if (original == null) return@io createPage(path, merged)
        val existing = if (original.isEmpty()) MergePage(page.name) else {
            parse(original, path, page, merged.journalDate).getOrReturn { return@io it.left() }.mergePage
        }
        val insertions = mutableListOf<BlockInsertion>()
        if (!BlockEditing.planInsertions(existing.blocks, merged.blocks, emptyList(), insertions)) {
            return@io refuse(NotRoundTrippable.ExistingContentChanged("merged page lost or altered an existing block")).left()
        }
        val spliced = RoundTripGuard.splice(original, SpliceRequest(insertions, merged.properties), path, page.isJournal)
            .getOrReturn { return@io refuse(it).left() }
        if (spliced.text == original) return@io WriteOutcome.Unchanged.right()
        val bytes = spliced.text.encodeToByteArray()
        replaceFile(path, bytes, original.encodeToByteArray()).map {
            WriteOutcome.Updated(path, sha256(bytes), spliced.insertedBlocks)
        }
    }

    override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> = io {
        val path = resolveWritable(page).getOrReturn { return@io it.left() }
        val bytes = readBytes(path).getOrReturn { return@io it.left() } ?: return@io Unit.right()
        if (sha256(bytes) != expectedHash) {
            return@io DomainError.ConflictError.ConcurrentWrite(path, "File changed since it was created; not deleting: $path").left()
        }
        if (fs.deleteFile(path)) Unit.right() else DomainError.FileSystemError.DeleteFailed(path, "delete failed").left()
    }

    override suspend fun fileHash(page: PageKey): Either<DomainError, String?> = io {
        val path = resolveSafe(page).getOrReturn { return@io it.left() }
        readBytes(path).map { it?.let(::sha256) }
    }

    override suspend fun removeBlocks(
        page: PageKey,
        uuids: Set<String>,
        expectedContentHashes: Map<String, String>,
    ): Either<DomainError, RemoveReport> = io {
        val path = resolveWritable(page).getOrReturn { return@io it.left() }
        val original = readText(path).getOrReturn { return@io it.left() }
            ?: return@io RemoveReport(emptySet(), emptySet(), uuids).right()
        val before = parse(original, path, page, JournalUtils.parseJournalDate(page.name)).getOrReturn { return@io it.left() }.mergePage
        val acc = BlockEditing.Removal()
        val expected = BlockEditing.prune(before.blocks, uuids, expectedContentHashes, acc)
        val report = RemoveReport(acc.removed, acc.edited, uuids - acc.found)
        if (acc.top.isEmpty()) return@io report.right()

        val text = BlockEditing.cutBlocks(original, acc.top).getOrReturn { return@io refuse(it).left() }
        val after = parse(text, path, page, JournalUtils.parseJournalDate(page.name)).getOrReturn { return@io it.left() }.mergePage
        if (after.blocks != expected) {
            return@io refuse(NotRoundTrippable.ExistingContentChanged("removal would change blocks other than the requested ones")).left()
        }
        replaceFile(path, text.encodeToByteArray(), original.encodeToByteArray()).map { report }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(PlatformDispatcher.IO) { block() }

    // region paths

    private fun resolveSafe(page: PageKey): Either<DomainError, String> {
        val resolved = if (page.isJournal) resolveJournalPath(page) else {
            PageFileResolver.resolve(page.name, false, target.path, target.encrypted)
        }
        val path = resolved.getOrReturn { return@resolveSafe refuse(it.toRefusal()).left() }
        if (!PageFileResolver.isWithin(canonicalize(target.path), canonicalize(path))) {
            return refuse(WriteRefusedReason.PathOutsideGraph(path)).left()
        }
        return path.right()
    }

    private fun resolveWritable(page: PageKey): Either<DomainError, String> {
        capabilities.canWriteOffGraph(target).onLeft { return refuse(WriteRefusedReason.Unwritable(it)).left() }
        return resolveSafe(page)
    }

    /** Reuses an existing `2026-10-07.md` style stem for the same date instead of creating a second file. */
    private fun resolveJournalPath(page: PageKey): Either<PageFileError, String> {
        val date = JournalUtils.parseJournalDate(page.name)
            ?: return PageFileResolver.resolve(page.name, true, target.path, target.encrypted)
        val root = target.path.trimEnd('/')
        val stems = fs.listFiles("$root/journals").map { it.substringBefore('.') }
        return PageFileResolver.resolveJournal(date, target.path, stems, fs.readFile("$root/logseq/config.edn"), target.encrypted)
    }

    private fun PageFileError.toRefusal(): WriteRefusedReason = when (this) {
        is PageFileError.InvalidPageName -> WriteRefusedReason.InvalidPageName(name)
        is PageFileError.PathOutsideGraph -> WriteRefusedReason.PathOutsideGraph(path)
        is PageFileError.JournalFormatUnsupported -> WriteRefusedReason.JournalFormatUnsupported(format)
    }

    // endregion

    // region reading and parsing

    private fun readBytes(path: String): Either<DomainError, ByteArray?> {
        if (!fs.fileExists(path)) return null.right()
        return try {
            fs.readFileBytes(path)?.right() ?: DomainError.FileSystemError.ReadFailed(path, "unreadable").left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.FileSystemError.ReadFailed(path, e.message ?: "read failed").left()
        }
    }

    /** Null when absent. Refuses non-UTF-8 content: decoding would not round-trip the bytes. */
    private fun readText(path: String): Either<DomainError, String?> {
        val bytes = readBytes(path).getOrReturn { return@readText it.left() } ?: return null.right()
        val text = bytes.decodeToString()
        if (!text.encodeToByteArray().contentEquals(bytes)) {
            return refuse(NotRoundTrippable.ParseFailed("file is not valid UTF-8")).left()
        }
        return text.right()
    }

    private fun parse(text: String, path: String, page: PageKey, date: kotlinx.datetime.LocalDate?): Either<DomainError, ParsedMarkdown> = try {
        MergeConverters.parseMarkdown(text, path, page.name, page.isJournal, date).right()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        refuse(NotRoundTrippable.ParseFailed("${e::class.simpleName}: ${e.message?.take(120)}")).left()
    }

    // endregion

    // region writing

    private fun createPage(path: String, merged: MergePage): Either<DomainError, WriteOutcome> {
        val text = MergeRenderer.renderNewPage(merged)
        if (text.isEmpty()) return WriteOutcome.Unchanged.right()
        val dir = path.substringBeforeLast('/')
        if (!fs.directoryExists(dir) && !fs.createDirectory(dir)) {
            return DomainError.FileSystemError.WriteFailed(dir, "cannot create directory").left()
        }
        val bytes = text.encodeToByteArray()
        return replaceFile(path, bytes, null).map { WriteOutcome.Created(path, sha256(bytes)) }
    }

    /** [original] is null when [path] does not exist yet. */
    private fun replaceFile(path: String, bytes: ByteArray, original: ByteArray?): Either<DomainError, Unit> {
        val tmp = path + TMP_SUFFIX
        var deletedOriginal = false
        try {
            if (!fs.writeFileBytes(tmp, bytes)) return failAndClean(tmp, path, "temp write failed")
            if (fs.supportsAtomicReplace(path)) {
                if (!fs.replaceFileAtomically(tmp, path)) return failAndClean(tmp, path, "atomic replace failed")
                return Unit.right()
            }
            if (original != null) {
                deletedOriginal = fs.deleteFile(path)
                if (!deletedOriginal) return failAndClean(tmp, path, "could not replace existing file")
            }
            if (!fs.renameFile(tmp, path)) {
                if (original != null) fs.writeFileBytes(path, original)
                return failAndClean(tmp, path, "rename failed")
            }
            return Unit.right()
        } catch (e: Throwable) {
            if (deletedOriginal && original != null && !fs.fileExists(path)) runCatching { fs.writeFileBytes(path, original) }
            runCatching { fs.deleteFile(tmp) }
            if (e is CancellationException) throw e
            return DomainError.FileSystemError.WriteFailed(path, e.message ?: "write failed").left()
        }
    }

    private fun failAndClean(tmp: String, path: String, why: String): Either<DomainError, Unit> {
        runCatching { fs.deleteFile(tmp) }
        return DomainError.FileSystemError.WriteFailed(path, why).left()
    }

    // endregion


    private fun refuse(reason: NotRoundTrippable) = refuse(WriteRefusedReason.NotRoundTrippable(reason))

    private fun refuse(reason: WriteRefusedReason) = DomainError.MergeError.WriteRefused(reason)

    private companion object {
        const val TMP_SUFFIX = ".stele-merge.tmp"
    }
}

private fun sha256(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

private inline fun <L, R> Either<L, R>.getOrReturn(onLeft: (L) -> Nothing): R = when (this) {
    is Either.Left -> onLeft(value)
    is Either.Right -> value
}

/** Pure block alignment and line-cutting used by [MarkdownTargetWriter]. */
private object BlockEditing {
    private val BULLET = Regex("^([ \\t]*)-(?: .*)?$")

    private fun idLine(uuid: String) = Regex("^\\s*id::\\s*${Regex.escape(uuid)}\\s*$", RegexOption.IGNORE_CASE)

    private fun sameBlock(e: MergeBlock, m: MergeBlock) = e.uuid == m.uuid && e.content == m.content && e.properties == m.properties

    /**
     * Aligns merged siblings with existing ones in order; unmatched merged blocks become last-child
     * insertions under the matched parent. False when an existing block has no counterpart.
     */
    fun planInsertions(existing: List<MergeBlock>, merged: List<MergeBlock>, parent: List<Int>, out: MutableList<BlockInsertion>): Boolean {
        var j = 0
        for (m in merged) {
            val e = existing.getOrNull(j)
            if (e != null && sameBlock(e, m)) {
                if (!planInsertions(e.children, m.children, parent + j, out)) return false
                j++
            } else {
                out += BlockInsertion(parent, m)
            }
        }
        return j == existing.size
    }

    class Removal {
        val top = mutableListOf<String>()
        val removed = mutableSetOf<String>()
        val edited = mutableSetOf<String>()
        val found = mutableSetOf<String>()
    }

    /** Tree without the blocks to remove; a block edited since the copy stays but its children are still considered. */
    fun prune(blocks: List<MergeBlock>, uuids: Set<String>, hashes: Map<String, String>, acc: Removal): List<MergeBlock> =
        blocks.mapNotNull { b ->
            val u = b.uuid
            if (u != null && u in uuids) {
                acc.found += u
                if (hashes[u] == BlockContentHash.of(b)) {
                    acc.top += u
                    markRemoved(b, uuids, acc)
                    return@mapNotNull null
                }
                acc.edited += u
            }
            b.copy(children = prune(b.children, uuids, hashes, acc))
        }

    private fun markRemoved(b: MergeBlock, uuids: Set<String>, acc: Removal) {
        b.uuid?.takeIf { it in uuids }?.let { acc.removed += it; acc.found += it }
        b.children.forEach { markRemoved(it, uuids, acc) }
    }

    /** Drops each block's lines (bullet through last descendant); callers re-parse to verify the cut. */
    fun cutBlocks(original: String, uuids: List<String>): Either<NotRoundTrippable, String> {
        val lines = splitKeepingTerminators(original)
        val drop = BooleanArray(lines.size)
        for (u in uuids) {
            val idLine = lines.indices.filter { idLine(u).matches(lines[it].trimEnd('\r', '\n')) }
            if (idLine.size != 1) return NotRoundTrippable.ExistingContentChanged("cannot locate block $u").left()
            val bullet = (idLine[0] - 1 downTo 0).firstOrNull { i ->
                BULLET.matchEntire(lines[i].trimEnd('\r', '\n'))?.let { width(it.groupValues[1]) < width(leading(lines[idLine[0]])) } == true
            } ?: return NotRoundTrippable.ExistingContentChanged("no bullet for block $u").left()
            val w = width(leading(lines[bullet]))
            var end = (bullet + 1 until lines.size).firstOrNull { i ->
                lines[i].isNotBlank() && width(leading(lines[i])) <= w
            } ?: lines.size
            while (end > bullet + 1 && lines[end - 1].isBlank()) end--
            for (i in bullet until end) drop[i] = true
        }
        return lines.filterIndexed { i, _ -> !drop[i] }.joinToString("").right()
    }

    private fun splitKeepingTerminators(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) { out += text.substring(start); break }
            out += text.substring(start, nl + 1)
            start = nl + 1
        }
        return out
    }

    private fun leading(line: String) = line.takeWhile { it == ' ' || it == '\t' }

    private fun width(indent: String) = indent.sumOf { if (it == '\t') 4 else 1 }
}
