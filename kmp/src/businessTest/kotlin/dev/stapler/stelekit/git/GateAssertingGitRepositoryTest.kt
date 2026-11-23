// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.right
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GateAssertingGitRepositoryTest {
    private val config = sampleConfig
    private val key = "gate-test-key"

    private class Recording : StubGitRepository() {
        var pushes = 0
        override suspend fun push(config: dev.stapler.stelekit.git.model.GitConfig) = run { pushes++; Unit.right() }
    }

    @Test
    fun mutatingCall_should_Throw_When_GateNotHeld() = runTest {
        val delegate = Recording()
        val repo = GateAssertingGitRepository(delegate, RepoOpGate()) { key }

        assertFailsWith<AssertionError> { repo.push(config) }
        assertEquals(0, delegate.pushes, "the delegate must not be reached without the gate")
    }

    @Test
    fun mutatingCall_should_Delegate_When_GateHeld() = runTest {
        val gate = RepoOpGate()
        val delegate = Recording()
        val repo = GateAssertingGitRepository(delegate, gate) { key }
        val lease = checkNotNull(gate.tryAcquire(key, SyncOrigin.UiPress))

        repo.push(config)
        gate.releaseOrHandOff(lease)

        assertEquals(1, delegate.pushes)
    }
}
