// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.Settings
import kotlin.math.max

/** Which change set a guard inspects. */
enum class GuardScope {
    /** The index against `HEAD`: what a commit about to be made would record. */
    Staged,

    /** The `<remote>/<branch>` tree against `HEAD`: what a push about to be made would publish. */
    NetPush,
}

/** Whether git's own repository state allows staging, committing and pushing. */
data class RepositoryHealth(val safe: Boolean, val state: String) {
    companion object {
        val Clean = RepositoryHealth(safe = true, state = "SAFE")
    }
}

/** Result of scanning a [GuardScope] for conflict markers in added lines. */
sealed interface MarkerScan {
    data object Clean : MarkerScan
    data class Found(val files: List<String>) : MarkerScan

    /** A size cap was hit; callers must treat this as a refusal, never as "nothing found". */
    data class Incomplete(val reason: String) : MarkerScan
}

/** Files a [GuardScope] would delete, plus the size of the tree they are deleted from. */
data class DeletionSummary(val deletedPaths: List<String>, val previousTreeSize: Int) {
    companion object {
        val None = DeletionSummary(emptyList(), 0)
    }
}

/** One line of a unified diff printed with full context: [added] lines are the new ones. */
data class DiffLine(val added: Boolean, val text: String)

/** Pure marker detection over a diff of one file, shared by every platform's repository. */
object ConflictMarkerDiffScan {
    private const val OPEN = "<<<<<<< "
    private const val CLOSE = ">>>>>>> "

    /**
     * True when added lines hold a line-start `<<<<<<< ` followed by a later added line-start `>>>>>>> `,
     * both outside a fenced code block, so a page documenting merge syntax cannot wedge sync.
     * [lines] must be the whole new file (context and added lines) so fence state is exact.
     */
    fun hasAddedMarkerPair(lines: List<DiffLine>): Boolean {
        var fence: Char? = null
        var opened = false
        for (line in lines) {
            val trimmed = line.text.trimStart()
            val fenceChar = when {
                trimmed.startsWith("```") -> '`'
                trimmed.startsWith("~~~") -> '~'
                else -> null
            }
            if (fenceChar != null) {
                fence = if (fence == null) fenceChar else if (fence == fenceChar) null else fence
                continue
            }
            if (fence != null || !line.added) continue
            if (!opened && line.text.startsWith(OPEN)) opened = true
            else if (opened && line.text.startsWith(CLOSE)) return true
        }
        return false
    }
}

/**
 * Mass-deletion limits. Defaults are INFERRED starting values (calibrated on the owner's graph in the
 * dev plan's Task 7.1d); the three Settings keys override them without a rebuild.
 */
data class MassChangeThresholds(
    val minFiles: Int = DEFAULT_MIN_FILES,
    val percent: Int = DEFAULT_PERCENT,
    val maxJournals: Int = DEFAULT_MAX_JOURNALS,
) {
    /** The file-count limit for a tree of [previousTreeSize] files: `max(minFiles, percent% of it)`. */
    fun fileLimit(previousTreeSize: Int): Int = max(minFiles, previousTreeSize * percent / 100)

    /** Null when [summary] is within both limits. */
    fun evaluate(summary: DeletionSummary): DomainError.GitError.MassChangeBlocked? {
        val deleted = summary.deletedPaths.size
        val limit = fileLimit(summary.previousTreeSize)
        val journals = summary.deletedPaths.count { it.startsWith("journals/") || it.contains("/journals/") }
        return when {
            deleted > limit -> DomainError.GitError.MassChangeBlocked(deleted, limit, summary.deletedPaths.take(SAMPLE_SIZE))
            journals > maxJournals -> DomainError.GitError.MassChangeBlocked(
                journals, maxJournals, summary.deletedPaths.filter { it.startsWith("journals/") || it.contains("/journals/") }.take(SAMPLE_SIZE),
            )
            else -> null
        }
    }

    companion object {
        const val DEFAULT_MIN_FILES = 20
        const val DEFAULT_PERCENT = 5
        const val DEFAULT_MAX_JOURNALS = 10
        const val SAMPLE_SIZE = 50
        const val KEY_MIN_FILES = "git_mass_change_min_files"
        const val KEY_PERCENT = "git_mass_change_pct"
        const val KEY_MAX_JOURNALS = "git_mass_change_max_journals"

        /** Reads the overrides from [settings]; absent, non-positive or unreadable values fall back to the defaults. */
        fun from(settings: Settings?): MassChangeThresholds {
            if (settings == null) return MassChangeThresholds()
            fun read(key: String, default: Int): Int = try {
                settings.getString(key, "").toIntOrNull()?.takeIf { it > 0 } ?: default
            } catch (_: Exception) {
                default
            }
            return MassChangeThresholds(
                minFiles = read(KEY_MIN_FILES, DEFAULT_MIN_FILES),
                percent = read(KEY_PERCENT, DEFAULT_PERCENT),
                maxJournals = read(KEY_MAX_JOURNALS, DEFAULT_MAX_JOURNALS),
            )
        }
    }
}

/**
 * The safety checks that run before every commit and push in [GitSyncService]. Returns the typed
 * refusal, or null when the step may proceed. [liftMassChange] is honored only for a foreground run.
 */
internal class GitChangeGuards(
    private val repository: GitRepository,
    private val settings: Settings?,
) {
    suspend fun repositoryState(config: GitConfig): DomainError.GitError.RepairNeeded? =
        repository.repositoryHealth(config).takeIf { !it.safe }?.let { DomainError.GitError.RepairNeeded(it.state) }

    suspend fun markers(config: GitConfig, scope: GuardScope): DomainError.GitError? =
        when (val scan = repository.scanForConflictMarkers(config, scope)) {
            MarkerScan.Clean -> null
            is MarkerScan.Found -> DomainError.GitError.ConflictMarkersPresent(scan.files)
            is MarkerScan.Incomplete -> DomainError.GitError.ScanIncomplete(scan.reason)
        }

    suspend fun massChange(config: GitConfig, scope: GuardScope, liftMassChange: Boolean): DomainError.GitError? {
        if (liftMassChange) return null
        return MassChangeThresholds.from(settings).evaluate(repository.deletionSummary(config, scope))
    }

    /** Marker scan then mass-change check for [scope]; the first refusal wins. */
    suspend fun changes(config: GitConfig, scope: GuardScope, liftMassChange: Boolean): DomainError.GitError? =
        markers(config, scope) ?: massChange(config, scope, liftMassChange)
}
