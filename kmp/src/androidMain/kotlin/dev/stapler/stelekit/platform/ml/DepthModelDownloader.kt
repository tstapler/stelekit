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
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

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

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ensure the model file is present.
     *
     * If the file already exists and passes the sanity-size check (> 10 MB), transitions to
     * [ModelState.Ready] and returns it immediately. Otherwise starts the download and suspends
     * until [DownloadManager] signals completion via [DownloadManager.ACTION_DOWNLOAD_COMPLETE].
     *
     * Must be called from a coroutine (suspend function). Safe to call multiple times.
     */
    suspend fun downloadModel(): Either<DomainError, File> {
        // Fast path: model already present and non-corrupt.
        if (isModelReady()) {
            _modelState.value = ModelState.Ready
            return modelFile.right()
        }

        // Ensure destination directory exists.
        modelFile.parentFile?.mkdirs()

        return suspendCancellableCoroutine { continuation ->
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

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
            _modelState.value = ModelState.Downloading(progress = 0)

            // BroadcastReceiver — fires when this download (or any other) completes.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val completedId =
                        intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (completedId != downloadId) return // not our download

                    context.unregisterReceiver(this)
                    activeDownloadId = -1L

                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    val succeeded = cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            c.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL
                        } else false
                    } ?: false

                    if (succeeded && isModelReady()) {
                        _modelState.value = ModelState.Ready
                        continuation.resume(modelFile.right())
                    } else {
                        _modelState.value = ModelState.Failed
                        continuation.resume(
                            DomainError.SensorError.HardwareUnavailable(
                                "Depth model download failed",
                            ).left(),
                        )
                    }
                }
            }

            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED,
            )

            // Cancel the download if the coroutine scope is cancelled.
            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
                downloadManager.remove(downloadId)
                _modelState.value = ModelState.Absent
                activeDownloadId = -1L
            }
        }
    }

    /** Absolute path of the model file. Safe to pass to [ai.onnxruntime.OrtSession]. */
    fun modelFilePath(): String = modelFile.absolutePath

    /** True when the model file exists on disk and exceeds the minimum sanity size. */
    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE_BYTES

    // ── Sealed state hierarchy ────────────────────────────────────────────────

    /** Download lifecycle state exposed as [StateFlow]. */
    sealed interface ModelState {
        /** No model file on disk. */
        data object Absent : ModelState

        /** Download in progress — [progress] is 0–100, or -1 if indeterminate. */
        data class Downloading(val progress: Int) : ModelState

        /** Model file present and verified. */
        data object Ready : ModelState

        /** Download failed or model file corrupt. */
        data object Failed : ModelState
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
