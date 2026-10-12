// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

/** The resolved state of one attribute for one path, plus the rule that decided it. */
data class AttrResolution(val state: AttrState, val source: String?)

/**
 * Layered `.gitattributes` evaluation: lower layers first. Within a file the last matching line wins
 * per attribute; a deeper directory's file overrides a shallower one; `.git/info/attributes` (the
 * [infoAttributes] layer) overrides every file, exactly as in gitattributes(5).
 */
class AttributeRuleSet(
    attributeFiles: List<GitAttributesFile>,
    infoAttributes: GitAttributesFile? = null,
    private val ignoreCase: Boolean = false,
) {
    private val layers: List<GitAttributesFile> =
        attributeFiles.sortedBy { depth(it.dirPrefix) } + listOfNotNull(infoAttributes)

    private val macros: Map<String, List<Pair<String, AttrState>>> = buildMap {
        put("binary", BINARY_MACRO)
        layers.forEach { putAll(it.macros) }
    }

    /** True when no layer carries any rule at all (every path resolves to Unspecified). */
    val isEmpty: Boolean get() = layers.all { it.rules.isEmpty() }

    fun resolve(path: String, attribute: String): AttrState = resolveWithSource(path, attribute).state

    /**
     * Evaluated the way git does: bottom-up, so the first rule to decide an attribute (the last matching line of
     * the highest-precedence file) wins, and a macro attribute only expands where its own final state is Set.
     * That second point is why a `lfs` line for all PNGs followed by a `-lfs` line for one directory leaves `filter`
     * unspecified inside that directory: the later line decides `lfs` first, so the earlier line's macro never expands.
     */
    fun resolveWithSource(path: String, attribute: String): AttrResolution {
        val normalised = path.removePrefix("./").trimStart('/')
        val decided = HashSet<String>()
        var state: AttrState = AttrState.Unspecified
        var source: String? = null

        fun fill(attributes: List<Pair<String, AttrState>>, from: String, depth: Int) {
            for ((name, attrState) in attributes.asReversed()) {
                if (!decided.add(name)) continue
                if (name == attribute) {
                    state = attrState
                    source = from
                }
                if (attrState == AttrState.Set && depth < MAX_MACRO_DEPTH) macros[name]?.let { fill(it, from, depth + 1) }
            }
        }

        for (file in layers.asReversed()) {
            val relative = relativeTo(file.dirPrefix, normalised) ?: continue
            for (rule in file.rules.asReversed()) {
                if (ruleMatches(rule, relative)) fill(rule.attributes, "${file.sourceName}:${rule.line}", 0)
            }
        }
        return AttrResolution(state, source)
    }

    private fun ruleMatches(rule: AttrRule, relativePath: String): Boolean {
        if (rule.pattern.endsWith("/")) return false // directory patterns never match a file
        val pattern = if (ignoreCase) rule.pattern.lowercase() else rule.pattern
        val subject = if (ignoreCase) relativePath.lowercase() else relativePath
        return if (rule.hasSlash) {
            GitWildmatch.matches(pattern.removePrefix("/"), subject, pathname = true)
        } else {
            GitWildmatch.matches(pattern, subject.substringAfterLast('/'), pathname = false)
        }
    }

    private fun relativeTo(dirPrefix: String, path: String): String? {
        if (dirPrefix.isEmpty()) return path
        val prefix = "$dirPrefix/"
        return if (path.startsWith(prefix)) path.substring(prefix.length) else null
    }

    private fun depth(dir: String): Int = if (dir.isEmpty()) 0 else dir.count { it == '/' } + 1

    private companion object {
        const val MAX_MACRO_DEPTH = 8
        val BINARY_MACRO = listOf("diff" to AttrState.Unset, "merge" to AttrState.Unset, "text" to AttrState.Unset)
    }
}
