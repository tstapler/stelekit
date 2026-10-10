// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.ApplyFailure
import dev.stapler.stelekit.merge.CapabilityAction
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.MergeProgress
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.OffGraphTarget
import dev.stapler.stelekit.merge.PageSource
import dev.stapler.stelekit.merge.PlanRequest
import dev.stapler.stelekit.merge.PullIndexState
import dev.stapler.stelekit.merge.ReadCapabilityReason
import dev.stapler.stelekit.merge.SelectionFilter
import dev.stapler.stelekit.merge.SourceReadCapabilities
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.WriteCapabilityReason
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlin.jvm.JvmInline

/**
 * Pages the user ticked, keyed by page uuid so the set is independent of what the list shows
 * (search and filter changes never touch it). Reducers are pure.
 */
@JvmInline
value class PickedPages(val uuids: Set<PageUuid> = emptySet()) {
    val size: Int get() = uuids.size
    fun isEmpty(): Boolean = uuids.isEmpty()
    operator fun contains(uuid: PageUuid): Boolean = uuid in uuids

    fun toggle(uuid: PageUuid): PickedPages = PickedPages(if (uuid in uuids) uuids - uuid else uuids + uuid)
    fun addAll(more: Collection<PageUuid>): PickedPages = PickedPages(uuids + more)
    fun clear(): PickedPages = PickedPages()
}

/** Pages and Journals chips plus date range, namespace and tag; all AND together. */
data class CopyFilters(
    val showPages: Boolean = true,
    val showJournals: Boolean = true,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val namespace: String = "",
    val tag: String = "",
) {
    val isDefault: Boolean get() = this == CopyFilters()

    fun toSelectionFilter(): SelectionFilter = SelectionFilter(
        journals = showJournals,
        // "Journals only" has no flag in SelectionFilter; a floor date keeps only journal pages.
        dateFrom = dateFrom ?: if (showJournals && !showPages) JOURNALS_ONLY_FLOOR else null,
        dateTo = dateTo,
        namespace = namespace.trim().ifEmpty { null },
        tag = tag.trim().ifEmpty { null },
    )

    private companion object {
        val JOURNALS_ONLY_FLOOR = LocalDate(1, 1, 1)
    }
}

data class PageRowState(
    val uuid: PageUuid,
    val name: String,
    val isJournal: Boolean,
    /** Null when unknown: [PageSource] has no per-page block count, so the list omits it. */
    val blockCount: Int? = null,
    /** Pull only: "size - modified date" (no block counts without a database). */
    val subtitle: String? = null,
) {
    /** Full text exposed to accessibility services (never truncated). */
    val label: String
        get() = buildString {
            append(name)
            if (isJournal) append(", journal")
            if (blockCount != null) append(", ").append(blockCount).append(if (blockCount == 1) " block" else " blocks")
            if (subtitle != null) append(", ").append(subtitle)
        }
}

enum class ListLoad {
    /** First page not yet back: skeleton rows. */
    Initial,
    Idle,
    /** Filter/search changed: previous rows stay, dimmed. */
    Updating,
    /** Next 100-row page is loading: footer spinner. */
    AppendingMore,
    Failed,
}

enum class DestinationActionKind { RegrantAccess, OpenGraph, SwitchToPull }

data class DestinationAction(val kind: DestinationActionKind, val label: String)

enum class DisabledKind { CurrentGraph, Encrypted, NoGrant, SafInboxOnly, PlatformUnsupported, FolderMissing, Unreadable }

sealed interface DestinationStatus {
    /** Probe running: spinner plus "Checking...". */
    data object Checking : DestinationStatus
    data object Available : DestinationStatus

    /** Never selectable; [text] states why and [action] (if any) is the way out. */
    data class Disabled(val kind: DisabledKind, val text: String, val action: DestinationAction? = null) : DestinationStatus

    /** Probe timed out or threw; [text] reads "Couldn't check <graph>: <reason>" and Retry re-runs it. */
    data class CouldntCheck(val text: String) : DestinationStatus
}

data class DestinationRow(val graphId: GraphId, val name: String, val status: DestinationStatus) {
    val isCurrentGraph: Boolean
        get() = (status as? DestinationStatus.Disabled)?.kind == DisabledKind.CurrentGraph
}

sealed interface ReviewState {
    data object Idle : ReviewState
    data object Planning : ReviewState
    /** Dry run done; the dry-run dialog (S4) takes [plan] and [request]. */
    data class Ready(val request: PlanRequest, val plan: MergePlan) : ReviewState
    data class Failed(val message: String) : ReviewState
}

