// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.util.Log
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.GitWorktreeLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Drains a [GitShadowWorktree]'s [GitWriteBackQueue] to SAF — the shadow→SAF direction of write
 * mirroring, complementing [GitShadowWorktree.syncFromSafRoot]'s SAF→shadow direction. Modeled on
 * [dev.stapler.stelekit.platform.ShadowFlushActor]'s `flush()`/`flushPage()` structure (plan.md
 * Task 3.1.2a).
 *
 * For each queued git-relative path: reads shadow content, compares the live SAF mtime against
 * the shadow worktree's sync manifest to detect a concurrent external edit before overwriting
 * (Task 3.1.2b), and writes back to SAF only when no race is detected.
 *
 * [flush]'s body is wrapped in `GitWorktreeLocks.lockFor(worktree.shadowKey).withLock { ... }` —
 * this is the write-back direction's lock coverage, distinct from
 * [GitShadowWorktree.syncFromSafRoot]'s lock (SAF→shadow direction) and
 * `PlatformFileSystem.flushPendingWrites()`'s lock (the unrelated page-content write-behind
 * queue); all three share the same shadow key so they mutually exclude each other correctly.
 *
 * Lifecycle: instantiate and call [flush] directly — no long-lived scope is created.
 */
internal class GitShadowFlushActor(
    private val fileSystem: FileSystem,
    private val worktree: GitShadowWorktree,
    private val queue: GitWriteBackQueue,
    /** `config.repoRoot` — the SAF root this shadow worktree mirrors. */
    private val safRoot: String,
) {
    companion object {
        private const val TAG = "GitShadowFlushActor"
    }

    /** Drain all pending write-backs to SAF. Returns one [Either] per queued path processed. */
    suspend fun flush(): List<Either<DomainError.GitError, Unit>> =
        GitWorktreeLocks.lockFor(worktree.shadowKey).withLock {
            withContext(PlatformDispatcher.IO) {
                queue.getAll().map { relativePath -> flushPage(relativePath) }
            }
        }

    /** Flush a single queued path: read from shadow, race-check, write to SAF, dequeue on success. */
    private fun flushPage(relativePath: String): Either<DomainError.GitError, Unit> {
        val safPath = "$safRoot/$relativePath"
        return try {
            val content = worktree.readShadowFile(relativePath) ?: run {
                Log.w(TAG, "flushPage: shadow missing for $relativePath — dequeuing without flush")
                queue.dequeue(relativePath)
                return Unit.right()
            }

            // Concurrent-edit detection (Task 3.1.2b): refuse to overwrite a SAF file that was
            // modified (by another app/widget/share-target) after the last SAF->shadow sync
            // recorded its mtime in the manifest — never silently clobber it. No manifest entry
            // (e.g. a file that only ever existed in the shadow tree) means there is nothing to
            // race against, so the write proceeds.
            val liveMtime = fileSystem.getLastModifiedTime(safPath)
            val manifestMtime = worktree.manifestSafMtimeFor(relativePath)
            if (liveMtime != null && manifestMtime != null && liveMtime > manifestMtime) {
                return DomainError.GitError.WorkingTreeConcurrentEditDetected(safPath).left()
            }

            val ok = fileSystem.writeFile(safPath, content)
            if (ok) {
                queue.dequeue(relativePath)
                // Stamp the manifest with the post-write mtime, mirroring
                // ShadowFlushActor.kt:91-93's stampMtime pattern, so the entry isn't seen as
                // stale on the next isFresh()/ensureFresh() check.
                fileSystem.getLastModifiedTime(safPath)?.let { mtime ->
                    worktree.updateManifestEntry(relativePath, mtime, content.encodeToByteArray().size.toLong())
                }
                Log.d(TAG, "flushPage: flushed $relativePath to SAF")
                Unit.right()
            } else {
                // Task 3.1.2c: do NOT dequeue — leave queued for retry on the next flush(),
                // matching ShadowFlushActor's existing retry-by-leaving-queued semantics. Unlike
                // that sibling's silent Log.w-only handling, the caller learns about this failure
                // via the returned Either.
                Log.w(TAG, "flushPage: SAF write failed for $relativePath — will retry")
                DomainError.GitError.WorkingTreeWriteBackFailed(safPath, "SAF write failed for $safPath").left()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "flushPage: unexpected error for $relativePath", e)
            DomainError.GitError.WorkingTreeWriteBackFailed(
                safPath,
                e.message ?: "unexpected error during write-back",
            ).left()
        }
    }
}
