// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import android.os.StatFs
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import org.eclipse.jgit.api.Git
import java.util.concurrent.ConcurrentHashMap

/**
 * Shadow-worktree resolution and SAF-path handling for [AndroidGitRepository], split out to keep
 * that class under the file-size guideline. Owns the per-graph [GitShadowWorktree] cache, so
 * lives for exactly as long as the [AndroidGitRepository] instance that constructs it (one
 * instance per repository, not shared).
 *
 * @param context Used to derive the per-graph shadow-worktree storage root
 *                (`context.filesDir/graphs/$shadowKey/gitshadow`) for SAF-only users who lack
 *                `MANAGE_EXTERNAL_STORAGE` — see [GitShadowWorktree] and ADR-018.
 * @param pathResolver Resolves `saf://` URIs to a real filesystem path when the fast path
 *                      (`MANAGE_EXTERNAL_STORAGE` or Desktop) is available; returns null otherwise.
 * @param fileSystem Used to list/read SAF content for shadow-worktree sync (`ensureFresh`).
 */
internal class AndroidGitShadowSupport(
    private val context: Context,
    private val pathResolver: (String) -> String?,
    private val fileSystem: FileSystem,
    private val logger: Logger,
) {
    private val shadowWorktrees = ConcurrentHashMap<String, GitShadowWorktree>()

    /**
     * Resolves (or lazily creates and caches) the shadow worktree for *this call's* [repoRoot] —
     * never a value captured once at construction time (plan.md design decision #6). Returns null
     * when [pathResolver] already resolves [repoRoot] directly (fast path active — e.g. Desktop or
     * `MANAGE_EXTERNAL_STORAGE`, no shadow needed) or when [repoRoot] isn't a `saf://` path.
     */
    fun shadowWorktreeFor(repoRoot: String): GitShadowWorktree? {
        // AppOwned/DirectAccess/Desktop branch (Story 2.2.2): a repoRoot that isn't a saf:// URI
        // at all needs no SAF resolution — checked first so pathResolver (which only ever
        // resolves saf:// input) is never invoked for it, not just harmlessly returns null.
        if (!repoRoot.startsWith("saf://")) return null
        if (pathResolver(repoRoot) != null) return null // fast path resolves directly, no shadow needed
        val key = GitShadowWorktree.shadowKeyForSafPath(repoRoot)
        // .also { touchLastUsed() } refreshes the orphan-sweep liveness signal (Task 6.1.1a) on
        // every real resolution, so GitShadowWorktree.sweepOrphans() never deletes an actively
        // used graph's shadow tree — no per-call-site opt-in required.
        // Task 1.2.1b finding: fires on every JGit-touching op (clone/init/openGit's commit/fetch/
        // push/merge choke point, incl. WorkManager's background fetch) — not on "graph opened" in
        // the UI per se; switching the active graph alone runs no git op and doesn't touch it.
        return shadowWorktrees.getOrPut(key) {
            GitShadowWorktree(context, key, repoRoot)
        }.also {
            it.touchLastUsed()
            // Task 5.2.1c: keep PlatformFileSystem's write-behind flush lock-key in sync with the
            // git side's shadowKey derivation on every real resolution, so a concurrent flush for
            // this graph contends on the same GitWorktreeLocks Mutex as syncFromSafRoot().
            fileSystem.setGitShadowKeyProvider { GitShadowWorktree.shadowKeyForSafPath(repoRoot) }
        }
    }

    /**
     * Storage-space guard (plan.md Phase 6, Epic 6.2): fails fast with a diagnosable error before
     * `init()`/`clone()` when [worktree] (shadow-mirror mode active) has less than
     * [MIN_SHADOW_FREE_BYTES] free, instead of surfacing a raw JGit I/O exception mid-clone.
     * Returns null (proceed) when [worktree] is null (fast path, no shadow needed) or storage is
     * sufficient. See [MIN_SHADOW_FREE_BYTES]'s own doc for the sizing rationale.
     */
    fun insufficientShadowStorageError(
        worktree: GitShadowWorktree?,
        path: String,
    ): DomainError.GitError.WorkingTreeSyncFailed? {
        if (worktree == null) return null
        val available = StatFs(worktree.worktreeRootPath).availableBytes
        if (available >= MIN_SHADOW_FREE_BYTES) return null
        return DomainError.GitError.WorkingTreeSyncFailed(
            "clone",
            path,
            "Insufficient storage for git shadow clone",
        )
    }

    /**
     * Resolves saf:// URIs to real filesystem paths for JGit's File-based API. Tries the fast
     * path ([pathResolver], e.g. `MANAGE_EXTERNAL_STORAGE` or Desktop) first, then falls back to
     * the shadow worktree's real `java.io.File` root when shadow-mirror mode is active, and only
     * falls through to the raw (unresolvable-by-JGit) [path] string if neither applies.
     */
    fun resolveForJGit(path: String): String {
        val resolved = pathResolver(path) ?: shadowWorktreeFor(path)?.worktreeRootPath
        if (resolved == null && path.startsWith("saf://")) {
            // JGit only knows java.io.File — a SAF content:// grant alone can't back that, so this
            // path is unusable for git sync unless "All files access" is granted (Android Settings >
            // Apps > SteleKit > Permissions > All files access), which unlocks resolveSafToRealPath()
            // (see PlatformFileSystem.resolveSafToRealPath). Falling through to the raw saf:// string
            // below is what produces the cryptic "repository not found: /saf:/content%3A..." error.
            logger.warn(
                "Cannot resolve SAF path to a real file for JGit — grant \"All files access\" " +
                    "to SteleKit in Android Settings to use git sync with this folder. path=$path"
            )
        }
        return resolved ?: path
    }

    /**
     * After a successful `init()`/`clone()`, when shadow-mirror mode is active for [repoRoot],
     * pulls any pre-existing SAF markdown into the freshly created/cloned shadow tree
     * unconditionally (bypassing the freshness check — there is no prior manifest yet) so it's
     * present before the user's first `status()`/`commit()`, and disables file-mode tracking
     * (pitfall §2.4 — SAF documents carry no Unix permissions). No-op when shadow-mirror mode
     * isn't active for this [repoRoot] (Desktop / `MANAGE_EXTERNAL_STORAGE` fast path).
     */
    suspend fun syncShadowAfterInitOrClone(repoRoot: String, git: Git) {
        val worktree = shadowWorktreeFor(repoRoot) ?: return
        worktree.syncFromSafRoot(
            listRecursive = { root -> fileSystem.listFilesRecursiveWithModTimes(root) },
            readSafFile = { relPath -> fileSystem.readFile("$repoRoot/$relPath") },
        )
        worktree.disableFileModeTracking(git.repository)
    }

    companion object {
        /**
         * Conservative estimate, not a measured figure — the plan's full empirical measurement
         * (materializing the XLARGE synthetic fixture, 7 978 pages, and recording actual `.git`
         * object-store growth) is deferred (plan.md Task 6.2.0a), not blocking this guard. A
         * typical markdown wiki page runs a few KB, so a working tree at XLARGE's page count is
         * plausibly tens of MB; an actively-edited `.git` object store (blob history, pre-gc
         * packfiles) commonly runs several times the working-tree content size. 200 MB covers a
         * 3-5x multiplier with headroom, without blocking low-storage devices that have plenty of
         * room for a wiki-scale repo.
         */
        private const val MIN_SHADOW_FREE_BYTES = 200L * 1024 * 1024 // 200 MB
    }
}
