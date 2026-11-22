// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real-directory claim tests (Task 1.2f, I-E1-04..06 claim half, U-E1-07 real half): [LockRecovery.claim] over the
 * real [JavaIoLockFiles] and [JvmProcessLiveness]. Several claimers in one JVM use separate [ActiveRunRegistry]
 * instances to stand in for separate processes; the genuinely cross-process cases are in `TwoJvmRecoveryRaceTest`.
 */
class LockClaimIntegrationTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newGitDir(): File {
        val root = Files.createTempDirectory("lock-claim").toFile().also { dirs += it }
        return File(root, ".git").also { it.mkdirs() }
    }

    private val exitedPid: Long by lazy {
        val process = ProcessBuilder("true").start()
        process.waitFor(30, TimeUnit.SECONDS)
        process.pid()
    }

    // A record whose owner exited: same host/boot/pid namespace as us, absent pid, so real /proc probing says Dead.
    private fun deadRecord(startedAtMs: Long): LockOwnerRecord =
        selfRecordFor("dead-owner", startedAtMs).copy(pid = exitedPid, processStartTicks = 1L)

    private fun writeDeadOwner(gitDir: File, now: Long, lockAgeMs: Long = 60_000): File {
        val file = File(gitDir, JavaIoLockFiles.RECORD_NAME).apply { writeText(deadRecord(now - 100_000).encode()) }
        file.setLastModified(now - 60_000)
        return File(gitDir, "index.lock").apply {
            writeText("dead")
            setLastModified(now - lockAgeMs)
        }
    }

    private fun claim(
        gitDir: File,
        instance: String,
        registry: ActiveRunRegistry = ActiveRunRegistry(),
        files: LockFiles = JavaIoLockFiles(gitDir),
    ) = LockRecovery.claim(
        key = OwnedOperationSupport.keyFor(gitDir),
        lockFiles = files,
        liveness = JvmProcessLiveness(),
        self = selfRecordFor(instance),
        registry = registry,
    )

    private fun recordOwner(gitDir: File): String? =
        File(gitDir, JavaIoLockFiles.RECORD_NAME).takeIf { it.exists() }
            ?.let { (LockOwnerRecord.decode(it.readText()) as? RecordDecode.Valid)?.record?.instanceId?.value }

    // ---- real /proc liveness (U-E1-07 real half) ----

    @Test
    fun jvmProcessLiveness_should_ReadRealProc_SelfAlive_ExitedDead_WrongTicksDead() {
        val liveness = JvmProcessLiveness()
        val me = selfRecordFor("me")
        assertEquals(Liveness.Alive, liveness.probe(me.pid, me.processStartTicks, me.pidNs, me.host))
        assertEquals(Liveness.Dead, liveness.probe(me.pid, me.processStartTicks!! + 1_000_000, me.pidNs, me.host), "pid reuse: same pid, other start")
        assertEquals(Liveness.Dead, liveness.probe(exitedPid, 1L, me.pidNs, me.host))
    }

    // ---- Story 1.2 ordering AC at claim level ----

    @Test
    fun claim_should_ReadOwnLeftoverRecordBeforeOverwritingIt_AndClearItsCorpse() {
        val gitDir = newGitDir()
        val now = System.currentTimeMillis()
        File(gitDir, JavaIoLockFiles.RECORD_NAME).apply {
            writeText(selfRecordFor("me", now - 100_000).encode())
            setLastModified(now - 60_000)
        }
        val lock = File(gitDir, "index.lock").apply { writeText("corpse"); setLastModified(now - 60_000) }

        val result = claim(gitDir, "me")

        assertEquals(LockVerdict.ClearedOwn, assertIs<Either.Right<ClaimedRun>>(result).value.verdict)
        assertFalse(lock.exists())
        assertEquals("me", recordOwner(gitDir))
    }

    @Test
    fun claim_should_LeaveLiveSecondInstancesLockAndRecord_AndReturnSyncInProgress() {
        val gitDir = newGitDir()
        val now = System.currentTimeMillis()
        File(gitDir, JavaIoLockFiles.RECORD_NAME).apply {
            writeText(selfRecordFor("instance-a", now - 100_000).encode())
            setLastModified(now - 1_000)
        }
        val lock = File(gitDir, "index.lock").apply { writeText("live"); setLastModified(now - 10_000) }

        val result = claim(gitDir, "instance-b")

        assertEquals(DomainError.GitError.SyncInProgress(null), assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertTrue(lock.exists())
        assertEquals("instance-a", recordOwner(gitDir))
    }

    // ---- no-record CREATE_NEW race ----

    @Test
    fun claim_should_LetExactlyOneOfManyClaimersWin_When_NoRecordExists() {
        repeat(50) { round ->
            val gitDir = newGitDir()
            val barrier = CyclicBarrier(3)
            val results = ConcurrentLinkedQueue<Either<DomainError.GitError, ClaimedRun>>()
            val threads = (0 until 3).map { i ->
                Thread {
                    barrier.await(30, TimeUnit.SECONDS)
                    results += claim(gitDir, "c$i")
                }.also { it.start() }
            }
            threads.forEach { it.join(30_000) }
            assertEquals(1, results.count { it.isRight() }, "round $round")
            assertEquals(2, results.count { it.isLeft() }, "round $round")
        }
    }

    // ---- pre-existing dead record: two and three claimers, 200 seeded interleavings ----

    private fun raceOverDeadRecord(claimers: Int, seed: Long) {
        val gitDir = newGitDir()
        val now = System.currentTimeMillis()
        writeDeadOwner(gitDir, now)
        val allDecided = CyclicBarrier(claimers)
        val random = Random(seed)
        val spins = IntArray(claimers) { random.nextInt(2_000) }
        val results = ConcurrentLinkedQueue<Pair<String, Either<DomainError.GitError, ClaimedRun>>>()
        val threads = (0 until claimers).map { i ->
            Thread {
                val files = HookedLockFiles(
                    delegate = JavaIoLockFiles(gitDir),
                    afterFirstRead = {
                        allDecided.await(30, TimeUnit.SECONDS)
                        repeat(spins[i]) { Thread.onSpinWait() }
                    },
                    // The winner's git starts right after its claim: a live lock that no loser may delete.
                    afterPublish = { File(gitDir, "index.lock").writeText("live") },
                )
                results += "c$i" to claim(gitDir, "c$i", files = files)
            }.also { it.start() }
        }
        threads.forEach { it.join(60_000) }

        val winners = results.filter { it.second.isRight() }
        assertEquals(1, winners.size, "seed $seed: exactly one winner, got ${results.map { it.first to it.second.isRight() }}")
        assertEquals(winners.single().first, recordOwner(gitDir), "seed $seed: the record is the winner's")
        val live = File(gitDir, "index.lock")
        assertTrue(live.exists() && live.readText() == "live", "seed $seed: the winner's live lock survived")
        results.filter { it.second.isLeft() }.forEach { (_, outcome) ->
            assertEquals(DomainError.GitError.SyncInProgress(null), (outcome as Either.Left).value, "seed $seed: losers are told to retry")
        }
    }

    @Test
    fun twoClaimersOverPreExistingDeadRecord_should_HaveOneWinnerAndKeepItsLiveLock_Over200SeededInterleavings() {
        for (seed in 0L until 200L) raceOverDeadRecord(claimers = 2, seed = seed)
    }

    @Test
    fun threeClaimersOverPreExistingDeadRecord_should_HaveOneWinnerAndKeepItsLiveLock_Over200SeededInterleavings() {
        for (seed in 0L until 200L) raceOverDeadRecord(claimers = 3, seed = seed)
    }

    @Test
    fun lateClaimer_should_SeeWinnersLiveRecordAndDeleteNothing() {
        val gitDir = newGitDir()
        writeDeadOwner(gitDir, System.currentTimeMillis())
        assertIs<Either.Right<ClaimedRun>>(claim(gitDir, "winner"))
        val live = File(gitDir, "index.lock").apply { writeText("live") }

        val late = claim(gitDir, "late")

        assertEquals(DomainError.GitError.SyncInProgress(null), assertIs<Either.Left<DomainError.GitError>>(late).value)
        assertTrue(live.exists())
        assertEquals("winner", recordOwner(gitDir))
    }

    // ---- fail-closed paths ----

    @Test
    fun claim_should_FailClosed_When_TheRecoveryLockIsUnsupported() {
        val gitDir = newGitDir()
        val lock = writeDeadOwner(gitDir, System.currentTimeMillis())
        // A directory where the permanent recovery file must be: it cannot be opened for locking.
        File(gitDir, JavaIoLockFiles.RECOVER_NAME).mkdirs()

        val result = claim(gitDir, "c")

        val error = assertIs<DomainError.GitError.StaleLockFile>(assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertEquals(LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported), error.verdict)
        assertTrue(lock.exists())
        assertEquals("dead-owner", recordOwner(gitDir))
    }

    @Test
    fun claim_should_DeleteNothing_When_ALockFallsOutsideTheDeadOwnersWindow() {
        val now = System.currentTimeMillis()
        // Newer than the record's last heartbeat plus 10 s (a terminal git run today), older than the owner's start,
        // and younger than 5 s: each alone must veto the delete.
        mapOf("after heartbeat" to 30_000L, "before start" to 150_000L, "still warm" to 1_000L).forEach { (name, ageMs) ->
            val gitDir = newGitDir()
            val record = File(gitDir, JavaIoLockFiles.RECORD_NAME).apply {
                writeText(deadRecord(now - 100_000).encode())
                setLastModified(if (name == "still warm") now - 0 else now - 60_000)
            }
            val lock = File(gitDir, "index.lock").apply { writeText("x"); setLastModified(now - ageMs) }

            val result = claim(gitDir, "c")

            val error = assertIs<DomainError.GitError.StaleLockFile>(assertIs<Either.Left<DomainError.GitError>>(result).value, name)
            assertEquals(LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected), error.verdict, name)
            assertTrue(lock.exists() && record.exists(), "$name: nothing deleted")
        }
    }

    // ---- .git layouts ----

    @Test
    fun resolveGitDir_should_FollowAGitdirLine_AndClaimThere() {
        val root = Files.createTempDirectory("lock-claim-saf").toFile().also { dirs += it }
        val real = File(root, "shadow/real-git").also { it.mkdirs() }
        File(root, ".git").writeText("gitdir: ${real.absolutePath}\n")

        val resolved = JavaIoLockFiles.resolveGitDir(root)

        assertEquals(real.canonicalFile, resolved.canonicalFile)
        assertIs<Either.Right<ClaimedRun>>(claim(resolved, "c"))
        assertTrue(File(real, JavaIoLockFiles.RECORD_NAME).exists())
    }

    @Test
    fun beginOnAnUnresolvableGitFile_should_BeOwnerUnknown_NeverClaim() {
        val root = Files.createTempDirectory("lock-claim-file").toFile().also { dirs += it }
        File(root, ".git").writeText("not a gitdir line\n")
        val support = OwnedOperationSupport(JvmProcessLiveness(), JvmOwnedOperations.identity(), ActiveRunRegistry())

        val verdict = support.begin(JavaIoLockFiles.resolveGitDir(root), OwnedOperationInfo("sync", SyncOrigin.UiPress))

        assertEquals(Either.Right(LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)), verdict)
    }

    // ---- cross-process: a killed recovery-lock holder releases ----

    @Test
    fun recoveryLockHeldByAChildJvm_should_BlockClaimsUntilTheChildIsKilled() {
        val gitDir = newGitDir()
        val lock = writeDeadOwner(gitDir, System.currentTimeMillis())
        val child = ChildJvm.spawn("hold", gitDir.absolutePath)
        try {
            assertEquals("HELD", child.awaitLine { it == "HELD" })

            val blocked = claim(gitDir, "parent")
            assertEquals(DomainError.GitError.SyncInProgress(null), assertIs<Either.Left<DomainError.GitError>>(blocked).value)
            assertTrue(lock.exists(), "a contended claim deletes nothing")
        } finally {
            child.kill()
        }

        assertIs<Either.Right<ClaimedRun>>(claim(gitDir, "parent"), "the kernel released the killed holder's lock")
        assertFalse(File(gitDir, "index.lock").exists())
    }
}
