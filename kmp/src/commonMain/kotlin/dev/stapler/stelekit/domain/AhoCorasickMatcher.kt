package dev.stapler.stelekit.domain

/**
 * Aho-Corasick multi-pattern string matcher for page-name suggestion highlighting.
 *
 * Construction: O(sum of pattern lengths)
 * Search: O(text length + number of matches)
 *
 * Thread safety: immutable after construction — safe to share across coroutines.
 *
 * Stem-match support: use [TrieEntry] with [TrieEntry.reportedBaseLength] shorter than the
 * full pattern length. The trie stores the full variant (e.g. "running") for matching but
 * reports only the base span (e.g. positions 0..3 for "run"). The word-boundary check is
 * applied at the end of the *full* variant so that "running" does not match inside
 * "runningmore" (no word boundary after the variant).
 */
class AhoCorasickMatcher(entries: List<TrieEntry>) {

    /**
     * Input record for one pattern to index.
     *
     * @param pattern         Lowercase form of the word/phrase to find in text.
     * @param canonical       Display name of the matching page.
     * @param reportedBaseLength Number of characters from the *start* of the match to include
     *   in the returned [MatchSpan.end]. Defaults to the full pattern length (exact match).
     *   Set shorter for stem variants so the span covers only the base word.
     */
    data class TrieEntry(
        val pattern: String,
        val canonical: String,
        val reportedBaseLength: Int = pattern.length,
    ) {
        init {
            require(reportedBaseLength in 0..pattern.length) {
                "reportedBaseLength $reportedBaseLength out of range [0, ${pattern.length}] for pattern '$pattern'"
            }
        }
    }

    data class MatchSpan(val start: Int, val end: Int, val canonicalName: String)

    /** @param canonicalNames map of lowercase page name -> canonical (display) page name */
    constructor(canonicalNames: Map<String, String>) : this(
        canonicalNames.map { (lower, canonical) -> TrieEntry(lower, canonical) }
    )

    private data class OutputEntry(
        val canonical: String,
        val patternLength: Int,
        val reportedBaseLength: Int,
    )

    private val nodeChildren = ArrayList<HashMap<Char, Int>>()
    private val failureLinks = ArrayList<Int>()
    private val nodeOutput = ArrayList<List<OutputEntry>>()

    init {
        // Add root node
        nodeChildren.add(HashMap())
        failureLinks.add(0)
        nodeOutput.add(emptyList())

        // Build trie from entries (already lowercased patterns)
        for (entry in entries) {
            val lowercase = entry.pattern
            if (lowercase.isEmpty()) continue
            var cur = 0
            for (ch in lowercase) {
                val next = nodeChildren[cur].getOrPut(ch) {
                    nodeChildren.add(HashMap())
                    failureLinks.add(0)
                    nodeOutput.add(emptyList())
                    nodeChildren.size - 1
                }
                cur = next
            }
            nodeOutput[cur] = nodeOutput[cur] + OutputEntry(
                canonical = entry.canonical,
                patternLength = lowercase.length,
                reportedBaseLength = entry.reportedBaseLength,
            )
        }

        // Build failure links via BFS
        val queue = ArrayDeque<Int>()
        for ((_, child) in nodeChildren[0]) {
            failureLinks[child] = 0
            queue.add(child)
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for ((ch, v) in nodeChildren[u]) {
                var fail = failureLinks[u]
                while (fail != 0 && !nodeChildren[fail].containsKey(ch)) {
                    fail = failureLinks[fail]
                }
                failureLinks[v] = if (ch in nodeChildren[fail] && nodeChildren[fail][ch] != v) {
                    nodeChildren[fail][ch]!!
                } else {
                    0
                }
                // Merge output from failure link
                val failOut = nodeOutput[failureLinks[v]]
                if (failOut.isNotEmpty()) {
                    nodeOutput[v] = nodeOutput[v] + failOut
                }
                queue.add(v)
            }
        }
    }

    /**
     * Finds all non-overlapping page-name matches in [text].
     *
     * Matching is case-insensitive (text is lowercased before searching).
     * Only word-boundary matches are returned: the character immediately before
     * the match start and immediately after the *full variant end* must each be absent or
     * a non-word character.
     *
     * For stem entries: [MatchSpan.end] points after the base form only (e.g. "run" inside
     * "running"), while the boundary check is performed at the end of the full variant so
     * that "running" in "runningmore" is correctly rejected.
     *
     * Overlapping matches are resolved by choosing the leftmost-longest reported span.
     */
    fun findAll(text: String): List<MatchSpan> {
        if (text.isEmpty()) return emptyList()
        val lowered = text.lowercase()
        val raw = mutableListOf<MatchSpan>()
        var state = 0

        for (i in lowered.indices) {
            val ch = lowered[i]
            while (state != 0 && !nodeChildren[state].containsKey(ch)) {
                state = failureLinks[state]
            }
            state = nodeChildren[state][ch] ?: 0
            for (entry in nodeOutput[state]) {
                val start = i - entry.patternLength + 1
                val fullEnd = i + 1  // end of full variant in text (for boundary check)
                val reportedEnd = start + entry.reportedBaseLength
                // Boundary check at start and at end of full variant
                if (isWordBoundary(text, start, fullEnd)) {
                    raw.add(MatchSpan(start, reportedEnd, entry.canonical))
                }
            }
        }

        return resolveOverlaps(raw)
    }

    /**
     * Returns true if the substring [start, fullEnd) is surrounded by word boundaries.
     * A word character is a letter, digit, or underscore.
     */
    private fun isWordBoundary(text: String, start: Int, fullEnd: Int): Boolean {
        val beforeOk = start == 0 || !text[start - 1].isWordChar()
        val afterOk = fullEnd == text.length || !text[fullEnd].isWordChar()
        return beforeOk && afterOk
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

    private fun resolveOverlaps(matches: List<MatchSpan>): List<MatchSpan> {
        if (matches.isEmpty()) return emptyList()
        // For each start position, keep the longest reported span
        val bestByStart = matches
            .groupBy { it.start }
            .mapValues { (_, group) -> group.maxByOrNull { it.end - it.start }!! }
        val sorted = bestByStart.values.sortedBy { it.start }
        val result = mutableListOf<MatchSpan>()
        var nextFree = 0
        for (m in sorted) {
            if (m.start >= nextFree) {
                result.add(m)
                nextFree = m.end
            }
        }
        return result
    }
}
