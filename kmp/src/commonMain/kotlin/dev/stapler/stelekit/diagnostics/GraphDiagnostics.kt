package dev.stapler.stelekit.diagnostics

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Builds a plain-text report comparing what the registry, the disk and the database each say
 * about the active graph. Meant to be exported from the Logs screen when pages or journals are
 * missing, so the failing layer (wrong folder, files not scanned, files not indexed) is visible
 * without adb. Read-only: nothing here writes to the graph or the DB.
 *
 * Reads are bounded: the DB side pages through `getJournalPages` in [DB_BATCH] rows (never an
 * unbounded query — see CLAUDE.md "Graph-scale reads").
 */
class GraphDiagnosticsCollector(
    private val graphManager: GraphManager,
    private val fileSystem: FileSystem,
    private val repos: RepositorySet,
    private val settings: Settings,
    private val gitConfigRepository: GitConfigRepository? = null,
    private val gitRepository: GitRepository? = null,
) {
    suspend fun collect(): String = withContext(PlatformDispatcher.IO) {
        buildString {
            appendLine("# SteleKit graph diagnostics")
            appendLine("# WARNING: contains file and page names from your graph. Share only with trusted parties.")
            appendLine("generated: ${Clock.System.now()}")
            appendLine()
            appendRegistry()
            appendSettings()
            val active = graphManager.getActiveGraphInfo()
            if (active == null) {
                appendLine("## Active graph\nnone")
            } else {
                appendActiveGraph(active)
                appendGit(active)
            }
            appendLogTail()
        }
    }

    private fun StringBuilder.appendRegistry() {
        val registry = graphManager.graphRegistry.value
        appendLine("## Registry (${registry.graphs.size} graphs)")
        for (g in registry.graphs) {
            val marker = if (g.id == registry.activeGraphId) "* ACTIVE" else "  "
            appendLine("$marker ${g.displayName} id=${g.id.value} demo=${g.isDemo}")
            appendLine("    path=${g.path}")
            if (g.detectedRepoRoot != null) {
                appendLine("    detectedRepoRoot=${g.detectedRepoRoot} wikiSubdir=${g.detectedWikiSubdir}")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendSettings() {
        appendLine("## Settings")
        for (key in SETTING_KEYS) appendLine("$key=${settings.getString(key, "<unset>")}")
        appendLine()
    }

    private suspend fun StringBuilder.appendActiveGraph(active: GraphInfo) {
        appendLine("## Active graph: ${active.displayName}")
        appendLine("path=${active.path}")
        appendLine("pathExists=${fileSystem.directoryExists(active.path)}")
        val notesPath = active.effectiveNotesPath.value
        appendLine("effectiveNotesPath=$notesPath (wikiSubdir=${active.detectedWikiSubdir ?: "<none>"})")
        appendLine("lastGraphPath matches effectiveNotesPath=${settings.getString("lastGraphPath", "") == notesPath}")
        appendLine()

        val disk = appendDisk(notesPath)
        val db = appendDatabase()
        appendDiff(disk, db)
    }

    private suspend fun StringBuilder.appendGit(active: GraphInfo) {
        appendLine()
        appendLine("## Git sync")
        val git = gitRepository
        val configRepo = gitConfigRepository
        if (git == null || configRepo == null) {
            appendLine("git not available on this platform")
            return
        }
        val config = try {
            configRepo.getConfig(active.id.value).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appendLine("config read failed: ${e.message}")
            return
        }
        if (config == null) {
            appendLine("no git config stored for graph ${active.id.value}")
            return
        }
        appendGitConfig(config)
        appendGitCall("status") { git.status(config).fold({ "error: ${it.message}" }, { s ->
            "hasLocalChanges=${s.hasLocalChanges} modified=${s.modifiedFiles.size} untracked=${s.untrackedFiles.size} " +
                "untracked(first $MAX_LISTED)=${s.untrackedFiles.take(MAX_LISTED)}"
        }) }
        appendGitCall("detachedHead") { git.hasDetachedHead(config).toString() }
        appendGitCall("refs") { "\n" + git.describeRefs(config) }
        appendGitCall("log (last $GIT_LOG_ENTRIES)") {
            git.log(config, GIT_LOG_ENTRIES).fold({ "error: ${it.message}" }, { commits ->
                commits.joinToString(prefix = "\n", separator = "\n") { c -> "${c.sha.take(9)} ${c.timestamp} ${c.shortMessage}" }
            })
        }
    }

    private fun StringBuilder.appendGitConfig(c: GitConfig) {
        appendLine("remoteName=${c.remoteName} remoteBranch=${c.remoteBranch} authType=${c.authType}")
        appendLine("repoRoot=${c.repoRoot} wikiSubdir=${c.wikiSubdir ?: "<none>"}")
        appendLine("pollIntervalMinutes=${c.pollIntervalMinutes} autoCommit=${c.autoCommit} cloneDepthState=${c.cloneDepthState}")
    }

    private suspend fun StringBuilder.appendGitCall(label: String, block: suspend () -> String) {
        val text = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "<failed: ${e::class.simpleName}: ${e.message}>"
        }
        appendLine("$label: $text")
    }

    private class DiskJournals(val dates: Set<LocalDate>)

    private fun StringBuilder.appendDisk(root: String): DiskJournals {
        appendLine("## On disk")
        val rootDirs = safeList { fileSystem.listDirectories(root) }
        val rootFiles = safeList { fileSystem.listFiles(root) }
        appendLine("root: ${rootDirs.size} dirs, ${rootFiles.size} files")
        appendLine("root dirs (first $MAX_LISTED): ${rootDirs.take(MAX_LISTED).joinToString()}")
        appendLine("root files (first $MAX_LISTED): ${rootFiles.take(MAX_LISTED).joinToString()}")

        val pageFiles = safeList { fileSystem.listFiles("$root/pages") }
        val journalFiles = safeList { fileSystem.listFiles("$root/journals") }
        appendLine("pages/    exists=${fileSystem.directoryExists("$root/pages")} files=${pageFiles.size}")
        appendLine("journals/ exists=${fileSystem.directoryExists("$root/journals")} files=${journalFiles.size}")

        // The loader only scans <root>/pages and <root>/journals. If the real graph is one folder
        // down (a repo with the wiki in a subdirectory), the loader sees nothing — flag it.
        val candidates = scanForWikiCandidates(root, fileSystem)
        for (candidate in candidates) {
            appendLine("NESTED GRAPH CANDIDATE: $root/${candidate.path} (pages=${candidate.pages} journals=${candidate.journals})")
        }

        val names = journalFiles.map { it.substringAfterLast('/') }
        val parsed = names.associateWith { parseJournalDate(it) }
        val dates = parsed.values.filterNotNull().toSet()
        val unparsable = parsed.filterValues { it == null }.keys.toList()
        appendLine("journal files: ${names.size}, parsable dates: ${dates.size}, range: ${rangeOf(dates)}")
        appendLine("newest journal files on disk: ${dates.sortedDescending().take(NEWEST_LISTED).joinToString()}")
        if (unparsable.isNotEmpty()) {
            appendLine("unparsable journal filenames (first $MAX_LISTED): ${unparsable.take(MAX_LISTED).joinToString()}")
        }
        appendLine()
        return DiskJournals(dates)
    }

    private class DbJournals(val dates: Set<LocalDate>)

    private suspend fun StringBuilder.appendDatabase(): DbJournals {
        appendLine("## In database")
        val pageCount = repos.pageRepository.countPages().first().getOrNull()
        val unloaded = repos.pageRepository.countUnloadedPages().getOrNull()
        appendLine("pages (total): ${pageCount ?: "error"}")
        appendLine("pages awaiting full index: ${unloaded ?: "error"}")

        val dates = mutableSetOf<LocalDate>()
        var journalRows = 0
        var withoutDate = 0
        var offset = 0
        while (offset < DB_BATCH * MAX_DB_BATCHES) {
            val batch = repos.pageRepository.getJournalPages(DB_BATCH, offset).first().getOrNull().orEmpty()
            if (batch.isEmpty()) break
            journalRows += batch.size
            for (page in batch) {
                val date = page.journalDate
                if (date != null) dates.add(date) else withoutDate++
            }
            offset += batch.size
        }
        if (offset >= DB_BATCH * MAX_DB_BATCHES) appendLine("(journal scan hit the ${DB_BATCH * MAX_DB_BATCHES}-row cap; counts are a lower bound)")
        appendLine("journal pages: $journalRows (without date: $withoutDate), distinct dates: ${dates.size}, range: ${rangeOf(dates)}")
        appendLine()
        return DbJournals(dates)
    }

    private fun StringBuilder.appendDiff(disk: DiskJournals, db: DbJournals) {
        appendLine("## Disk vs database (journals)")
        val missingFromDb = (disk.dates - db.dates).sorted()
        val missingFromDisk = (db.dates - disk.dates).sorted()
        appendLine("on disk but NOT in DB: ${missingFromDb.size}  ${sample(missingFromDb)}")
        appendLine("in DB but NOT on disk: ${missingFromDisk.size}  ${sample(missingFromDisk)}")
        appendLine()
    }

    private fun StringBuilder.appendLogTail() {
        appendLine("## Recent log (newest last, last $LOG_TAIL entries)")
        LogManager.logs.value.take(LOG_TAIL).asReversed().forEach { e ->
            appendLine("[${e.level}] ${e.tag}: ${e.message}")
        }
    }

    private fun parseJournalDate(fileName: String): LocalDate? {
        val m = JOURNAL_FILE.matchEntire(fileName.substringBefore(".md")) ?: return null
        val (y, mo, d) = m.destructured
        return try { LocalDate(y.toInt(), mo.toInt(), d.toInt()) } catch (_: IllegalArgumentException) { null }
    }

    private fun rangeOf(dates: Set<LocalDate>): String =
        if (dates.isEmpty()) "none" else "${dates.min()} .. ${dates.max()}"

    private fun sample(dates: List<LocalDate>): String =
        if (dates.isEmpty()) {
            ""
        } else {
            "oldest: ${dates.take(MAX_LISTED).joinToString()}" +
                if (dates.size > MAX_LISTED) " | newest: ${dates.takeLast(NEWEST_LISTED).joinToString()}" else ""
        }

    private inline fun safeList(block: () -> List<String>): List<String> =
        try { block() } catch (_: Exception) { emptyList() }

    private companion object {
        val JOURNAL_FILE = Regex("""(\d{4})_(\d{2})_(\d{2})""")
        val SETTING_KEYS = listOf("cached_graph_path", "lastGraphPath", "onboardingCompleted")
        const val DB_BATCH = 500
        const val MAX_DB_BATCHES = 40
        const val MAX_LISTED = 40
        const val MAX_NESTED_PROBES = 40
        const val NEWEST_LISTED = 15
        const val GIT_LOG_ENTRIES = 10
        const val LOG_TAIL = 150
    }
}
