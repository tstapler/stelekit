// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin
import java.io.File

/**
 * [LockFiles] decorator for claim-race tests: [afterFirstRead] runs once, after the first record read has already
 * returned its value (so a barrier there means every claimer decided on the same record), and [afterPublish]
 * runs after a successful exclusive publish (a test uses it to start a simulated live `index.lock`).
 */
internal class HookedLockFiles(
    private val delegate: LockFiles,
    private val afterFirstRead: () -> Unit = {},
    private val afterPublish: () -> Unit = {},
) : LockFiles by delegate {
    private var firstReadDone = false

    override fun readRecord(): RecordRead? {
        val read = delegate.readRecord()
        if (!firstReadDone) {
            firstReadDone = true
            afterFirstRead()
        }
        return read
    }

    override fun createRecordExclusive(content: String): Boolean {
        val created = delegate.createRecordExclusive(content)
        if (created) afterPublish()
        return created
    }
}

/** Builds the record a claimer in this JVM writes, with the real identity values of this process. */
internal fun selfRecordFor(instance: String, nowMs: Long = System.currentTimeMillis()): LockOwnerRecord {
    val identity = JvmOwnedOperations.identity()
    return LockOwnerRecord(
        instanceId = InstanceId(instance),
        pid = identity.pid,
        processStartTicks = identity.startTicks,
        pidNs = identity.pidNs,
        host = identity.host,
        bootId = identity.bootId,
        op = "sync",
        origin = SyncOrigin.UiPress.name,
        startedAtMs = nowMs,
    )
}

/**
 * Entry point of the child JVMs spawned by `TwoJvmRecoveryRaceTest` and `LockClaimIntegrationTest`. Speaks a line
 * protocol on stdin/stdout; any other stdout line (logging) is ignored by the parent.
 *
 * `race`: per `ROUND <gitDir>` line prints `READY`, waits for `GO`, runs `LockRecovery.claim` over the real adapters
 * and prints `RESULT WIN` or `RESULT LOSE <error>`. A winner then starts a simulated live `index.lock`.
 * `hold <gitDir>`: takes the recovery lock, prints `HELD`, then blocks on stdin until killed or closed.
 */
object RecoveryRaceChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "race" -> race()
            "hold" -> hold(File(args[1]))
            else -> error("unknown mode ${args.toList()}")
        }
    }

    private fun hold(gitDir: File) {
        val handle = JavaIoLockFiles(gitDir).tryLockRecovery() ?: error("recovery lock already held")
        println("HELD")
        System.out.flush()
        System.`in`.read()
        handle.release()
    }

    private fun race() {
        val input = System.`in`.bufferedReader()
        val instance = "child-${ProcessHandle.current().pid()}"
        while (true) {
            val line = input.readLine() ?: return
            if (!line.startsWith("ROUND ")) continue
            val gitDir = File(line.removePrefix("ROUND "))
            println("READY")
            System.out.flush()
            while (input.readLine() != "GO") Unit
            println(claimOnce(gitDir, instance))
            System.out.flush()
        }
    }

    private fun claimOnce(gitDir: File, instance: String): String {
        val files = HookedLockFiles(
            delegate = JavaIoLockFiles(gitDir),
            afterPublish = { File(gitDir, "index.lock").writeText("live") },
        )
        val result = LockRecovery.claim(
            key = OwnedOperationSupport.keyFor(gitDir),
            lockFiles = files,
            liveness = JvmProcessLiveness(),
            self = selfRecordFor(instance),
            registry = ActiveRunRegistry(),
        )
        return result.fold({ "RESULT LOSE $it" }, { "RESULT WIN" })
    }
}
