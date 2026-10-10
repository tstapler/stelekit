package dev.stapler.stelekit.merge

import dev.stapler.stelekit.benchmark.SyntheticGraphGenerator
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CI form of Spike 0.1.4 (ADR-001 "Spike result"): the production guard is the splice-based
 * predicate, which passed 100% of the synthetic graph and 99.68% of the author's real one.
 * The env-gated real-graph run stays in [RoundTripGuardRealGraphTest]; this keeps the recorded
 * strictness from drifting in every CI run.
 */
class RoundTripGuardPassRateRegressionTest {
    private companion object {
        const val GO_THRESHOLD_PERCENT = 95.0
    }

    private fun pages(root: File) = listOf("pages", "journals").flatMap { sub ->
        File(root, sub).listFiles { f -> f.isFile && f.name.endsWith(".md") }?.toList().orEmpty()
    }.sortedBy { it.path }

    @Test
    fun `synthetic graph passes the splice-based guard at the recorded rate`() {
        val root = Files.createTempDirectory("guard-passrate").toFile()
        try {
            SyntheticGraphGenerator(SyntheticGraphGenerator.MEDIUM).generate(root)
            val files = pages(root)
            assertTrue(files.size >= 500, "generator produced ${files.size} files")
            val failures = files.mapNotNull { f ->
                RoundTripGuard.probe(f.readText(), f.path, f.parentFile.name == "journals").leftOrNull()?.let { f.name to it.message }
            }
            val pct = (files.size - failures.size) * 100.0 / files.size
            assertTrue(pct >= GO_THRESHOLD_PERCENT, "pass rate $pct% below the $GO_THRESHOLD_PERCENT% go threshold: ${failures.take(5)}")
            // Spike 0.1.4 recorded 100% on the synthetic graph; any drop is a guard regression.
            assertEquals(emptyList(), failures.take(5), "synthetic pass rate $pct% (recorded: 100%)")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `named failing real-graph shapes stay refused with the recorded reason`() {
        // The 35 real-graph failures: a fence or heading swallows an appended bullet.
        assertIs<NotRoundTrippable.AppendAbsorbed>(
            RoundTripGuard.probe(MergeFixtures.FENCE_WITH_DASH_LINES, MergeFixtures.PATH, false).leftOrNull(),
        )
        assertIs<NotRoundTrippable>(RoundTripGuard.probe(MergeFixtures.REAL_CRLF_TAB, MergeFixtures.PATH, false).leftOrNull())
    }

    @Test
    fun `clean fixtures and the empty file pass`() {
        listOf(
            MergeFixtures.REAL_CRLF_CLEAN, MergeFixtures.UNLABELED_FLAT, MergeFixtures.UNLABELED_NESTED,
            MergeFixtures.MIXED_LABELED, MergeFixtures.SPACE_INDENTED, "",
        ).forEach { text ->
            assertEquals(null, RoundTripGuard.probe(text, MergeFixtures.PATH, false).leftOrNull()?.message, "should pass: ${text.take(30)}")
        }
    }
}
