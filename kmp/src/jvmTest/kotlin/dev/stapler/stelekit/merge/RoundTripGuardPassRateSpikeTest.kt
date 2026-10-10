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
 * (structure-stable), plus the SPLICE-BASED predicate: the planned MarkdownSplicer only inserts new
 * lines into the original bytes, so the guard is "parse(spliced) == parse(original) + exactly the new
 * block, untouched lines byte-identical" for (a) a new last root block and (b) a new last child of the
 * first bullet. The splice is a throw-away line-based stand-in (production MarkdownSplicer is Phase 2).
 * Story 1.1.4 replaces this with the production guard.
 *
 * Inputs: env SPIKE_GRAPH_PATH (real graph, read-only) and/or SPIKE_SYNTHETIC=1
 * (SyntheticGraphGenerator.XLARGE, the config scripts/benchmark-local.sh uses; written to a temp dir).
 * Report is also written to kmp/build/spike-roundtrip-report.txt.
 */
class RoundTripGuardPassRateSpikeTest {

    private data class Snapshot(val pageProps: Map<String, String>, val blocks: List<Block>)

    private data class Verdict(
        val file: File, val exact: Boolean, val stable: Boolean, val cause: String, val reason: String,
        val splice: SpliceResult,
    )

    /** [rootCause]/[childCause] are null on pass; [childApplicable] false when the page has no bullet to nest under. */
    private data class SpliceResult(
        val rootCause: String?, val rootReason: String,
        val childCause: String?, val childReason: String, val childApplicable: Boolean,
    ) {
        val ok get() = rootCause == null && childCause == null
    }

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
        val spliceOk = verdicts.count { it.splice.ok }
        val rootOk = verdicts.count { it.splice.rootCause == null }
        val childOk = verdicts.count { it.splice.childCause == null }
        val childNa = verdicts.count { !it.splice.childApplicable }
        out("SPLICE-BASED pass (root-append AND child-append): $spliceOk (${"%.2f".format(pct(spliceOk))}%)")
        out("  root-append alone: $rootOk (${"%.2f".format(pct(rootOk))}%); child-append alone: $childOk (${"%.2f".format(pct(childOk))}%, of which $childNa pages had no bullet so child variant not applicable and counted as pass)")
        val spliceFail = verdicts.filter { !it.splice.ok }
        out("splice-failure groups (a file can appear under both variants):")
        spliceFail.flatMap { v -> listOfNotNull(v.splice.rootCause?.let { "root-append: $it" }, v.splice.childCause?.let { "child-append: $it" }) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { out("  ${it.key}: ${it.value}") }
        out("first 150 splice failures:")
        spliceFail.take(150).forEach { v ->
            out("  ${v.file.name}: " + listOfNotNull(v.splice.rootCause?.let { "root[$it] ${v.splice.rootReason}" }, v.splice.childCause?.let { "child[$it] ${v.splice.childReason}" }).joinToString(" | "))
        }
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
            val splice = spliceCheck(original, first, path, isJournal)
            val rendered = LogseqPageSerializer.serialize(page, first.blocks)
            val exact = rendered == original
            val stable = exact || run {
                val (second, _) = parseSnapshot(rendered, path, isJournal)
                structureEquals(first, second)
            }
            if (exact) Verdict(file, true, true, "", "", splice)
            else Verdict(file, false, stable, classify(original, rendered), diffReason(original, rendered), splice)
        } catch (e: Throwable) {
            Verdict(file, false, false, "exception", "${e::class.simpleName}: ${e.message?.take(120)}", SpliceResult("exception", "", "exception", "", true))
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

    // ---- splice-based predicate ------------------------------------------------------------

    private val rootMarker = "SPIKE_NEW_ROOT_BLOCK"
    private val childMarker = "SPIKE_NEW_CHILD_BLOCK"
    private val bulletLine = Regex("^(\\t*)- .*|^(\\t*)-$")

    private fun spliceCheck(original: String, t: Snapshot, path: String, isJournal: Boolean): SpliceResult {
        val origLines = original.split("\n")
        val endsWithNewline = original.endsWith("\n")

        // (a) new last root block
        val rootAt = if (endsWithNewline) origLines.size - 1 else origLines.size
        val rootErr = verifySplice(origLines, rootAt, "- $rootMarker", rootMarker, t, path, isJournal) { new ->
            if (new.level != 0 || new.parentUuid != null) "new root block mis-placed (level=${new.level}, parent=${new.parentUuid})" else null
        }

        // (b) new last child of the first bullet in the file
        val target = findFirstBulletSubtreeEnd(origLines)
        if (target == null) return SpliceResult(rootErr?.first, rootErr?.second.orEmpty(), null, "", false)
        val (bulletIdx, insertAt, indent) = target
        val firstBullet = t.blocks.firstOrNull { it.level == indent.length && it.parentUuid == null }
            ?: t.blocks.firstOrNull()
        val childErr = verifySplice(origLines, insertAt, "$indent\t- $childMarker", childMarker, t, path, isJournal) { new ->
            val parent = t.blocks.firstOrNull { it.uuid == new.parentUuid }
            if (new.level != indent.length + 1 || parent == null || parent.level != indent.length)
                "new child mis-placed (level=${new.level}, parent level=${parent?.level}, expected parent level ${indent.length}, first-bullet line ${bulletIdx + 1}, firstBlockLevel=${firstBullet?.level})" else null
        }
        return SpliceResult(rootErr?.first, rootErr?.second.orEmpty(), childErr?.first, childErr?.second.orEmpty(), true)
    }

    /** Line index of the first bullet's subtree end (insertion point for a last child) + that bullet's indent, tracking fences. */
    private fun findFirstBulletSubtreeEnd(lines: List<String>): Triple<Int, Int, String>? {
        var inFence = false
        var start = -1
        var indent = ""
        var lastContent = -1
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            val isFence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            if (start < 0) {
                if (!inFence && bulletLine.matches(line)) { start = i; indent = line.takeWhile { it == '\t' }; lastContent = i }
                if (isFence) inFence = !inFence
                continue
            }
            if (!inFence && bulletLine.matches(line) && line.takeWhile { it == '\t' }.length <= indent.length) break
            if (line.isNotBlank()) lastContent = i
            if (isFence) inFence = !inFence
        }
        return if (start < 0) null else Triple(start, lastContent + 1, indent)
    }

