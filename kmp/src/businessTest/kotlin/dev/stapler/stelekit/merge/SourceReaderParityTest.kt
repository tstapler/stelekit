package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The same files, loaded once through the real [GraphLoader] into an in-memory repo and once
 * through [MarkdownSourceGraphReader], must give equal pages (uuids, props, nesting, order).
 */
class SourceReaderParityTest {
    private val root = "/graphs/src"
    private val graph = GraphInfo(id = GraphId("src"), path = root, displayName = "Src", addedAt = 0L)

    private val fixtures: Map<String, String> = mapOf(
        "pages/Unlabeled.md" to MergeFixtures.UNLABELED_NESTED,
        "pages/Mixed.md" to MergeFixtures.MIXED_LABELED,
        "pages/Spaces.md" to MergeFixtures.SPACE_INDENTED,
        "pages/Fence.md" to MergeFixtures.FENCE_WITH_DASH_LINES,
        "pages/Props.md" to "alias:: p, q\ntags:: t\n\n- first\n  collapsed:: true\n\t- child\n  id:: 33333333-3333-3333-3333-333333333333\n- second\n",
        "pages/a%2Fb.md" to "- namespaced\n",
        "journals/2026_10_07.md" to "- morning\n\t- coffee\n- evening\n",
        "journals/2026-10-08.md" to "- hyphenated journal\n",
        "pages/CrlfClean.md" to MergeFixtures.REAL_CRLF_CLEAN,
    )

    @Test
    fun readerMatchesGraphLoaderForEveryFixture() = runTest {
        val fs = FakeRelocationFileSystem()
        fixtures.forEach { (rel, text) -> fs.writeFileBytes("$root/$rel", text.encodeToByteArray()) }

        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        GraphLoader(fs, pageRepo, blockRepo, JournalService(pageRepo, blockRepo)).loadGraph(root) {}

        val reader = MarkdownSourceGraphReader(fs)
        val entries = (reader.listEntries(graph, null, 100) as Either.Right).value
        assertEquals(fixtures.size, entries.size, "entries: ${entries.map { it.fileName }}")

        for (entry in entries) {
            val loaded = pageRepo.getPageByName(entry.name).first().getOrNull()
            assertNotNull(loaded, "GraphLoader did not load ${entry.fileName}")
            val blocks = blockRepo.getBlocksForPage(loaded.uuid).first().getOrNull().orEmpty()
            val expected = StagedPage.from(MergeConverters.toMergePage(loaded, blocks))
            val actual = (reader.readPage(graph, entry) as Either.Right).value
            assertEquals(expected, actual, entry.fileName)
        }
    }
}
