// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.ApplyFailure
import dev.stapler.stelekit.merge.CopyRunOutcome
import dev.stapler.stelekit.merge.InterruptedCopy
import dev.stapler.stelekit.merge.MergeId
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.MergeStagingDirectory
import dev.stapler.stelekit.merge.PageSelection
import dev.stapler.stelekit.merge.PageSource
import dev.stapler.stelekit.merge.PlanRequest
import dev.stapler.stelekit.merge.UndoResult
import dev.stapler.stelekit.merge.UndoUnavailableReason
import dev.stapler.stelekit.merge.interruptedCopies
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlinx.coroutines.withContext

/** What the open graph's composition root offers the copy flow; swapped on every graph switch. */
class CopyGraphBinding(
    val graphId: GraphId,
    val source: PageSource,
    val pagesByNames: suspend (List<String>) -> Either<DomainError, List<Page>>,
    val onAddGraph: () -> Unit,
)

/** Everything the copy dialogs can ask of the flow; the host composable depends on this, not the controller. */
interface CopyFlowActions {
    fun onPickerEvent(event: CopyPagesEvent)
    fun closeFlow()
    fun dryRunBack()
    fun dryRunRetry()
    fun dryRunChooseAnother()
    fun dryRunConfirm()
    fun stop()
    fun done()
    fun retryFailedPages()
    fun continueStopped()
    fun runFailedRetry()
    fun runFailedChooseAnother()
    fun requestUndo()
    fun cancelUndo()
    fun confirmUndo()
    fun reviewConflicts()
    fun closeConflicts()
    fun dismissInterrupted()
    fun openPickerFromInterrupted()
    fun resume()
}

enum class CopyStage { Idle, Picking, Running, Finished, RunFailed, ConfirmUndo, Conflicts, Interrupted }

/** The dry-run dialog's content plus what confirming it needs. */
class DryRunView(val ui: DryRunUiState, val request: PlanRequest? = null, val plan: MergePlan? = null)

/** Everything the host composable renders. [picker] is non-null from open until the run produces an outcome. */
data class CopyFlowState(
    val stage: CopyStage = CopyStage.Idle,
    val picker: CopyPagesViewModel? = null,
    val dryRun: DryRunView? = null,
    val request: PlanRequest? = null,
    val plan: MergePlan? = null,
    val result: MergeResult? = null,
    val failure: String? = null,
    val stopping: Boolean = false,
    /** True once the graph that started the run was switched away from. */
    val backgrounded: Boolean = false,
    val conflictsTarget: GraphId? = null,
    val interrupted: InterruptedCopyNotice? = null,
    val interruptedCopy: InterruptedCopy? = null,
)

/**
 * Sequences picker -> dry run -> progress -> result -> (retry | conflicts | undo) and the launch-time
 * interrupted-copy notice. App-scoped: it outlives the per-graph `GraphContent`, which attaches a
 * [CopyGraphBinding] while composed. Owns its scope. The run itself lives in `CopyRunHost`, so a dialog
 * leaving the screen never cancels it.
 */
