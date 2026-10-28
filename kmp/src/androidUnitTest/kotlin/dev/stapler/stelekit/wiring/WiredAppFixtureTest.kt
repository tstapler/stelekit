// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.ui.GraphContentGitSyncStack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IT-01 (Android half): the Android fixture composes the real vault, graph-IO and sync stack files
 * with fake ports on Robolectric. The service-identity and shutdown half of IT-01 runs against real
 * SQLDelight in `DesktopWiredAppFixtureTest`, because Robolectric's SQLite has no FTS5 module and so
 * cannot create the schema (see [WiredAppFixture.android]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WiredAppFixtureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var fixture: WiredAppFixture? = null
    private val dataDir: File = createTempDirectory("wired_app_fixture_").toFile()

    @After
    fun tearDown() {
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    @Test
    fun wiredAppFixtureAndroid_should_ComposeRealStackFilesWithoutThrowing_And_LeaveNoServiceRegistered_When_BackendIsInMemory() {
        val wired = runBlocking {
            WiredAppFixture.android(ApplicationProvider.getApplicationContext<Context>(), dataDir)
        }
        fixture = wired

        var stack: GraphContentGitSyncStack? = null
        composeRule.setContent { stack = wired.composeGitSyncStack() }
        composeRule.waitForIdle()

        val composed = assertNotNull(stack, "the vault, graph-IO and sync stack files must compose on Robolectric")
        assertNull(composed.gitConfigRepository, "no config repository off the SQLDelight backend")
        assertNull(composed.gitSyncService)
        assertNull(wired.graphManager.activeGitSyncService.value)
        assertTrue(wired.graphManager.activeRepositorySet.value === wired.repos, "the fixture's repos are the manager's active set")
    }
}
