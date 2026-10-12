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

    fun resolveWithSource(path: String, attribute: String): AttrResolution {
        val normalised = path.removePrefix("./").trimStart('/')
        var state: AttrState = AttrState.Unspecified
        var source: String? = null

        fun apply(name: String, newState: AttrState, from: String, depth: Int, allowBuiltin: Boolean) {
            if (name == attribute) {
                state = newState
                source = from
            }
            if (newState != AttrState.Set || depth > MAX_MACRO_DEPTH) return
            val expansion = macros[name]
            // The built-in `binary` macro is only expanded from the root, info and global layers.
            if (expansion != null && (name != "binary" || allowBuiltin || name in userMacroNames())) {
                expansion.forEach { (n, s) -> apply(n, s, from, depth + 1, allowBuiltin) }
            }
        }

        for (file in layers) {
            val relative = relativeTo(file.dirPrefix, normalised) ?: continue
            for (rule in file.rules) {
                if (!ruleMatches(rule, relative)) continue
                val from = "${file.sourceName}:${rule.line}"
                rule.attributes.forEach { (name, s) -> apply(name, s, from, 0, file.dirPrefix.isEmpty()) }
            }
        }
        return AttrResolution(state, source)
    }

    private fun userMacroNames(): Set<String> = layers.flatMap { it.macros.keys }.toSet()

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