@Suppress("TooManyFunctions") // one function per user intent
class CopyFlowController(
    private val services: CopyServices,
    private val graphRegistry: StateFlow<GraphRegistry>,
    private val switchTo: (GraphId) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CopyFlowActions {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, e ->
                if (e !is CancellationException) {
                    notices.trySend("Copy failed: ${e.message ?: e::class.simpleName}")
                    _state.update { it.copy(stage = CopyStage.Idle, dryRun = null) }
                }
            },
    )

    private val _state = MutableStateFlow(CopyFlowState())
    val state: StateFlow<CopyFlowState> = _state.asStateFlow()

    private val notices = Channel<String>(Channel.BUFFERED)

    /** One-line messages for the host's snackbar. */
    val noticeFlow: Flow<String> = notices.receiveAsFlow()

    val progress = services.service.progress

    @Volatile private var binding: CopyGraphBinding? = null
    private var pickerJob: Job? = null
    private var pendingResume: InterruptedCopy? = null

    private val outcomeSink: (CopyRunOutcome) -> Unit = ::onOutcome

    init {
        // A run still going from before this controller existed (Activity recreation): show it, adopt its outcome.
        if (services.runHost.running.value) _state.value = CopyFlowState(stage = CopyStage.Running, backgrounded = true)
        services.runHost.attach(outcomeSink)
    }

    /** Unbinds the UI only: the run and the (host-retained) service keep going and are adopted by the next controller. */
    fun close() {
        services.runHost.detach(outcomeSink)
        notices.close()
        scope.cancel()
        _state.value.picker?.close()
    }

    fun nameOf(id: GraphId): String = graphRegistry.value.graphs.firstOrNull { it.id == id }?.displayName ?: id.value

    private fun graphExists(id: GraphId) = graphRegistry.value.graphs.any { it.id == id }

    // ---- graph binding -----------------------------------------------------------------------

    fun attachGraph(next: CopyGraphBinding) {
        binding = next
        val st = _state.value
        if (st.stage == CopyStage.Conflicts && st.conflictsTarget == next.graphId) return
        pendingResume?.takeIf { it.sourceGraphId == next.graphId.value }?.let {
            pendingResume = null
            resumeInterrupted(it)
        }
    }

    /** The open graph went away: an unstarted picker dies with it; a running copy carries on in the background. */
    fun detachGraph(old: CopyGraphBinding) {
        if (binding !== old) return
        binding = null
        val st = _state.value
        if (st.stage == CopyStage.Running) {
            releasePicker()
            _state.update { it.copy(backgrounded = true) }
        } else if (st.stage == CopyStage.Picking || st.dryRun != null && st.stage == CopyStage.Idle) {
            closeFlow()
        } else {
            releasePicker()
        }
    }

    // ---- picker ------------------------------------------------------------------------------

    /** Opens the picker; [preselect] (the page overflow entry) starts with that page ticked. */
    fun open(preselect: PageUuid? = null) {
        val b = binding ?: return
        val st = _state.value
        if (st.stage == CopyStage.Running || services.runHost.running.value) {
            notices.trySend("A copy is already running")
            return
        }
        if (st.stage != CopyStage.Idle) return
        val picker = CopyPagesViewModel(
            source = b.source,
            activeGraphId = b.graphId,
            graphRegistry = graphRegistry,
            gateway = PageMergeServiceGateway(services.service, b.source),
            probe = services.probe,
            destinationSettings = services.destinationSettings,
        )
        preselect?.let(picker::toggleRow)
        _state.value = CopyFlowState(stage = CopyStage.Picking, picker = picker)
        pickerJob = scope.launch {
            picker.state.map { it.review }.distinctUntilChanged().collect { onReviewState(it) }
        }
    }

    override fun onPickerEvent(event: CopyPagesEvent) {
        when (event) {
            CopyPagesEvent.Closed -> closeFlow()
            CopyPagesEvent.AddGraph -> binding?.let { closeFlow(); it.onAddGraph() }
            is CopyPagesEvent.OpenGraph -> { closeFlow(); switchTo(event.graphId) }
            is CopyPagesEvent.RegrantAccess -> notices.trySend("Re-grant folder access in ${nameOf(event.graphId)}'s settings")
            CopyPagesEvent.SwitchToPull -> notices.trySend("Copying from another graph is not available on this device yet")
        }
    }

    private fun onReviewState(review: ReviewState) {
        _state.update { st ->
            if (st.stage != CopyStage.Picking) return@update st
            when (review) {
                ReviewState.Idle -> st
                ReviewState.Planning -> st.copy(dryRun = DryRunView(DryRunUiState.Checking(0, 0)))
                is ReviewState.Failed -> st.copy(dryRun = DryRunView(DryRunUiState.PlanFailed(review.message)))
                is ReviewState.Ready -> st.copy(dryRun = readyView(review.request, review.plan, stale = false))
            }
        }
    }

    private fun readyView(request: PlanRequest, plan: MergePlan, stale: Boolean): DryRunView =
        if (!graphExists(request.targetGraphId)) {
            DryRunView(DryRunUiState.TargetGone)
        } else {
            DryRunView(DryRunUiState.Ready(plan.summary, plan.closure, plan.conflictDetails, stale), request, plan)
        }

    /** Tears the picker down and leaves the rest of the state alone (a run may still be using it). */
    private fun releasePicker() {
        pickerJob?.cancel()
        pickerJob = null
        _state.value.picker?.close()
        _state.update { it.copy(picker = null) }
    }

    /** Esc/Back on the picker with nothing to lose, or any "leave the flow" exit. */
    override fun closeFlow() {
        releasePicker()
        _state.update { it.copy(stage = CopyStage.Idle, dryRun = null) }
    }

    // ---- dry run -----------------------------------------------------------------------------

    override fun dryRunBack() {
        val picker = _state.value.picker
        if (picker != null) {
            picker.consumeReview()
            _state.update { it.copy(dryRun = null) }
        } else {
            _state.update { it.copy(stage = CopyStage.Idle, dryRun = null) }
        }
    }

    override fun dryRunRetry() {
        _state.value.picker?.review()
    }

    /** "Choose another destination" from a vanished target: back to the picker, selection intact. */
    override fun dryRunChooseAnother() = dryRunBack()

    override fun dryRunConfirm() {
        val st = _state.value
        val view = st.dryRun ?: return
        val ready = view.ui as? DryRunUiState.Ready ?: return
        val request = view.request ?: return
        val plan = view.plan ?: return
        if (!graphExists(request.targetGraphId)) {
            _state.update { it.copy(dryRun = DryRunView(DryRunUiState.TargetGone)) }
            return
        }
        st.picker?.recordDestinationConfirmed()
        if (!ready.stale) {
            startRun(request, plan)
            return
        }
        // The user has now seen the recomputed counts; plan again so the fingerprint is fresh.
        val source = binding?.source ?: return
        scope.launch {
            services.service.plan(request, source).fold(
                { e -> _state.update { it.copy(dryRun = DryRunView(DryRunUiState.PlanFailed(e.message))) } },
                { fresh -> startRun(request, fresh) },
            )
        }
    }

    // ---- run ---------------------------------------------------------------------------------

    private fun startRun(request: PlanRequest, plan: MergePlan) {
        _state.update { it.copy(stage = CopyStage.Running, dryRun = null, request = request, plan = plan, stopping = false) }
        if (!services.runHost.start(services.service, plan, ::onOutcome)) {
            notices.trySend("A copy is already running")
            _state.update { it.copy(stage = if (it.picker != null) CopyStage.Picking else CopyStage.Idle) }
        }
    }

    override fun stop() {
        _state.update { it.copy(stopping = true) }
        services.runHost.stop(services.service)
    }

    private fun onOutcome(outcome: CopyRunOutcome) {
        when (outcome) {
            is CopyRunOutcome.Crashed -> fail("Copy stopped unexpectedly (${outcome.message}). It can be resumed next time the app starts.")
            is CopyRunOutcome.Finished -> outcome.result.fold(
                { failure -> onApplyFailure(failure) },
                { result -> finish(result) },
            )
        }
    }

    private fun onApplyFailure(failure: ApplyFailure) {
        when (failure) {
            is ApplyFailure.PlanStale -> restoreAfterStale(failure)
            is ApplyFailure.Failed -> fail(failure.error.message)
            is ApplyFailure.StagingFailed -> fail(failure.message)
            ApplyFailure.Busy -> fail("Another copy is already running.")
            ApplyFailure.UnknownPlan -> fail("This copy plan expired. Review and try again.")
        }
    }

    private fun restoreAfterStale(failure: ApplyFailure.PlanStale) {
        val st = _state.value
        val request = st.request ?: return fail("Things changed. Review and try again.")
        val plan = st.plan ?: return fail("Things changed. Review and try again.")
        val stale = DryRunUiState.Ready(failure.recomputed, plan.closure, plan.conflictDetails, stale = true)
        _state.update {
            it.copy(
                stage = if (it.picker != null) CopyStage.Picking else CopyStage.Idle,
                dryRun = DryRunView(stale, request, plan),
            )
        }
    }

    private fun finish(result: MergeResult) {
        val st = _state.value
        releasePicker()
        if (st.backgrounded) {
            notices.trySend(st.plan?.let { "Copy to ${nameOf(GraphId(it.targetGraphId))} finished" } ?: "Copy finished")
        }
        _state.update { it.copy(stage = CopyStage.Finished, result = result, backgrounded = false, stopping = false, failure = null) }
    }

    private fun fail(reason: String) {
        releasePicker()
        _state.update { it.copy(stage = CopyStage.RunFailed, failure = reason, backgrounded = false, stopping = false) }
    }

    // ---- result ------------------------------------------------------------------------------

    override fun done() {
        val st = _state.value
        val result = st.result
        val target = st.request?.targetGraphId
        if (result != null && target != null && result.newPages + result.combinedPages > 0) {
            notices.trySend("Copied ${CopyDialogStrings.pages(result.newPages + result.combinedPages)} to ${nameOf(target)}")
        }
        _state.value = CopyFlowState()
    }

    override fun retryFailedPages() {
        _state.update { it.copy(stage = CopyStage.Running, stopping = false) }
        if (!services.runHost.retryFailed(services.service, ::onOutcome)) {
            notices.trySend("A copy is already running")
            _state.update { it.copy(stage = CopyStage.Finished) }
        }
    }

    /** After a Stop: plan again with the same selection (idempotent) and run it. */
    override fun continueStopped() {
        val st = _state.value
        val request = st.request ?: return
        val b = binding
        if (b == null || b.graphId != request.sourceGraphId) {
            notices.trySend("Open ${nameOf(request.sourceGraphId)} to continue this copy")
            return
        }
        scope.launch {
            services.service.plan(request, b.source).fold(
                { e -> fail(e.message) },
                { plan -> startRun(request, plan) },
            )
        }
    }

    /** "Retry" after a total failure: re-plan the same request and run it. */
    override fun runFailedRetry() = continueStopped()

    override fun runFailedChooseAnother() {
        _state.value = CopyFlowState()
        open()
    }

    // ---- undo --------------------------------------------------------------------------------

    override fun requestUndo() = _state.update { it.copy(stage = CopyStage.ConfirmUndo) }

    override fun cancelUndo() = _state.update { it.copy(stage = CopyStage.Finished) }

    override fun confirmUndo() {
        val st = _state.value
        val mergeId = st.result?.mergeId ?: return
        val target = st.request?.targetGraphId
        scope.launch {
            val message = when (val r = services.undo.undo(MergeId(mergeId))) {
                is UndoResult.Unavailable -> when (r.reason) {
                    UndoUnavailableReason.Expired -> "This copy can no longer be undone (older than 7 days)."
                    UndoUnavailableReason.NotFound -> "This copy can no longer be undone."
                }
                is UndoResult.Done -> undoMessage(r, target)
            }
            notices.trySend(message)
            _state.value = CopyFlowState()
        }
    }

    private fun undoMessage(r: UndoResult.Done, target: GraphId?): String {
        val base = "Undid copy: removed ${CopyDialogStrings.pages(r.filesDeleted)}, ${r.blocksRemoved} blocks."
        return if (r.issues.isEmpty()) base else "$base ${r.issues.size} left in place" +
            (target?.let { " in ${nameOf(it)}" }.orEmpty()) + " (edited since the copy or couldn't be reverted)."
    }

    // ---- conflicts ---------------------------------------------------------------------------

    /** Conflict flags live in the target's database, so the target must be the open graph. */
    override fun reviewConflicts() {
        val target = _state.value.request?.targetGraphId ?: return
        _state.update { it.copy(stage = CopyStage.Conflicts, conflictsTarget = target) }
        if (binding?.graphId != target) switchTo(target)
    }

    override fun closeConflicts() {
        _state.value = CopyFlowState()
    }

    // ---- interrupted copies ------------------------------------------------------------------

    /** App start: offers Resume/Dismiss for a copy whose manifest never completed. */
    fun checkInterrupted() {
        if (_state.value.stage != CopyStage.Idle) return
        scope.launch {
            val found = withContext(PlatformDispatcher.IO) { interruptedCopies(services.manifests).firstOrNull() } ?: return@launch
            val staging = withContext(PlatformDispatcher.IO) {
                MergeStagingDirectory.open(services.fileSystem, services.appDataDir, MergeId(found.mergeId))
            }
            val total = staging?.pageCount()
            val problem = when {
                !graphExists(GraphId(found.targetGraphId)) -> InterruptedProblem.TargetGone
                staging == null || total == 0 || !graphExists(GraphId(found.sourceGraphId)) -> InterruptedProblem.StagingGone
                else -> null
            }
            val notice = InterruptedCopyNotice(nameOf(GraphId(found.targetGraphId)), found.pagesCopied, total, problem)
            _state.update { if (it.stage == CopyStage.Idle) it.copy(stage = CopyStage.Interrupted, interrupted = notice, interruptedCopy = found) else it }
        }
    }

    override fun dismissInterrupted() {
        val found = _state.value.interruptedCopy
        _state.value = CopyFlowState()
        if (found != null) {
            scope.launch(PlatformDispatcher.IO) { services.manifests.writerFor(MergeId(found.mergeId))?.complete() }
            notices.trySend(CopyDialogStrings.DISMISS_SNACKBAR)
        }
    }

    override fun openPickerFromInterrupted() {
        dismissInterrupted()
        open()
    }

    override fun resume() {
        val found = _state.value.interruptedCopy ?: return
        _state.value = CopyFlowState()
        if (binding?.graphId?.value == found.sourceGraphId) {
            resumeInterrupted(found)
        } else {
            pendingResume = found
            switchTo(GraphId(found.sourceGraphId))
        }
    }

    /** Re-plans the staged page names against the open source graph, then shows the dry run (S4). */
    private fun resumeInterrupted(found: InterruptedCopy) {
        val b = binding ?: return
        scope.launch {
            val names = withContext(PlatformDispatcher.IO) { stagedNames(found.mergeId) }
            val uuids = LinkedHashSet<PageUuid>()
            for (chunk in names.chunked(RESUME_LOOKUP_CHUNK)) {
                b.pagesByNames(chunk).fold(
                    { e -> notices.trySend("Couldn't resume: ${e.message}"); return@launch },
                    { pages -> pages.forEach { uuids += it.uuid } },
                )
            }
            val request = PlanRequest(
                selection = PageSelection(include = uuids),
                sourceGraphId = GraphId(found.sourceGraphId),
                targetGraphId = GraphId(found.targetGraphId),
                sourceGraphName = nameOf(GraphId(found.sourceGraphId)),
            )
            _state.update { it.copy(dryRun = DryRunView(DryRunUiState.Checking(0, 0))) }
            services.service.plan(request, b.source).fold(
                { e -> _state.update { it.copy(dryRun = DryRunView(DryRunUiState.PlanFailed(e.message))) } },
                { plan -> _state.update { it.copy(dryRun = readyView(request, plan, stale = false)) } },
            )
        }
    }

    private fun stagedNames(mergeId: String): List<String> =
        MergeStagingDirectory.open(services.fileSystem, services.appDataDir, MergeId(mergeId))
            ?.readAll()?.mapNotNull { it.getOrNull()?.name }?.toList().orEmpty()

    private companion object {
        const val RESUME_LOOKUP_CHUNK = 500
    }
}
