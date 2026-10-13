// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

/** State of one attribute, as in gitattributes(5). */
sealed interface AttrState {
    data object Set : AttrState
    data object Unset : AttrState
    data object Unspecified : AttrState
    data class Value(val value: String) : AttrState
}

/** One `.gitattributes` line: a pattern plus its attribute assignments, in written order. */
data class AttrRule(
    val pattern: String,
    val attributes: List<Pair<String, AttrState>>,
    val line: Int,
) {
    /** A pattern containing a slash (other than a trailing one) is anchored to its file's directory. */
    val hasSlash: Boolean get() = pattern.trimEnd('/').contains('/')
}

/**
 * One parsed attributes file. [dirPrefix] is the file's directory relative to the repository root
 * ("" for the root file); [sourceName] is used only to describe a rule to the user.
 */
data class GitAttributesFile(
    val dirPrefix: String,
    val rules: List<AttrRule>,
    val macros: Map<String, List<Pair<String, AttrState>>> = emptyMap(),
    val sourceName: String = if (dirPrefix.isEmpty()) ".gitattributes" else "$dirPrefix/.gitattributes",
)
