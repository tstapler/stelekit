package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.restoreDefaultTabTraversal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop JVM image file picker using [JFileChooser].
 *
 * Shows a native-styled open dialog filtered to JPEG and PNG files.
 * The chosen file is returned as a [PlatformImageFile] — no byte copy is needed since
 * the desktop graph is always a local file path.
 *
 * Runs the Swing dialog on the AWT Event Dispatch Thread and suspends the calling
 * coroutine until the user makes a selection.
 */
object DesktopFilePicker {

    /**
     * Open a file-open dialog and return the chosen image as a [PlatformImageFile].
     *
     * Returns [DomainError.SensorError.CaptureFailed] if the user cancels.
     * Returns [DomainError.SensorError.HardwareUnavailable] in headless environments.
     */
    suspend fun pickImageFile(): Either<DomainError.SensorError, PlatformImageFile> {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return DomainError.SensorError.HardwareUnavailable(
                "file_picker: headless environment — cannot show JFileChooser"
            ).left()
        }

        return withContext(Dispatchers.IO) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal: system L&F is cosmetic only
            }

            var chosenFile: File? = null
            val done = java.util.concurrent.CountDownLatch(1)

            SwingUtilities.invokeLater {
                try {
                    val chooser = JFileChooser().apply {
                        dialogTitle = "Select Image"
                        fileSelectionMode = JFileChooser.FILES_ONLY
                        isMultiSelectionEnabled = false
                        fileFilter = FileNameExtensionFilter(
                            "Image files (JPEG, PNG)",
                            "jpg", "jpeg", "png"
                        )
                        restoreDefaultTabTraversal()
                    }
                    val result = chooser.showOpenDialog(null)
                    if (result == JFileChooser.APPROVE_OPTION) {
                        chosenFile = chooser.selectedFile
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Swallow; chosenFile stays null → user-cancel path
                } finally {
                    done.countDown()
                }
            }

            done.await()

            val file = chosenFile
                ?: return@withContext DomainError.SensorError.CaptureFailed(
                    "User cancelled file picker"
                ).left()

            if (!file.exists() || !file.isFile) {
                return@withContext DomainError.SensorError.CaptureFailed(
                    "Selected file does not exist: ${file.absolutePath}"
                ).left()
            }

            PlatformImageFile(
                path = file.absolutePath,
                mimeType = when (file.extension.lowercase()) {
                    "png" -> "image/png"
                    else -> "image/jpeg"
                },
                capturedAtMs = null,
            ).right()
        }
    }
}
