// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.getDirectoryHandle
import dev.stapler.stelekit.platform.getEntryName
import dev.stapler.stelekit.platform.getOpfsRoot
import dev.stapler.stelekit.platform.isDirectoryEntry
import dev.stapler.stelekit.platform.listOpfsEntries
import dev.stapler.stelekit.platform.opfsDeleteDirectoryRecursive
import dev.stapler.stelekit.platform.opfsReadFileAtPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Web startup wiring for Story 3.1.2's [RelocationStagingDirectory.sweep] — the counterpart to
 * `MainActivity.kt`'s Android call. [RelocationStagingDirectory.sweep] needs a synchronous
 * [FileSystem] (`listDirectories`/`readFile`/`deleteFile`), but `PlatformFileSystem`'s (wasmJs)
 * synchronous facade only ever reflects whichever single graph's subtree `preload()` last loaded
 * into its cache — a root-level staging directory (a sibling of that subtree, never a descendant)
 * is never in it, so handing `sweep()` the app's real `PlatformFileSystem` here would silently find
 * nothing every time. This does its own tiny async pre-scan of [root]'s immediate
 * `.stele-relocate-staging-*` entries and their `.marker` files, then hands `sweep()` a throwaway
 * synchronous view over exactly that snapshot — the actual sweep decision (age check, marker
 * parsing, "no marker = never sweep") stays in [RelocationStagingDirectory.sweep] itself.
 */
suspend fun sweepWasmJsRelocationStaging(scope: CoroutineScope, root: String = "/stelekit") {
    val markersByStagingDir = mutableMapOf<String, String?>()
    try {
        var dir = getOpfsRoot()
        for (part in root.removePrefix("/").split("/").filter { it.isNotEmpty() }) {
            dir = getDirectoryHandle(dir, part, false)
        }
        for (entry in listOpfsEntries(dir)) {
            if (!isDirectoryEntry(entry)) continue
            val name = getEntryName(entry)
            if (!name.startsWith(RELOCATION_STAGING_PREFIX)) continue
            val stagingPath = "$root/$name"
            markersByStagingDir[stagingPath] = opfsReadFileAtPath("$stagingPath/.marker")
        }
    } catch (e: Throwable) {
        // No `/stelekit` directory yet (brand-new install) or another OPFS access failure —
        // nothing to sweep, matching RelocationStagingDirectory.sweep()'s own crash-safe posture.
        println("[SteleKit] Relocation staging sweep scan failed: ${e.message}")
        return
    }
    if (markersByStagingDir.isEmpty()) return
    RelocationStagingDirectory.sweep(WasmJsStagingSweepView(root, markersByStagingDir, scope), root)
}

// Mirrors RelocationStagingDirectory's own private STAGING_PREFIX — duplicated rather than exposed
// since that constant is private to the commonMain object and this is the only external reader.
private const val RELOCATION_STAGING_PREFIX = ".stele-relocate-staging-"

/** One-shot, read-only-except-for-delete [FileSystem] view backing [sweepWasmJsRelocationStaging]. */
private class WasmJsStagingSweepView(
    private val root: String,
    private val markersByStagingDir: Map<String, String?>,
    private val scope: CoroutineScope,
) : FileSystem {
    override fun getDefaultGraphPath(): String = root
    override fun expandTilde(path: String): String = path
    override fun readFile(path: String): String? = markersByStagingDir[path.removeSuffix("/.marker")]
    override fun writeFile(path: String, content: String): Boolean = false
    override fun listFiles(path: String): List<String> = emptyList()
    override fun listDirectories(path: String): List<String> =
        if (path == root) markersByStagingDir.keys.map { it.removePrefix("$root/") } else emptyList()
    override fun fileExists(path: String): Boolean = readFile(path) != null
    override fun directoryExists(path: String): Boolean = false
    override fun createDirectory(path: String): Boolean = false

    // Real deletion is inherently async (OPFS); sweep()'s deleteFile is not suspend, so this
    // fires the removal and returns immediately — matches PlatformFileSystem's own established
    // `scope.launch { opfsDeleteFile(...) }` pattern for async write/delete side effects.
    override fun deleteFile(path: String): Boolean {
        scope.launch { opfsDeleteDirectoryRecursive(path) }
        return true
    }

    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
}
