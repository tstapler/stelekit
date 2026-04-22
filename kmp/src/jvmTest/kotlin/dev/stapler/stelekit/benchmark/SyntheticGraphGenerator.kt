package dev.stapler.stelekit.benchmark

import java.io.File
import kotlin.random.Random

/**
 * Generates synthetic Stelekit/Logseq graphs on disk for benchmarking.
 *
 * Produces realistic-looking markdown with configurable page counts, journal counts,
 * link density, and block hierarchy depth. All output is deterministic for a given seed.
 *
 * Usage:
 *   val gen = SyntheticGraphGenerator(SyntheticGraphGenerator.DENSE)
 *   val stats = gen.generate(outputDir)
 *   // stats.graphDir contains pages/ and journals/ ready for GraphLoader
 *
 * Preset configs:
 *   TINY     — 50 pages,  14 journals, sparse links  (quick smoke test)
 *   SMALL    — 200 pages, 30 journals, moderate links (CI regression baseline)
 *   MEDIUM   — 500 pages, 90 journals, moderate links (realistic personal library)
 *   LARGE    — 2000 pages, 365 journals, dense links  (stress test)
 *   MESH     — 500 pages, 90 journals, 80% link density (worst-case Aho-Corasick)
 */
class SyntheticGraphGenerator(val config: Config = Config()) {

    data class Config(
        val pageCount: Int = 200,
        val journalCount: Int = 30,
        /** Fraction of blocks that contain at least one WikiLink (0.0–1.0). */
        val linkDensity: Float = 0.2f,
        /** Range of WikiLinks inserted into a block when it has links. */
        val linksPerBlock: IntRange = 1..3,
        /** Range of top-level blocks per page. */
        val blocksPerPage: IntRange = 3..15,
        /** Maximum nesting depth of child blocks. */
        val hierarchyDepth: Int = 3,
        /** Fraction of pages that use namespace notation ("Area/Topic"). */
        val namespaceFraction: Float = 0.15f,
        /** Reproducible output — change to get a different graph shape. */
        val seed: Long = 42L,
    )

    data class Stats(
        val pageCount: Int,
        val journalCount: Int,
        val totalBlocks: Int,
        val totalLinks: Int,
        val graphDir: File,
    )

    companion object {
        val TINY   = Config(pageCount =   50, journalCount =  14, linkDensity = 0.05f, blocksPerPage = 2..8,  hierarchyDepth = 2)
        val SMALL  = Config(pageCount =  200, journalCount =  30, linkDensity = 0.20f, blocksPerPage = 3..15, hierarchyDepth = 3)
        val MEDIUM = Config(pageCount =  500, journalCount =  90, linkDensity = 0.25f, blocksPerPage = 4..20, hierarchyDepth = 4)
        val LARGE  = Config(pageCount = 2000, journalCount = 365, linkDensity = 0.35f, blocksPerPage = 3..25, hierarchyDepth = 4)
        val MESH   = Config(pageCount =  500, journalCount =  90, linkDensity = 0.80f, linksPerBlock = 3..8,  blocksPerPage = 4..15, hierarchyDepth = 3)
    }

    // ── word pools for generating realistic page names ─────────────────────

    private val topics = listOf(
        "Philosophy", "Mathematics", "Programming", "Literature", "History",
        "Economics", "Psychology", "Biology", "Physics", "Chemistry",
        "Architecture", "Design", "Music", "Art", "Cinema",
        "Health", "Nutrition", "Exercise", "Meditation", "Sleep",
        "Finance", "Investing", "Productivity", "Leadership", "Communication",
        "Learning", "Memory", "Focus", "Habits", "Goals",
        "Systems Thinking", "Mental Models", "First Principles", "Decision Making",
        "Note Taking", "Knowledge Management", "Personal Finance", "Time Management",
        "Software Design", "Data Structures", "Algorithms", "Distributed Systems",
        "Machine Learning", "Statistics", "Linear Algebra", "Probability",
        "Stoicism", "Epistemology", "Ethics", "Logic", "Metaphysics",
        "Ecology", "Climate", "Energy", "Sustainability", "Urban Planning",
        "Cooking", "Travel", "Languages", "Writing", "Public Speaking",
        "Entrepreneurship", "Strategy", "Marketing", "Sales", "Negotiation",
        "Relationships", "Parenting", "Community", "Culture", "Society",
        "Cognitive Science", "Neuroscience", "Genetics", "Evolution", "Anthropology",
    )

    private val namespaces = listOf(
        "Projects", "Books", "Meetings", "People", "Resources",
        "Areas", "Archive", "Ideas", "Research", "Notes",
    )

    private val sentenceFragments = listOf(
        "This is related to", "Key insight:", "See also", "Important concept:",
        "Note that", "Remember:", "TODO:", "Hypothesis:", "Example:",
        "Definition:", "Question:", "Observation:", "Reference:", "Summary:",
        "According to", "The main idea is", "One approach is", "Consider",
        "Evidence suggests", "This connects to", "Contrast with", "Building on",
    )

    private val fillerPhrases = listOf(
        "the fundamental principles behind this",
        "a nuanced understanding of the domain",
        "practical applications in everyday life",
        "the historical context and development",
        "current research and open questions",
        "key frameworks and mental models",
        "common pitfalls and misconceptions",
        "first-principles reasoning",
        "empirical evidence and case studies",
        "the systemic effects and feedback loops",
        "counterintuitive results and edge cases",
        "the underlying mechanisms and causes",
        "best practices and recommendations",
        "trade-offs and constraints",
        "future directions and open problems",
    )

