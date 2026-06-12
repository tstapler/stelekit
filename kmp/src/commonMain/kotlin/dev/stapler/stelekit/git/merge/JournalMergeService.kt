package dev.stapler.stelekit.git.merge

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class JournalMergeProposal(
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
        val JOURNAL_FILENAME_REGEX = Regex("""\d{4}[-_]\d{2}[-_]\d{2}\.md""")

        fun isJournalFile(fileName: String): Boolean =
            JOURNAL_FILENAME_REGEX.containsMatchIn(fileName)
    }

    @OptIn(ExperimentalTime::class)
    suspend fun propose(conflictFile: ConflictFile, graphRoot: String): JournalMergeProposal {
        val content = withContext(PlatformDispatcher.IO) {
            fileSystem.readFile(conflictFile.filePath) ?: ""
        }

        val (base, local, remote) = extractConflictSides(content)

        val fileName = conflictFile.filePath.substringAfterLast("/")
        val backupPath = writeBackup(graphRoot, fileName, content)

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

        return JournalMergeProposal(
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
                    ParseState.OUTSIDE -> { /* ignore non-conflict lines */ }
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
        val epochMs = Clock.System.now().toEpochMilliseconds()
        val backupDir = "$graphRoot/.stelekit-backup"
        val backupPath = "$backupDir/$fileName-$epochMs.md"

        withContext(PlatformDispatcher.IO) {
            fileSystem.createDirectory(backupDir)
            fileSystem.writeFile(backupPath, content)
        }

        return backupPath
    }

    private enum class ParseState {
        OUTSIDE, LOCAL, BASE, REMOTE
    }
}
