package dev.stapler.stelekit.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ShadowFileCacheTest {

    private lateinit var cache: ShadowFileCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        cache = ShadowFileCache(context, "test-graph-${System.nanoTime()}")
    }

    @Test
    fun `syncFromSaf copies missing files`() = runBlocking {
        val mods = listOf("Foo.md" to 1000L, "Bar.md" to 2000L)
        cache.syncFromSaf("pages", mods) { fileName -> "content of $fileName" }

        assertEquals("content of Foo.md", cache.resolve("pages/Foo.md")?.readText())
        assertEquals("content of Bar.md", cache.resolve("pages/Bar.md")?.readText())
    }

    @Test
    fun `syncFromSaf skips fresh files and copies stale ones`() = runBlocking {
        // Pre-populate a fresh shadow file; then sync with the same mtime
        cache.update("pages/Fresh.md", "original content")
        val freshFile = cache.resolve("pages/Fresh.md")!!
        val mtime = freshFile.lastModified()
        // Fresh: shadowMtime == safMtime → should NOT be overwritten
        var freshReadCount = 0
        cache.syncFromSaf("pages", listOf("Fresh.md" to mtime)) { _ -> freshReadCount++; "overwritten" }
        assertEquals(0, freshReadCount, "fresh shadow file should not be re-read from SAF")
        assertEquals("original content", cache.resolve("pages/Fresh.md")?.readText())

        // Stale: safMtime > shadowMtime → should be re-copied
        var staleReadCount = 0
        cache.syncFromSaf("pages", listOf("Fresh.md" to mtime + 1000L)) { _ -> staleReadCount++; "updated" }
        assertEquals(1, staleReadCount, "stale shadow file should be re-read from SAF")
        assertEquals("updated", cache.resolve("pages/Fresh.md")?.readText())
    }

    @Test
    fun `syncFromSaf skips files where readSafFile returns null`() = runBlocking {
        cache.syncFromSaf("pages", listOf("Missing.md" to 1000L)) { null }
        assertNull(cache.resolve("pages/Missing.md"), "null SAF read should not create shadow file")
    }

    @Test
    fun `resolve returns null for absent shadow file`() {
        assertNull(cache.resolve("pages/DoesNotExist.md"))
    }

    @Test
    fun `resolve returns file for present shadow file`() {
        cache.update("pages/Present.md", "hello")
        assertNotNull(cache.resolve("pages/Present.md"))
    }

    @Test
    fun `update writes correct content and resolve returns it`() {
        cache.update("journals/2026_04_30.md", "- today's entry")
        assertEquals("- today's entry", cache.resolve("journals/2026_04_30.md")?.readText())
    }

    @Test
    fun `update creates parent subdirectory automatically`() {
        cache.update("pages/DeepPage.md", "content")
        assertNotNull(cache.resolve("pages/DeepPage.md"))
    }

    @Test
    fun `invalidate removes shadow file`() {
        cache.update("pages/ToDelete.md", "content")
        assertNotNull(cache.resolve("pages/ToDelete.md"))
        cache.invalidate("pages/ToDelete.md")
        assertNull(cache.resolve("pages/ToDelete.md"))
    }

    @Test
    fun `invalidate on absent file does not throw`() {
        cache.invalidate("pages/NeverExisted.md")
    }

    @Test
    fun `deleteAll removes all shadow files`() = runBlocking {
        cache.syncFromSaf("pages", listOf("A.md" to 1000L)) { "content A" }
        cache.syncFromSaf("journals", listOf("J.md" to 2000L)) { "content J" }
        assertNotNull(cache.resolve("pages/A.md"))

        cache.deleteAll()

        assertNull(cache.resolve("pages/A.md"))
        assertNull(cache.resolve("journals/J.md"))
    }

    @Test
    fun `graphIdFor replaces unsafe filesystem characters`() {
        val id = ShadowFileCache.graphIdFor("primary:personal-wiki/logseq")
        assertEquals("primary-personal-wiki-logseq", id)
    }

    @Test
    fun `graphIdFor truncates long IDs`() {
        val longId = "x".repeat(200)
        assertEquals(128, ShadowFileCache.graphIdFor(longId).length)
    }
}