    // ── public API ─────────────────────────────────────────────────────────

    fun generate(outputDir: File): Stats {
        val rng = Random(config.seed)
        outputDir.mkdirs()
        val pagesDir   = File(outputDir, "pages").also   { it.mkdirs() }
        val journalsDir = File(outputDir, "journals").also { it.mkdirs() }

        val pageNames = buildPageNames(rng)
        var totalBlocks = 0
        var totalLinks  = 0

        for (name in pageNames) {
            val (content, blocks, links) = generatePageContent(name, pageNames, rng)
            totalBlocks += blocks
            totalLinks  += links
            val fileName = name.replace('/', '%').replace(' ', '_') + ".md"
            File(pagesDir, fileName).writeText(content)
        }

        for (i in 0 until config.journalCount) {
            val date = baseDate.minusDays(i)
            val (content, blocks, links) = generateJournalContent(date, pageNames, rng)
            totalBlocks += blocks
            totalLinks  += links
            val fileName = "${date.year}_${date.month.toString().padStart(2, '0')}_${date.day.toString().padStart(2, '0')}.md"
            File(journalsDir, fileName).writeText(content)
        }

        return Stats(
            pageCount    = pageNames.size,
            journalCount = config.journalCount,
            totalBlocks  = totalBlocks,
            totalLinks   = totalLinks,
            graphDir     = outputDir,
        )
    }

    // ── private helpers ────────────────────────────────────────────────────

    private fun buildPageNames(rng: Random): List<String> {
        val names = mutableSetOf<String>()

        // Namespace pages
        val nsCount = (config.pageCount * config.namespaceFraction).toInt()
        repeat(nsCount) {
            val ns = namespaces.random(rng)
            val topic = topics.random(rng)
            names.add("$ns/$topic")
        }

        // Plain topic pages
        val shuffled = topics.shuffled(rng)
        for (topic in shuffled) {
            if (names.size >= config.pageCount) break
            names.add(topic)
        }

        // Synthetic names to fill up to pageCount
        var idx = 0
        while (names.size < config.pageCount) {
            names.add("${topics[idx % topics.size]} ${idx / topics.size + 2}")
            idx++
        }

        return names.take(config.pageCount).toList()
    }

    private data class GeneratedContent(val markdown: String, val blockCount: Int, val linkCount: Int)

    private fun generatePageContent(
        pageName: String,
        allPages: List<String>,
        rng: Random,
    ): GeneratedContent {
        val sb = StringBuilder()
        val blockCount = config.blocksPerPage.random(rng)
        var blocks = 0
        var links  = 0

        repeat(blockCount) { i ->
            val (line, lineLinks) = generateBlock(pageName, allPages, rng, indent = 0, position = i)
            sb.append(line)
            links  += lineLinks
            blocks += 1

            // Child blocks
            if (config.hierarchyDepth > 1 && rng.nextFloat() < 0.4f) {
                val childDepth = (1 until minOf(config.hierarchyDepth, 4)).random(rng)
                val childCount = (1..3).random(rng)
                repeat(childCount) { j ->
                    val (child, childLinks) = generateBlock(pageName, allPages, rng, indent = childDepth, position = j)
                    sb.append(child)
                    links  += childLinks
                    blocks += 1
                }
            }
        }

        return GeneratedContent(sb.toString(), blocks, links)
    }

    private fun generateJournalContent(
        date: SimpleDate,
        allPages: List<String>,
        rng: Random,
    ): GeneratedContent {
        val sb = StringBuilder()
        val blockCount = (2..8).random(rng)
        var blocks = 0
        var links  = 0

        repeat(blockCount) { i ->
            val (line, lineLinks) = generateBlock("journal-${date}", allPages, rng, indent = 0, position = i)
            sb.append(line)
            links  += lineLinks
            blocks += 1
        }

        return GeneratedContent(sb.toString(), blocks, links)
    }

    private fun generateBlock(
        sourcePage: String,
        allPages: List<String>,
        rng: Random,
        indent: Int,
        position: Int,
    ): Pair<String, Int> {
        val prefix = "  ".repeat(indent) + "- "
        val fragment = sentenceFragments.random(rng)
        val filler   = fillerPhrases.random(rng)

        var links = 0
        val sb = StringBuilder()
        sb.append(prefix)
        sb.append("$fragment $filler")

        if (rng.nextFloat() < config.linkDensity) {
            val linkCount = config.linksPerBlock.random(rng)
            val candidates = allPages.filter { it != sourcePage }.shuffled(rng)
            for (k in 0 until minOf(linkCount, candidates.size)) {
                sb.append(" [[${candidates[k]}]]")
                links++
            }
        }

        sb.append("\n")
        return sb.toString() to links
    }

    // ── minimal date arithmetic (avoids kotlinx-datetime in test utils) ────

    private val baseDate = SimpleDate(2026, 1, 15)

    private data class SimpleDate(val year: Int, val month: Int, val day: Int) {
        fun minusDays(n: Int): SimpleDate {
            // Day-level arithmetic only; good enough for generating filenames.
            var d = day - n
            var m = month
            var y = year
            while (d <= 0) {
                m--
                if (m <= 0) { m = 12; y-- }
                d += daysInMonth(y, m)
            }
            return SimpleDate(y, m, d)
        }

        override fun toString() = "$year-${month.toString().padStart(2,'0')}-${day.toString().padStart(2,'0')}"

        private fun daysInMonth(y: Int, m: Int): Int = when (m) {
            2    -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}
