// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

import dev.stapler.stelekit.git.model.ConflictFile

/**
 * Resolves conflicts in app-owned `.stelekit/pages/<slug>.meta.json` sidecars without asking the user.
 *
 * A sidecar is NDJSON (one block per line) that [dev.stapler.stelekit.db.SidecarManager] reads as a
 * content-hash -> uuid lookup where the first line for a hash wins, and rewrites on every page save.
 * It is derived data: keeping every line from both sides is always safe, so this is "keep both",
 * local lines first, with exact duplicate lines dropped.
 */
object SidecarConflictResolver {

    fun isSidecar(wikiRelativePath: String): Boolean {
        val path = wikiRelativePath.replace('\\', '/')
        return path.endsWith(".meta.json") && (path.startsWith(SIDECAR_DIR) || path.contains("/$SIDECAR_DIR"))
    }

    /**
     * Returns [conflict] resolved as a clean union merge ([ConflictFile.autoResolvedBy] =
     * [MergePolicy.Union]), or null when it is not a sidecar or has no conflict markers to merge
     * (modify/delete, rename, binary), which stay with the user.
     */
    fun resolve(conflict: ConflictFile): ConflictFile? {
        if (!isSidecar(conflict.wikiRelativePath)) return null
        val raw = conflict.rawContent ?: return null
        val merged = unionMerge(raw) ?: return null
        return conflict.copy(rawContent = merged, autoResolvedBy = MergePolicy.Union)
    }

    private fun unionMerge(raw: String): String? {
        val out = LinkedHashSet<String>()
        var side = Side.Common
        var sawMarkers = false
        for (line in raw.lines()) {
            when {
                line.startsWith("<<<<<<<") -> { side = Side.Local; sawMarkers = true }
                line.startsWith("|||||||") && side == Side.Local -> side = Side.Base
                line.startsWith("=======") && (side == Side.Local || side == Side.Base) -> side = Side.Remote
                side == Side.Base -> Unit
                line.startsWith(">>>>>>>") && side == Side.Remote -> side = Side.Common
                line.isBlank() -> Unit
                else -> out.add(line.trimEnd())
            }
        }
        // An unterminated hunk means we cannot tell which lines belong where.
        return if (sawMarkers && side == Side.Common) out.joinToString("\n") else null
    }

    private enum class Side { Common, Local, Base, Remote }

    private const val SIDECAR_DIR = ".stelekit/"
}
