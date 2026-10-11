package dev.stapler.stelekit.platform.ml

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.ui.annotate.DepthModelUiState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads the Depth Anything V2 ViT-S ONNX model on first use via Android's [DownloadManager].
 *
 * Using [DownloadManager] (system service) rather than OkHttp/Ktor ensures the download survives
 * process death — the system service continues the transfer even if the app is backgrounded.
 *
 * Model is stored at [Context.filesDir]/models/depth_anything_v2_small.onnx so the path is
 * stable across restarts (unlike cacheDir, which can be cleared by the OS).
 *
 * @param context application context
 */
class DepthModelDownloader(private val context: Context) {

    private val modelFile: File =
        File(context.filesDir, "models/depth_anything_v2_small.onnx")

    private val _modelState = MutableStateFlow(resolveInitialState())
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** Current in-flight [DownloadManager] enqueue ID, or -1 when idle. */
    private var activeDownloadId: Long = -1L

    /** The polling loop's [Job], stored so cancel/completion paths can stop it explicitly. */
    private var pollingJob: Job? = null

    /**
     * The completion [BroadcastReceiver] for the current in-flight download, if any — stored so
     * [cancelDownload] and the stall-timeout path can unregister it explicitly instead of leaking
     * a registered receiver whenever a transfer ends by any path other than its own `onReceive`.
     */
    private var activeReceiver: BroadcastReceiver? = null

    private val logger = Logger("DepthModelDownloader")

    /**
     * Instance-owned scope, living as long as this class's own singleton lifetime (see
     * [OnnxMonocularDepthEstimator], which is process-lifetime via `SensorModule`) — NOT tied to
     * any single [downloadModel] caller's coroutine, so polling survives screen navigation
     * (ADR-001).
     */
    private val scope = CoroutineScope(
        SupervisorJob() +
            PlatformDispatcher.IO +
            CoroutineExceptionHandler { _, e -> logger.warn("Uncaught in DepthModelDownloader scope", e) },
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ensure the model file is present.
     *
     * If the file already exists and passes the sanity-size check (> 10 MB), transitions to
     * [ModelState.Ready] and returns it immediately. Otherwise ensures a download is in flight
     * (enqueuing one if none is — reattaching to an existing one instead of double-enqueuing) and
     * suspends until it reaches a terminal state.
     *
     * The download itself — enqueue, completion [BroadcastReceiver], and progress polling — lives
     * entirely on [scope], not on this call's coroutine (ADR-001): navigating away (which cancels
     * whatever coroutine called this) only stops *waiting* for the result, it does not touch the
     * in-flight transfer. A later call (e.g. after navigating back) reattaches to the same
     * transfer via the same [awaitTerminal] path used here.
     *
     * Must be called from a coroutine (suspend function). Safe to call multiple times.
     */
    suspend fun downloadModel(): Either<DomainError, File> {
        // Fast path: model already present and non-corrupt.
        if (isModelReady()) {
            _modelState.value = ModelState.Ready
            return modelFile.right()
        }

        if (activeDownloadId == -1L) {
            enqueueDownload()
        } else {
            logger.info("downloadModel() called while a download is already in flight — reattaching")
        }

        return awaitTerminal()
    }

    /**
     * Explicitly cancel an in-progress download: stops polling, unregisters the completion
     * receiver, removes the [DownloadManager] request, deletes any partial file, and resets state
     * to [ModelState.Absent] — which [awaitTerminal] treats as a valid (cancelled) resolution for
     * any caller currently suspended in [downloadModel] (ADR-001).
     *
     * Distinct from ordinary coroutine cancellation — this is the user-initiated path triggered by
     * the UI's Cancel button, not incidental teardown from navigating away.
     */
    fun cancelDownload() {
        if (activeDownloadId == -1L) return
        pollingJob?.cancel()
        runCatching { activeReceiver?.let { context.unregisterReceiver(it) } }
        activeReceiver = null
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.remove(activeDownloadId)
        runCatching { modelFile.delete() } // belt-and-suspenders — guards an async-deletion race
        activeDownloadId = -1L
        _modelState.value = ModelState.Absent
    }

