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
 * Each [listEntries] call re-lists the folder names (strings only, no stat/content) and sorts them.
 * The cursor is the name, so a page and a journal sharing one name (not producible by the app)
 * could skip one entry.
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
        val candidates = try {
            FOLDERS.flatMap { (folder, kind) ->
                if (!fileSystem.directoryExists("$root/$folder")) emptyList()
                else fileSystem.listFiles("$root/$folder").mapNotNull { candidate(folder, kind, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ReadError.Unreadable(e.message ?: "listing failed").left()
        }
        val window = candidates
            .sortedWith(compareBy({ it.name }, { it.kind }))
            .filter { afterName == null || it.name > afterName }
            .take(limit.coerceIn(1, SourceGraphReader.MAX_PAGE_SIZE))
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
