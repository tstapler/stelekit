package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.util.indentContinuationLines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * Real-graph check (skipped unless `SPIKE_GRAPH_PATH` is set; the graph is only read): load a page through the real
 * loader, save it the way the editor does (GraphWriter.savePage), and compare with what the previous serializer wrote.
 * Prints counts and every changed-and-worse file with a diff.
 */
class EditorSaveRealGraphTest {
    private fun oldSerialize(page: dev.stapler.stelekit.model.Page, blocks: List<Block>): String = buildString {
        page.properties.forEach { (k, v) -> appendLine("$k:: $v") }
        val byParent = blocks.groupBy { it.parentUuid }
        fun write(parent: BlockUuid?) {
            byParent[parent].orEmpty().sortedBy { it.position }.forEach { b ->
                val indent = "\t".repeat(b.level)
                append(indent).append("- ").appendLine(b.content.indentContinuationLines(indent + "\t"))
                b.properties.forEach { (k, v) -> appendLine("$indent\t$k:: $v") }
                write(b.uuid)
            }
        }
        write(null)
    }

    private fun diff(a: String, b: String): String {
        val x = a.lines()
        val y = b.lines()
        val out = StringBuilder()
        var shown = 0
        for (i in 0 until maxOf(x.size, y.size)) {
            if (x.getOrNull(i) != y.getOrNull(i) && shown++ < 8) out.append("  L${i + 1}: -${x.getOrNull(i)}\n        +${y.getOrNull(i)}\n")
        }
        return out.toString()
    }

    private fun lineDistance(a: String, b: String): Int {
        val x = a.lines()
        val y = b.lines()
        return (0 until maxOf(x.size, y.size)).count { x.getOrNull(it) != y.getOrNull(it) } + kotlin.math.abs(x.size - y.size)
    }

    @Test
    fun editorSaveVersusPreviousSerializer() = runBlocking {
        val graph = System.getenv("SPIKE_GRAPH_PATH")
        assumeTrue("SPIKE_GRAPH_PATH not set", graph != null)
        val n = System.getenv("SAVE_SAMPLE")?.toInt() ?: 300
        val all = listOf("pages", "journals").flatMap { d ->
            File(graph, d).listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().toList()
        }.sortedBy { it.path }
        val sample = all.shuffled(Random(42)).take(n)

        var identicalToOriginal = 0
        var oldRoundTripped = 0
        var sameAsOld = 0
        var better = 0
        var worse = 0
        var skipped = 0
        val worseReports = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (src in sample) {
                val dir = Files.createTempDirectory("editor-save").toFile()
                try {
                    val kind = src.parentFile.name
                    val copy = File(dir, "$kind/${src.name}").also { it.parentFile.mkdirs(); src.copyTo(it) }
                    val original = copy.readText()
                    val fs = PlatformFileSystem.withRoot(dir.absolutePath)
                    val pages = InMemoryPageRepository()
                    val blocks = InMemoryBlockRepository()
                    val actor = DatabaseWriteActor(blocks, pages, scope = scope)
                    val loader = GraphLoader(fs, pages, blocks, externalWriteActor = actor)
                    val writer = GraphWriter(fs, actor)
                    writer.currentEpoch = GraphEpoch(GraphId("g"), dir.absolutePath, 1L)
                    loader.applyExternalFileChange(FilePath(copy.absolutePath), original, ParseMode.FULL, DatabaseWriteActor.Priority.HIGH)
                    val page = pages.getAllPagesSnapshot().getOrNull().orEmpty().firstOrNull()
                    if (page == null) { skipped++; actor.close(); continue }
                    val rows = blocks.getBlocksForPage(page.uuid).first().getOrNull().orEmpty()
                    val old = oldSerialize(page, rows)
                    writer.savePage(page, rows, dir.absolutePath)
                    val now = copy.readText()
                    actor.close()
                    val oldRt = old == original
                    if (oldRt) oldRoundTripped++
                    when {
                        now == original -> identicalToOriginal++
                        now == old -> sameAsOld++
                        lineDistance(original, now) < lineDistance(original, old) -> better++
                        oldRt || lineDistance(original, now) > lineDistance(original, old) -> {
                            worse++
                            worseReports += "${src.name} (oldRoundTripped=$oldRt)\n${diff(original, now)}"
                        }
                        else -> sameAsOld++
                    }
                } finally {
                    dir.deleteRecursively()
                }
            }
        } finally {
            scope.cancel()
        }
        println("EDITOR-SAVE: sampled=${sample.size} skipped=$skipped oldRoundTripped=$oldRoundTripped identicalToOriginal=$identicalToOriginal sameAsOldSerializer=$sameAsOld changedAndBetter=$better changedAndWorse=$worse")
        worseReports.forEach { println("EDITOR-SAVE-WORSE: $it") }
    }
}
