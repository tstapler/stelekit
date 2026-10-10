package dev.stapler.stelekit.capture

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of one drain append. Maps 1:1 from the journal appender's outcome (Appended / AlreadyPresent / Queued / Failed). */
sealed interface DrainAppendResult {
    data object Appended : DrainAppendResult
    data object AlreadyPresent : DrainAppendResult

    /** Target not writable right now; keep the item and stop this pass. */
    data class Retry(val reason: String) : DrainAppendResult

    /** This item failed; keep it and continue with the next. */
    data class Failed(val reason: String) : DrainAppendResult
}

/**
 * Appends one queued share to [graphId]'s journal. Must be idempotent per [captureId] (deterministic
 * block uuid), so a replay after a crash reports [DrainAppendResult.AlreadyPresent].
 */
fun interface InboxAppender {
    suspend fun append(graphId: GraphId, content: ShareContent, captureId: String): DrainAppendResult
}

enum class RetryResult { Drained, Kept, NotReady, NotFound }

/**
 * Drains [ShareInbox] when a graph becomes ready. Fires only when [readyGraphId] (from
 * `GraphManager.readyGraph.map { it?.id }`; [currentReadyId] is `{ graphManager.readyGraphId }`) equals the item's graph AND [awaitPendingMigration] has
 * returned for it, never on the registry's `activeGraphId` flip. Takes no `GraphWriteLock`: the
 * appender (the router) takes it after readiness, so this never holds a lock while awaiting.
 *
 * Also re-keys the UNASSIGNED slot to the first registered graph ([registeredGraphs], ADR-004).
 * Owns its scope; call [start] once and [close] to stop.
 */
class ShareInboxDrain(
    private val inbox: ShareInbox,
    private val readyGraphId: Flow<GraphId?>,
    private val awaitPendingMigration: suspend () -> Unit,
    private val currentReadyId: () -> GraphId?,
    private val appender: InboxAppender,
    private val registeredGraphs: Flow<List<GraphId>>? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val retryBackoffMs: List<Long> = DEFAULT_RETRY_BACKOFF_MS,
) {
    private val logger = Logger("ShareInboxDrain")
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) logger.error("share inbox drain failed: ${e::class.simpleName}")
        },
    )
    private val drainMutex = Mutex()
    private var currentReady: GraphId? = null
    private var started = false
    private var retryJob: Job? = null
    private var retryAttempt = 0

    fun start() {
        if (started) return
        started = true
        scope.launch {
            inbox.recover()
            registeredGraphs?.let { graphs -> launch { graphs.collectLatest(::rekeyToFirst) } }
            readyGraphId.distinctUntilChanged().collectLatest(::onReady)
        }
    }

    private suspend fun rekeyToFirst(graphs: List<GraphId>) {
        val first = graphs.firstOrNull() ?: return
        val moved = inbox.rekeyUnassigned(first).getOrNull() ?: 0
        if (moved > 0 && currentReady == first) drain(first)
        else currentReady?.let { if (retryJob != null) drain(it) } // a registry change may unblock a stalled pass
    }

    private suspend fun onReady(id: GraphId?) {
        currentReady = null
        cancelRetry()
        if (id == null) return
        awaitPendingMigration()
        if (currentReadyId() != id) return
        currentReady = id
        drain(id)
    }

    fun close() = scope.cancel()

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
    }

    /** A pass that stopped on Retry gets a bounded number of timed re-runs; a ready or registry change also re-runs it. */
    private fun scheduleRetry(graph: GraphId) {
        val delayMs = retryBackoffMs.getOrNull(retryAttempt) ?: return
        retryAttempt++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (currentReady == graph) drain(graph)
        }
    }

    /** Rescue: try one item now. Only meaningful when its graph is ready and migrated. */
    suspend fun retryNow(slot: InboxSlot, captureId: String): RetryResult {
        val graph = (slot as? InboxSlot.Graph)?.id ?: return RetryResult.NotReady
        if (currentReady != graph) return RetryResult.NotReady
        val item = inbox.state.value.items.firstOrNull { it.slot == slot && it.captureId == captureId }
            ?: return RetryResult.NotFound
        return drainMutex.withLock { if (attempt(graph, item) == Attempt.Done) RetryResult.Drained else RetryResult.Kept }
    }

    private suspend fun drain(graph: GraphId) {
        val stopped = drainMutex.withLock {
            val items = inbox.state.value.items.filter { it.slot == InboxSlot.Graph(graph) }
            items.any { attempt(graph, it) == Attempt.Stop }
        }
        if (stopped) {
            scheduleRetry(graph)
        } else {
            cancelRetry()
        }
    }

    private enum class Attempt { Done, Kept, Stop }

    private suspend fun attempt(graph: GraphId, item: InboxItem): Attempt {
        if (item.status !is InboxItemStatus.Ready) return Attempt.Kept
        val content = inbox.readContent(item.slot, item.captureId).getOrNull()
        if (content == null) {
            inbox.recordAttempt(item.slot, item.captureId, "share could not be read")
            return Attempt.Kept
        }
        val result = try {
            appender.append(graph, content, item.captureId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DrainAppendResult.Failed(e.message ?: "append threw")
        }
        return when (result) {
            DrainAppendResult.Appended, DrainAppendResult.AlreadyPresent -> {
                inbox.remove(item.slot, item.captureId)
                Attempt.Done
            }
            is DrainAppendResult.Retry -> {
                inbox.recordAttempt(item.slot, item.captureId, result.reason)
                Attempt.Stop
            }
            is DrainAppendResult.Failed -> {
                inbox.recordAttempt(item.slot, item.captureId, result.reason)
                Attempt.Kept
            }
        }
    }
}

private val DEFAULT_RETRY_BACKOFF_MS = listOf(30_000L, 60_000L, 120_000L, 300_000L)
