// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitConfig
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun buildCommitMessage(config: GitConfig, isMerge: Boolean = false): String {
    val date = Clock.System.now().toString().take(10) // yyyy-MM-dd
    val base = config.commitMessageTemplate.replace("{date}", date)
    return if (isMerge) "$base (merge)" else base
}
