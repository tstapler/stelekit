// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.diagnostics

import dev.stapler.stelekit.platform.FileSystem

/**
 * Scans a directory for potential wiki content candidates.
 *
 * Searches the directory tree (shallow depth) for directories that could contain
 * a wiki graph (typically directories with both pages/ and/or journals/ subdirectories).
 * App automatically filters out app-internal directories and conventionally ignored
 * paths (e.g., .git, .stelekit, .obsidian).
 *
 * @param configuredRoot The root directory to scan from
 * @param fs FileSystem implementation to use for scanning
 * @return List of DirectoryScanResult representing potential wiki content locations
 */
const val MAX_NESTED_PROBES = 40

fun scanForWikiCandidates(
    configuredRoot: String,
    fs: FileSystem,
): List<DirectoryScanResult> {
    // List directories at the root level
    val rootDirs = try {
        fs.listDirectories(configuredRoot)
    } catch (e: Exception) {
        return emptyList()
    }

    val candidates = mutableListOf<DirectoryScanResult>()

    // Skip app-internal directories
    val appInternalPrefixes = setOf(".stelekit", ".git", ".obsidian")

    var probes = 0
    for (dir in rootDirs) {
        if (probes >= MAX_NESTED_PROBES) break
        probes++

        // Skip if it's an app-internal directory
        if (dir.startsWith(".") || appInternalPrefixes.any { dir.startsWith(it) }) {
            continue
        }

        // Check if this directory has pages/ and/or journals/
        val hasPages = fs.directoryExists("$configuredRoot/$dir/pages")
        val hasJournals = fs.directoryExists("$configuredRoot/$dir/journals")

        // Only consider candidates that have at least one of pages or journals
        if (!hasPages && !hasJournals) {
            continue
        }

        // Determine human-readable name
        val name = when {
            dir.equals("logseq", ignoreCase = true) -> "logseq"
            dir.equals("notes", ignoreCase = true) -> "notes"
            dir.equals("pages", ignoreCase = true) -> "pages"
            dir.equals("journals", ignoreCase = true) -> "journals"
            else -> dir
        }

        candidates.add(
            DirectoryScanResult(
                path = dir,
                pages = hasPages,
                journals = hasJournals,
                name = name,
            )
        )
    }

    return candidates
}
