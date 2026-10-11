package dev.stapler.stelekit.merge

import arrow.core.getOrElse
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class PullIndexingTest {
    private val graph = GraphInfo(id = GraphId("src"), path = "/graphs/src", displayName = "Src", addedAt = 0L)

    private class CountingFs(private val d: FileSystem) : FileSystem by d {
        var listCalls = 0
        override fun listFiles(path: String): List<String> {
            listCalls++
            return d.listFiles(path)
        }
    }

    private fun FakeFileSystem.seed(rel: String) = writeFileBytes("/graphs/src/$rel", "- x\n".encodeToByteArray())

    private suspend fun index(fs: FileSystem): Pair<PullPageSource, Int> {
        val source = PullPageSource(MarkdownSourceGraphReader(fs), Dispatchers.Default)
        source.select(graph)
        val ready = withTimeout(60_000) { source.indexState.first { it is PullIndexState.Ready || it is PullIndexState.Failed } }
        return source to (ready as PullIndexState.Ready).count
    }

    @Test
    fun `indexing 8000 entries lists each folder once`() = runBlocking {
        val base = FakeFileSystem()
        (0 until 4000).forEach { base.seed("pages/P%05d.md".format(it)) }
        (0 until 4000).forEach { base.seed("journals/%04d_%02d_%02d.md".format(1000 + it / 336, 1 + it / 28 % 12, 1 + it % 28)) }
        val fs = CountingFs(base)
        val (source, count) = index(fs)
        assertEquals(base.listFiles("/graphs/src/pages").size + base.listFiles("/graphs/src/journals").size, count)
        assertEquals(2, fs.listCalls, "one listing per folder for the whole traversal")
        source.close()
    }

    @Test
    fun `a page and journal sharing a name across a batch boundary are both indexed`() = runBlocking {
        val base = FakeFileSystem()
        (1..99).forEach { base.seed("pages/%04d.md".format(it)) }
        base.seed("pages/2024_01_05.md")
        base.seed("journals/2024_01_05.md")
        base.seed("pages/zzz.md")
        val (source, count) = index(base)
        assertEquals(102, count)
        val names = source.listPages(SelectionFilter(), null, 100, 100).getOrElse { error("$it") }.map { it.name }
        assertEquals(listOf("2024_01_05", "zzz"), names)
        assertEquals(102L, source.countPages(SelectionFilter(), null).getOrElse { error("$it") })
        source.close()
    }
}
