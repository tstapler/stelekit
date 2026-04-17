package dev.stapler.stelekit.domain

/**
 * Aho-Corasick multi-pattern string matcher for page-name suggestion highlighting.
 *
 * Construction: O(sum of pattern lengths)
 * Search: O(text length + number of matches)
 *
 * Thread safety: immutable after construction — safe to share across coroutines.
 *
 * @param canonicalNames map of lowercase page name -> canonical (display) page name
 */
class AhoCorasickMatcher(canonicalNames: Map<String, String>) {

    data class MatchSpan(val start: Int, val end: Int, val canonicalName: String)

    private data class OutputEntry(val canonical: String, val patternLength: Int)

    private val nodeChildren = ArrayList<HashMap<Char, Int>>()
    private val failureLinks = ArrayList<Int>()
    private val nodeOutput = ArrayList<List<OutputEntry>>()

    init {
        // Add root node
        nodeChildren.add(HashMap())
        failureLinks.add(0)
        nodeOutput.add(emptyList())

        // Build trie from lowercase patterns
        for ((lowercase, canonical) in canonicalNames) {
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
            nodeOutput[cur] = nodeOutput[cur] + OutputEntry(canonical, lowercase.length)
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
     * the match start and immediately after the match end must each be absent or
     * a non-word character. This prevents "the" matching inside "there" or
     * "other". Multi-word page names (e.g., "Meeting Notes") are treated as a
     * unit — the boundary check applies to the start of the first word and the
     * end of the last word only.
     *
     * Overlapping matches are resolved by choosing the leftmost-longest.
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
                val end = i + 1
                // Enforce word boundary: char before start and char after end must not be a word char
                if (isWordBoundary(text, start, end)) {
                    raw.add(MatchSpan(start, end, entry.canonical))
                }
            }
        }

        return resolveOverlaps(raw)
    }

    /**
     * Returns true if the substring [start, end) is surrounded by word boundaries.
     * A word character is a letter, digit, or underscore.
     */
    private fun isWordBoundary(text: String, start: Int, end: Int): Boolean {
        val beforeOk = start == 0 || !text[start - 1].isWordChar()
        val afterOk = end == text.length || !text[end].isWordChar()
        return beforeOk && afterOk
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

    private fun resolveOverlaps(matches: List<MatchSpan>): List<MatchSpan> {
        if (matches.isEmpty()) return emptyList()
        // For each start position, keep the longest match
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
