package dev.stapler.stelekit.model

import kotlin.jvm.JvmInline

/**
 * Canonical location of a graph's notes root.
 *
 * A git clone has two locations — the repo root and the wiki subdirectory inside it — and only
 * the latter is the graph. Building the path through [wikiRoot] keeps the two from being
 * confused, and [of] gives every spelling (trailing slash, backslashes, doubled separators)
 * the same value so equality means "same graph".
 */
@JvmInline
value class GraphPath private constructor(val value: String) {
    companion object {
        fun of(raw: String): GraphPath = GraphPath(normalize(raw))

        /** Notes root of a clone; an empty [wikiSubdir] means the notes live at the repo root. */
        fun wikiRoot(repoRoot: String, wikiSubdir: String): GraphPath {
            val subdir = normalize(wikiSubdir).trim(SEPARATOR[0])
            return if (subdir.isEmpty()) of(repoRoot) else of("${normalize(repoRoot)}$SEPARATOR$subdir")
        }

        private const val SEPARATOR = "/"
        private val separatorRuns = Regex("/{2,}")

        private fun normalize(raw: String): String {
            val unified = raw.trim().replace('\\', '/')
            val schemeEnd = unified.indexOf("://")
            val scheme = if (schemeEnd > 0) unified.substring(0, schemeEnd + 3) else ""
            val rest = unified.substring(scheme.length)
            val collapsed = rest.replace(separatorRuns, SEPARATOR).trimEnd(SEPARATOR[0])
            return scheme + collapsed.ifEmpty { if (rest.startsWith(SEPARATOR)) SEPARATOR else "" }
        }
    }
}
