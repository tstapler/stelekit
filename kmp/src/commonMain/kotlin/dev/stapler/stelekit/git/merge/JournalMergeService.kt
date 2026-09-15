package dev.stapler.stelekit.git.merge

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class JournalMergeProposal(
    val filePath: String,
    val localContent: String,
    val remoteContent: String,
    val proposedMerge: String,
    val confidenceWarning: Boolean,
    val hasConflictMarkers: Boolean,
    val backupPath: String,
)

class JournalMergeService(
    private val mergeDriver: LogseqMergeDriver = LogseqMergeDriver(),
    private val fileSystem: FileSystem,
) {
    companion object {
        // Anchored: matches the whole filename, not a substring inside a longer path.
        // "project-2026-06-12-notes.md" must NOT match.
        val JOURNAL_FILENAME_REGEX = Regex("""^\d{4}[-_]\d{2}[-_]\d{2}\.md$""")

        fun isJournalFile(fileName: String): Boolean =
            JOURNAL_FILENAME_REGEX.matches(fileName)
    }

    @OptIn(ExperimentalTime::class)
    suspend fun propose(
        conflictFile: ConflictFile,
        graphRoot: String,
        writeBackupToDisk: Boolean = true,
    ): JournalMergeProposal {
        val content = withContext(PlatformDispatcher.IO) {
            fileSystem.readFile(conflictFile.filePath) ?: ""
        }

        val (base, local, remote) = extractConflictSides(content)

        val mergeResult = mergeDriver.merge(
            base.lines(),
            local.lines(),
            remote.lines(),
        )

        val proposedMerge = mergeResult.lines.joinToString("\n")
        val localLines = local.lines()
        val remoteLines = remote.lines()
        val proposedLines = mergeResult.lines
        val confidenceWarning = proposedLines.size < maxOf(localLines.size, remoteLines.size) * 0.9

        val fileName = conflictFile.filePath.substringAfterLast("/")
        val backupPath = if (writeBackupToDisk) {
            writeBackup(graphRoot, fileName, content)
        } else {
            computeBackupPath(graphRoot, fileName)
        }

        return JournalMergeProposal(
            filePath = conflictFile.filePath,
            localContent = local,
            remoteContent = remote,
            proposedMerge = proposedMerge,
            confidenceWarning = confidenceWarning,
            hasConflictMarkers = mergeResult.hasConflictMarkers,
            backupPath = backupPath,
        )
    }

    fun extractConflictSides(content: String): Triple<String, String, String> {
        val lines = content.lines()
        val localLines = mutableListOf<String>()
        val baseLines = mutableListOf<String>()
        val remoteLines = mutableListOf<String>()

        var state = ParseState.OUTSIDE

        for (line in lines) {
            when {
                line.startsWith("<<<<<<< ") -> {
                    state = ParseState.LOCAL
                }
                line.startsWith("||||||| ") -> {
                    state = ParseState.BASE
                }
                line == "=======" -> {
                    state = ParseState.REMOTE
                }
                line.startsWith(">>>>>>> ") -> {
                    state = ParseState.OUTSIDE
                }
                else -> when (state) {
                    ParseState.LOCAL -> localLines.add(line)
                    ParseState.BASE -> baseLines.add(line)
                    ParseState.REMOTE -> remoteLines.add(line)
                    // Non-conflicting lines are identical in all three sides — preserve them.
                    ParseState.OUTSIDE -> { localLines.add(line); baseLines.add(line); remoteLines.add(line) }
                }
            }
        }

        val base = baseLines.joinToString("\n")
        val local = localLines.joinToString("\n")
        val remote = remoteLines.joinToString("\n")
        return Triple(base, local, remote)
    }

    @OptIn(ExperimentalTime::class)
    suspend fun writeBackup(graphRoot: String, fileName: String, content: String): String {
        val backupPath = computeBackupPath(graphRoot, fileName)
        val backupDir = "$graphRoot/.stelekit-backup"
        withContext(PlatformDispatcher.IO) {
            fileSystem.createDirectory(backupDir)
            fileSystem.writeFile(backupPath, content)
        }
        return backupPath
    }

    @OptIn(ExperimentalTime::class)
    private fun computeBackupPath(graphRoot: String, fileName: String): String {
        val epochMs = Clock.System.now().toEpochMilliseconds()
        val backupDir = "$graphRoot/.stelekit-backup"
        val stem = if (fileName.endsWith(".md")) fileName.removeSuffix(".md") else fileName
        return "$backupDir/$stem-$epochMs.md"
    }

    private enum class ParseState {
        OUTSIDE, LOCAL, BASE, REMOTE
    }
}
