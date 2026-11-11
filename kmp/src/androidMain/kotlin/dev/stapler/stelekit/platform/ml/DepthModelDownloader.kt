// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.content.Context
import androidx.work.WorkInfo
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
class DepthModelDownloader(
    private val context: Context,
    scheduler: BackgroundTaskScheduler? = null,
    store: TaskSettingsStore? = null,
) {

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
    @Volatile private var requestInFlight = false

    private val _modelState = MutableStateFlow(initialState())
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + PlatformDispatcher.IO +
            CoroutineExceptionHandler { _, e -> logger.warn("Uncaught in DepthModelDownloader scope", e) },
    )

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
        requestInFlight = true
        try {
            taskScheduler.runOnce(ModelDownloadTask.KEY, storedConstraints(), OneShotConflict.Keep)
            if (_modelState.value !is ModelState.Downloading) _modelState.value = ModelState.Downloading(progress = -1)
            return awaitTerminal()
        } finally {
            requestInFlight = false
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
        workManagerOrNull(context)?.cancelUniqueWork(UNIQUE_WORK_NAME)
        runCatching { File(modelFile.parentFile, "${modelFile.name}.part").delete() }
        if (!isModelReady()) _modelState.value = ModelState.Absent
    }

    private suspend fun awaitTerminal(): Either<DomainError, File> =
        when (val terminal = modelState.first { it is ModelState.Ready || it is ModelState.Failed || it is ModelState.Absent }) {
            is ModelState.Ready -> modelFile.right()
            is ModelState.Failed -> DomainError.SensorError.HardwareUnavailable(terminal.reason ?: "Depth model download failed").left()
            else -> DomainError.SensorError.HardwareUnavailable("Depth model download cancelled").left()
        }

    /** Absolute path of the model file. Safe to pass to [ai.onnxruntime.OrtSession]. */
    fun modelFilePath(): String = modelFile.absolutePath

    fun isModelReady(): Boolean = DepthModelStorage.isReady(modelFile)

    private fun apply(infos: List<WorkInfo>) {
        if (isModelReady()) {
            _modelState.value = ModelState.Ready
            return
        }
        // A new run can sit beside a finished one; an unfinished job is what the user is waiting on.
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
        _modelState.value = when (info?.state) {
            null -> if (requestInFlight) return else ModelState.Absent
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelState.Downloading(progress = -1)
            WorkInfo.State.RUNNING -> ModelState.Downloading(info.progress.getInt(DepthModelDownloadWorker.KEY_PROGRESS, -1))
            WorkInfo.State.SUCCEEDED -> ModelState.Failed("The model file went missing after downloading. Try again.")
            WorkInfo.State.FAILED -> ModelState.Failed(info.outputData.getString(DepthModelDownloadWorker.KEY_ERROR))
            WorkInfo.State.CANCELLED -> ModelState.Absent
        }
    }

    private fun initialState(): ModelState = if (isModelReady()) ModelState.Ready else ModelState.Absent

    /** Download lifecycle state exposed as [StateFlow]. */
    sealed interface ModelState {
        /** No model file on disk. */
        data object Absent : ModelState

        /** Download queued or in progress — [progress] is 0–100, or -1 if indeterminate. */
        data class Downloading(val progress: Int) : ModelState

        /** Model file present and verified. */
        data object Ready : ModelState

        /** Download failed or model file corrupt. [reason] carries plain-language copy when set. */
        data class Failed(val reason: String? = null) : ModelState
    }

    companion object {
        const val MODEL_URL =
            "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"
        const val UNIQUE_WORK_NAME = "depth-model-download"
    }
}

/** Explicit 1:1 mapping — kept as two separate types (see ADR-001/Pattern Decisions). */
fun DepthModelDownloader.ModelState.toUiState(): DepthModelUiState =
    when (this) {
        is DepthModelDownloader.ModelState.Absent -> DepthModelUiState.Absent
        is DepthModelDownloader.ModelState.Downloading -> DepthModelUiState.Downloading(progress)
        is DepthModelDownloader.ModelState.Ready -> DepthModelUiState.Ready
        is DepthModelDownloader.ModelState.Failed -> DepthModelUiState.Failed(reason)
    }

/** Pure — testable without WorkManager. -1 for an unknown total size. */
internal fun computeProgressPercent(bytesDownloaded: Long, totalBytes: Long): Int =
    if (totalBytes <= 0L) -1 else ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
