// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A spawned JVM running [RecoveryRaceChildMain] on this test's own classpath, spoken to over stdin/stdout lines. */
internal class ChildJvm private constructor(private val process: Process) {
    private val lines = LinkedBlockingQueue<String>()
    private val stdin = PrintWriter(process.outputStream, true)

    init {
        Thread {
            process.inputStream.bufferedReader().forEachLine { lines.put(it) }
        }.apply { isDaemon = true }.start()
    }

    fun send(line: String) = stdin.println(line)

    /** The next line matching [accept], skipping unrelated output such as log lines; fails the test after [timeoutSec]. */
    fun awaitLine(timeoutSec: Long = 120, accept: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val line = lines.poll(maxOf(remaining, 0), TimeUnit.NANOSECONDS) ?: error("child JVM produced no matching line in ${timeoutSec}s")
            if (accept(line)) return line
        }
    }

    fun kill() {
        process.destroyForcibly()
        process.waitFor(30, TimeUnit.SECONDS)
    }

    companion object {
        fun spawn(vararg args: String): ChildJvm = spawnMain(RecoveryRaceChildMain::class.java.name, *args)

        fun spawnMain(mainClass: String, vararg args: String): ChildJvm {
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val command = listOf(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"), mainClass) + args
            return ChildJvm(ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start())
        }
    }
}

/**
 * I-E1-06 (adversarial C6): two separate JVMs race `LockRecovery.claim` over a pre-existing dead record, 40 rounds.
 * The in-JVM races in `LockClaimIntegrationTest` lean on the JVM's own file-lock table; here the kernel decides.
 * Real `/proc` liveness is exercised by both children.
 */
class TwoJvmRecoveryRaceTest {
    private val rounds = 40

    @Test
    fun twoJvmsRacingOverADeadRecord_should_HaveExactlyOneWinnerPerRound_AndKeepItsLiveLock() {
        val parentDirs = mutableListOf<File>()
        val children = listOf(ChildJvm.spawn("race"), ChildJvm.spawn("race"))
        try {
            repeat(rounds) { round ->
                val gitDir = newDeadOwnerGitDir(parentDirs)
                children.forEach { it.send("ROUND ${gitDir.absolutePath}") }
                children.forEach { it.awaitLine { line -> line == "READY" } }
                children.forEach { it.send("GO") }
                val results = children.map { it.awaitLine { line -> line.startsWith("RESULT ") } }

                assertEquals(1, results.count { it == "RESULT WIN" }, "round $round: $results")
                val live = File(gitDir, "index.lock")
                assertTrue(live.exists() && live.readText() == "live", "round $round: the winner's live lock survived")
                val owner = (LockOwnerRecord.decode(File(gitDir, JavaIoLockFiles.RECORD_NAME).readText()) as RecordDecode.Valid).record
                assertTrue(owner.instanceId.value.startsWith("child-"), "round $round: the record is a child's, not the dead owner's")
            }
        } finally {
            children.forEach { it.kill() }
            parentDirs.forEach { it.deleteRecursively() }
        }
    }

    private fun newDeadOwnerGitDir(dirs: MutableList<File>): File {
        val root = Files.createTempDirectory("two-jvm-race").toFile().also { dirs += it }
        val gitDir = File(root, ".git").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        val exited = ProcessBuilder("true").start().also { it.waitFor(30, TimeUnit.SECONDS) }
        val dead = selfRecordFor("dead-owner", now - 100_000).copy(pid = exited.pid(), processStartTicks = 1L)
        File(gitDir, JavaIoLockFiles.RECORD_NAME).apply { writeText(dead.encode()); setLastModified(now - 60_000) }
        File(gitDir, "index.lock").apply { writeText("dead"); setLastModified(now - 60_000) }
        return gitDir
    }
}