data class CopyPagesState(
    val direction: CopyDirection = CopyDirection.Push,
    /** Name of the active graph: the source in Push, the destination in Pull. */
    val activeGraphName: String = "",
    val searchText: String = "",
    val filters: CopyFilters = CopyFilters(),
    val rows: List<PageRowState> = emptyList(),
    /** Matching pages; null renders "Counting...". */
    val totalMatching: Long? = null,
    val hasMore: Boolean = false,
    val listLoad: ListLoad = ListLoad.Initial,
    val loadError: String? = null,
    val picked: PickedPages = PickedPages(),
    /** True while select-all pages through the source. */
    val selecting: Boolean = false,
    /** Search is title-only and sees at most the first [PageSource.MAX_PAGE_SIZE] hits. */
    val searchCapped: Boolean = false,
    /** Non-null while the "Select all N pages?" confirmation is open. */
    val confirmAllPages: Long? = null,
    val discardPrompt: Boolean = false,
    val destinations: List<DestinationRow> = emptyList(),
    val destinationId: GraphId? = null,
    val gate2LinkedPages: Boolean = false,
    val includeLinked: Boolean = false,
    val includeAssets: Boolean = false,
    /** Live "adds N pages" delta; null while unknown. */
    val linkedDelta: Int? = null,
    val review: ReviewState = ReviewState.Idle,
    /** Pull only: progress of the chosen source's name index. */
    val indexState: PullIndexState = PullIndexState.Idle,
) {
    val isPull: Boolean get() = direction == CopyDirection.Pull
    val selectedCount: Int get() = picked.size
    val chosenDestination: DestinationRow? get() = destinations.firstOrNull { it.graphId == destinationId }
    val noOtherGraph: Boolean get() = destinations.all { it.isCurrentGraph }

    /** Pull: other graphs exist but every one is settled and unusable as a source. */
    val noSourceAvailable: Boolean
        get() = isPull && !noOtherGraph &&
            destinations.filterNot { it.isCurrentGraph }.all { it.status is DestinationStatus.Disabled }

    /** Pull: the name index is still streaming in. */
    val stillReading: Boolean get() = indexState is PullIndexState.Reading

    val canReview: Boolean
        get() = !picked.isEmpty() && chosenDestination?.status == DestinationStatus.Available &&
            review != ReviewState.Planning

    /** Why Review is disabled, or null when it is enabled. */
    val reviewHelper: String?
        get() {
            val chosen = chosenDestination
            return when {
                picked.isEmpty() -> "Select at least one page"
                chosen == null -> "Choose a destination"
                chosen.status == DestinationStatus.Checking -> "Checking ${chosen.name}..."
                chosen.status != DestinationStatus.Available -> "Choose a destination"
                else -> null
            }
        }

    /** Count line, e.g. "213 results, 12 selected". */
    val countLine: String
        get() {
            val total = totalMatching
            val head = if (total == null) "Counting..." else "${groupThousands(total)} ${if (total == 1L) "result" else "results"}"
            val tail = if (listLoad == ListLoad.Updating) ", Updating..." else ""
            return "$head, ${groupThousands(selectedCount.toLong())} selected$tail"
        }

    companion object {
        const val CURRENT_GRAPH_TEXT = "current graph"
        const val NOT_AVAILABLE_PULL = "Not available when copying from a graph that isn't open"
        const val RESELECT_FOLDER = "Re-select folder"
    }
}

