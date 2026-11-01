// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.db.BackupFileInfo
import dev.stapler.stelekit.db.BackupFiles
import dev.stapler.stelekit.db.RestoreRecovery
import dev.stapler.stelekit.ui.components.RestoreRecoveryBannerHost
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNull

/** AC 3.4-G: the one-time banner after a recovered restore, independent of developer mode and the console. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RestoreRecoveryBannerTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Just enough of a file system for [RestoreRecovery.recoverIfNeeded] to run a "Copied" journal to completion. */
    private class OneJournalFiles(private val db: String) : BackupFiles {
        val store = mutableMapOf("$db.restore-journal" to "state=Copied\nsource=x\nbytes=1\nstartedMs=1\n")
        override fun ensureDir(path: String) = true
        override fun list(dir: String) = emptyList<BackupFileInfo>()
        override fun delete(path: String) = store.remove(path) != null
        override fun rename(from: String, to: String) = false
        override fun sizeOf(path: String) = store[path]?.length?.toLong()
        override fun usableSpace(path: String): Long? = null
        override fun copy(from: String, to: String) = false
        override fun readText(path: String) = store[path]
        override fun writeText(path: String, text: String): Boolean { store[path] = text; return true }
    }

    @Before
    fun reset() = RestoreRecovery.resetForTest()

    @After
    fun clear() = RestoreRecovery.resetForTest()

    @Test
    fun banner_should_ShowOnceAndDisappear_When_AnInterruptedRestoreWasRecovered() {
        composeRule.setContent { RestoreRecoveryBannerHost() }
        composeRule.onNodeWithTag("restore-recovery-banner").assertDoesNotExist()

        RestoreRecovery.recoverIfNeeded("/data/graph.db", OneJournalFiles("/data/graph.db"))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("restore-recovery-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("restore-recovery-ok").performClick()
        composeRule.waitForIdle()
        assertNull(RestoreRecovery.notice.value)
        composeRule.onNodeWithTag("restore-recovery-banner").assertDoesNotExist()
    }
}
