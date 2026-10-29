// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.ui.annotate.DepthModelUiState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App-facing handle on the Depth Anything V2 ViT-S model download. The transfer is a unique WorkManager job
 * ([DepthModelDownloadWorker]), so it survives process death and screen navigation; this class only enqueues it and
 * mirrors WorkManager's persisted state into [modelState]. A fresh instance after a restart re-attaches to
 * whatever job is still queued or running.
 */
class DepthModelDownloader(private val context: Context) {

    private val modelFile: File = DepthModelStorage.modelFile(context)
    private val logger = Logger("DepthModelDownloader")

    private val _modelState = MutableStateFlow(initialState())
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + PlatformDispatcher.IO +
            CoroutineExceptionHandler { _, e -> logger.warn("Uncaught in DepthModelDownloader scope", e) },
    )

    init {
        scope.launch {
            val workManager = workManagerOrNull() ?: return@launch
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
        val workManager = workManagerOrNull()
            ?: return DomainError.SensorError.HardwareUnavailable("Background downloads are unavailable right now").left()
        val request = OneTimeWorkRequestBuilder<DepthModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.EMPTY)
            .build()
        // KEEP: an already queued or running job is left alone; a finished one is replaced by this new run.
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        if (_modelState.value !is ModelState.Downloading) _modelState.value = ModelState.Downloading(progress = -1)
        return awaitTerminal()
    }

    /** User-initiated cancel: stops the job, drops the partial file and resolves any waiting [downloadModel] caller. */
    fun cancelDownload() {
        workManagerOrNull()?.cancelUniqueWork(UNIQUE_WORK_NAME)
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
            null -> ModelState.Absent
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelState.Downloading(progress = -1)
            WorkInfo.State.RUNNING -> ModelState.Downloading(info.progress.getInt(DepthModelDownloadWorker.KEY_PROGRESS, -1))
            WorkInfo.State.SUCCEEDED -> ModelState.Failed("The model file went missing after downloading. Try again.")
            WorkInfo.State.FAILED -> ModelState.Failed(info.outputData.getString(DepthModelDownloadWorker.KEY_ERROR))
            WorkInfo.State.CANCELLED -> ModelState.Absent
        }
    }

    private fun workManagerOrNull(): WorkManager? = try {
        WorkManager.getInstance(context)
    } catch (e: IllegalStateException) {
        logger.warn("WorkManager is not initialised", e)
        null
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
