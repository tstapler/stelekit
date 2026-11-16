// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Task 1.1d / U-E1-34 (research H5). `GitSyncServiceRegistry` is an `object`; the app composes the new
 * graph's stack before the old one is disposed, so two services can briefly be registered for one graph id.
 * The replaced service's periodic driver must not keep ticking in that window.
 */
class GitSyncServiceRegistryTest {
    private val graphId = "registry-test-graph"
    private val services = mutableListOf<GitSyncService>()

    private fun newService(): GitSyncService = buildGitSyncTestService(
        gitRepository = StubGitRepository(),
        configRepository = StubConfigRepository(Either.Right(sampleConfig)),
    ).also { services += it }

    @AfterTest
    fun tearDown() {
        GitSyncServiceRegistry.unregister(graphId)
        services.forEach { it.shutdown() }
    }

    @Test
    fun register_should_StopReplacedDriver_When_SecondServiceForSameGraph() {
        val first = newService().also { it.startPeriodicSync(graphId, 15) }
        val second = newService().also { it.startPeriodicSync(graphId, 15) }
        GitSyncServiceRegistry.register(graphId, first)
        assertTrue(first.scheduledSyncStatus.value.running)

        GitSyncServiceRegistry.register(graphId, second)

        assertFalse(first.scheduledSyncStatus.value.running, "the replaced service's driver must not stay running")
        assertTrue(second.scheduledSyncStatus.value.running, "the newly registered service keeps its driver")
        assertSame(second, GitSyncServiceRegistry.getService(graphId))
    }

    @Test
    fun register_should_LeaveDriverRunning_When_SameServiceRegisteredAgain() {
        val service = newService().also { it.startPeriodicSync(graphId, 15) }
        GitSyncServiceRegistry.register(graphId, service)

        GitSyncServiceRegistry.register(graphId, service)

        assertTrue(service.scheduledSyncStatus.value.running)
    }
}
