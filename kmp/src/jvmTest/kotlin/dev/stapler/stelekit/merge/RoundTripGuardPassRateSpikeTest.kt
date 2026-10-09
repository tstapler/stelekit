package dev.stapler.stelekit.merge

import dev.stapler.stelekit.benchmark.SyntheticGraphGenerator
import dev.stapler.stelekit.db.LogseqPageSerializer
import dev.stapler.stelekit.db.MarkdownPageParser
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.parsing.ParseMode
import kotlin.time.Clock
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * THROW-AWAY spike (plan Story 0.1.4, ADR-001): measures how many real pages satisfy
 * `serialize(parse(file)) == file` (exact) and `parse(serialize(parse(f))) == parse(f)`
 * (structure-stable). Story 1.1.4 replaces this with the production guard.
 *
 * Inputs: env SPIKE_GRAPH_PATH (real graph, read-only) and/or SPIKE_SYNTHETIC=1
 * (SyntheticGraphGenerator.XLARGE, the config scripts/benchmark-local.sh uses; written to a temp dir).
 * Report is also written to kmp/build/spike-roundtrip-report.txt.
 */
class RoundTripGuardPassRateSpikeTest {

    private data class Snapshot(val pageProps: Map<String, String>, val blocks: List<Block>)

    private data class Verdict(val file: File, val exact: Boolean, val stable: Boolean, val cause: String, val reason: String)

    private val now = Clock.System.now()
    private val report = StringBuilder()
    private fun out(s: String = "") { println(s); report.appendLine(s) }

    @Test
    fun measure() {
        val realPath = System.getenv("SPIKE_GRAPH_PATH")?.takeIf { it.isNotBlank() }
        val synthetic = System.getenv("SPIKE_SYNTHETIC") == "1"
        if (realPath == null && !synthetic) {
            println("SKIPPED RoundTripGuardPassRateSpikeTest: no graph supplied (set SPIKE_GRAPH_PATH=/path/to/graph and/or SPIKE_SYNTHETIC=1)")
            return
        }
        if (realPath != null) run("REAL graph $realPath", File(realPath))
        if (synthetic) {
            val dir = Files.createTempDirectory("spike-xlarge").toFile()
            SyntheticGraphGenerator(SyntheticGraphGenerator.XLARGE).generate(dir)
            try { run("SYNTHETIC XLARGE (SyntheticGraphGenerator.XLARGE, UNVERIFIED against real data)", dir) }
            finally { dir.deleteRecursively() }
        }
        val f = File("build/spike-roundtrip-report.txt")
        f.parentFile?.mkdirs()
        f.writeText(report.toString())
    }

    private fun run(label: String, root: File) {
        val files = listOf("pages", "journals").flatMap { sub ->
            File(root, sub).listFiles { x -> x.isFile && x.name.endsWith(".md") }?.toList().orEmpty()
        }.sortedBy { it.path }
        val verdicts = files.map { evaluate(it) }
        val total = verdicts.size
        val exact = verdicts.count { it.exact }
        val stable = verdicts.count { it.stable }
        fun pct(n: Int) = if (total == 0) 0.0 else n * 100.0 / total
        out("=== $label ===")
        out("total files: $total")
        out("EXACT pass: $exact (${"%.2f".format(pct(exact))}%)")
        out("STRUCTURE-STABLE pass: $stable (${"%.2f".format(pct(stable))}%) [measured always; the decision rule only needs it when exact < 95%]")
        val failures = verdicts.filter { !it.exact }
        out("exact-failure groups:")
        failures.groupingBy { it.cause }.eachCount().entries.sortedByDescending { it.value }
            .forEach { out("  ${it.key}: ${it.value}") }
        out("first 20 exact failures:")
        failures.take(20).forEach { out("  [${it.cause}] ${it.file.name}: ${it.reason}") }
        val unstable = verdicts.filter { !it.stable }
        out("structure-stable failures: ${unstable.size}")
        unstable.take(20).forEach { out("  [${it.cause}] ${it.file.name}: ${it.reason}") }
        out()
    }

    private fun parseSnapshot(text: String, path: String, isJournal: Boolean): Pair<Snapshot, Page> {
        val parsed = MarkdownParser().parsePage(text, ParseMode.FULL)
        val built = MarkdownPageParser.buildPageModel(
            filePath = path, name = File(path).nameWithoutExtension, isJournal = isJournal,
            journalDate = null, existingPage = null, now = now, mode = ParseMode.FULL,
            parsedPage = parsed, fileModTime = null,
        )
        val roots = if (built.firstBlockSkipped) parsed.blocks.drop(1) else parsed.blocks
        val blocks = mutableListOf<Block>()
        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = roots, pagePath = path, pageUuid = built.page.uuid, parentUuid = null,
            baseLevel = 0, now = now, destinationList = blocks, mode = ParseMode.FULL,
        )
        return Snapshot(built.page.properties, blocks) to built.page
    }

    private fun evaluate(file: File): Verdict {
        val original = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        val path = file.path
        val isJournal = file.parentFile.name == "journals"
        return try {
            val (first, page) = parseSnapshot(original, path, isJournal)
            val rendered = LogseqPageSerializer.serialize(page, first.blocks)
            val exact = rendered == original
            val stable = exact || run {
                val (second, _) = parseSnapshot(rendered, path, isJournal)
                structureEquals(first, second)
            }
            if (exact) Verdict(file, true, true, "", "")
            else Verdict(file, false, stable, classify(original, rendered), diffReason(original, rendered))
        } catch (e: Throwable) {
            Verdict(file, false, false, "exception", "${e::class.simpleName}: ${e.message?.take(120)}")
        }
    }

    // uuid, content, properties (maps, so ordering-insensitive), nesting (level + parent), sibling order
    private fun structureEquals(a: Snapshot, b: Snapshot): Boolean {
        if (a.pageProps != b.pageProps || a.blocks.size != b.blocks.size) return false
        return a.blocks.zip(b.blocks).all { (x, y) ->
            x.uuid == y.uuid && x.content == y.content && x.properties == y.properties &&
                x.level == y.level && x.parentUuid == y.parentUuid
        }
    }

    private fun classify(orig: String, ser: String): String {
        if (orig.contains("\r")) return "line endings"
        fun rtrim(s: String) = s.lines().joinToString("\n") { it.trimEnd() }
        if (rtrim(orig) == rtrim(ser)) return "trailing whitespace"
        fun noBlank(s: String) = s.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        if (noBlank(orig) == noBlank(ser)) return "blank lines / final newline"
        if (noBlank(orig).sorted() == noBlank(ser).sorted()) return "property ordering (or sibling line order)"
        fun lstrip(s: String) = noBlank(s).map { it.trimStart() }
        if (lstrip(orig) == lstrip(ser)) return "indentation (spaces vs tabs / depth)"
        return "unsupported construct (content/structure rewritten)"
    }

    private fun diffReason(orig: String, ser: String): String {
        val a = orig.split("\n"); val b = ser.split("\n")
        val i = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: minOf(a.size, b.size)
        fun q(l: List<String>) = l.getOrNull(i)?.let { "'" + it.take(60).replace("\t", "\\t").replace("\r", "\\r") + "'" } ?: "<EOF>"
        return "line ${i + 1}: file=${q(a)} vs serialized=${q(b)}"
    }
}