    /** Returns null on pass, else (cause, reason). */
    private fun verifySplice(
        origLines: List<String>, insertAt: Int, newLine: String, marker: String,
        t: Snapshot, path: String, isJournal: Boolean, checkNew: (Block) -> String?,
    ): Pair<String, String>? {
        // A 0-byte file has no lines to preserve; a real splicer emits just the new block (no leading blank line).
        val blankFile = origLines == listOf("")
        val splicedLines = if (blankFile) listOf(newLine, "") else origLines.toMutableList().also { it.add(insertAt, newLine) }
        // untouched lines byte-identical: dropping the inserted line must give back the original exactly
        if (!blankFile) {
            val back = splicedLines.toMutableList().also { it.removeAt(insertAt) }
            if (back != origLines) return "splice bug: untouched lines changed" to ""
        }
        val spliced = splicedLines.joinToString("\n")
        val after = try { parseSnapshot(spliced, path, isJournal).first } catch (e: Throwable) {
            return "parse exception on spliced" to "${e::class.simpleName}"
        }
        val newBlocks = after.blocks.filter { it.content == marker }
        if (newBlocks.size != 1) {
            return if (newBlocks.isEmpty()) "new block absorbed into existing content" to "last lines: " + origLines.filter { it.isNotEmpty() }.takeLast(2).joinToString(" // ") { it.take(50).replace("\t", "\\t") }
            else "new block duplicated" to "${newBlocks.size} blocks"
        }
        val new = newBlocks.single()
        val rest = after.blocks.filter { it !== new }
        if (after.pageProps != t.pageProps) return "page properties changed" to "${t.pageProps} -> ${after.pageProps}"
        if (rest.size != t.blocks.size) return "pre-existing block count changed" to "${t.blocks.size} -> ${rest.size}"
        for ((i, pair) in t.blocks.zip(rest).withIndex()) {
            val (x, y) = pair
            val what = when {
                x.uuid != y.uuid -> "uuid"
                x.content != y.content -> "content"
                x.properties != y.properties -> "properties"
                x.level != y.level || x.parentUuid != y.parentUuid -> "nesting"
                x.position != y.position -> "sibling order"
                else -> null
            }
            if (what != null) return "pre-existing block $what changed" to "block #$i '${x.content.take(40).replace("\n", "\\n")}' vs '${y.content.take(40).replace("\n", "\\n")}'"
        }
        checkNew(new)?.let { return "new block placement wrong" to it }
        return null
    }
}
