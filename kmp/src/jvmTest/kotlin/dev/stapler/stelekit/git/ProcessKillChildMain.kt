// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git

/**
 * Child JVM of `ProcessKillOrphanLockTest`: claims the repository through a real `JvmGitRepository`, then takes
 * JGit's own `index.lock` (the lock a `commit` holds while it writes) and parks until the parent kills it.
 * Prints `LOCK_HELD` once both exist. Arg 0 is the repository root.
 */
object ProcessKillChildMain {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val root = args[0]
        val config = GitConfig(graphId = "kill-graph", repoRoot = RepoRoot(root), wikiSubdir = null, authType = GitAuthType.NONE)
        val repo = JvmGitRepository()
        repo.beginOwnedOperation(config, OwnedOperationInfo("commit", SyncOrigin.Cli)).fold({ error("claim refused: $it") }, { })
        Git.open(java.io.File(root)).use { git ->
            git.repository.lockDirCache()
            println("LOCK_HELD")
            System.out.flush()
            System.`in`.read()
            Unit
        }
    }
}
