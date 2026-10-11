package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.util.FileUtils
import kotlinx.datetime.LocalDate

/** Why a page or journal could not be mapped to a file path inside the graph. */
sealed interface PageFileError {
    val message: String

    /** The page name cannot be a file name (NUL byte). */
    data class InvalidPageName(val name: String) : PageFileError {
        override val message: String = "Invalid page name for a file path"
    }

    /** A resolved or supplied path is not inside the allowed folder (lexical or canonical). */
    data class PathOutsideGraph(val path: String) : PageFileError {
        override val message: String = "Path is outside the graph: $path"
    }

    /** Target `config.edn` sets a non-default `:journal/file-name-format`; we cannot name journals. */
    data class JournalFormatUnsupported(val format: String) : PageFileError {
        override val message: String = "Unsupported journal file-name format: $format"
    }
}

/**
 * Single place that maps a page (or journal date) to its file path under a graph root.
 * Path only: no write-capability checks (those belong to the target-writer capabilities).
 *
 * ## Page file names (rules 1 and 5)
 * - Folder: `journals/` when `isJournal`, else `pages/`.
 * - File name: [FileUtils.sanitizeFileName] of the page name, unchanged, so namespaces
 *   (`a/b` becomes `a%2Fb.md`) match what `GraphWriter.getPageFilePath` always produced.
 * - Extension: `.md`, or `.md.stek` when the graph is encrypted.
 *
 * ## Journal file names (DECIDED rules 2-4)
 * - Creation stem is [JournalUtils.formatDateForJournal], `YYYY_MM_DD`.
 * - Discovery: [resolveJournal] reuses any existing stem matching `YYYY[-_]MM[-_]DD` that
 *   parses to the same date, so `2026-10-07.md` is appended to, never duplicated.
 * - If `config.edn` declares a non-default `:journal/file-name-format`, [resolveJournal]
 *   returns [PageFileError.JournalFormatUnsupported] (conservative stop; nothing else in the
 *   code base reads that setting).
 *
 * ## Verified mapping to loader and capture code
 * - `JournalService.ensureTodayJournal` creates only a DB page named `YYYY_MM_DD`
 *   (`date.toString().replace('-', '_')`, equal to [JournalUtils.formatDateForJournal]); it writes no file.
 * - `GraphLoader` derives the page name as `FileUtils.decodeFileName(<file stem>)` and treats the
 *   page as a journal when the path contains `/journals/` and the name parses via
 *   [JournalUtils.parseJournalDate] (both `-` and `_`); identity is then looked up by DATE,
 *   so `2026-10-07.md` and `2026_10_07.md` map to the same journal page.
 * - `GraphWriter.getPageFilePath` has no journal-date logic: it sanitizes `page.name`.
 *   `GraphLoader.createSectionJournalPage` writes `journals/<sectionId>/<LocalDate>.md` (hyphens);
 *   section sub-folders are not modelled here.
 *
 * ## `pagePath` string `GraphLoader` passes to the parser (block-uuid seed)
 * `MarkdownPageParser.generateUuid` hashes `pagePath` for blocks without `id::`. `GraphLoader`
 * passes the full file path string exactly as enumerated from the directory listing
 * (`<graphPath>/pages/<name>.md` or `<graphPath>/journals/<stem>.md`, with the `.md.stek`
 * extension when encrypted, `filePath`/`filePathStr` in `parsePageWithoutSaving` and the
 * parse-and-save path). Exception: the later full-load of a stub page passes `page.uuid.value`
 * (`GraphLoader` `pagePath = page.uuid.value`). Off-graph readers must pass the same string.
 *
 * ## Containment
 * [isWithin] is the reusable lexical check; callers that touch the filesystem must also compare
 * canonical (symlink-resolved) forms with it. Wiring into the target writer, staging directory,
 * asset copier and source reader is the responsibility of those classes.
 */
object PageFileResolver {
    private const val DEFAULT_JOURNAL_FORMAT = "yyyy_MM_dd"

