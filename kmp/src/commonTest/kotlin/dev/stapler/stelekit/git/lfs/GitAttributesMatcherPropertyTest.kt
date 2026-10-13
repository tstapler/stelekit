package dev.stapler.stelekit.git.lfs

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GitAttributesMatcherPropertyTest {
    private val patterns = listOf("*.png", "*.txt", "a.png", "assets/*", "/x.png", "*", "docs/**", "**/raw/*.bin")
    private val paths = listOf(
        "a.png", "b.png", "x.png", "n/x.png", "n/a.png", "notes.txt", "assets/a.png", "assets/s/a.png",
        "docs/a.md", "docs/s/t/a.md", "raw/f.bin", "q/raw/f.bin", "q/r/raw/f.bin", "README",
    )
    private val states = listOf("filter=lfs", "-filter", "!filter", "filter=other", "-text", "text")

    private data class Line(val pattern: String, val attr: String)

    private val arbLine = Arb.bind(Arb.element(patterns), Arb.element(states)) { p, a -> Line(p, a) }
    private val arbLines = Arb.list(arbLine, 0..8)

    private fun render(lines: List<Line>) = lines.joinToString("\n") { "${it.pattern} ${it.attr}" }

    private fun tracked(lines: List<Line>, path: String) =
        LfsPathPolicy(AttributeRuleSet(listOf(GitAttributesParser.parse(render(lines))))).isTracked(path)

    /** Deliberately naive second implementation over the small pattern vocabulary above. */
    private fun naiveMatches(pattern: String, path: String): Boolean {
        val base = path.substringAfterLast('/')
        return when (pattern) {
            "*.png" -> base.endsWith(".png")
            "*.txt" -> base.endsWith(".txt")
            "a.png" -> base == "a.png"
            "assets/*" -> path.startsWith("assets/") && !path.removePrefix("assets/").contains('/')
            "/x.png" -> path == "x.png"
            "*" -> true
            "docs/**" -> path.startsWith("docs/")
            "**/raw/*.bin" -> {
                val dirs = path.substringBeforeLast('/', "")
                base.endsWith(".bin") && (dirs == "raw" || dirs.endsWith("/raw"))
            }
            else -> error("unknown pattern $pattern")
        }
    }

    private fun naiveTracked(lines: List<Line>, path: String): Boolean {
        var state = "unspecified"
        for (line in lines) {
            if (!naiveMatches(line.pattern, path)) continue
            when (line.attr) {
                "filter=lfs" -> state = "lfs"
                "-filter", "!filter", "filter=other" -> state = "other"
            }
        }
        return state == "lfs"
    }

    @Test
    fun matchesNaiveReferenceOracle() = runTest {
        checkAll(arbLines, Arb.element(paths)) { lines, path ->
            assertEquals(naiveTracked(lines, path), tracked(lines, path), "rules=${render(lines)} path=$path")
        }
    }

    @Test
    fun unrelatedLineNeverChangesResult() = runTest {
        checkAll(arbLines, Arb.element(paths), Arb.element(patterns)) { lines, path, pattern ->
            val withNoise = lines + Line(pattern, "-text")

            assertEquals(tracked(lines, path), tracked(withNoise, path), "adding a -text line must not affect filter")
        }
    }

    @Test
    fun reorderingNonMatchingLinesIsNoOp() = runTest {
        checkAll(arbLines, Arb.element(paths)) { lines, path ->
            val matching = lines.filter { naiveMatches(it.pattern, path) }
            val nonMatching = lines.filterNot { naiveMatches(it.pattern, path) }

            assertEquals(
                tracked(matching, path),
                tracked(nonMatching.reversed() + matching + nonMatching, path),
                "non-matching lines must not influence the result",
            )
        }
    }

    @Test
    fun rootedAndRecursiveGlobEquivalence() = runTest {
        checkAll(Arb.element(paths)) { path ->
            val plain = tracked(listOf(Line("*.png", "filter=lfs")), path)
            val recursive = tracked(listOf(Line("**/*.png", "filter=lfs")), path)

            assertEquals(plain, recursive, "*.png and **/*.png must agree for $path")
        }
    }

    @Test
    fun laterLineWins() = runTest {
        checkAll(arbLines, Arb.element(paths)) { lines, path ->
            val matchingPattern = patterns.first { naiveMatches(it, path) }

            assertEquals(false, tracked(lines + Line(matchingPattern, "-filter"), path))
            assertEquals(true, tracked(lines + Line(matchingPattern, "filter=lfs"), path))
        }
    }

    @Test
    fun deeperFileWins() = runTest {
        checkAll(Arb.element(listOf("assets/a.png", "assets/s/a.png", "assets/x/y/z.png"))) { path ->
            fun resolve(rootAttr: String, nestedAttr: String) = LfsPathPolicy(
                AttributeRuleSet(
                    listOf(
                        GitAttributesParser.parse("*.png $rootAttr"),
                        GitAttributesParser.parse("*.png $nestedAttr", dirPrefix = "assets"),
                    ),
                ),
            ).isTracked(path)

            assertEquals(false, resolve("filter=lfs", "-filter"))
            assertEquals(true, resolve("-filter", "filter=lfs"))
        }
    }
}