    /**
     * Enqueues a new [DownloadManager] request, starts progress polling, and registers the
     * completion receiver — all state mutation here happens synchronously against [scope]-owned
     * fields, not inside a per-caller continuation, so it survives whichever coroutine happened to
     * call [downloadModel].
     */
    private fun enqueueDownload() {
        modelFile.parentFile?.mkdirs()
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(MODEL_URL)).apply {
            setTitle("Depth model")
            setDescription("Downloading depth estimation model (~100 MB)")
            setDestinationUri(Uri.fromFile(modelFile))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }

        val downloadId = downloadManager.enqueue(request)
        activeDownloadId = downloadId
        pollingJob = startPolling(downloadId, downloadManager)
        _modelState.value = ModelState.Downloading(progress = 0)

        // BroadcastReceiver — fires when this download (or any other) completes.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val completedId =
                    intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (completedId != downloadId) return // not our download

                runCatching { context.unregisterReceiver(this) }
                activeReceiver = null
                pollingJob?.cancel()
                activeDownloadId = -1L

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                val succeeded = cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        statusCol >= 0 && c.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL
                    } else false
                } ?: false

                _modelState.value = if (succeeded && isModelReady()) {
                    ModelState.Ready
                } else {
                    ModelState.Failed()
                }
            }
        }
        activeReceiver = receiver

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Suspends until the current download reaches a terminal state, then maps it to a result.
     * [ModelState.Absent] is a valid terminal here (not just [ModelState.Ready]/[ModelState.Failed])
     * so that [cancelDownload] — which sets [ModelState.Absent] — resolves any caller currently
     * suspended here instead of hanging forever.
     */
    private suspend fun awaitTerminal(): Either<DomainError, File> =
        when (
            val terminal = modelState.first {
                it is ModelState.Ready || it is ModelState.Failed || it is ModelState.Absent
            }
        ) {
            is ModelState.Ready -> modelFile.right()
            is ModelState.Failed -> DomainError.SensorError.HardwareUnavailable(
                terminal.reason ?: "Depth model download failed",
            ).left()
            is ModelState.Absent -> DomainError.SensorError.HardwareUnavailable(
                "Depth model download cancelled",
            ).left()
            else -> error("unreachable — filtered to Ready/Failed/Absent above")
        }

    /** Absolute path of the model file. Safe to pass to [ai.onnxruntime.OrtSession]. */
    fun modelFilePath(): String = modelFile.absolutePath

    /** True when the model file exists on disk and exceeds the minimum sanity size. */
    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE_BYTES

    // ── Polling ───────────────────────────────────────────────────────────────

    /**
     * Polls [DownloadManager] for byte progress every [POLL_INTERVAL_MS] while [downloadId] is
     * in flight, emitting real [ModelState.Downloading] progress instead of a frozen snapshot.
     * Runs on [scope] — a sibling of the calling coroutine, not a child — so it survives the
     * caller's cancellation (e.g. screen navigation) and is stopped only by [cancelDownload],
     * a terminal [DownloadManager] status, or the stall timeout below.
     */
    private fun startPolling(downloadId: Long, downloadManager: DownloadManager): Job = scope.launch {
        var lastBytes = -1L
        var lastProgressAt = System.currentTimeMillis()
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            val query = DownloadManager.Query().setFilterById(downloadId)
            val (bytes, total, status) = downloadManager.query(query)?.use { c ->
                if (!c.moveToFirst()) return@use Triple(-1L, -1L, -1)
                val bytesIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (bytesIdx < 0 || totalIdx < 0 || statusIdx < 0) return@use Triple(-1L, -1L, -1)
                Triple(c.getLong(bytesIdx), c.getLong(totalIdx), c.getInt(statusIdx))
            } ?: Triple(-1L, -1L, -1)

            val bytesAdvanced = bytes != lastBytes
            if (bytesAdvanced) lastProgressAt = System.currentTimeMillis()
            when (
                val decision = decidePollTick(
                    bytes = bytes,
                    total = total,
                    status = status,
                    bytesAdvanced = bytesAdvanced,
                    stalled = hasStalled(lastProgressAt, System.currentTimeMillis()),
                )
            ) {
                is PollDecision.Terminal -> break // BroadcastReceiver remains sole authority.
                is PollDecision.Stalled -> {
                    logger.warn("Download stalled for ${STALL_TIMEOUT_MS}ms (status=$status) — cancelling")
                    runCatching { activeReceiver?.let { context.unregisterReceiver(it) } }
                    activeReceiver = null
                    downloadManager.remove(downloadId)
                    runCatching { modelFile.delete() }
                    activeDownloadId = -1L
                    _modelState.value = ModelState.Failed(reason = "This is taking longer than expected.")
                    break
                }
                is PollDecision.Progress -> _modelState.value = ModelState.Downloading(progress = decision.percent)
            }
            lastBytes = bytes
        }
    }

    // ── Sealed state hierarchy ────────────────────────────────────────────────

    /** Download lifecycle state exposed as [StateFlow]. */
    sealed interface ModelState {
        /** No model file on disk. */
        data object Absent : ModelState

        /** Download in progress — [progress] is 0–100, or -1 if indeterminate. */
        data class Downloading(val progress: Int) : ModelState

        /** Model file present and verified. */
        data object Ready : ModelState

        /** Download failed or model file corrupt. [reason] carries plain-language copy when set. */
        data class Failed(val reason: String? = null) : ModelState
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolveInitialState(): ModelState =
        if (isModelReady()) ModelState.Ready else ModelState.Absent

    companion object {
        const val MODEL_URL =
            "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"

        /** Sanity threshold: a valid model must be larger than 10 MB. */
        private const val MIN_MODEL_SIZE_BYTES = 10L * 1024 * 1024
    }
}

