// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.Serializable

@Serializable
data class GitConfig(
    val graphId: String,
    val repoRoot: String,
    val wikiSubdir: String,
    val remoteName: String = "origin",
    val remoteBranch: String = "main",
    val authType: GitAuthType,
    val sshKeyPath: String? = null,
    val sshKeyPassphraseKey: String? = null,
    val httpsTokenKey: String? = null,
    val pollIntervalMinutes: Int = 5,
    val autoCommit: Boolean = true,
    val commitMessageTemplate: String = "SteleKit: {date}",
)

val GitConfig.wikiRoot: String get() = if (wikiSubdir.isEmpty()) repoRoot else "$repoRoot/$wikiSubdir"

enum class GitAuthType { NONE, SSH_KEY, HTTPS_TOKEN }
