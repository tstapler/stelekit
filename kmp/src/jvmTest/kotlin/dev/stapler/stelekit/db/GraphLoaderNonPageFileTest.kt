package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.model.EffectiveNotesPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A git merge reloads every changed path; only markdown may become a page. */
class GraphLoaderNonPageFileTest {

    private class MapFs(val files: Map<String, String>) : FileSystem {
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = files[path]
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? = 1L
    }

    @Test
    fun reloadFiles_should_OnlyCreatePagesForMarkdown_When_MergeChangedBinaryAndSidecarFiles() = runTest {
        val fs = MapFs(
            mapOf(
                "/graph/pages/Note.md" to "- a block",
                "/graph/assets/images/pic.jpg" to "- looks\n- like\n- markdown\n",
                "/graph/.stelekit/pages/Note.meta.json" to "{\"x\": 1}",
            )
        )
        val pages = InMemoryPageRepository()
        val blocks = InMemoryBlockRepository()
        val loader = GraphLoader(fileSystem = fs, pageRepository = pages, blockRepository = blocks)

        loader.reloadFiles(fs.files.keys.map { FilePath(it) })

        val names = pages.getPages(100, 0).first().getOrNull().orEmpty().map { it.name }
        assertEquals(listOf("Note"), names)
        assertTrue(blocks.getBlocksForPage(pages.getPages(100, 0).first().getOrNull()!!.single().uuid).first().getOrNull().orEmpty().size == 1)
    }

    @Test
    fun dispose_should_StopTheWatcher_When_TheLoaderIsSuperseded() = runBlocking {
        val fs = MapFs(emptyMap())
        val loader = GraphLoader(
            fileSystem = fs, pageRepository = InMemoryPageRepository(), blockRepository = InMemoryBlockRepository(),
            watcherPollIntervalMs = 50L,
        )
        loader.startWatching(EffectiveNotesPath("/graph"))
        withTimeout(5_000) { while (!loader.isWatching) delay(10) }

        loader.dispose()

        assertFalse(loader.isWatching, "a disposed loader must not keep polling the graph")
    }
}
