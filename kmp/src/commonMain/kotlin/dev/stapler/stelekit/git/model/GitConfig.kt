// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import dev.stapler.stelekit.logging.Logger
import kotlinx.serialization.Serializable

@Serializable
data class GitConfig(
    val graphId: String,
    val repoRoot: String,
    val wikiSubdir: String?,
    val remoteName: String = "origin",
    val remoteBranch: String = "main",
    val authType: GitAuthType,
    val sshKeyPath: String? = null,
    val sshKeyPassphraseKey: String? = null,
    val httpsTokenKey: String? = null,
    val oauthTokenKey: String? = null,
    val pollIntervalMinutes: Int = 5,
    val autoCommit: Boolean = true,
    val commitMessageTemplate: String = "SteleKit: {date}",
    val llmApiKeyRef: String? = null,
    val cloneDepthState: CloneDepthState = CloneDepthState.None,
)

val GitConfig.wikiRoot: String get() = if (wikiSubdir.isNullOrEmpty()) repoRoot else "$repoRoot/$wikiSubdir"

enum class GitAuthType { NONE, SSH_KEY, HTTPS_TOKEN, GITHUB_OAUTH }

/**
 * Default `CloneCommand`/`FetchCommand` depth for a new shallow clone (git-sync-resilience
 * Story 2.1.1, ADR-001) — bounds a retry's cost to a small fixed-size unit, not a literal
 * partial-pack resume.
 *
 * Lives here (commonMain), not in `GitOperationSupport.kt`'s `jvmCommonMain` as originally
 * sketched: `GitSetupScreenSaveLogic.kt` (commonMain, shared with the wasmJs target) also needs
 * this value to stamp `CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH)` right after a successful
 * clone, and `jvmCommonMain` depends on `commonMain` — never the reverse — so a constant declared
 * there would be unreachable from here.
 */
const val DEFAULT_CLONE_DEPTH = 50

private val cloneDepthStateLogger = Logger("CloneDepthState")

/**
 * Shallow-vs-full clone-depth checkpoint for a graph (git-sync-resilience Story 2.1.2), persisted
 * as two raw SQL columns (`git_config.clone_depth_state`, `git_config.shallow_depth`) but
 * represented here as one sealed value at the repository boundary — see plan.md's `CloneDepthState`
 * Domain Glossary row. A sealed interface, **not** an enum with a separate nullable
 * `shallowDepth: Int?` field (architecture-review.md's Concern, adopted): depth lives only inside
 * [Shallow], so "shallow but depth unknown" and "full history but a stale depth still set" are both
 * unconstructible in Kotlin, forcing exhaustive `when` handling at every call site.
 */
@Serializable
sealed interface CloneDepthState {
    /** No shallow-clone tracking — a pre-existing repo migrated before this project shipped, or a
     * graph with no successful clone yet. */
    @Serializable
    data object None : CloneDepthState

    /** Shallow-cloned to [depth] commits; a deepen (unshallow) is pending/available. */
    @Serializable
    data class Shallow(val depth: Int) : CloneDepthState

    /** Full history — cloned in full, or successfully deepened via `unshallow()`. Carries no depth. */
    @Serializable
    data object FullHistory : CloneDepthState

    companion object {
        /**
         * Fail-closed SQL parse rule (plan.md's `CloneDepthState` SQL parse rule Domain Glossary
         * row), applied once at the repository boundary: `'SHALLOW'` with a non-null [rawDepth]
         * parses to [Shallow]; `'FULL_HISTORY'`/`'NONE'` always parse to [FullHistory]/[None]
         * regardless of what [rawDepth] contains (a stale non-null value there is logged and
         * ignored, never trusted); any other combination — `'SHALLOW'` with a null [rawDepth], or
         * an unrecognized [rawState] — falls back to [None] rather than throwing or fabricating a
         * depth.
         */
        fun fromRaw(rawState: String, rawDepth: Long?): CloneDepthState = when (rawState) {
            "SHALLOW" -> rawDepth?.let { Shallow(it.toInt()) } ?: run {
                cloneDepthStateLogger.warn(
                    "clone_depth_state='SHALLOW' with a null shallow_depth — falling back to None"
                )
                None
            }
            "FULL_HISTORY" -> {
                if (rawDepth != null) {
                    cloneDepthStateLogger.warn(
                        "clone_depth_state='FULL_HISTORY' with a stale shallow_depth=$rawDepth — ignoring it"
                    )
                }
                FullHistory
            }
            "NONE" -> None
            else -> {
                cloneDepthStateLogger.warn("Unrecognized clone_depth_state='$rawState' — falling back to None")
                None
            }
        }
    }
}

/** Raw `git_config.clone_depth_state` column value for this [CloneDepthState]. */
fun CloneDepthState.toRawState(): String = when (this) {
    CloneDepthState.None -> "NONE"
    is CloneDepthState.Shallow -> "SHALLOW"
    CloneDepthState.FullHistory -> "FULL_HISTORY"
}

/** Raw `git_config.shallow_depth` column value for this [CloneDepthState] — `null` for every
 * variant except [CloneDepthState.Shallow], closing architecture-review.md's Concern about a stale
 * depth surviving a transition to [CloneDepthState.FullHistory] by construction (there is no depth
 * field to null out separately). */
fun CloneDepthState.toRawDepth(): Long? = when (this) {
    is CloneDepthState.Shallow -> depth.toLong()
    CloneDepthState.None, CloneDepthState.FullHistory -> null
}
