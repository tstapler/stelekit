// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.cli

import dev.stapler.stelekit.git.ActiveRunRegistry
import dev.stapler.stelekit.git.InstanceId
import dev.stapler.stelekit.git.JavaIoLockFiles
import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.JvmOwnedOperations
import dev.stapler.stelekit.git.JvmProcessLiveness
import dev.stapler.stelekit.git.LockVerdict
import dev.stapler.stelekit.git.OwnedOperationInfo
import dev.stapler.stelekit.git.OwnedOperationSupport
import dev.stapler.stelekit.git.RepoOpGate
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 1.2e: the CLI claims through the same protocol as the app, so a second instance coalesces in both directions. */
class SyncMainClaimTest {
    private val root = Files.createTempDirectory("sync-main-claim").toFile().also { File(it, ".git").mkdirs() }
    private val config = GitConfig(graphId = "g", repoRoot = RepoRoot(root.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)
    private val notes = EffectiveNotesPath(root.absolutePath)
    private val output = SyncOutput(jsonMode = true)
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeTest
    fun silence() {
        System.setOut(PrintStream(ByteArrayOutputStream(), true))
        System.setErr(PrintStream(ByteArrayOutputStream(), true))
    }

    @AfterTest
    fun cleanup() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        root.deleteRecursively()
    }

    // A separate registry and instance id stand in for a separate process.
    private fun instance(name: String) = JvmGitRepository(
        ownedOperations = OwnedOperationSupport(
            JvmProcessLiveness(),
            JvmOwnedOperations.identity().copy(instanceId = InstanceId(name)),
            registry = ActiveRunRegistry(),
        ),
    )

    private val gitDir get() = File(root, ".git")

    @Test
    fun cliAndApp_should_CoalesceViaOwnerAlive_InBothDirections() = runBlocking {
        val app = instance("app")
        assertEquals(LockVerdict.NoLock, app.beginOwnedOperation(config, OwnedOperationInfo("sync", SyncOrigin.UiPress)).getOrNull())
        var ran = false
        val busy = assertFailsWith<SyncExitException> {
            runOwned(RepoOpGate(), instance("cli"), config, output, notes) { ran = true }
        }
        assertEquals(EXIT_BUSY, busy.code)
        assertFalse(ran)
        assertTrue(File(gitDir, JavaIoLockFiles.RECORD_NAME).exists(), "the live app's record survives")
        app.endOwnedOperation(config)

        val inBody = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cli = async {
            runOwned(RepoOpGate(), instance("cli2"), config, output, notes) {
                inBody.complete(Unit)
                release.await()
            }
        }
        withTimeout(30_000) { inBody.await() }
        val verdict = instance("app2").beginOwnedOperation(config, OwnedOperationInfo("sync", SyncOrigin.UiPress)).getOrNull()
        assertEquals(LockVerdict.OwnerAlive, verdict)
        release.complete(Unit)
        cli.await()
        assertFalse(File(gitDir, JavaIoLockFiles.RECORD_NAME).exists(), "the CLI releases its record when done")
    }

    @Test
    fun runOwned_should_BumpTheRecordHeartbeat_AndEndTheClaimOnFailure() = runBlocking {
        val record = File(gitDir, JavaIoLockFiles.RECORD_NAME)
        var stamped = 0L
        assertFailsWith<IllegalStateException> {
            runOwned(RepoOpGate(), instance("cli"), config, output, notes, heartbeatIntervalMs = 50) {
                record.setLastModified(1_000L)
                withTimeout(30_000) { while (record.lastModified() < 100_000L) yield() }
                stamped = record.lastModified()
                error("boom")
            }
        }
        assertTrue(stamped > 100_000L, "heartbeat touched the record")
        assertFalse(record.exists(), "claim ended after the body threw")
    }
}