internal fun groupThousands(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

sealed interface CopyPagesEvent {
    /** Close the picker (selection already discarded or empty). */
    data object Closed : CopyPagesEvent
    data object SwitchToPull : CopyPagesEvent
    data object AddGraph : CopyPagesEvent
    data class RegrantAccess(val graphId: GraphId) : CopyPagesEvent
    data class OpenGraph(val graphId: GraphId) : CopyPagesEvent
}

/**
 * The only door from the picker to the merge engine. Copies never go through `ShareInbox`;
 * [plan] is the dry run, [apply] the commit. Implemented over `PageMergeService` and the
 * [PageSource] being browsed; the picker itself calls only [plan] and [linkedPageDelta].
 */
interface CopyFlowGateway {
    val progress: StateFlow<MergeProgress>
    suspend fun plan(request: PlanRequest): Either<DomainError, MergePlan>
    suspend fun apply(plan: MergePlan): Either<ApplyFailure, MergeResult>
    fun cancel()

    /** Re-applies only the pages that failed in the last run; the Pull gateway first re-opens the destination if the user left it. */
    suspend fun retryFailed(): Either<ApplyFailure, MergeResult> = Either.Left(ApplyFailure.Busy)

    /** Pages "Include linked pages" would add (depth 1); null if it cannot be computed. */
    suspend fun linkedPageDelta(request: PlanRequest): Int? = null
}

/** Answers whether [graph] can be written to (Push) or read from (Pull); the ViewModel bounds it with a timeout. */
fun interface DestinationProbe {
    suspend fun probe(graph: GraphInfo, direction: CopyDirection): DestinationStatus
}

/** [DestinationProbe] over the capability policies shared with the router. */
class CapabilityDestinationProbe(
    private val writer: TargetWriterCapabilities,
    private val reader: SourceReadCapabilities,
) : DestinationProbe {
    override suspend fun probe(graph: GraphInfo, direction: CopyDirection): DestinationStatus {
        val target = OffGraphTarget(
            graphId = graph.id,
            path = graph.path,
            isActive = false,
            encrypted = graph.isParanoidMode,
        )
        val name = graph.displayName
        return when (direction) {
            CopyDirection.Push -> writer.canWriteOffGraph(target).fold({ pushDisabled(name, it) }) { DestinationStatus.Available }
            CopyDirection.Pull -> reader.canReadOffGraph(target).fold({ pullDisabled(name, it) }) { DestinationStatus.Available }
        }
    }

    private fun pushDisabled(name: String, reason: WriteCapabilityReason): DestinationStatus.Disabled = when (reason) {
        is WriteCapabilityReason.PlatformUnsupported -> DestinationStatus.Disabled(
            DisabledKind.PlatformUnsupported,
            "Can't copy into $name from here on this device. Open $name, then use Copy pages from...",
            DestinationAction(DestinationActionKind.SwitchToPull, "Copy pages from..."),
        )
        else -> DestinationStatus.Disabled(
            kind = when (reason) {
                is WriteCapabilityReason.Encrypted -> DisabledKind.Encrypted
                is WriteCapabilityReason.NoGrant -> DisabledKind.NoGrant
                is WriteCapabilityReason.SafInboxOnly -> DisabledKind.SafInboxOnly
                is WriteCapabilityReason.PlatformUnsupported -> DisabledKind.PlatformUnsupported
            },
            text = "Can't write here: ${reason.userText}",
            action = reason.action?.toDestinationAction(),
        )
    }

    private fun pullDisabled(name: String, reason: ReadCapabilityReason): DestinationStatus.Disabled =
        DestinationStatus.Disabled(
            kind = when (reason) {
                is ReadCapabilityReason.Encrypted -> DisabledKind.Encrypted
                is ReadCapabilityReason.NoGrant -> DisabledKind.NoGrant
                is ReadCapabilityReason.FolderMissing -> DisabledKind.FolderMissing
                is ReadCapabilityReason.PlatformUnsupported -> DisabledKind.PlatformUnsupported
                is ReadCapabilityReason.UnreadableIo -> DisabledKind.Unreadable
            },
            text = "Can't read $name: ${reason.userText}",
            action = reason.action?.toDestinationAction(pullLabels = true),
        )

    private fun CapabilityAction.toDestinationAction(pullLabels: Boolean = false) = DestinationAction(
        when (this) {
            CapabilityAction.RegrantAccess -> DestinationActionKind.RegrantAccess
            CapabilityAction.OpenGraph -> DestinationActionKind.OpenGraph
        },
        if (pullLabels && this == CapabilityAction.RegrantAccess) CopyPagesState.RESELECT_FOLDER else label,
    )
}

/** Last chosen copy destination (Gate 2). Independent of the `capture_*` keys. */
class CopyDestinationSettings(private val platformSettings: Settings) {
    var lastDestinationGraphId: GraphId?
        get() = platformSettings.getString(KEY_LAST_DESTINATION_GRAPH_ID, "").takeIf { it.isNotBlank() }?.let(::GraphId)
        set(value) = platformSettings.putString(KEY_LAST_DESTINATION_GRAPH_ID, value?.value.orEmpty())

    companion object {
        const val KEY_LAST_DESTINATION_GRAPH_ID = "copy_last_destination_graph_id"
    }
}
