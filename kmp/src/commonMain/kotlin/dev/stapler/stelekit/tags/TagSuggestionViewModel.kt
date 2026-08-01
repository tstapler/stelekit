package dev.stapler.stelekit.tags

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

sealed interface BulkScanState {
    data object Idle : BulkScanState
    data class Scanning(val done: Int, val total: Int) : BulkScanState
    data class Complete(val found: Int) : BulkScanState
}

data class JournalScanEntry(
    val pageUuid: String,
    /** First non-empty block — where accepted tags are appended. */
    val targetBlockUuid: String,
    /** Block content at scan time — staleness re-check on accept. */
    val contentSnapshot: String,
    /** All blocks joined — LLM prompt context. */
    val fullContent: String,
    val alreadyLinked: Set<String>,
    val graphId: String,
)

class TagSuggestionViewModel(
    private val engine: TagSuggestionEngine,
    private val onPropose: ((PendingLlmSuggestion) -> Unit)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pollDeadlineMs: Long = TagAvailabilityPoller.DEFAULT_POLL_DEADLINE_MS,
    private val pollIntervalMs: Long = TagAvailabilityPoller.DEFAULT_POLL_INTERVAL_MS,
    private val pollEscalationThresholdMs: Long = TagAvailabilityPoller.CAPTION_ESCALATION_THRESHOLD_MS,
) {
    private val logger = Logger("TagSuggestionViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher +
        CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                logger.error("Uncaught error: ${e::class.simpleName}: ${e.message}")
                _state.value = TagSuggestionState.Error(e.message ?: "Unknown error")
            }
        }
    )

    private val _state = MutableStateFlow<TagSuggestionState>(TagSuggestionState.Idle)
    val state: StateFlow<TagSuggestionState> = _state.asStateFlow()

    private var suggestionJob: Job? = null
    // UUID of the block the current suggestionJob is running for (null = no job).
    private var activeBlockUuid: String? = null
    // Results cache keyed by block UUID. Survives dismiss() so the LLM can finish in the
    // background and the sheet shows instantly on reopen.
    private val cache = mutableMapOf<String, TagSuggestionState.Ready>()

    private var scanJob: Job? = null
    private val _scanState = MutableStateFlow<BulkScanState>(BulkScanState.Idle)
    val scanState: StateFlow<BulkScanState> = _scanState.asStateFlow()

    /** True when an LLM provider is wired — controls scan button visibility. */
    val hasLlmProvider: Boolean get() = engine.hasLlmProvider

    /**
     * Session-scoped "when did this VM instance first observe the on-device model as
     * not-yet-available" timestamp (pre-mortem P1 #1/#2 fix). Set ONCE by [runLlmSuggest]
     * the first time a retryable-unavailable signal is observed; NEVER reset by a
     * block-switch or [retryLastRequest]; only cleared back to null when [engine].llmSuggest()
     * actually succeeds.
     */
    private var downloadFirstObservedAtMs: Long? = null

    /** Test-only accessor — lets tests assert the suggestionJob coroutine itself terminates on
     * its own once the poll deadline elapses, without weakening suggestionJob's private visibility. */
    internal val isSuggestionJobActiveForTest: Boolean
        get() = suggestionJob?.isActive == true

    private data class LastRequest(
        val blockUuid: String,
        val blockContent: String,
        val alreadyLinkedTerms: Set<String>,
        val allowPolling: Boolean,
    )
    private var lastRequest: LastRequest? = null

    /** Warm up the on-device model. Called at app start so first real request is never cold. */
    fun preload() {
        scope.launch { engine.preload() }
    }

    /**
     * Single call site for both requestSuggestions() (allowPolling=true) and scanEntries()
     * (allowPolling=false) — FR-7's literal, greppable implementation. Pitfall #2: only
     * TagAvailabilityPoller.pollUntilAvailable's checkAvailability probe is called on every
     * poll tick — engine.llmSuggest() (which calls format(), which can trigger the AICore
     * download) is called at most twice: once for the first attempt, once more after Available
     * is observed.
     *
     * Pre-mortem P1 #1/#2: [downloadFirstObservedAtMs] is set once (never reset by a relaunch)
     * and threaded into pollUntilAvailable as startedAtOverride, so a block-switch-and-return or
     * a manual retry resumes the existing elapsed-time budget instead of restarting the
     * escalation/deadline clock from zero.
     */
    private suspend fun runLlmSuggest(
        blockContent: String,
        alreadyLinkedTerms: Set<String>,
        allowPolling: Boolean,
        onStatusUpdate: (LlmSuggestionStatus) -> Unit,
    ): Either<DomainError, List<TagSuggestion>> {
        val firstAttempt = engine.llmSuggest(blockContent, alreadyLinkedTerms)
        if (firstAttempt is Either.Right) {
            // Model actually produced a result — the download (if any was in flight) is over.
            // Clear the session-scoped tracking so a *future* stall starts a fresh clock rather
            // than inheriting this resolved cycle's origin.
            downloadFirstObservedAtMs = null
            return firstAttempt
        }
        if (!allowPolling) return firstAttempt

        val probe = engine.checkAvailability ?: return firstAttempt
        val failure = (firstAttempt as Either.Left).value as? DomainError.NetworkError.RequestFailed
        if (failure == null || !failure.retryable) return firstAttempt

        // Pre-mortem P1 #1/#2 fix: set ONCE per VM lifetime, the first time a retryable signal
        // is observed; a later relaunch (block-switch-and-return, manual retry) reuses this same
        // value rather than overwriting it with "now".
        val observedAt = downloadFirstObservedAtMs
            ?: Clock.System.now().toEpochMilliseconds().also { downloadFirstObservedAtMs = it }
        val elapsedSoFar = Clock.System.now().toEpochMilliseconds() - observedAt

        // AC0: initial "Downloading..." caption is the SDK-sourced reason string already
        // produced by format() — reused verbatim — UNLESS this is a resumed poll that's already
        // past the escalation threshold, in which case show the escalated caption immediately
        // rather than a cold-start string the user has already seen once this session.
        val initialCaption = if (elapsedSoFar >= pollEscalationThresholdMs) {
            TagAvailabilityPoller.ESCALATED_WAIT_CAPTION
        } else {
            failure.message
        }
        onStatusUpdate(LlmSuggestionStatus.Pending(initialCaption))

        val resolved = TagAvailabilityPoller.pollUntilAvailable(
            checkAvailability = probe,
            onStatusUpdate = onStatusUpdate,
            deadlineMs = pollDeadlineMs,
            intervalMs = pollIntervalMs,
            escalationThresholdMs = pollEscalationThresholdMs,
            startedAtOverride = downloadFirstObservedAtMs,
        )
        return when (resolved) {
            is LlmProviderAvailability.Available -> {
                val retried = engine.llmSuggest(blockContent, alreadyLinkedTerms) // AC1: auto re-run
                if (retried is Either.Right) downloadFirstObservedAtMs = null
                retried
            }
            is LlmProviderAvailability.Unavailable ->
                // Note: resolved.reason is threaded through DomainError.NetworkError.RequestFailed.message
                // here but is NOT what the UI displays — LlmSuggestionStatus.Stalled has no
                // message field (only `retryable`); the terminal caption is UI-owned copy.
                DomainError.NetworkError.RequestFailed(resolved.reason, retryable = resolved.retryable).left()
            is LlmProviderAvailability.Preparing ->
                // Unreachable — pollUntilAvailable's contract never returns Preparing — kept
                // for exhaustiveness on the sealed LlmProviderAvailability `when`.
                DomainError.NetworkError.RequestFailed("Taking longer than expected", retryable = true).left()
        }
    }

    fun requestSuggestions(
        blockUuid: String,
        blockContent: String,
        alreadyLinkedTerms: Set<String> = emptySet(),
        allowPolling: Boolean = true,
    ) {
        lastRequest = LastRequest(blockUuid, blockContent, alreadyLinkedTerms, allowPolling)

        val cached = cache[blockUuid]
        if (cached != null) {
            _state.value = cached
            val activelyRunning = activeBlockUuid == blockUuid && cached.llmStatus is LlmSuggestionStatus.Pending
            if (activelyRunning) return
            val terminal = cached.llmStatus == LlmSuggestionStatus.Resolved ||
                (cached.llmStatus as? LlmSuggestionStatus.Failed)?.retryable == false
            if (terminal) return
            // NotStarted, Stalled, retryable Failed, or a Pending job that was cancelled
            // (block switch) all fall through to re-run — this is also the FR-3 retry path.
        }

        // Cancel the previous job only if it's for a different block.
        suggestionJob?.cancel()
        activeBlockUuid = blockUuid

        suggestionJob = scope.launch {
            // GAP-003 fix: emit local matches immediately so chips appear without waiting for LLM.
            val localSuggestions = engine.directMatch(blockContent)
            val initial = TagSuggestionState.Ready(
                blockUuid = blockUuid,
                localSuggestions = localSuggestions,
                llmSuggestions = emptyList(),
                llmStatus = if (engine.hasLlmProvider) LlmSuggestionStatus.Pending() else LlmSuggestionStatus.Resolved,
            )
            cache[blockUuid] = initial
            _state.value = initial

            val onStatusUpdate: (LlmSuggestionStatus) -> Unit = { status ->
                cache[blockUuid]?.let { cache[blockUuid] = it.copy(llmStatus = status) }
                _state.update { current ->
                    if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) current.copy(llmStatus = status)
                    else current
                }
            }

            runLlmSuggest(blockContent, alreadyLinkedTerms, allowPolling, onStatusUpdate).fold(
                ifLeft = { err ->
                    // Stalled is reserved for the on-device-availability signal specifically.
                    // DomainError.NetworkError.Timeout is a different, also-plausibly-transient
                    // condition (a completed-but-slow network round-trip, not a model-download
                    // wait) and gets its own retryable Failed rather than being folded into
                    // Stalled's "still downloading" framing.
                    val status = when {
                        err is DomainError.NetworkError.RequestFailed && err.retryable ->
                            LlmSuggestionStatus.Stalled(retryable = true)
                        err is DomainError.NetworkError.Timeout ->
                            LlmSuggestionStatus.Failed(message = err.message, retryable = true)
                        else ->
                            LlmSuggestionStatus.Failed(message = err.message, retryable = false)
                    }
                    onStatusUpdate(status)
                },
                ifRight = { llmSuggestions ->
                    cache[blockUuid]?.let {
                        cache[blockUuid] = it.copy(llmSuggestions = llmSuggestions, llmStatus = LlmSuggestionStatus.Resolved)
                    }
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmSuggestions = llmSuggestions, llmStatus = LlmSuggestionStatus.Resolved)
                        } else current
                    }
                }
            )
            activeBlockUuid = null
        }
    }

    /** FR-3 manual-retry call target — re-invokes the most recent requestSuggestions() call. No-op if none yet. */
    fun retryLastRequest() {
        lastRequest?.let { requestSuggestions(it.blockUuid, it.blockContent, it.alreadyLinkedTerms, it.allowPolling) }
    }

    /** Scan a batch of journal entries sequentially, proposing results to the inbox when done. */
    fun scanEntries(entries: List<JournalScanEntry>) {
        if (entries.isEmpty()) return
        scanJob?.cancel()
        _scanState.value = BulkScanState.Scanning(0, entries.size)
        scanJob = scope.launch {
            val proposals = mutableListOf<PendingLlmSuggestion>()
            entries.forEachIndexed { index, entry ->
                _scanState.value = BulkScanState.Scanning(index, entries.size)
                // FR-7: bulk scan never polls — a stalled on-device model must not block the
                // whole scan for up to pollDeadlineMs per entry (Story 4.3, AC7).
                runLlmSuggest(entry.fullContent, entry.alreadyLinked, allowPolling = false) { }.fold(
                    ifLeft = { /* skip — continue to next entry */ },
                    ifRight = { suggestions ->
                        cache[entry.targetBlockUuid] = TagSuggestionState.Ready(
                            blockUuid = entry.targetBlockUuid,
                            localSuggestions = engine.directMatch(entry.fullContent),
                            llmSuggestions = suggestions,
                            llmStatus = LlmSuggestionStatus.Resolved,
                        )
                        if (suggestions.isNotEmpty()) {
                            proposals += PendingLlmSuggestion.TagChange(
                                id = UuidGenerator.generateV7(),
                                graphId = entry.graphId,
                                sourceProviderId = "on-device-tag-suggester",
                                proposedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                                rationale = null,
                                pageUuid = entry.pageUuid,
                                blockUuid = entry.targetBlockUuid,
                                currentContentSnapshot = entry.contentSnapshot,
                                addedTerms = suggestions.map { it.term },
                                removedTerms = emptyList(),
                            )
                        }
                    }
                )
            }
            // Batch-propose so the review screen opens once at the end, not per-entry.
            proposals.forEach { onPropose?.invoke(it) }
            _scanState.value = BulkScanState.Complete(proposals.size)
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _scanState.value = BulkScanState.Idle
    }

    fun resetScan() {
        _scanState.value = BulkScanState.Idle
    }

    fun dismiss() {
        // Do NOT cancel suggestionJob — let the LLM finish in the background and cache the result.
        // The next requestSuggestions() for the same block will serve from cache immediately.
        _state.value = TagSuggestionState.Idle
    }

    fun close() {
        scope.cancel()
    }
}
