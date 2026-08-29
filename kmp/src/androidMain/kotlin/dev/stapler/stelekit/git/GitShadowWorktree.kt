// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import android.util.Log
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Maps between shadow-tree-absolute paths (as [GitShadowWorktree] and JGit see them) and the
 * SAF-facing paths the rest of the app (`GitSyncService`, UI, `GraphFileWatcher`) must use.
 *
 * [GitShadowWorktree] implements this directly — see ADR-018 for why this is a separate
 * collaborator rather than an extension of `ShadowFileCache`.
 */
interface GitWorktreePathMapper {
    /** Maps a shadow-tree-absolute path to the SAF-facing path GitSyncService/UI/GraphFileWatcher must use. */
    fun toUserFacingPath(shadowAbsolutePath: String): String

    /** Inverse: maps a SAF-facing path back to the git-relative path JGit's checkout()/add() need. */
    fun toGitRelativePath(userFacingPath: String): String
}

/**
 * A real `java.io.File`-backed working tree, disjoint from [dev.stapler.stelekit.platform.ShadowFileCache]'s
 * pages/journals indexing cache, that JGit operates on directly. Bidirectionally mirrored to the
 * user's SAF folder ([safRoot]).
 *
 * Instances are resolved lazily, per-call, keyed off `config.repoRoot` — see
 * `AndroidGitRepository.shadowWorktreeFor` and plan.md design decision #6. Never construct this
 * at UI composition time keyed off `graphPath`.
 *
 * See ADR-018 for the rationale behind this being a separate collaborator, and plan.md Phase 1
 * (Epic 1.1/1.2) for the design this class implements.
 */
