package dev.stapler.stelekit.platform

import javax.swing.JFileChooser
import javax.swing.SwingUtilities

actual class PlatformFileSystem actual constructor() : JvmFileSystemBase(), FileSystem {

    actual override fun getDefaultGraphPath(): String = super.getDefaultGraphPath()

    actual override fun expandTilde(path: String): String = super.expandTilde(path)

    actual override fun readFile(path: String): String? = super.readFile(path)

    actual override fun writeFile(path: String, content: String): Boolean = super.writeFile(path, content)

    actual override fun listFiles(path: String): List<String> = super.listFiles(path)

    actual override fun listDirectories(path: String): List<String> = super.listDirectories(path)

    actual override fun fileExists(path: String): Boolean = super.fileExists(path)

    actual override fun directoryExists(path: String): Boolean = super.directoryExists(path)

    actual override fun createDirectory(path: String): Boolean = super.createDirectory(path)

    actual override fun deleteFile(path: String): Boolean = super.deleteFile(path)

    actual override fun getLastModifiedTime(path: String): Long? = super.getLastModifiedTime(path)

    actual override fun pickDirectory(): String? {
        var selectedPath: String? = null
        val task = Runnable {
            val chooser = JFileChooser()
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                selectedPath = chooser.selectedFile.absolutePath
            }
        }

        if (SwingUtilities.isEventDispatchThread()) {
            task.run()
        } else {
            SwingUtilities.invokeAndWait(task)
        }
        
        // Whitelist the picked directory
        selectedPath?.let { registerGraphRoot(it) }
        
        return selectedPath
    }

    actual override suspend fun pickDirectoryAsync(): String? = pickDirectory()
}
