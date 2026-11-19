// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

/**
 * Growth, git-sync and ignore rules of a sidecar's `backup/` and `conflicts/` directories (plan D-6).
 *
 * `backup/` is synced by git and never auto-pruned (one original per converted sheet, the only restore source on
 * other devices). `conflicts/` is device-local (git-ignored) and keeps the newest [KEEP_CONFLICT_COPIES_PER_SHEET]
 * copies per sheet; an older copy is pruned only once its conflict is resolved. Neither directory is ever part of
 * `SidecarRead.Present(files)` or the bundle hash.
 */
object RetentionPolicy {
    const val KEEP_CONFLICT_COPIES_PER_SHEET = 10

    /** Written to the graph `.gitignore` beside the `*.tmp` rule; `backup/` has no rule so it stays tracked. */
    const val CONFLICTS_IGNORE_RULE = "assets/stelekit-sketch/**/conflicts/"

    private const val CONFLICT_INFIX = ".v2.conflict-"

    data class ConflictCopy(val name: String, val modifiedAt: Long)

    /** Bytes and file counts held by a project's `backup/` and `conflicts/` directories. */
    data class Size(val backupFiles: Int, val backupBytes: Long, val conflictFiles: Int, val conflictBytes: Long)

    /** The sheet uuid a `conflicts/` file name belongs to, or null for a name this policy did not write. */
    fun sheetOfConflictCopy(name: String): String? =
        if (name.endsWith(".json") && CONFLICT_INFIX in name) name.substringBefore(CONFLICT_INFIX).takeIf { it.isNotEmpty() } else null

    /**
     * Names to delete from [copies] (one sheet's copies): everything past the newest [KEEP_CONFLICT_COPIES_PER_SHEET]
     * (by [ConflictCopy.modifiedAt], ties by name) except the names in [unresolved]. Pure.
     */
    fun conflictCopiesToPrune(copies: List<ConflictCopy>, unresolved: Set<String>): List<String> =
        copies.sortedWith(compareByDescending<ConflictCopy> { it.modifiedAt }.thenByDescending { it.name })
            .drop(KEEP_CONFLICT_COPIES_PER_SHEET)
            .map { it.name }
            .filter { it !in unresolved }
}
