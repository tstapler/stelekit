package dev.stapler.stelekit.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ShadowFlushActor]:
 *
 * 1. [onFlushed] callback is invoked after a successful SAF write so FileRegistry can record
 *    the post-flush mtime and suppress spurious watcher events on encrypted files.
 * 2. [onPreFlush] callback is invoked before the SAF write so FileRegistry can set the
 *    Long.MAX_VALUE sentinel, closing the mtime race window for .md.stek files.
 * 3. [onFlushFailed] callback is invoked when the SAF write fails, so FileRegistry can remove
 *    the sentinel and not permanently suppress external-change detection.
 * 4. Shadow-missing path dequeues without calling [onFlushed] (no write occurred).
 * 5. SAF write failure does NOT dequeue and does NOT call [onFlushed].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ShadowFlushActorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cache: ShadowFileCache
    private lateinit var queue: WriteBehindQueue

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        cache = ShadowFileCache(context, "flush-actor-test-${System.nanoTime()}")
        queue = WriteBehindQueue(tempFolder.newFile("queue.txt"))
    }

    private fun makeFakeFileSystem(writeOk: Boolean = true): FakeWriteFileSystem {
        return FakeWriteFileSystem(writeOk)
    }

    private inner class FakeWriteFileSystem(private val writeOk: Boolean) : dev.stapler.stelekit.platform.FileSystem {
        val written = mutableMapOf<String, String>()
        var lastModifiedResult: Long? = 12345L

        override fun getDefaultGraphPath() = "saf://root"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = null
        override fun writeFile(path: String, content: String): Boolean {
            if (writeOk) written[path] = content
            return writeOk
        }
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String) = lastModifiedResult
    }

    @Test
    fun `onFlushed is called after successful SAF write`() = runBlocking {
        val fs = makeFakeFileSystem(writeOk = true)
        val safPath = "saf://encoded-root/pages/Note.md"
        cache.update("pages/Note.md", "- Hello\n")
        queue.enqueue(safPath)

        val flushedPaths = mutableListOf<String>()
        val actor = ShadowFlushActor(fs, cache, queue, onFlushed = { flushedPaths.add(it) })
        actor.flush()

        assertEquals(listOf(safPath), flushedPaths, "onFlushed must be called with the SAF path after a successful write")
        assertTrue(queue.isEmpty(), "Queue must be empty after successful flush")
    }

    @Test
    fun `onPreFlush is called before SAF write`() = runBlocking {
        val fs = makeFakeFileSystem(writeOk = true)
        val safPath = "saf://encoded-root/pages/Note.md"
        cache.update("pages/Note.md", "- Hello\n")
        queue.enqueue(safPath)

        val order = mutableListOf<String>()
        val actor = ShadowFlushActor(
            fs, cache, queue,
            onPreFlush = { order.add("pre:$it") },
            onFlushed = { order.add("done:$it") },
        )
        actor.flush()

        assertEquals(
            listOf("pre:$safPath", "done:$safPath"),
            order,
            "onPreFlush must be called before onFlushed"
        )
    }

    @Test
    fun `onFlushed is NOT called when SAF write fails`() = runBlocking {
        val fs = makeFakeFileSystem(writeOk = false)
        val safPath = "saf://encoded-root/pages/Note.md"
        cache.update("pages/Note.md", "- Hello\n")
        queue.enqueue(safPath)

        val flushedPaths = mutableListOf<String>()
        val actor = ShadowFlushActor(fs, cache, queue, onFlushed = { flushedPaths.add(it) })
        actor.flush()

        assertTrue(flushedPaths.isEmpty(), "onFlushed must NOT be called when SAF write fails")
        assertTrue(!queue.isEmpty(), "Queue must retain the entry when SAF write fails")
    }

    @Test
    fun `onFlushFailed is called when SAF write fails`() = runBlocking {
        val fs = makeFakeFileSystem(writeOk = false)
        val safPath = "saf://encoded-root/pages/Note.md"
        cache.update("pages/Note.md", "- Hello\n")
        queue.enqueue(safPath)

        val failedPaths = mutableListOf<String>()
        val actor = ShadowFlushActor(
            fs, cache, queue,
            onFlushFailed = { failedPaths.add(it) },
        )
        actor.flush()

        assertEquals(listOf(safPath), failedPaths, "onFlushFailed must be called with SAF path when write fails")
    }

    @Test
    fun `onFlushFailed is NOT called on successful write`() = runBlocking {
        val fs = makeFakeFileSystem(writeOk = true)
        val safPath = "saf://encoded-root/pages/Note.md"
        cache.update("pages/Note.md", "- Hello\n")
        queue.enqueue(safPath)

        val failedPaths = mutableListOf<String>()
        val actor = ShadowFlushActor(
            fs, cache, queue,
            onFlushFailed = { failedPaths.add(it) },
        )
        actor.flush()

        assertTrue(failedPaths.isEmpty(), "onFlushFailed must NOT be called when write succeeds")
    }

    @Test
    fun `onFlushed is NOT called when shadow is missing`() = runBlocking {
        val fs = makeFakeFileSystem(writeOk = true)
        val safPath = "saf://encoded-root/pages/Missing.md"
        // Do NOT populate shadow — simulates crash between enqueue and shadow write
        queue.enqueue(safPath)

        val flushedPaths = mutableListOf<String>()
        val preFlushPaths = mutableListOf<String>()
        val actor = ShadowFlushActor(
            fs, cache, queue,
            onPreFlush = { preFlushPaths.add(it) },
            onFlushed = { flushedPaths.add(it) },
        )
        actor.flush()

        assertTrue(flushedPaths.isEmpty(), "onFlushed must NOT be called when shadow is missing")
        assertTrue(preFlushPaths.isEmpty(), "onPreFlush must NOT be called when shadow is missing — sentinel would be stranded forever")
        assertTrue(queue.isEmpty(), "Queue must dequeue the entry even when shadow is missing (no retry)")
    }

    @Test
    fun `flush drains multiple pages and calls onFlushed for each success`() = runBlocking {
        val fs = makeFakeFileSystem(writeOk = true)
        val paths = listOf("pages/A.md", "pages/B.md", "journals/2024_01_15.md")
        val safPaths = paths.map { "saf://encoded-root/$it" }

        paths.forEach { cache.update(it, "content of $it") }
        safPaths.forEach { queue.enqueue(it) }

        val flushedPaths = mutableListOf<String>()
        val actor = ShadowFlushActor(fs, cache, queue, onFlushed = { flushedPaths.add(it) })
        actor.flush()

        assertEquals(safPaths.sorted(), flushedPaths.sorted(),
            "onFlushed must be called for every successfully flushed path")
        assertTrue(queue.isEmpty(), "Queue must be empty after flushing all pages")
    }

    @Test
    fun `onPreFlush called before write and onFlushFailed called on failure, onFlushed not called`() = runBlocking {
        val fs = makeFakeFileSystem(writeOk = false)
        val safPath = "saf://encoded-root/pages/Note.md"
        cache.update("pages/Note.md", "- Hello\n")
        queue.enqueue(safPath)

        val order = mutableListOf<String>()
        val actor = ShadowFlushActor(
            fs, cache, queue,
            onPreFlush = { order.add("pre:$it") },
            onFlushed = { order.add("done:$it") },
            onFlushFailed = { order.add("fail:$it") },
        )
        actor.flush()

        assertEquals(
            listOf("pre:$safPath", "fail:$safPath"),
            order,
            "onPreFlush then onFlushFailed must be called when write fails; onFlushed must not be called"
        )
    }
}
