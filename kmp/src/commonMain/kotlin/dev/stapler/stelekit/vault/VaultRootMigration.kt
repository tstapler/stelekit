// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.vault

import dev.stapler.stelekit.git.VaultCredentialStore
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.FileSystem

/**
 * ADR-020: vault files live at the repo root. Wiki-subfolder users have them under the notes folder,
 * so on open this copies each one to the repo root, verifies it, then deletes the original.
 *
 * Stateless and idempotent: a destination that already exists is never overwritten (unless it is a truncated
 * prefix of the source, i.e. a torn copy) and its source is left alone, so a crash between copy and delete just resumes on the next open. A flat layout (repo root ==
 * notes folder) is a no-op.
 */
class VaultRootMigration(private val fileSystem: FileSystem) {
    private val logger = Logger("VaultRootMigration")

    /** Moves whatever vault files exist; returns how many were moved. Never throws for a single failed file. */
    fun migrate(repoRoot: RepoRoot, notesPath: EffectiveNotesPath): Int {
        val root = repoRoot.value.trimEnd('/')
        val notes = notesPath.directory()
        if (root.isEmpty() || notes.isEmpty() || root == notes) return 0
        val files = listOf(
            VaultManager.vaultFilePath(notesPath.asRepoRoot()) to VaultManager.vaultFilePath(repoRoot),
            VaultCredentialStore.credentialFilePath(notesPath.asRepoRoot()) to VaultCredentialStore.credentialFilePath(repoRoot),
            VaultManager.hiddenReserveSentinelPath(notesPath.asRepoRoot()) to VaultManager.hiddenReserveSentinelPath(repoRoot),
        )
        return files.count { (from, to) -> move(from, to) }
    }

    // A crash mid-write leaves a truncated destination; without this the next open would treat it as the real file.
    private fun isTornCopy(dest: ByteArray?, source: ByteArray): Boolean =
        dest != null && dest.size < source.size && source.copyOf(dest.size).contentEquals(dest)

    private fun EffectiveNotesPath.asRepoRoot() = RepoRoot(directory())

    private fun move(from: String, to: String): Boolean {
        if (!fileSystem.fileExists(from)) return false
        return try {
            val bytes = fileSystem.readFileBytes(from) ?: return false
            if (fileSystem.fileExists(to) && !isTornCopy(fileSystem.readFileBytes(to), bytes)) return false
            fileSystem.createDirectory(to.substringBeforeLast('/'))
            if (!fileSystem.writeFileBytes(to, bytes) || !fileSystem.readFileBytes(to).contentEquals(bytes)) {
                logger.warn("copy to $to failed verification; leaving $from in place")
                return false
            }
            fileSystem.deleteFile(from)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("could not move $from to $to: ${e.message}")
            false
        }
    }
}
