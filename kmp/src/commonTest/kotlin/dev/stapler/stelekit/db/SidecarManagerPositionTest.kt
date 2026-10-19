package dev.stapler.stelekit.db

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SidecarManagerPositionTest {

    private val notes = EffectiveNotesPath("/g")
    private val at = Instant.fromEpochMilliseconds(0)

    private fun block(uuid: String, content: String, position: String) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid("page-1"),
        content = content,
        position = position,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `fractional-index positions survive a write and read`() = runTest {
        val manager = SidecarManager(FakeFileSystem(), notes)
        val blocks = listOf(block("b1", "one", "a0"), block("b2", "two", "a0V"), block("b3", "three", "Zz"))

        manager.write("Page", blocks)

        val entries = manager.read("Page")!!
        blocks.forEach { b ->
            assertEquals(b.position, entries.getValue(ContentHasher.sha256ForContent(b.content)).pos)
        }
    }

    @Test
    fun `sidecars with a bare integer or unquoted fractional position still read`() = runTest {
        val fs = FakeFileSystem()
        val manager = SidecarManager(fs, notes)
        fs.writeFile(
            manager.sidecarPath("Legacy"),
            """
            {"uuid":"u1","hash":"h1","pos":5,"parent":null}
            {"uuid":"u2","hash":"h2","pos":a0V,"parent":"u1"}
            """.trimIndent(),
        )

        val entries = manager.read("Legacy")!!

        assertEquals("5", entries.getValue("h1").pos)
        assertEquals("a0V", entries.getValue("h2").pos)
        assertEquals("u1", entries.getValue("h2").parentUuid)
    }
}
