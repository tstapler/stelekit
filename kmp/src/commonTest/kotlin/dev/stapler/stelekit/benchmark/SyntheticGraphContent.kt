package dev.stapler.stelekit.benchmark

import kotlin.random.Random

/**
 * Platform-agnostic in-memory graph content for benchmarking.
 *
 * Mirrors the structure of [SyntheticGraphGenerator] (JVM-only) but uses no java.io.File,
 * no AtomicInteger, and no System APIs — safe for wasmJs and all commonTest consumers.
 *
 * keys   = page/journal name
 * values = list of markdown block strings (one entry per block, without leading "- ")
 */
data class PageContent(
    val name: String,
    val blocks: List<String>,
)

data class SyntheticGraphContent(
    val pages: List<PageContent>,
    val journals: List<PageContent>,
)

/**
 * Generates [SyntheticGraphContent] matching [GraphPreset] page and journal counts.
 * Output is fully deterministic for a given [seed].
 *
 * Preset counts are aligned with [SyntheticGraphGenerator] companion constants:
 *   TINY   —  50 pages, 14 journals
 *   SMALL  — 200 pages, 30 journals
 *   MEDIUM — 500 pages, 90 journals
 *   LARGE  — 2000 pages, 365 journals
 */
object SyntheticGraphContentFactory {

    fun build(preset: GraphPreset, seed: Long = 42L): SyntheticGraphContent {
        val rng = Random(seed)
        val (pageCount, journalCount, minBlocks, maxBlocks, linkDensity) = presetParams(preset)

        val pageNames = (0 until pageCount).map { i -> "Page ${i + 1}" }

        val pages = pageNames.map { name ->
            val blockCount = rng.nextInt(minBlocks, maxBlocks + 1)
            val blocks = (0 until blockCount).map { b ->
                buildBlock(rng, b, pageNames, linkDensity)
            }
            PageContent(name, blocks)
        }

        val journals = (0 until journalCount).mapIndexed { i, _ ->
            val date = formatJournalDate(2024, i)
            val blockCount = rng.nextInt(minBlocks, maxBlocks + 1)
            val blocks = (0 until blockCount).map { b ->
                buildBlock(rng, b, pageNames, linkDensity)
            }
            PageContent(date, blocks)
        }

        return SyntheticGraphContent(pages, journals)
    }

    fun tiny(): SyntheticGraphContent = build(GraphPreset.TINY)

    fun small(): SyntheticGraphContent = build(GraphPreset.SMALL)

    private data class Params(
        val pageCount: Int,
        val journalCount: Int,
        val minBlocks: Int,
        val maxBlocks: Int,
        val linkDensity: Float,
    )

    private fun presetParams(preset: GraphPreset): Params = when (preset) {
        GraphPreset.TINY   -> Params(  50,  14, 2,  8, 0.05f)
        GraphPreset.SMALL  -> Params( 200,  30, 3, 15, 0.20f)
        GraphPreset.MEDIUM -> Params( 500,  90, 4, 20, 0.25f)
        GraphPreset.LARGE  -> Params(2000, 365, 3, 25, 0.35f)
    }

    private fun buildBlock(rng: Random, index: Int, pageNames: List<String>, linkDensity: Float): String {
        val base = "Block content $index with some text about a topic"
        return if (rng.nextFloat() < linkDensity && pageNames.isNotEmpty()) {
            val target = pageNames[rng.nextInt(pageNames.size)]
            "$base — see [[${target}]]"
        } else {
            base
        }
    }

    private fun formatJournalDate(baseYear: Int, dayOffset: Int): String {
        val year = baseYear + dayOffset / 365
        val dayOfYear = dayOffset % 365
        val month = (dayOfYear / 30).coerceIn(0, 11) + 1
        val day = (dayOfYear % 30) + 1
        return "${year}_${month.toString().padStart(2, '0')}_${day.toString().padStart(2, '0')}"
    }
}