class GitShadowWorktree(
    context: Context,
    internal val shadowKey: String,
    private val safRoot: String,
    private val fileSystem: FileSystem,
) : GitWorktreePathMapper {

    private val worktreeRoot = File(context.filesDir, "graphs/$shadowKey/gitshadow")

    init {
        worktreeRoot.mkdirs()
    }

    /** Public accessor used by `AndroidGitRepository.resolveForJGit` and the Phase 6 `StatFs` check. */
    val worktreeRootPath: String get() = worktreeRoot.absolutePath

    private val manifestFile = File(worktreeRoot, MANIFEST_FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    // ── Path-traversal guard (ported from ShadowFileCache.safeShadowFile) ──────────────────────

    /**
     * Returns a [File] resolved from `worktreeRoot`/[relativePath] only if the canonical path
     * stays within [worktreeRoot]. Returns null and logs a warning if path traversal is detected.
     */
    private fun safeWorktreeFile(relativePath: String): File? {
        val target = File(worktreeRoot, relativePath).canonicalFile
        return if (target.path.startsWith(worktreeRoot.canonicalPath + File.separator) ||
            target.path == worktreeRoot.canonicalPath
        ) {
            target
        } else {
            Log.w(TAG, "safeWorktreeFile: path escape blocked for '$relativePath'")
            null
        }
    }

    // ── GitWorktreePathMapper ────────────────────────────────────────────────────────────────

    override fun toUserFacingPath(shadowAbsolutePath: String): String {
        val prefix = worktreeRoot.absolutePath + "/"
        val relative = shadowAbsolutePath.removePrefix(prefix)
        return "$safRoot/$relative"
    }

    override fun toGitRelativePath(userFacingPath: String): String {
        val prefix = "$safRoot/"
        // Lenient: falls back to treating the input as already-relative if the prefix isn't
        // present, matching the existing pattern at AndroidGitRepository.kt (checkoutFile/markResolved).
        return userFacingPath.removePrefix(prefix)
    }

    // ── core.fileMode = false (pitfall §2.4) ────────────────────────────────────────────────

    /** Disables file-mode (executable bit) tracking — SAF documents don't carry Unix permissions. */
    fun disableFileModeTracking(repo: org.eclipse.jgit.lib.Repository) {
        repo.config.setBoolean("core", null, "fileMode", false)
        repo.config.save()
    }

    // ── SAF → shadow sync ────────────────────────────────────────────────────────────────────

    /**
     * Syncs the entire `repoRoot` subtree from SAF into the shadow worktree.
     *
     * [listRecursive] returns every `(relativePath, mtime)` under `repoRoot`, excluding `.git/`.
     * [readSafFile] reads a single SAF file's content by relative path. Unchanged files (dual
     * mtime+size staleness check) are skipped. Shadow-tracked files no longer present in the SAF
     * listing are deleted (handles SAF-side deletions). Writes the sync manifest once at the end.
     */
    suspend fun syncFromSafRoot(
        listRecursive: suspend (String) -> List<Pair<String, Long>>,
        readSafFile: suspend (String) -> String?,
    ) = withContext(PlatformDispatcher.IO) {
        val listing = listRecursive(safRoot).filterNot { (relativePath, _) ->
            relativePath == ".git" || relativePath.startsWith(".git/")
        }

        val manifestEntries = mutableListOf<SyncManifestEntry>()
        val liveRelativePaths = mutableSetOf<String>()

        for ((relativePath, safMtime) in listing) {
            liveRelativePaths += relativePath
            val shadowFile = safeWorktreeFile(relativePath) ?: continue

            // Pre-read skip check: `listRecursive`'s narrow (path, mtime) signature has no cheap
            // SAF size query (unlike ShadowFileCache.invalidateStale's batch cursor, which reads
            // size from DocumentsContract metadata, not file content) — so the *pre-read* skip
            // decision here is mtime-only (Task 1.1.2a). Passing the shadow file's own current
            // size as the "presumed" SAF size makes isEntryStale's size term a no-op (always
            // equal), i.e. this call degrades cleanly to the mtime check without a second method.
            // The genuine dual mtime+size check only becomes possible once content is actually
            // read below, computing a real safSize — the Termux-while-backgrounded case
            // (stale-looking mtime, changed size) can't be caught before that read without
            // widening the listRecursive callback, which the plan deliberately keeps narrow.
            if (shadowFile.exists() && !isEntryStale(shadowFile, safMtime, shadowFile.length())) {
                manifestEntries += SyncManifestEntry(relativePath, safMtime, shadowFile.length())
                continue
            }

            val content = readSafFile(relativePath) ?: continue
            val safSize = content.encodeToByteArray().size.toLong()
            try {
                shadowFile.parentFile?.mkdirs()
                shadowFile.writeText(content)
                if (safMtime > 0L) shadowFile.setLastModified(safMtime)
                manifestEntries += SyncManifestEntry(relativePath, safMtime, safSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "syncFromSafRoot: failed to write shadow file '$relativePath'", e)
            }
        }

        deleteOrphanedShadowFiles(worktreeRoot, "", liveRelativePaths)

        writeManifest(SyncManifest(manifestEntries))
    }

    /** Recursively deletes shadow files (never `.git/`) not present in [liveRelativePaths]. */
    private fun deleteOrphanedShadowFiles(dir: File, relPrefix: String, liveRelativePaths: Set<String>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (relPrefix.isEmpty() && child.name == ".git") continue
            if (child.name == MANIFEST_FILE_NAME) continue
            val relPath = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
            if (child.isDirectory) {
                deleteOrphanedShadowFiles(child, relPath, liveRelativePaths)
            } else if (relPath !in liveRelativePaths) {
                child.delete()
            }
        }
    }

    /**
     * Dual mtime+size staleness check, ported from `ShadowFileCache.invalidateStale`: a shadow
     * entry is stale when EITHER the SAF mtime is newer than the shadow mtime, OR the SAF size
     * differs from the shadow size (catches SAF providers that report a stale mtime — e.g. a
     * Termux write while the app is backgrounded, before the provider refreshes its metadata).
     */
    private fun isEntryStale(shadowFile: File, safMtime: Long, safSize: Long): Boolean {
        if (safMtime <= 0L && safSize <= 0L) return false
        val mtimeStale = safMtime > 0L && shadowFile.lastModified() < safMtime
        val sizeChanged = safSize >= 0L && shadowFile.length() != safSize
        return mtimeStale || sizeChanged
    }

    // ── Freshness manifest ───────────────────────────────────────────────────────────────────

    @Serializable
    data class SyncManifestEntry(val relativePath: String, val safMtime: Long, val safSize: Long)

    @Serializable
    data class SyncManifest(val entries: List<SyncManifestEntry>)

    private fun readManifest(): SyncManifest {
        return try {
            if (!manifestFile.exists()) return SyncManifest(emptyList())
            json.decodeFromString(SyncManifest.serializer(), manifestFile.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "readManifest: failed to read/parse manifest, treating as empty", e)
            SyncManifest(emptyList())
        }
    }

    private fun writeManifest(manifest: SyncManifest) {
        try {
            manifestFile.writeText(json.encodeToString(SyncManifest.serializer(), manifest))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "writeManifest: failed to write manifest", e)
        }
    }

    /**
     * Returns true when the shadow worktree's manifest matches the current recursive SAF listing
     * (mtime per entry, and entry count — catching additions/deletions). Returns false on the
     * first mismatch or a missing manifest.
     */
    suspend fun isFresh(listRecursive: suspend (String) -> List<Pair<String, Long>>): Boolean =
        withContext(PlatformDispatcher.IO) {
            val listing = listRecursive(safRoot).filterNot { (relativePath, _) ->
                relativePath == ".git" || relativePath.startsWith(".git/")
            }
            val manifest = readManifest()
            if (manifest.entries.isEmpty() && listing.isEmpty()) return@withContext manifestFile.exists()
            if (listing.size != manifest.entries.size) return@withContext false

            val manifestByPath = manifest.entries.associateBy { it.relativePath }
            for ((relativePath, safMtime) in listing) {
                val entry = manifestByPath[relativePath] ?: return@withContext false
                if (entry.safMtime != safMtime) return@withContext false
            }
            true
        }

    /** Runtime-enforced freshness precondition: resyncs from SAF if [isFresh] returns false. */
    suspend fun ensureFresh(
        listRecursive: suspend (String) -> List<Pair<String, Long>>,
        readSafFile: suspend (String) -> String?,
    ) {
        if (!isFresh(listRecursive)) {
            syncFromSafRoot(listRecursive, readSafFile)
        }
    }

    companion object {
        private const val TAG = "GitShadowWorktree"
        private const val MANIFEST_FILE_NAME = ".sync-manifest.json"

        /** Same hash basis as `GraphManager.graphIdFromPath()` — see plan.md design decision #1. */
        fun shadowKeyForSafPath(repoRoot: String): String =
            ContentHasher.sha256(repoRoot).take(16)
    }
}
