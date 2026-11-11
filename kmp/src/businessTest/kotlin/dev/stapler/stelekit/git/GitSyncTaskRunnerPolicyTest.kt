// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunContext
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [GitSyncTask] over the real [ScheduledSyncRunner]: the policy stays the gate (REQ-6). */
class GitSyncTaskRunnerPolicyTest {
    private class CallRecorder : StubGitRepository() {
        val calls = mutableListOf<String>()
        override suspend fun repositoryHealth(config: GitConfig): RepositoryHealth { calls += "health"; return RepositoryHealth.Clean }
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
            calls += "fetch"
            return FetchResult(false, 0).right()
        }
    }

    private val graphId = sampleConfig.graphId
    private val key = TaskKey(GitSyncTask.KIND, TaskScope(graphId))
    private val ctx = TaskRunContext(null, 0L, false, ConstraintState(online = true, isMetered = false, charging = null))

    @Test
    fun run_should_returnSkippedVaultLockedAndMakeNoGitCall_When_PolicyVaultLocked() = runTest {
        val repo = CallRecorder()
        val service = buildGitSyncTestService(
            gitRepository = repo,
            configRepository = StubConfigRepository(sampleConfig.right()),
            credentialAccessProvider = { LockedCredentialAccess },
            isOnline = { true },
        )
        val task = GitSyncTask(liveTick = { service.scheduled.run(it) })

        assertEquals(TaskRunResult.Skipped("vault-locked"), task.run(key, ctx))
        assertTrue(repo.calls.isEmpty(), "no git call on a policy skip: ${repo.calls}")
    }

    @Test
    fun toTaskRunResult_should_redactCredentials_When_FailureMessageEmbedsRemoteUrlAndToken() {
        val result = TickOutcome.Failure("push https://user:hunter2pw@example.com/r.git ghp_AbCdEf0123456789xyz").toTaskRunResult()
        val message = (result as TaskRunResult.Failed).message
        assertTrue("hunter2pw" !in message && "ghp_AbCdEf0123456789xyz" !in message, message)
    }

    @Test
    fun run_should_redactCredentials_When_BodyThrowsWithSecretInMessage() = runTest {
        val task = GitSyncTask(liveTick = { throw IllegalStateException("clone https://user:hunter2pw@example.com/r.git") })
        val failed = task.run(key, ctx) as TaskRunResult.Failed
        assertTrue("hunter2pw" !in failed.message, failed.message)
    }
}
