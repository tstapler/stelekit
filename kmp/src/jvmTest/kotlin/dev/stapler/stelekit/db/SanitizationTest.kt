package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SanitizationTest {

    private val fileSystem = object : FileSystem {
        val files = mutableMapOf<String, String>()
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = files[path]
        override fun writeFile(path: String, content: String): Boolean {
            files[path] = content
            return true
        }
        override fun listFiles(path: String) = files.keys
            .filter { it.startsWith(path) && it != path }
            .map { it.substringAfterLast("/") }
            .distinct()
            
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String): Boolean {
            return files.remove(path) != null
        }
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val pageRepository = InMemoryPageRepository()
    private val blockRepository = InMemoryBlockRepository()
    private val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)

    @Test
    fun `test migration renames unsafe files`() = runTest {
        // Setup: Create file with unsafe name (simulated)
        // In real FS, creating "Test:Page.md" might fail on Windows, but this is an in-memory mock
        // representing a file that might have been created on Linux/Mac.
        val unsafeName = "Test:Page.md"
        val unsafePath = "/graph/pages/$unsafeName"
        val content = "- Test content"
        fileSystem.files[unsafePath] = content
        
        // Ensure safe file doesn't exist
        val safeName = "Test%3APage.md"
        val safePath = "/graph/pages/$safeName"
        assertFalse(fileSystem.fileExists(safePath))

        // Run loadGraph which triggers sanitizeDirectory
        graphLoader.loadGraph("/graph") { }
        
        // Verify migration
        assertFalse(fileSystem.fileExists(unsafePath), "Unsafe file should be deleted")
        assertTrue(fileSystem.fileExists(safePath), "Safe file should be created")
        
        // Verify content preserved
        // Note: graphLoader actually loads the file after renaming, so readFile should work
        // But our mock fileSystem is simple.
        // Let's check the map directly.
        assertTrue(fileSystem.files[safePath] == content)
    }

    @Test
    fun `test migration prevents duplicates`() = runTest {
        // Setup: Both unsafe and safe files exist
        val unsafePath = "/graph/pages/Duplicate:Name.md"
        val safePath = "/graph/pages/Duplicate%3AName.md"
        
        val contentUnsafe = "- Unsafe content"
        val contentSafe = "- Safe content"
        
        fileSystem.files[unsafePath] = contentUnsafe
        fileSystem.files[safePath] = contentSafe
        
        // Run loadGraph
        graphLoader.loadGraph("/graph") { }
        
        // Verify no overwrite happened
        assertTrue(fileSystem.fileExists(unsafePath), "Unsafe file should remain if target exists (to avoid data loss)")
        assertTrue(fileSystem.fileExists(safePath), "Safe file should remain untouched")
        assertTrue(fileSystem.files[safePath] == contentSafe, "Safe file content should not change")
    }
}
