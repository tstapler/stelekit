// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git.testsupport

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RefUpdate
import java.io.File
import kotlin.io.path.createTempDirectory

/** Temp bare-origin helpers shared by the real-JGit sync tests. */
internal object BareOriginFixtures {

    fun setIdentity(git: Git) {
        val cfg = git.repository.config
        cfg.setString("user", null, "name", "Stelekit Test")
        cfg.setString("user", null, "email", "stelekit-test@example.com")
        cfg.save()
    }

    /** Bare origin whose only branch is [branch], holding one commit that adds `journal.md`. */
    fun createBareOrigin(prefix: String, branch: String, seed: Boolean = true): File {
        val bare = createTempDirectory(prefix).toFile()
        Git.init().setBare(true).setDirectory(bare).setInitialBranch(branch).call().close()
        if (seed) pushCommit(bare, branch, "journal.md", "entry 0\n", "seed")
        return bare
    }

    /** Adds one commit writing [path] to [branch] of the bare [origin] via a throwaway clone. */
    fun pushCommit(origin: File, branch: String, path: String, content: String, message: String) {
        val work = createTempDirectory("stelekit_seed_").toFile()
        try {
            Git.init().setDirectory(work).setInitialBranch(branch).call().use { git ->
                setIdentity(git)
                git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(origin.absolutePath)).call()
                if (git.repository.refDatabase.exactRef("refs/heads/$branch") == null) {
                    runCatching { git.fetch().setRemote("origin").call() }
                    val remoteTip = git.repository.exactRef("refs/remotes/origin/$branch")
                    if (remoteTip != null) {
                        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("origin/$branch").call()
                    }
                }
                File(work, path).also { it.parentFile.mkdirs() }.writeText(content)
                git.add().addFilepattern(".").call()
                git.commit().setMessage(message).call()
                git.push().setRemote("origin").add("refs/heads/$branch:refs/heads/$branch").call()
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /** Renames branch [from] to [to] on the bare [origin] (what a remote default-branch rename does). */
    fun renameBranch(origin: File, from: String, to: String) {
        Git.open(origin).use { git ->
            val repo = git.repository
            val tip = repo.exactRef("refs/heads/$from").objectId
            val create = repo.updateRef("refs/heads/$to")
            create.setNewObjectId(tip)
            check(create.update() == RefUpdate.Result.NEW)
            val delete = repo.updateRef("refs/heads/$from")
            delete.isForceUpdate = true
            check(delete.delete() == RefUpdate.Result.FORCED || delete.delete() == RefUpdate.Result.NO_CHANGE)
            repo.updateRef(Constants.HEAD).link("refs/heads/$to")
        }
    }

    fun remoteBranches(origin: File): List<String> =
        Git.open(origin).use { git ->
            git.branchList().call().map { it.name.removePrefix("refs/heads/") }.sorted()
        }

    fun remoteHead(origin: File, branch: String): String? =
        Git.open(origin).use { it.repository.exactRef("refs/heads/$branch")?.objectId?.name }
}
