package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

/**
 * Story 3.1.2's `requireNotNull` fail-fast: `renamePage`/`deletePage`, called on a freshly
 * constructed [GraphWriter] before any graph has been loaded (`currentEpoch == null`), must
 * throw a clear [IllegalStateException] with the stated message — never an NPE, never a
 * silent no-op. A `null` `currentEpoch` here is a real programming error (these two functions
 * are only ever called once a graph is active), not a state this migration needs to quietly
 * tolerate.
 */
class GraphWriterNotYetLoadedTest {

    private val now = Clock.System.now()

    @Test
    fun `renamePage on a freshly constructed GraphWriter throws IllegalStateException, not an NPE or silent no-op`() {
        val writer = GraphWriter(fileSystem = FakeFileSystem())
        val page = Page(
            uuid = PageUuid("not-loaded-uuid"),
            name = "NotLoaded",
            filePath = "/graph/pages/NotLoaded.md",
            createdAt = now,
            updatedAt = now,
        )

        val exception = assertFailsWith<IllegalStateException> {
            runBlocking { writer.renamePage(page, "NewName", "/graph") }
        }
        assertEquals("renamePage/deletePage called before any graph was loaded", exception.message)
    }

    @Test
    fun `deletePage on a freshly constructed GraphWriter throws IllegalStateException, not an NPE or silent no-op`() {
        val writer = GraphWriter(fileSystem = FakeFileSystem())
        val page = Page(
            uuid = PageUuid("not-loaded-uuid-2"),
            name = "NotLoaded",
            filePath = "/graph/pages/NotLoaded.md",
            createdAt = now,
            updatedAt = now,
        )

        val exception = assertFailsWith<IllegalStateException> {
            runBlocking { writer.deletePage(page) }
        }
        assertEquals("renamePage/deletePage called before any graph was loaded", exception.message)
    }
}
