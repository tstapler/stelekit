// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.wiring

import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.ui.GraphContentGitSyncStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * IT-01 (real SQLDelight half) and IT-02: the desktop fixture composes the same stack files with no
 * display, the returned `GitSyncService` is the one registered on `GraphManager`, and disposing the
 * composition shuts it down so no tick can run afterwards.
 */
class DesktopWiredAppFixtureTest {
    private val dataDir: File = createTempDirectory("desktop_wired_fixture_").toFile()
    private var fixture: DesktopWiredAppFixture? = null
    private var headless: HeadlessComposition? = null

    @AfterTest
    fun tearDown() {
        headless?.close()
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private fun GitSyncService.scopeJob(): Job {
        val field = GitSyncService::class.java.getDeclaredField("scope").apply { isAccessible = true }
        return (field.get(this) as CoroutineScope).coroutineContext[Job]!!
    }

    @Test
    fun wiredAppFixtureDesktop_should_BehaveIdenticallyWithoutDisplay_When_StackComposed() {
        val wired = runBlocking { DesktopWiredAppFixture.desktop(dataDir) }
        fixture = wired
        val composition = HeadlessComposition().also { headless = it }

        var stack: GraphContentGitSyncStack? = null
        composition.setContent { stack = wired.composeGitSyncStack() }

        val service = assertNotNull(stack?.gitSyncService, "a gitRepository port on the SQLDelight backend must yield a GitSyncService")
        assertNotNull(stack?.gitConfigRepository)
        assertSame(service, wired.graphManager.activeGitSyncService.value)
        assertTrue(service.scopeJob().isActive, "service is live while composed")

        composition.dispose()

        assertNull(wired.graphManager.activeGitSyncService.value, "disposing unregisters the service")
        assertTrue(service.scopeJob().isCancelled, "disposing calls shutdown(): its scope is cancelled, so no tick can run")
    }
}
