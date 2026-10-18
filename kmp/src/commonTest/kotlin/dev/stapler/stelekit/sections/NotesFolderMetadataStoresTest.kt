package dev.stapler.stelekit.sections

import dev.stapler.stelekit.db.SidecarManager
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Metadata files live under the notes folder, never beside it in the repo root. */
class NotesFolderMetadataStoresTest {

    private val notes = EffectiveNotesPath("/repo/logseq")

    @Test
    fun `block sidecar is written under the notes folder and read back`() = runTest {
        val fs = FakeFileSystem()
        val manager = SidecarManager(fs, notes)
        val at = Instant.fromEpochMilliseconds(0)
        val block = Block(
            uuid = BlockUuid("block-1"),
            pageUuid = PageUuid("page-1"),
            content = "hello",
            position = "5", // SidecarManager.read only parses integer positions
            createdAt = at,
            updatedAt = at,
        )

        manager.write("Page", listOf(block))

        assertEquals("/repo/logseq/.stelekit/pages/Page.meta.json", manager.sidecarPath("Page"))
        assertNotNull(fs.readFile("/repo/logseq/.stelekit/pages/Page.meta.json"))
        assertNull(fs.readFile("/repo/.stelekit/pages/Page.meta.json"))
        assertEquals(1, manager.read("Page")?.size)
    }

    @Test
    fun `section manifest is written to the notes folder`() {
        val fs = FakeFileSystem()
        val manifest = SectionManifest(
            sections = listOf(
                SectionDefinition(
                    id = "acme",
                    displayName = "Acme",
                    pagePathPrefix = "pages/acme",
                    journalPathPrefix = "journals/acme",
                ),
            ),
        )

        val result = SectionManifestWriter(fs).write(notes, manifest)

        // Platforms without TOML support (wasm) report a failed write rather than throwing.
        if (TOML_PARSING_SUPPORTED) {
            assertTrue(result.isRight())
            assertNotNull(fs.readFile("/repo/logseq/${SectionManifest.FILENAME}"))
            assertNull(fs.readFile("/repo/${SectionManifest.FILENAME}"))
        } else {
            assertTrue(result.isLeft())
        }
    }
}