    /**
     * Unvalidated mapping, byte-for-byte what `GraphWriter.getPageFilePath` returned before
     * extraction. Safe only because [FileUtils.sanitizeFileName] encodes separators; prefer [resolve].
     */
    fun pagePath(name: String, isJournal: Boolean, graphPath: String, encrypted: Boolean): String {
        val base = if (graphPath.endsWith("/")) graphPath else "$graphPath/"
        val folder = if (isJournal) "journals" else "pages"
        val extension = if (encrypted) ".md.stek" else ".md"
        return "$base$folder/${FileUtils.sanitizeFileName(name)}$extension"
    }

    /** Validated variant of [pagePath]: refuses NUL names and anything that lands outside its folder. */
    fun resolve(
        name: String,
        isJournal: Boolean,
        graphPath: String,
        encrypted: Boolean = false,
    ): Either<PageFileError, String> {
        if (name.contains('\u0000')) return PageFileError.InvalidPageName(name).left()
        val path = pagePath(name, isJournal, graphPath, encrypted)
        return checkInFolder(path, graphPath, isJournal)
    }

    /**
     * Resolves the journal file for [date], reusing an existing file stem for the same date.
     *
     * @param existingStems file stems (no extension) found in the target's `journals/` folder
     * @param configEdn text of the target's `config.edn`, or null when absent
     */
    fun resolveJournal(
        date: LocalDate,
        graphPath: String,
        existingStems: Collection<String>,
        configEdn: String? = null,
        encrypted: Boolean = false,
    ): Either<PageFileError, String> {
        val format = configEdn?.let(::parseJournalFileNameFormat)
        if (format != null && format != DEFAULT_JOURNAL_FORMAT) {
            return PageFileError.JournalFormatUnsupported(format).left()
        }
        val creationStem = JournalUtils.formatDateForJournal(date)
        val matches = existingStems.filter { JournalUtils.parseJournalDate(it) == date }
        val stem = when {
            creationStem in matches -> creationStem
            matches.isNotEmpty() -> matches.min()
            else -> creationStem
        }
        return resolve(stem, isJournal = true, graphPath = graphPath, encrypted = encrypted)
    }

    /**
     * Minimal EDN read of `:journal/file-name-format`: returns the string value, or null when the
     * key is absent, `nil`, or not a string. Comments (`;` to end of line, outside strings) are ignored.
     */
    fun parseJournalFileNameFormat(configEdn: String): String? {
        val stripped = stripEdnComments(configEdn)
        return JOURNAL_FORMAT_KEY.find(stripped)?.groupValues?.get(1)
    }

    private val JOURNAL_FORMAT_KEY = Regex(""":journal/file-name-format\s+"((?:[^"\\]|\\.)*)"""")

    private fun stripEdnComments(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var inComment = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inComment -> if (c == '\n') { inComment = false; out.append(c) }
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i++ } else if (c == '"') inString = false
                }
                c == ';' -> inComment = true
                else -> { out.append(c); if (c == '"') inString = true }
            }
            i++
        }
        return out.toString()
    }

    private fun checkInFolder(path: String, graphPath: String, isJournal: Boolean): Either<PageFileError, String> {
        val folder = (if (graphPath.endsWith("/")) graphPath else "$graphPath/") + if (isJournal) "journals" else "pages"
        return if (isWithin(folder, path)) path.right() else PageFileError.PathOutsideGraph(path).left()
    }

    /**
     * True when [path] is strictly inside [root] after lexically resolving `.`/`..` and treating
     * `\` as a separator. Pure string logic: for symlink safety pass canonical paths for both.
     * Paths containing NUL, or `..` that climbs above the start, are never inside.
     */
    fun isWithin(root: String, path: String): Boolean {
        val r = normalize(root) ?: return false
        val p = normalize(path) ?: return false
        return r.absolute == p.absolute && p.segments.size > r.segments.size &&
            p.segments.subList(0, r.segments.size) == r.segments
    }

    private class Normalized(val absolute: Boolean, val segments: List<String>)

    private fun normalize(path: String): Normalized? {
        if (path.contains('\u0000')) return null
        val unified = path.replace('\\', '/')
        val stack = ArrayList<String>()
        for (seg in unified.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.lastIndex)
                else -> stack.add(seg)
            }
        }
        return Normalized(unified.startsWith("/"), stack)
    }
}
