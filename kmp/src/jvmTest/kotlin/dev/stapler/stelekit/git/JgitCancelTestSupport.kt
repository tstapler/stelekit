// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

/** A real working clone plus its local "origin", used to cancel real JGit calls (Tasks 1.1b, 1.5a). */
internal class JgitCancelFixture(fileCount: Int) : AutoCloseable {
    private val root: File = Files.createTempDirectory("jgit_cancel_").toFile()
    val origin: File = File(root, "origin")
    val work: File = File(root, "work")
    val config = GitConfig(
        graphId = "cancel-graph",
        repoRoot = RepoRoot(work.absolutePath),
        wikiSubdir = null,
        authType = GitAuthType.NONE,
    )

    init {
        Git.init().setDirectory(origin).setInitialBranch("main").call().use { git ->
            identify(git)
            File(origin, "seed.md").writeText("seed\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("seed").call()
        }
        Git.cloneRepository().setURI(origin.toURI().toString()).setDirectory(work).call().use { identify(it) }
        repeat(fileCount) { i -> File(work, "page_$i.md").writeText("- block $i\n") }
    }

    private fun identify(git: Git) {
        git.repository.config.apply {
            setString("user", null, "name", "Stelekit Test")
            setString("user", null, "email", "stelekit-test@example.com")
            save()
        }
    }

    /** Stages and commits the generated files directly through JGit, so a later call has something to do. */
    fun commitLocally() {
        Git.open(work).use { it.add().addFilepattern(".").call(); it.commit().setMessage("local").call() }
    }

    /** Adds a commit to origin so the next fetch and merge have remote work to do. */
    fun advanceOrigin() {
        Git.open(origin).use { git ->
            File(origin, "remote_${System.nanoTime()}.md").writeText("remote\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("remote").call()
        }
    }

    fun locksUnderGitDir(): List<String> =
        File(work, ".git").walkTopDown().filter { it.isFile && it.name.endsWith(".lock") }.map { it.path }.toList()

    fun statusIsHealthy(): Boolean = runCatching { Git.open(work).use { it.status().call() } }.isSuccess

    override fun close() {
        root.deleteRecursively()
    }
}
