// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.merge.MergePolicy
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun buildCommitMessage(config: GitConfig, isMerge: Boolean = false): String {
    val date = Clock.System.now().toString().take(10) // yyyy-MM-dd
    val base = config.commitMessageTemplate.replace("{date}", date)
    return if (isMerge) "$base (merge)" else base
}

/**
 * Commit-message suffix naming files merged without a user decision, grouped by the policy that
 * merged them (e.g. `Auto-merged (logseq-union): a.md, b.md`); empty when there are none.
 */
fun autoMergeNote(autoMerged: List<ConflictFile>): String =
    autoMerged.groupBy { it.autoResolvedBy }.entries
        .filter { it.key != null }
        .joinToString("") { (policy, files) ->
            val label = when (policy) {
                MergePolicy.LogseqUnion -> "logseq-union"
                MergePolicy.Union -> "union"
                else -> "block-merge"
            }
            "\n\nAuto-merged ($label): ${files.joinToString(", ") { it.wikiRelativePath }}"
        }