/** Poll interval for [DepthModelDownloader]'s progress-polling loop. */
private const val POLL_INTERVAL_MS = 300L

/**
 * No-byte-movement threshold before a [DepthModelDownloader.ModelState.Downloading] transfer is
 * treated as stalled — matches `SafChangeDetector`'s existing 30s poll-tolerance constant used
 * elsewhere in this codebase as the "reasonable slow network" reference point.
 */
internal const val STALL_TIMEOUT_MS = 30_000L

/** Explicit 1:1 mapping — kept as two separate types (see ADR-001/Pattern Decisions). */
fun DepthModelDownloader.ModelState.toUiState(): DepthModelUiState =
    when (this) {
        is DepthModelDownloader.ModelState.Absent -> DepthModelUiState.Absent
        is DepthModelDownloader.ModelState.Downloading -> DepthModelUiState.Downloading(progress)
        is DepthModelDownloader.ModelState.Ready -> DepthModelUiState.Ready
        is DepthModelDownloader.ModelState.Failed -> DepthModelUiState.Failed(reason)
    }

/** Pure — testable without mocking [DownloadManager]/`Cursor`. -1 for an unknown total size. */
internal fun computeProgressPercent(bytesDownloaded: Long, totalBytes: Long): Int =
    if (totalBytes <= 0L) -1 else ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)

/** Pure — testable without mocking [DownloadManager]/`Cursor`. */
internal fun hasStalled(lastProgressAt: Long, now: Long, timeoutMs: Long = STALL_TIMEOUT_MS): Boolean =
    (now - lastProgressAt) > timeoutMs

/** Outcome of one polling tick — see [decidePollTick]. */
internal sealed interface PollDecision {
    /** Emit [ModelState.Downloading] with this percent (already run through [computeProgressPercent]). */
    data class Progress(val percent: Int) : PollDecision

    /** No byte movement for longer than the stall timeout — transition to [ModelState.Failed]. */
    data object Stalled : PollDecision

    /** [DownloadManager] reports a terminal status — stop polling; the BroadcastReceiver owns the transition. */
    data object Terminal : PollDecision
}

/**
 * Pure decision logic for one polling tick, extracted so the actual bug-prone branching (advance
 * vs. stall vs. terminal) is unit-testable without mocking [DownloadManager]/`Cursor` or waiting
 * on a real coroutine polling loop's `delay()`.
 */
internal fun decidePollTick(
    bytes: Long,
    total: Long,
    status: Int,
    bytesAdvanced: Boolean,
    stalled: Boolean,
): PollDecision = when {
    status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED ->
        PollDecision.Terminal
    !bytesAdvanced && stalled -> PollDecision.Stalled
    else -> PollDecision.Progress(computeProgressPercent(bytes, total))
}
