// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I-E1-07 (adversarial C6, service level): two separate JVMs run a real sync over a dead owner's record and
 * `index.lock`, both left by a third process that was killed mid-run. The dead owner must be cleared once and both
 * runs must end synced or coalesced, never failed. Pairs: app vs app, and `SyncMain` vs the app.
 */
class CrossProcessSyncOverDeadRecordTest {
    private val rounds = 2

    @Test
    fun twoAppJvms_should_ClearTheDeadOwnerOnce_AndEndSyncedOrCoalesced() = race("service", "service")

    @Test
    fun syncMainAndApp_should_ClearTheDeadOwnerOnce_AndEndSyncedOrCoalesced() = race("cli", "service")

    private fun race(vararg modes: String) {
        val dirs = mutableListOf<File>()
        val children = modes.map { ChildJvm.spawnMain(CrossProcessSyncChildMain::class.java.name, it) }
        val seen = mutableListOf<String>()
        try {
            repeat(rounds) { round ->
                val repo = newRepoWithUnpushedChange(dirs)
                killedOwnerLeavesRecordAndLock(repo)
                children.forEach { it.send("ROUND ${repo.absolutePath}") }
                children.forEach { it.awaitLine { line -> seen += line; line == "READY" } }
                children.forEach { it.send("GO") }
                val results = children.map { c -> c.awaitLine { line -> seen += line; line.startsWith("RESULT ") } }

                val ctx = "round $round, modes ${modes.toList()}: $results"
                assertTrue(results.all { it == "RESULT SYNCED" || it == "RESULT COALESCED" }, ctx)
                assertTrue(results.any { it == "RESULT SYNCED" }, "at least one run must complete: $ctx")
                assertFalse(File(repo, ".git/index.lock").exists(), "the dead owner's lock is gone: $ctx")
                assertFalse(File(repo, ".git/${JavaIoLockFiles.RECORD_NAME}").exists(), "no record is left behind: $ctx")
                assertEquals(headOf(File(repo.parentFile, "origin.git")), headOf(File(repo, ".git")), "the local commit reached origin: $ctx")
            }
        } finally {
            children.forEach { it.kill() }
            dirs.forEach { it.deleteRecursively() }
        }
        assertTrue(seen.count { it.contains("lock verdict=ClearedDeadOwner") } <= rounds, "the dead owner is cleared at most once per round: $seen")
    }

    private fun newRepoWithUnpushedChange(dirs: MutableList<File>): File {
        val root = Files.createTempDirectory("cross-process-sync").toFile().also { dirs += it }
        val origin = File(root, "origin.git")
        val local = File(root, "local")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(origin).call().close()
        Git.init().setInitialBranch("main").setDirectory(local).call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Stelekit Test")
                setString("user", null, "email", "stelekit-test@example.com")
                setBoolean("commit", null, "gpgsign", false)
                save()
            }
            git.remoteAdd().setName("origin").setUri(URIish(origin.absolutePath)).call()
            File(local, "pages").mkdirs()
            File(local, "pages/a.md").writeText("- seed\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("seed").setSign(false).call()
            git.push().setRemote("origin").add("main").call()
            git.repository.config.apply {
                setString("branch", "main", "remote", "origin")
                setString("branch", "main", "merge", "refs/heads/main")
                save()
            }
        }
        File(local, "pages/a.md").writeText("- seed\n- edited while the old owner died\n")
        return local
    }

    /** A third JVM claims the repository and leaves its `index.lock`, then is SIGKILLed; both files are then aged past the 5 s foreign-lock floor. */
    private fun killedOwnerLeavesRecordAndLock(repo: File) {
        val seed = ChildJvm.spawnMain(CrossProcessSyncChildMain::class.java.name, "seed", repo.absolutePath)
        try {
            seed.awaitLine { it == "SEEDED" }
        } finally {
            seed.kill()
        }
        assertTrue(File(repo, ".git/index.lock").exists() && File(repo, ".git/${JavaIoLockFiles.RECORD_NAME}").exists())
        ageFiles(repo)
    }

    /** Backdates the record's start and the files' mtimes together so the foreign-lock window (start-2s <= lock <= record+10s, lock <= now-5s) holds without waiting. */
    private fun ageFiles(repo: File) {
        val now = System.currentTimeMillis()
        val recordFile = File(repo, ".git/${JavaIoLockFiles.RECORD_NAME}")
        val record = (LockOwnerRecord.decode(recordFile.readText()) as RecordDecode.Valid).record
        recordFile.writeText(record.copy(startedAtMs = now - 60_000).encode())
        recordFile.setLastModified(now - 30_000)
        File(repo, ".git/index.lock").setLastModified(now - 50_000)
    }

    private fun headOf(gitDirOrBare: File): String? = Git.open(gitDirOrBare.let { if (it.name == ".git") it.parentFile else it }).use { git ->
        git.repository.resolve("refs/heads/main")?.name
    }
}
