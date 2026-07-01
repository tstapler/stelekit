package dev.stapler.stelekit.db

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionFilter
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for [GraphLoader] with a [SectionFilter] injected.
 *
 * [FakeFileSystem.listFiles] is non-recursive, matching real JVM filesystem scan behaviour.
 * Files in section subdirectories (e.g. pages/acme-work/) are therefore not discovered by
 * [GraphLoader.loadGraphProgressive] until Epic 2 (section-aware directory scanning) lands.
 */
class GraphLoaderSectionFilterTest {

    private val sectionFilter = SectionFilter(
        manifest = SectionManifest(
            sections = listOf(
                SectionDefinition(
                    id = "acme-work",
                    displayName = "Acme Work",
                    pagePathPrefix = "pages/acme-work",
                    journalPathPrefix = "journals/acme-work",
                ),
                SectionDefinition(
                    id = "personal",
                    displayName = "Personal",
                    pagePathPrefix = "pages/personal",
                    journalPathPrefix = "journals/personal",
                ),
            ),
        ),
        sectionStates = mapOf(
            "acme-work" to SectionState.ACTIVE,
            "personal" to SectionState.REMOVED,
        ),
    )

    private fun fakeFs() = FakeFileSystem().apply {
        // Global (root) page — listed by non-recursive scan of /graph/pages
        writeFile("/graph/pages/Root Page.md", "- Root page content")
        // Section-subdir pages — NOT listed by non-recursive scan; requires Epic 2
        writeFile("/graph/pages/acme-work/Work Page.md", "- Work page content")
        writeFile("/graph/pages/personal/Personal Page.md", "- Personal content")
    }

    private fun setup(): Pair<GraphLoader, InMemoryPageRepository> {
        val pageRepo = InMemoryPageRepository()
        val loader = GraphLoader(fakeFs(), pageRepo, InMemoryBlockRepository(), sectionFilter = sectionFilter)
        return loader to pageRepo
    }

    @Test
    fun `root page loaded with empty sectionId when no section prefix matches`() = runBlocking {
        val (loader, pageRepo) = setup()
        loader.loadGraphProgressive("/graph", 5, onProgress = {}, onPhase1Complete = {}, onFullyLoaded = {})

        val pages = pageRepo.getAllPagesSnapshot().getOrNull() ?: emptyList()
        val rootPage = pages.firstOrNull { it.name.equals("Root Page", ignoreCase = true) }
        assertNotNull(rootPage, "Root page should be loaded from /graph/pages/")
        assertEquals("", rootPage.sectionId, "Root page matches no section prefix — sectionId must be empty")
    }

    @Test
    fun `resolvePageFilePath finds root page in standard pages directory`() = runBlocking {
        val (loader, _) = setup()
        loader.loadGraphProgressive("/graph", 5, onProgress = {}, onPhase1Complete = {}, onFullyLoaded = {})

        assertEquals("/graph/pages/Root Page.md", loader.resolvePageFilePath("Root Page"))
    }

    @Test
    fun `resolvePageFilePath returns null for page only in section subdir (pre-Epic-2)`() = runBlocking {
        // Work Page lives in pages/acme-work/ — not found by resolvePageFilePath which only
        // checks pages/<name>.md and journals/<name>.md. Epic 2.2 will add section fallback.
        val (loader, _) = setup()
        loader.loadGraphProgressive("/graph", 5, onProgress = {}, onPhase1Complete = {}, onFullyLoaded = {})

        assertNull(loader.resolvePageFilePath("Work Page"), "Section-subdir pages not resolved pre-Epic-2")
    }
}
