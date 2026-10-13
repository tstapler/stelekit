// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/** The one answer to "is this path stored with Git LFS" on every platform. */
class LfsPathPolicy(private val rules: AttributeRuleSet) {
    /** True when the resolved `filter` attribute for [path] is exactly `lfs`. `.gitattributes` files never are. */
    fun isTracked(path: String): Boolean = classify(path) != null

    /** Where the deciding rule lives (for example `.gitattributes:3`), or null when [path] is not tracked. */
    fun ruleSource(path: String): String? = classify(path)?.source

    /** True when any layer holds at least one rule; false short-circuits every guard check. */
    val hasAnyRules: Boolean get() = !rules.isEmpty

    private fun classify(path: String): AttrResolution? {
        if (path.substringAfterLast('/') == GITATTRIBUTES_FILE) return null
        val resolution = rules.resolveWithSource(path, FILTER_ATTRIBUTE)
        val state = resolution.state
        return if (state is AttrState.Value && state.value == LFS_FILTER) resolution else null
    }

    companion object {
        const val GITATTRIBUTES_FILE = ".gitattributes"
        const val FILTER_ATTRIBUTE = "filter"
        const val LFS_FILTER = "lfs"
    }
}

/** Supplies attribute layers from somewhere other than a local file system (the web reads the remote). */
interface RemoteRulesSource {
    /** Rules for [changedPaths] at the base ref; any failure other than "no such file" must be a Left (fail closed). */
    suspend fun load(changedPaths: Collection<String>): Either<DomainError.GitError, AttributeRuleSet>
}
