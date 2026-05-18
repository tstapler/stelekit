package dev.stapler.stelekit.platform.sensor

import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.IOException

/**
 * Launches the Android Photo Picker (API 33+ native; backported to API 21+ via Play Services)
 * and copies the selected image into a stable local temp file before handing control back
 * to the caller.
 *
 * CRITICAL — content URI expiry:
 * The content URI returned by the Photo Picker carries a temporary read grant that expires
 * as soon as the process yields control across a suspension point. [readBytesFromUri] MUST be
 * called synchronously inside the [ActivityResultCallback] — BEFORE any `withContext` or `await`.
 *
 * Usage pattern (Activity/Fragment):
 * ```kotlin
 * val launcher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
 *     uri ?: return@registerForActivityResult
 *     // READ URI BYTES HERE — before any coroutine suspension
 *     val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
 *     // Then hand bytes to ImageImportService on a background coroutine
 * }
 * ```
 *
 * This class wraps that pattern with a coroutine-friendly API via [CompletableDeferred].
 * It must be registered once per Activity (not per composable) because
 * [ActivityResultLauncher] must be registered before onStart.
 */
class AndroidPhotoPickerLauncher(
    private val context: Context,
) {
    private var pendingResult: CompletableDeferred<Either<DomainError.SensorError, PlatformImageFile>>? = null
    private var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null

    /**
     * Register the Photo Picker result contract.
     *
     * Must be called from [androidx.activity.ComponentActivity] or a Fragment during
     * `onCreate` — before `onStart`.
     *
     * @param registerForActivityResult Use `registerForActivityResult` from the Activity.
     */
    fun register(
        registerForActivityResult: (
            ActivityResultContracts.PickVisualMedia,
            (Uri?) -> Unit,
        ) -> ActivityResultLauncher<PickVisualMediaRequest>,
    ) {
        launcher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val deferred = pendingResult
            if (deferred == null || deferred.isCompleted) return@registerForActivityResult

            if (uri == null) {
                deferred.complete(
                    DomainError.SensorError.CaptureFailed("User cancelled photo picker").left()
                )
                return@registerForActivityResult
            }

            // CRITICAL: Read URI bytes HERE, synchronously, before any coroutine suspension.
            // The content URI grant from the Photo Picker is temporary — it expires when
            // this callback returns. Calling openInputStream on a background dispatcher
            // would observe an expired grant and throw SecurityException.
            val result = readBytesFromUri(uri)
            deferred.complete(result)
        }
    }

    /**
     * Launch the Photo Picker and suspend until the user makes a selection or cancels.
     *
     * Returns a [PlatformImageFile] whose [PlatformImageFile.path] points to a stable
     * temp file inside the app's cache directory — safe to pass to [ImageImportService].
     */
    suspend fun pickPhoto(): Either<DomainError.SensorError, PlatformImageFile> {
        val l = launcher ?: return DomainError.SensorError.HardwareUnavailable(
            "PhotoPickerLauncher not registered — call register() in Activity.onCreate"
        ).left()

        val deferred = CompletableDeferred<Either<DomainError.SensorError, PlatformImageFile>>()
        pendingResult = deferred

        l.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        return deferred.await()
    }

    /**
     * Read all bytes from [uri] synchronously via ContentResolver and write them to a
     * temp file in the app's cache directory.
     *
     * This MUST run on the thread that called the [ActivityResultCallback] — before
     * any coroutine suspension boundary.
     */
    private fun readBytesFromUri(uri: Uri): Either<DomainError.SensorError, PlatformImageFile> {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
                ?: return DomainError.SensorError.CaptureFailed(
                    "contentResolver.openInputStream returned null for $uri"
                ).left()

            val tempFile = File(context.cacheDir, "photo_import_${System.currentTimeMillis()}.jpg")
            tempFile.writeBytes(bytes)

            PlatformImageFile(
                path = tempFile.absolutePath,
                mimeType = context.contentResolver.getType(uri) ?: "image/jpeg",
                capturedAtMs = System.currentTimeMillis(),
            ).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            DomainError.SensorError.PermissionDenied("photo_picker: ${e.message}").left()
        } catch (e: IOException) {
            DomainError.SensorError.CaptureFailed("I/O error reading photo picker URI: ${e.message}").left()
        } catch (e: Exception) {
            DomainError.SensorError.CaptureFailed("Unexpected error: ${e.message}").left()
        }
    }
}
