package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.PageFileResolver
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.util.FileUtils
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SourceGraphReader] over a [FileSystem]: lists `pages/` and `journals/` file names, stats only
 * the requested window, and reads one file at a time. Only read methods of [FileSystem] are used.
 *
 * Identity matches the push path: files are parsed with [MergeConverters.parseMarkdown] using the
 * path string `GraphLoader` would pass (`<graphPath>/<folder>/<file>`), so explicit `id::` is
 * verbatim and unlabeled blocks stay null, exactly as [MergeConverters.toMergePage] yields for a DB page.
 *
 * A listing that starts from the beginning (`afterName == null`) lists the folder names (strings
 * only, no stat/content) and sorts them once; continuation calls binary-search that cached
 * projection, so paging N entries costs one directory listing. The cursor is the name only, so
 * callers must resume from a name strictly below the last one they loaded to see a page and a
 * journal sharing a name ([PullPageSource] does).
 *
 * @param maxFileBytes files larger than this return [ReadError.TooLarge]
 */
class MarkdownSourceGraphReader(
    private val fileSystem: FileSystem,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) : SourceGraphReader {

    override suspend fun listEntries(
        graph: GraphInfo,
        afterName: String?,
        limit: Int,
    ): Either<ReadError, List<SourceEntry>> {
        gate(graph)?.let { return it.left() }
        val root = graph.path.trimEnd('/')
        if (!fileSystem.directoryExists(root)) return ReadError.FolderMissing.left()
        val sorted = if (afterName == null || session?.root != root) {
            val listed = try {
                FOLDERS.flatMap { (folder, kind) ->
                    if (!fileSystem.directoryExists("$root/$folder")) emptyList()
                    else fileSystem.listFiles("$root/$folder").mapNotNull { candidate(folder, kind, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ReadError.Unreadable(e.message ?: "listing failed").left()
            }
            listed.sortedWith(compareBy({ it.name }, { it.kind })).also { session = Session(root, it) }
        } else {
            session!!.sorted
        }
        val start = if (afterName == null) 0 else firstIndexAfter(sorted, afterName)
        val window = sorted.subList(start, minOf(sorted.size, start + limit.coerceIn(1, SourceGraphReader.MAX_PAGE_SIZE)))
        return window.map { c ->
            val path = "$root/${c.fileName}"
            SourceEntry(c.name, c.kind, c.fileName, fileSystem.getFileSize(path) ?: 0L, fileSystem.getLastModifiedTime(path))
        }.right()
    }

    override suspend fun readPage(graph: GraphInfo, entry: SourceEntry): Either<ReadError, StagedPage> {
        gate(graph)?.let { return it.left() }
        val root = graph.path.trimEnd('/')
        val path = "$root/${entry.fileName}"
        val folder = entry.fileName.substringBefore('/')
        if (folder !in FOLDERS.map { it.first } || !PageFileResolver.isWithin("$root/$folder", path)) {
            return ReadError.Unreadable("Path is outside the graph").left()
        }
        if (entry.sizeBytes > maxFileBytes) return ReadError.TooLarge(entry.sizeBytes, maxFileBytes).left()
        return try {
            val bytes = fileSystem.readFileBytes(path) ?: return ReadError.Unreadable("File not found").left()
            if (bytes.size > maxFileBytes) return ReadError.TooLarge(bytes.size.toLong(), maxFileBytes).left()
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            if (text.contains('\u0000')) return ReadError.Unreadable("Not a text file").left()
            val date = if (entry.isJournal) JournalUtils.parseJournalDate(entry.name) else null
            val parsed = MergeConverters.parseMarkdown(text, path, entry.name, entry.isJournal, date)
            StagedPage.from(parsed.mergePage).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReadError.Unreadable(e.message ?: "parse failed").left()
        }
    }

    private fun gate(graph: GraphInfo): ReadError? = when {
        graph.isParanoidMode -> ReadError.Encrypted
        !fileSystem.hasStoragePermission() -> ReadError.NoGrant
        else -> null
    }

    private class Session(val root: String, val sorted: List<Candidate>)

    // Single slot: one pull source is indexed at a time; a new traversal always re-lists.
    @kotlin.concurrent.Volatile
    private var session: Session? = null

    private fun firstIndexAfter(sorted: List<Candidate>, name: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].name > name) hi = mid else lo = mid + 1
        }
        return lo
    }

    private class Candidate(val name: String, val kind: SourceKind, val fileName: String)

    private fun candidate(folder: String, folderKind: SourceKind, file: String): Candidate? {
        if (!file.endsWith(".md") || file.startsWith(".")) return null
        val name = FileUtils.decodeFileName(file.removeSuffix(".md"))
        if (name.isBlank()) return null
        val isJournal = folderKind == SourceKind.Journal && JournalUtils.parseJournalDate(name) != null
        return Candidate(name, if (isJournal) SourceKind.Journal else SourceKind.Page, "$folder/$file")
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024
        private val FOLDERS = listOf("pages" to SourceKind.Page, "journals" to SourceKind.Journal)
    }
}
