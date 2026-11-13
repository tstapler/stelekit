// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.DimensionRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Local-only: runs [FusionHarness] over a real Logseq wiki, READ-ONLY (no write API is called on it).
 * Skipped unless `STELEKIT_FUSION_WIKI` is set, so CI never needs the wiki. Environment:
 *  - `STELEKIT_FUSION_WIKI`: wiki root. `STELEKIT_FUSION_PAGE_REGEX`: case-insensitive filter on page file names.
 *  - `STELEKIT_FUSION_YAML`, `_ELEVATION`, `_SECTION`: optional files, relative to the wiki root or absolute.
 *  - `STELEKIT_FUSION_REGISTRY`: optional registry JSON replacing the sanitised fixture registry.
 *  - `STELEKIT_FUSION_OUT`: where the full local report goes (default: the JVM temp dir). Keep it outside the repo.
 * The console gets counts and conflict kinds only.
 */
class FusionHarnessLocalRunTest {
    @Test
    fun fuseTheLocalWikiAndWriteTheReportOutsideTheRepo() = runTest {
        val root = System.getenv("STELEKIT_FUSION_WIKI")
        assumeTrue("STELEKIT_FUSION_WIKI not set", !root.isNullOrBlank())
        val wiki = Paths.get(root).toRealPath()
        val regex = Regex(requireNotNull(System.getenv("STELEKIT_FUSION_PAGE_REGEX")) { "set STELEKIT_FUSION_PAGE_REGEX" }, RegexOption.IGNORE_CASE)

        val all = listOf("pages", "logseq/pages").map(wiki::resolve).filter(Files::isDirectory).flatMap { dir ->
            Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".md") }.toList() }
        }.sorted()
        val selected = all.filter { regex.containsMatchIn(it.fileName.toString()) }
        fun optional(name: String): Path? = System.getenv(name)?.takeIf { it.isNotBlank() }?.let { wiki.resolve(it) }
        fun text(path: Path?): String? = path?.let(Files::readString)

        val registry = optional("STELEKIT_FUSION_REGISTRY")?.let { DimensionRegistry.fromJson(Files.readString(it)).fold({ e -> error("registry: $e") }, { r -> r }) }
            ?: ClosetFusion.registry
        val sheets = listOf("STELEKIT_FUSION_ELEVATION" to "elevation", "STELEKIT_FUSION_SECTION" to "section").mapNotNull { (env, role) ->
            text(optional(env))?.let { SheetSvg(role, "sheet-$role", it) }
        }
        val spec = FusionSpec(ClosetFusion.constraints(), listOf(SumCheck("Zones sum to opening", 1, "niche.width", ClosetFusion.zones)), ClosetFusion.zones)

        val report = FusionHarness.run(
            HarnessInputs(all.size, selected.map(Files::readString), text(optional("STELEKIT_FUSION_YAML")), sheets, registry, spec),
        )

        val out = Paths.get(System.getenv("STELEKIT_FUSION_OUT") ?: (System.getProperty("java.io.tmpdir") + "/fusion-harness-report.txt")).toAbsolutePath()
        require(!out.startsWith(wiki)) { "report must not be written inside the wiki" }
        Files.writeString(out, report.shareable + "\n" + report.localReport)
        println("FUSION-HARNESS\n" + report.shareable + "full report written to STELEKIT_FUSION_OUT")
        assertTrue(report.pagesScanned > 0, "no pages found under the wiki root")
    }
}
