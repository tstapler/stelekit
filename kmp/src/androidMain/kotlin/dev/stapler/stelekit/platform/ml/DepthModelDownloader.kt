// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.NetworkType
import androidx.work.WorkInfo
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.jobs.schedule.AndroidNetworkSnapshotProvider
import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshotProvider
import dev.stapler.stelekit.jobs.schedule.OneShotConflict
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler
import dev.stapler.stelekit.jobs.workManagerOrNull
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.ui.annotate.DepthModelUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-facing handle on the Depth Anything V2 ViT-S model download. The transfer is a unique one-shot job
 * ([ModelDownloadTask] under [UNIQUE_WORK_NAME]) enqueued through the scheduling port's `runOnce`, so it survives
 * process death and screen navigation; this class only requests it and mirrors WorkManager's persisted state into
 * [modelState]. A fresh instance after a restart re-attaches to whatever job is still queued or running.
 *
 * Constraints come from the `model-download` entry in [TaskSettingsStore] (never `enabled`), defaulting to the
 * spec's network policy when nothing is stored or the read fails.
 */
class DepthModelDownloader internal constructor(
    private val context: Context,
    scheduler: BackgroundTaskScheduler? = null,
    store: TaskSettingsStore? = null,
    network: NetworkSnapshotProvider? = null,
    private val notificationsOff: () -> Boolean = { areNotificationsOff(context) },
    private val readyCheck: (File) -> Boolean = DepthModelStorage::isReady,
    private val settleTimeoutMs: Long = SETTLE_TIMEOUT_MS,
    private val settlePollMs: Long = SETTLE_POLL_MS,
) {
    constructor(context: Context) : this(context, scheduler = null)

    private val networkProvider: NetworkSnapshotProvider by lazy { network ?: AndroidNetworkSnapshotProvider(context) }

    private val modelFile: File = DepthModelStorage.modelFile(context)
    private val logger = Logger("DepthModelDownloader")

    // Spec-only registry: tags and the store default need the spec, the worker resolves the real registry itself.
    private val specRegistry by lazy { TaskRegistry().also { it.register(ModelDownloadTask.KIND, ModelDownloadTask.spec, ModelDownloadTask(context)) } }
    private val settings by lazy { SteleKitContext.init(context); PlatformSettings() }
    private val taskStore: TaskSettingsStore by lazy {
        store ?: TaskSettingsStore(settings, TaskWorkerRuntime.legacySeed, specRegistry)
    }
    private val taskScheduler: BackgroundTaskScheduler by lazy {
        scheduler ?: WorkManagerTaskScheduler(context, specRegistry, taskStore, AppliedConfigLedger(settings))
    }

    /** True while [downloadModel] waits; a WorkInfo snapshot taken before our enqueue is visible must not read as "cancelled". */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** Bumped by [cancelDownload]; a [downloadModel] that began before a cancel must not outlive it. */
    private val cancelGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private val _modelState = MutableStateFlow(initialState())
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + PlatformDispatcher.IO +
            CoroutineExceptionHandler { _, e -> logger.warn("Uncaught in DepthModelDownloader scope", e) },
    )

    // Declared before `init`: the collector can reach onWorkSucceeded() before the constructor finishes, and a
    // later `= null` initializer would otherwise clobber the job it just stored.
    @Volatile private var settleJob: Job? = null

    init {
        scope.launch {
            val workManager = workManagerOrNull(context) ?: return@launch
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).collect { infos -> apply(infos) }
        }
    }

    /**
     * Ensures the model is present: a no-op when it already is, otherwise enqueues the download (re-attaching to
     * one already queued or running rather than starting a second) and suspends until it ends. Cancelling the
     * caller only stops waiting; the job keeps going.
     */
    suspend fun downloadModel(): Either<DomainError, File> {
        if (isModelReady()) {
            _modelState.value = ModelState.Ready
            return modelFile.right()
        }
        workManagerOrNull(context)
            ?: return DomainError.SensorError.HardwareUnavailable("Background downloads are unavailable right now").left()
        // KEEP: an already queued or running job is left alone; a finished one is replaced by this new run.
        inFlight.incrementAndGet()
        val generation = cancelGeneration.get()
        try {
            taskScheduler.runOnce(ModelDownloadTask.KEY, storedConstraints(), OneShotConflict.Keep)
            // A cancel that landed while runOnce was suspended must not be undone by this late write, and the job
            // runOnce just enqueued is an orphan: cancel it again.
            _modelState.update { current ->
                if (cancelGeneration.get() != generation || current is ModelState.Downloading) current else ModelState.Downloading(progress = -1)
            }
            if (cancelGeneration.get() != generation) {
                workManagerOrNull(context)?.cancelUniqueWork(UNIQUE_WORK_NAME)
                return DomainError.SensorError.HardwareUnavailable("Depth model download cancelled").left()
            }
            return awaitTerminal()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * "Download now anyway": replaces the queued request with one that accepts any network. Per request only; the
     * stored default is untouched, so the next plain request is unmetered again. A caller already waiting in
     * [downloadModel] keeps waiting on the replacement.
     */
    suspend fun downloadNowAnyway() {
        if (isModelReady()) return
        inFlight.incrementAndGet()
        try {
            taskScheduler.runOnce(ModelDownloadTask.KEY, ANYWAY_CONSTRAINTS, OneShotConflict.Replace)
            if (_modelState.value !is ModelState.Downloading) _modelState.value = ModelState.Downloading(progress = -1)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** The user's stored constraints (default from the spec); a failed read never blocks the tap. */
    private suspend fun storedConstraints(): TaskConstraints {
        val resolved = try {
            taskStore.read(ModelDownloadTask.KEY).constraints
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("model-download settings unreadable; using the default", e)
            ModelDownloadTask.defaultConfig.constraints
        }
        return resolved.copy(requiresStorageNotLow = true)
    }

    /** User-initiated cancel: stops the job, drops the partial file and resolves any waiting [downloadModel] caller. */
    fun cancelDownload() {
        cancelGeneration.incrementAndGet()
        settleJob?.cancel()
        workManagerOrNull(context)?.cancelUniqueWork(UNIQUE_WORK_NAME)
        runCatching { File(modelFile.parentFile, "${modelFile.name}.part").delete() }
        if (!isModelReady()) _modelState.value = ModelState.Cancelled
    }

    private suspend fun awaitTerminal(): Either<DomainError, File> =
        when (val terminal = modelState.first { it is ModelState.Ready || it is ModelState.Failed || it is ModelState.Absent || it is ModelState.Cancelled }) {
            is ModelState.Ready -> modelFile.right()
            is ModelState.Failed -> DomainError.SensorError.HardwareUnavailable(terminal.reason ?: "Depth model download failed").left()
            else -> DomainError.SensorError.HardwareUnavailable("Depth model download cancelled").left()
        }

    /** Absolute path of the model file. Safe to pass to [ai.onnxruntime.OrtSession]. */
    fun modelFilePath(): String = modelFile.absolutePath

    fun isModelReady(): Boolean = readyCheck(modelFile)

    private suspend fun apply(infos: List<WorkInfo>) {
        // An empty list is WorkManager's "nothing yet" and must not abort a settle already under way.
        if (infos.isNotEmpty()) settleJob?.cancel()
        if (isModelReady()) {
            _modelState.value = ModelState.Ready
            return
        }
        // A new run can sit beside a finished one; an unfinished job is what the user is waiting on.
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
        val generation = cancelGeneration.get()
        val next = when (info?.state) {
            null -> if (inFlight.get() > 0) return else ModelState.Absent
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> queuedState(info)
            WorkInfo.State.RUNNING -> {
                val notice = if (notificationsOff()) ModelDownloadPresentation.NOTIFICATIONS_OFF else null
                ModelState.Downloading(info.progress.getInt(DepthModelDownloadWorker.KEY_PROGRESS, -1), notice = notice)
            }
            WorkInfo.State.SUCCEEDED -> {
                onWorkSucceeded()
                return
            }
            WorkInfo.State.FAILED -> ModelState.Failed(info.outputData.getString(DepthModelDownloadWorker.KEY_ERROR))
            WorkInfo.State.CANCELLED -> ModelState.Cancelled
        }
        // queuedState suspends on the network read; a cancel during it must not be undone by this stale snapshot.
        // A snapshot taken before the cancel (or a straggling RUNNING/ENQUEUED one after it) must not reopen a cancelled download.
        _modelState.update { current ->
            when {
                cancelGeneration.get() != generation -> current
                next is ModelState.Downloading && current is ModelState.Cancelled && inFlight.get() == 0 -> current
                else -> next
            }
        }
    }

    /** Queued: explains why it is not running yet. The snapshot is taken at emission time, so it can go stale. */
    private suspend fun queuedState(info: WorkInfo): ModelState.Downloading {
        val snapshot: NetworkSnapshot? = try {
            networkProvider.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("network snapshot failed", e)
            null
        }
        val row = ModelDownloadPresentation.present(
            ModelDownloadInput(
                work = ModelWorkState.Queued,
                network = snapshot,
                attempt = info.runAttemptCount,
                requiresUnmetered = info.constraints.requiredNetworkType == NetworkType.UNMETERED,
                notificationsOff = notificationsOff(),
            ),
        )
        return if (row != null && row.isWaiting) {
            ModelState.Downloading(
                progress = -1,
                waitingMessage = row.text,
                canDownloadAnyway = ModelDownloadAction.DownloadNowAnyway in row.actions,
                notice = row.notice,
            )
        } else {
            ModelState.Downloading(progress = -1, notice = row?.notice)
        }
    }

    /**
     * WorkManager can report SUCCEEDED slightly before the file is visible to [isModelReady]. Hold the state at
     * "downloading" while polling for up to [settleTimeoutMs]; only a file that never appears is a failure.
     */
    internal fun onWorkSucceeded() {
        settleJob?.cancel()
        _modelState.value = ModelState.Downloading(progress = 100)
        settleJob = scope.launch {
            var polls = (settleTimeoutMs / settlePollMs).toInt()
            while (!isModelReady() && polls-- > 0) delay(settlePollMs)
            ensureActive() // a newer WorkManager state or a cancel must not be overwritten by this stale result
            _modelState.value =
                if (isModelReady()) ModelState.Ready
                else ModelState.Failed("The model file went missing after downloading. Try again.")
        }
    }


    private fun initialState(): ModelState = if (isModelReady()) ModelState.Ready else ModelState.Absent

    /** Download lifecycle state exposed as [StateFlow]. */
    sealed interface ModelState {
        /** No model file on disk. */
        data object Absent : ModelState

        /**
         * Download queued or in progress — [progress] is 0–100, or -1 if indeterminate. A non-null [waitingMessage]
         * means queued behind the network (unmetered wait, offline, retry backoff), not running.
         */
        data class Downloading(
            val progress: Int,
            val waitingMessage: String? = null,
            val canDownloadAnyway: Boolean = false,
            val notice: String? = null,
        ) : ModelState

        /** Model file present and verified. */
        data object Ready : ModelState

        /** The user (or the system) cancelled the download; terminal for a waiting [downloadModel] caller, offers "Download again". */
        data object Cancelled : ModelState

        /** Download failed or model file corrupt. [reason] carries plain-language copy when set. */
        data class Failed(val reason: String? = null) : ModelState
    }

    companion object {
        const val MODEL_URL =
            "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"
        const val UNIQUE_WORK_NAME = "depth-model-download"
        private const val SETTLE_TIMEOUT_MS = 2_000L
        private const val SETTLE_POLL_MS = 100L

        /** Per request, never persisted. */
        private val ANYWAY_CONSTRAINTS = TaskConstraints(network = NetworkPolicy.Any, requiresStorageNotLow = true)
    }
}

/** Explicit 1:1 mapping — kept as two separate types (see ADR-001/Pattern Decisions). */
fun DepthModelDownloader.ModelState.toUiState(): DepthModelUiState =
    when (this) {
        is DepthModelDownloader.ModelState.Absent -> DepthModelUiState.Absent
        is DepthModelDownloader.ModelState.Downloading ->
            if (waitingMessage != null) DepthModelUiState.Waiting(waitingMessage, canDownloadAnyway, notice)
            else DepthModelUiState.Downloading(progress, notice)
        is DepthModelDownloader.ModelState.Cancelled -> DepthModelUiState.Cancelled
        is DepthModelDownloader.ModelState.Ready -> DepthModelUiState.Ready
        is DepthModelDownloader.ModelState.Failed -> DepthModelUiState.Failed(ModelDownloadPresentation.failureReason(reason))
    }

/** Pure — testable without WorkManager. -1 for an unknown total size. */
internal fun computeProgressPercent(bytesDownloaded: Long, totalBytes: Long): Int =
    if (totalBytes <= 0L) -1 else ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)

private fun areNotificationsOff(context: Context): Boolean = try {
    // Below Android 13 notifications are on by default, and a foreground-service one is shown regardless.
    android.os.Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()
} catch (e: Exception) {
    false
}
