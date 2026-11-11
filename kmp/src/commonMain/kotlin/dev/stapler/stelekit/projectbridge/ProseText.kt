package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.SourceOrigin
import dev.stapler.stelekit.parsing.TableExtraction

/** What one prose pass read. [skippedSelf] counts blocks (or the whole page) dropped for carrying `stelekit-digest::`. */
data class ProseExtractionResult(
    val observations: List<Observation> = emptyList(),
    val skippedSelf: Int = 0,
) {
    operator fun plus(other: ProseExtractionResult) =
        ProseExtractionResult(observations + other.observations, skippedSelf + other.skippedSelf)
}

/** One eligible prose line; [number] is 1-based within the scanned text. */
internal data class ProseLine(val number: Int, val text: String)

/** Where a length written in text starts and ends, and the text to hand to the length parser. */
internal data class LengthSpan(val start: Int, val end: Int, val text: String)

/** A `qty`-style or `N noun` count found in a line. */
internal data class CountHit(val index: Int, val value: Long)

/** Pure text scanning shared by [ProseMeasurementExtractor] and [CountExtractor]. */
internal object ProseText {
    private val BULLET = Regex("""^(\s*)-(?:\s(.*))?$""")
    private val PROPERTY_KEY = Regex("""^\s*([A-Za-z0-9_-]+)::(?:\s.*)?$""")
    private val LENGTH_SPAN = Regex(
        """~?\s*(?:\d+\s*'\s*)?(?:\d+(?:\.\d+)?(?:[ -]\d+/\d+)?|\d+/\d+)\s*(?:"|″|in\b|inch(?:es)?\b)|~?\s*\d+\s*'""",
    )
    private val LIST_GAP = Regex("""^\s*[,;]\s*$""")
    private val TOKEN = Regex("""\d+|[A-Za-z][A-Za-z'-]*|\S""")
    private val QUANTITY_WORDS = setOf("qty", "quantity")
    private const val MAX_NOUN_GAP = 3

    private class Block(val indent: Int, val lines: MutableList<Pair<Int, String>> = ArrayList())

    /**
     * Lines of [text] that may be read: not in a table, not a property line, and not inside a
     * block (or child of a block) that carries `stelekit-digest::`. Page-level markers
     * ([page] or a first-block property) drop everything.
     */
    fun eligibleLines(text: String, page: SourceOrigin): Pair<List<ProseLine>, Int> {
        val origin = page.copy(firstBlockPropertyKeys = page.firstBlockPropertyKeys + TableExtraction.firstBlockPropertyKeys(text))
        if (origin.carriesDigestMarker) return emptyList<ProseLine>() to 1
        val kept = ArrayList<ProseLine>()
        var skipped = 0
        var skipBelowIndent: Int? = null
        for (block in blocks(text)) {
            val inherited = skipBelowIndent?.let { block.indent > it } ?: false
            if (!inherited) skipBelowIndent = null
            val marked = block.lines.any { (_, line) ->
                PROPERTY_KEY.find(line)?.groupValues?.get(1)?.let { SourceOrigin(blockPropertyKeys = setOf(it)).carriesDigestMarker } == true
            }
            if (marked && !inherited) {
                skipBelowIndent = block.indent
                skipped++
            }
            if (marked || inherited) continue
            for ((number, line) in block.lines) {
                val clean = line.replace("**", "").trim()
                if (clean.isEmpty() || clean.startsWith("|") || PROPERTY_KEY.matches(clean)) continue
                kept.add(ProseLine(number, clean))
            }
        }
        return kept to skipped
    }

