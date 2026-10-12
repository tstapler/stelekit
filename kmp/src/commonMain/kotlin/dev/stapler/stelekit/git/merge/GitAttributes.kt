// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

/**
 * How [mergeMarkdownBlocks] treats regions both sides changed.
 *
 * - [Strict]: every overlapping change is a conflict.
 * - [Insertions]: both sides inserting at the same spot keeps both (no base content is
 *   contested); edits to the same existing block still conflict. Default for markdown that has
 *   no explicit `merge` attribute.
 * - [Union]: both sides' blocks are always kept — approximates git's built-in `merge=union` and
 *   any unrecognized custom driver. Chosen only when `.gitattributes` asks.
 * - [LogseqUnion]: `merge=logseq-union`. Not block-level at all: it is the line-level strategy
 *   chain of the wiki's python driver, ported to [LogseqUnionMergeDriver]. Use
 *   [mergeMarkdownWithPolicy], not [mergeMarkdownBlocks], to honor it ([BlockDiff3] only
 *   approximates it as [Union]).
 */
enum class MergePolicy { Strict, Insertions, Union, LogseqUnion }

/**
 * Minimal `.gitattributes` reader covering only the `merge` attribute. JGit never runs custom
 * merge drivers, so the app honors the *intent* of the attribute natively instead of ignoring it.
 *
 * Pattern support: `*`, `?`, `**`, a leading `/` (anchored), and a pattern without `/` matching
 * the basename at any depth — the subset real wiki repos use. Macros and `[attr]` are ignored.
 */
class GitAttributes private constructor(private val rules: List<Rule>) {

    private data class Rule(val matcher: Regex, val mergeValue: String)

    /** The raw `merge` attribute for [relativePath]: `"union"`, `"logseq-union"`, `"-merge"`, … or null if unset. */
    fun mergeAttribute(relativePath: String): String? =
        rules.lastOrNull { it.matcher.matches(relativePath) }?.mergeValue

    /**
     * The merge policy the attribute asks for, or [default] when none is set.
     *
     * `logseq-union` maps to [MergePolicy.LogseqUnion] (a faithful port exists). Other unknown
     * custom drivers map to [MergePolicy.Union]: they can't be executed here, and keep-both is
     * the safest approximation of a user-defined "merge" driver. Opting out
     * (`-merge`, `merge=binary`, `merge=text`, `merge=lfs`) yields [MergePolicy.Strict].
     */
    fun policyFor(relativePath: String, default: MergePolicy): MergePolicy {
        val value = mergeAttribute(relativePath) ?: return default
        return when (value) {
            "-merge", "!merge", "binary", "text", "lfs" -> MergePolicy.Strict
            LogseqUnionMergeDriver.DRIVER_NAME -> MergePolicy.LogseqUnion
            else -> MergePolicy.Union
        }
    }

    companion object {
        val EMPTY = GitAttributes(emptyList())

        /**
         * Parses [text] from a `.gitattributes` located in [directory] (repo-relative, `""` for
         * the root); patterns are resolved relative to that directory.
         */
        fun parse(text: String, directory: String = ""): GitAttributes {
            val prefix = directory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val rules = text.lineSequence().mapNotNull { parseLine(it.trim(), prefix) }.toList()
            return GitAttributes(rules)
        }

        /** Later (deeper) files win, mirroring git's precedence. */
        fun combine(vararg ordered: GitAttributes): GitAttributes = GitAttributes(ordered.flatMap { it.rules })

        private fun parseLine(line: String, prefix: String): Rule? {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[attr]")) return null
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) return null
            val merge = parts.drop(1).firstNotNullOfOrNull { attr ->
                when {
                    attr == "merge" -> "text"
                    attr == "-merge" || attr == "!merge" -> attr
                    attr.startsWith("merge=") -> attr.removePrefix("merge=")
                    else -> null
                }
            }
            // A line that says nothing about `merge` must not shadow an earlier line that does.
            return merge?.let { Rule(globToRegex(parts[0], prefix), it) }
        }

        private fun globToRegex(pattern: String, prefix: String): Regex {
            val anchored = pattern.startsWith("/") || pattern.dropLast(1).contains('/')
            val body = pattern.removePrefix("/")
            val sb = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '*' && body.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3; continue }
                    c == '*' && body.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c in "\\.+()[]{}^$|" -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            val head = if (anchored) Regex.escape(prefix) else "${Regex.escape(prefix)}(?:.*/)?"
            return Regex(head + sb)
        }
    }
}
