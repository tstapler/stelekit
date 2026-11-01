// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.wiring

import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.GitSyncServiceRegistry
import dev.stapler.stelekit.ui.GraphContentGitSyncStack
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * IT-33 / pitfalls A3: the sync stack registers its service in [GitSyncServiceRegistry] beside
 * `GraphManager.registerGitSyncService` and removes exactly that entry on dispose, so five graph
 * switches leave only the active graph's service and never a shut-down one.
 */
class GitSyncServiceRegistryBalanceTest {
    private val dataDir: File = createTempDirectory("registry_balance_").toFile()
    private var fixture: DesktopWiredAppFixture? = null
    private val compositions = mutableListOf<HeadlessComposition>()

    @AfterTest
    fun tearDown() {
        compositions.forEach { it.close() }
        fixture?.let { runBlocking { it.close() } }
        GitSyncServiceRegistry.registeredGraphIds().toList().forEach { GitSyncServiceRegistry.unregister(it) }
        dataDir.deleteRecursively()
    }

    private fun compose(wired: DesktopWiredAppFixture): Pair<HeadlessComposition, () -> GraphContentGitSyncStack?> {
        var stack: GraphContentGitSyncStack? = null
        val composition = HeadlessComposition().also { compositions += it }
        composition.setContent { stack = wired.composeGitSyncStack() }
        return composition to { stack }
    }

    @Test
    fun gitSyncServiceRegistry_should_HoldOnlyActiveGraphService_When_FiveSwitchGraphCalls() {
        val wired = runBlocking { DesktopWiredAppFixture.desktop(dataDir, graphPath = "/test/graph-a") }
        fixture = wired
        val paths = listOf("/test/graph-b", "/test/graph-a", "/test/graph-b", "/test/graph-a", "/test/graph-b")

        var (previous, previousStack) = compose(wired)
        val first = assertNotNull(previousStack()?.gitSyncService)
        assertEquals(setOf(wired.graphManager.graphRegistry.value.activeGraphId!!.value), GitSyncServiceRegistry.registeredGraphIds())
        assertSame(first, GitSyncServiceRegistry.getService(previousStack()!!.gitSyncGraphId))

        var lastService: GitSyncService = first
        for (path in paths) {
            runBlocking { wired.graphManager.openGraph(path) }
            // The app swaps GraphContent under key(activeGraphId): new graph composes, then the old one leaves.
            val (next, nextStack) = compose(wired)
            previous.dispose()
            lastService = assertNotNull(nextStack()?.gitSyncService)
            val activeId = wired.graphManager.graphRegistry.value.activeGraphId!!.value
            assertEquals(setOf(activeId), GitSyncServiceRegistry.registeredGraphIds(), "only the active graph is registered after switching to $path")
            assertSame(lastService, GitSyncServiceRegistry.getService(activeId), "a worker run for $activeId finds its live service")
            previous = next
        }

        previous.dispose()
        assertTrue(GitSyncServiceRegistry.registeredGraphIds().isEmpty(), "disposing the last stack leaves nothing registered")
        assertNull(wired.graphManager.activeGitSyncService.value)
    }

    @Test
    fun registry_should_KeepNewerRegistration_When_OldServiceUnregistersLate() {
        val wired = runBlocking { DesktopWiredAppFixture.desktop(dataDir) }
        fixture = wired
        val (first, firstStack) = compose(wired)
        val (second, secondStack) = compose(wired)
        val newer = assertNotNull(secondStack()?.gitSyncService)
        val id = secondStack()!!.gitSyncGraphId
        assertSame(newer, GitSyncServiceRegistry.getService(id))
        first.dispose()
        assertSame(newer, GitSyncServiceRegistry.getService(id), "the older stack's dispose must not drop the newer service")
        assertNotNull(firstStack())
        second.dispose()
        assertNull(GitSyncServiceRegistry.getService(id))
    }
}
