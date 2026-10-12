// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

/**
 * How [mergeMarkdownBlocks] treats regions both sides changed.
 *
 * - [Strict]: every overlapping change is a conflict.
 * - [Insertions]: both sides inserting at the same spot keeps both; edits to the same existing
 *   block still conflict. Default for markdown with no `merge` attribute.
 * - [Union]: both sides' blocks are always kept (`merge=union`).
 * - [LogseqUnion]: `merge=logseq-union`, the line-level chain of [LogseqUnionMergeDriver]. Honor
 *   it via [mergeMarkdownWithPolicy]; [BlockDiff3] only approximates it as [Union].
 */
enum class MergePolicy { Strict, Insertions, Union, LogseqUnion }

/**
 * Minimal `.gitattributes` reader for the `merge` attribute only (JGit never runs custom merge
 * drivers). Patterns: `*`, `?`, `**`, leading `/` (anchored), and a slash-free pattern matches
 * the basename at any depth. Macros and `[attr]` are ignored; patterns over [MAX_PATTERN_LENGTH]
 * and rules past [MAX_RULES] are dropped so a hostile file cannot make matching expensive.
 */
class GitAttributes private constructor(private val rules: List<Rule>) {

    private class Rule(val pattern: GlobPattern, val mergeValue: String)

    /** The raw `merge` attribute for [relativePath], or null when unset or reset with `!merge`. */
    fun mergeAttribute(relativePath: String): String? =
        rules.lastOrNull { it.pattern.matches(relativePath) }?.mergeValue?.takeIf { it != UNSPECIFIED }

    /**
     * The policy the attribute asks for, or [default] when unset. Only `union` and
     * `logseq-union` keep both sides; every other value (`-merge`, `binary`, `text`, an unknown
     * custom driver) is [MergePolicy.Strict], since real git would not keep-both either.
     */
    fun policyFor(relativePath: String, default: MergePolicy): MergePolicy {
        val value = mergeAttribute(relativePath) ?: return default
        return when (value) {
            "union" -> MergePolicy.Union
            LogseqUnionMergeDriver.DRIVER_NAME -> MergePolicy.LogseqUnion
            else -> MergePolicy.Strict
        }
    }

    companion object {
        val EMPTY = GitAttributes(emptyList())

        const val MAX_PATTERN_LENGTH = 256
        const val MAX_RULES = 1024
        private const val UNSPECIFIED = "!merge"

        /**
         * Parses [text] from a `.gitattributes` located in [directory] (repo-relative, `""` for
         * the root); patterns are resolved relative to that directory.
         */
        fun parse(text: String, directory: String = ""): GitAttributes {
            val prefix = directory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val rules = text.lineSequence().mapNotNull { parseLine(it.trim(), prefix) }.take(MAX_RULES).toList()
            return GitAttributes(rules)
        }

        /** Later (deeper) files win, mirroring git's precedence. */
        fun combine(vararg ordered: GitAttributes): GitAttributes = GitAttributes(ordered.flatMap { it.rules })

        private fun parseLine(line: String, prefix: String): Rule? {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[attr]")) return null
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2 || parts[0].length > MAX_PATTERN_LENGTH) return null
            val merge = parts.drop(1).firstNotNullOfOrNull { attr ->
                when {
                    attr == "merge" -> "text"
                    attr == "-merge" || attr == UNSPECIFIED -> attr
                    attr.startsWith("merge=") -> attr.removePrefix("merge=")
                    else -> null
                }
            }
            // A line that says nothing about `merge` must not shadow an earlier line that does.
            return merge?.let { Rule(GlobPattern.compile(parts[0], prefix), it) }
        }
    }
}

/**
 * Glob matched by NFA simulation: O(path length x pattern length) with no backtracking, so
 * pathological patterns like `**a**a**a` stay cheap.
 */
private class GlobPattern private constructor(private val tokens: List<Token>) {

    private enum class Kind { Literal, One, Star, DoubleStar, DirPrefix }

    // DirPrefix is a leading-directories wildcard: empty, or anything ending in a slash.
    private class Token(val kind: Kind, val char: Char = ' ')

    fun matches(path: String): Boolean {
        val m = tokens.size
        var active = BooleanArray(m + 1)
        var next = BooleanArray(m + 1)
        active[0] = true
        closeOverSkips(active)
        for (c in path) {
            next.fill(false)
            for (i in 0 until m) {
                if (!active[i]) continue
                val t = tokens[i]
                when (t.kind) {
                    Kind.Literal -> if (c == t.char) next[i + 1] = true
                    Kind.One -> if (c != '/') next[i + 1] = true
                    Kind.Star -> if (c != '/') next[i] = true
                    Kind.DoubleStar -> next[i] = true
                    Kind.DirPrefix -> {
                        next[i] = true
                        if (c == '/') next[i + 1] = true
                    }
                }
            }
            closeOverSkips(next)
            val swap = active; active = next; next = swap
        }
        return active[m]
    }

    /** Wildcards may match nothing, so a live wildcard state also activates its successor. */
    private fun closeOverSkips(states: BooleanArray) {
        for (i in tokens.indices) {
            if (states[i] && tokens[i].kind != Kind.Literal && tokens[i].kind != Kind.One) states[i + 1] = true
        }
    }

    companion object {
        fun compile(pattern: String, prefix: String): GlobPattern {
            val anchored = pattern.startsWith("/") || pattern.dropLast(1).contains('/')
            val body = pattern.removePrefix("/")
            val tokens = ArrayList<Token>()
            prefix.forEach { tokens += Token(Kind.Literal, it) }
            if (!anchored) tokens += Token(Kind.DirPrefix)
            var i = 0
            while (i < body.length) {
                when {
                    body.startsWith("**/", i) -> { tokens += Token(Kind.DirPrefix); i += 3 }
                    body.startsWith("**", i) -> { tokens += Token(Kind.DoubleStar); i += 2 }
                    body[i] == '*' -> { tokens += Token(Kind.Star); i++ }
                    body[i] == '?' -> { tokens += Token(Kind.One); i++ }
                    else -> { tokens += Token(Kind.Literal, body[i]); i++ }
                }
            }
            return GlobPattern(tokens)
        }
    }
}

/**
 * Directories (repo-relative, `""` = root) whose `.gitattributes` apply to [apiPath], ordered
 * shallowest first so [GitAttributes.combine] lets deeper files win. Only the root and the wiki
 * subfolder are probed: one request per ancestor per conflict is too chatty for the web client.
 */
fun gitAttributeProbeDirs(apiPath: String, wikiSubdir: String?): List<String> {
    val subdir = wikiSubdir?.trim('/')?.takeIf { it.isNotEmpty() && apiPath.startsWith("$it/") }
    return listOfNotNull("", subdir)
}
