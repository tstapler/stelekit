package dev.stapler.stelekit.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

actual class PlatformFileSystem actual constructor() : JvmFileSystemBase(), FileSystem {

    actual override fun getDefaultGraphPath(): String = super.getDefaultGraphPath()

    actual override fun expandTilde(path: String): String = super.expandTilde(path)

    actual override fun readFile(path: String): String? = super.readFile(path)

    actual override fun writeFile(path: String, content: String): Boolean = super.writeFile(path, content)

    actual override fun listFiles(path: String): List<String> = super.listFiles(path)

    override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> = super<JvmFileSystemBase>.listFilesWithModTimes(path)

    actual override fun listDirectories(path: String): List<String> = super.listDirectories(path)

    actual override fun fileExists(path: String): Boolean = super.fileExists(path)

    actual override fun directoryExists(path: String): Boolean = super.directoryExists(path)

    actual override fun createDirectory(path: String): Boolean = super.createDirectory(path)

    actual override fun deleteFile(path: String): Boolean = super.deleteFile(path)

    actual override fun getLastModifiedTime(path: String): Long? = super.getLastModifiedTime(path)

    override fun renameFile(from: String, to: String): Boolean = super<JvmFileSystemBase>.renameFile(from, to)

    actual override fun pickDirectory(): String? {
        // Synchronous fallback — only safe when already on the AWT EDT and not inside
        // a Compose coroutine dispatcher. Prefer pickDirectoryAsync() from coroutines.
        val future = CompletableFuture<String?>()
        SwingUtilities.invokeLater {
            val chooser = JFileChooser()
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            val result = chooser.showOpenDialog(null)
            future.complete(if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null)
        }
        return future.get()?.also { registerGraphRoot(it) }
    }

    actual override suspend fun pickDirectoryAsync(): String? {
        // Must NOT call JFileChooser.showOpenDialog on the Compose FlushCoroutineDispatcher.
        // showOpenDialog creates a nested AWT event loop (WaitDispatchSupport.enter) which
        // corrupts coroutine continuation state. Fix: move to IO, schedule dialog on the
        // real AWT EDT via invokeLater, block only the IO thread on the result.
        return withContext(Dispatchers.IO) {
            val future = CompletableFuture<String?>()
            SwingUtilities.invokeLater {
                val chooser = JFileChooser()
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                val result = chooser.showOpenDialog(null)
                future.complete(if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null)
            }
            future.get()?.also { registerGraphRoot(it) }
        }
    }

    override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? {
        return withContext(Dispatchers.IO) {
            val future = CompletableFuture<String?>()
            SwingUtilities.invokeLater {
                val chooser = JFileChooser()
                chooser.selectedFile = java.io.File(getDownloadsPath(), suggestedName)
                val result = chooser.showSaveDialog(null)
                future.complete(if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null)
            }
            future.get()
        }
    }
}