    private fun blocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        text.lines().forEachIndexed { index, raw ->
            val number = index + 1
            val bullet = BULLET.matchEntire(raw)
            if (bullet != null) {
                out.add(Block(bullet.groupValues[1].length).also { it.lines.add(number to bullet.groupValues[2]) })
            } else {
                if (out.isEmpty()) out.add(Block(-1))
                out.last().lines.add(number to raw)
            }
        }
        return out
    }

    /** First case-insensitive position in [line] of any of [aliases], or null. */
    fun anchorIndex(entry: DimensionEntry, line: String): Int? =
        entry.aliases.prose.filter { it.isNotBlank() }.mapNotNull { line.indexOf(it.trim(), ignoreCase = true).takeIf { i -> i >= 0 } }.minOrNull()

    /**
     * Lengths with a unit mark in [region]. Spans that are part of something else (a screw gauge
     * `#10 x 2.5"`, a member size `2x4"`, a range `51-54"`, a decimal tail) are dropped.
     */
    fun lengthSpans(region: String): List<LengthSpan> =
        LENGTH_SPAN.findAll(region).mapNotNull {
            val start = it.range.first + (it.value.length - it.value.trimStart().length)
            LengthSpan(start, it.range.last + 1, it.value.trim()).takeUnless { span -> isFragment(region.substring(0, span.start)) }
        }.toList()

    /** True when what precedes a number shows the number is one part of a larger expression. */
    private fun isFragment(before: String): Boolean {
        val prev = before.lastOrNull() ?: return false
        if (prev.isLetterOrDigit() || prev in "#./") return true
        if (prev == '-') return before.getOrNull(before.length - 2)?.isDigit() == true
        return afterMultiplier(before)
    }

    /** `x` or `×` as a multiplication sign: `4 rods x 2`, `#10 × 2.5`, but not the end of `box`. */
    private fun afterMultiplier(before: String): Boolean {
        val trimmed = before.trimEnd()
        if (trimmed.endsWith('×')) return true
        return trimmed.endsWith('x') && trimmed.getOrNull(trimmed.length - 2)?.let { it.isWhitespace() || it.isDigit() } == true
    }

    /**
     * The length [region] states: after the last `=` when there is one (a sum), else the first
     * span; null when the first two spans form a comma list (which one is meant is not guessed).
     */
    fun statedLength(region: String): LengthSpan? {
        val tail = region.substringAfterLast('=', "").takeIf { region.contains('=') } ?: region
        val spans = lengthSpans(tail)
        val first = spans.firstOrNull() ?: return null
        val second = spans.getOrNull(1)
        if (!region.contains('=') && second != null && LIST_GAP.matches(tail.substring(first.end, second.start))) return null
        return first
    }

    /** First `qty N` or `N <up to two words> noun` in [line], where [noun] matches by singular stem. */
    fun statedCount(line: String, noun: String): CountHit? {
        val tokens = TOKEN.findAll(line).toList()
        val target = stem(noun)
        for ((i, token) in tokens.withIndex()) {
            if (!token.value[0].isDigit() || isFragmentNumber(line, token)) continue
            val value = token.value.toLongOrNull() ?: continue
            val previous = tokens.getOrNull(i - 1)?.value?.lowercase()
            if (previous in QUANTITY_WORDS || nounFollows(tokens, i, target)) return CountHit(token.range.first, value)
        }
        return null
    }

    private fun nounFollows(tokens: List<MatchResult>, from: Int, target: String): Boolean {
        for (step in 1..MAX_NOUN_GAP) {
            val word = tokens.getOrNull(from + step)?.value ?: return false
            if (!word[0].isLetter()) return false
            if (stem(word) == target) return true
        }
        return false
    }

    private fun isFragmentNumber(line: String, token: MatchResult): Boolean {
        val before = line.substring(0, token.range.first)
        val next = line.getOrNull(token.range.last + 1)
        val decimalTail = next == '.' && line.getOrNull(token.range.last + 2)?.isDigit() == true
        return isFragment(before) || decimalTail || (next != null && next in "\"'″/×")
    }

    private fun stem(word: String) = word.lowercase().trim().trimEnd('s')

    /** The last word of an alias phrase, which names the counted thing (`rod carriers` -> `carriers`). */
    fun nounOf(alias: String): String = alias.trim().split(Regex("""\s+""")).last().trim('.', ',', ':', ';')
}
