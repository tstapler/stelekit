package dev.stapler.stelekit.merge

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.PageUuid
import kotlinx.serialization.Serializable

/**
 * Which source pages to copy: [filter]/[search] list them, [include] (if set) restricts to those
 * uuids, [exclude] drops uuids. Independent of list position, so it survives filter changes.
 */
data class PageSelection(
    val filter: SelectionFilter = SelectionFilter(),
    val search: String? = null,
    val include: Set<PageUuid>? = null,
    val exclude: Set<PageUuid> = emptySet(),
)

data class PlanRequest(
    val selection: PageSelection,
    val sourceGraphId: GraphId,
    val targetGraphId: GraphId,
    val sourceGraphName: String = sourceGraphId.value,
    val closure: LinkClosurePolicy = LinkClosurePolicy.DEFAULT,
    val direction: CopyDirection = CopyDirection.Push,
)

/**
 * Counts only. [conflicts] is a SUBSET of [combined] (pages with >= 1 flagged conflict); the
 * "Copy N pages" count is [toCopy] = new + combined.
 */
@Serializable
data class DryRunSummary(
    val new: Int = 0,
    val combined: Int = 0,
    val unchanged: Int = 0,
    val conflicts: Int = 0,
    val unreadable: Int = 0,
    val assetsRenamed: Int = 0,
) {
    val toCopy: Int get() = new + combined
    val total: Int get() = new + combined + unchanged + unreadable
}

@Serializable
data class PlanConflict(val pageName: String, val targetUuid: String?, val incomingUuid: String)

/** Link-closure outcome: [added] pages are part of the plan, [notIncluded] were over the cap. */
@Serializable
data class ClosureSummary(val added: Int = 0, val notIncluded: Int = 0, val requiresConfirmation: Boolean = false)

/**
 * Result of `plan`. Retains counters and at most [MAX_CONFLICT_DETAILS] conflict details, never
 * page bodies or page lists, so it stays small for any graph size. Pass it back to `stage`/`apply`.
 * [planFingerprint] hashes the target's state for every planned page; `apply` recomputes it.
 */
@Serializable
data class MergePlan(
    val mergeId: String,
    val sourceGraphId: String,
    val targetGraphId: String,
    val direction: CopyDirection,
    val summary: DryRunSummary,
    val conflictDetails: List<PlanConflict>,
    val closure: ClosureSummary,
    val planFingerprint: String,
    val createdAtEpochMs: Long,
) {
    companion object {
        const val MAX_CONFLICT_DETAILS = 50
    }
}

/** Why `apply`/`stage`/`retryFailed` did not run. Nothing was written to the target in any case. */
sealed interface ApplyFailure {
    /** A target page changed since the plan; [recomputed] is the fresh dry run. */
    data class PlanStale(val recomputed: DryRunSummary) : ApplyFailure

    data class Failed(val error: DomainError) : ApplyFailure

    data class StagingFailed(val message: String) : ApplyFailure

    /** Another run is in progress, or there is no run to retry. */
    data object Busy : ApplyFailure

    /** The plan was not produced by this service instance (e.g. after a restart): re-plan. */
    data object UnknownPlan : ApplyFailure
}

/** One page that was not copied. The page stays in staging and is included in `retryFailed`. */
data class PageFailure(val stagedIndex: Int, val pageName: String, val error: DomainError)

/**
 * Every selected page is exactly one of newPages / combinedPages / unchangedPages / failed, or
 * (only when stopped) not attempted.
 */
data class MergeResult(
    val mergeId: String,
    val total: Int,
    val newPages: Int,
    val combinedPages: Int,
    val unchangedPages: Int,
    val failed: List<PageFailure>,
    val conflicts: Int,
    val assetsRenamed: Int,
    /** Set when cancelled: pages processed before the stop. */
    val stoppedAfter: Int? = null,
) {
    val notAttempted: Int get() = total - newPages - combinedPages - unchangedPages - failed.size

    val stoppedMessage: String?
        get() = stoppedAfter?.let { "Stopped after ${groupThousands(it)} of ${groupThousands(total)}" }

    private fun groupThousands(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
}

enum class MergePhase { Idle, Planning, Staging, Applying, Finished, Stopped }

/** Observable progress; counts only. */
data class MergeProgress(
    val phase: MergePhase = MergePhase.Idle,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
)
