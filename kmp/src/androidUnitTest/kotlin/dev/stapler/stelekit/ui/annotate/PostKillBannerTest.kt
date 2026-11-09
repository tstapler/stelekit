// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.platform.diagnostics.PostKillBannerController
import dev.stapler.stelekit.platform.diagnostics.PostKillNotice
import dev.stapler.stelekit.platform.diagnostics.SeenExitRecordStore
import dev.stapler.stelekit.ui.components.PostKillBannerHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class PostKillBannerTest {
    @get:Rule
    val rule = createComposeRule()

    private class MemoryStore : SeenExitRecordStore {
        var seen: String? = null
        override fun lastSeenId() = seen
        override fun markSeen(id: String) { seen = id }
    }

    @Test
    fun banner_should_ShowOnceForRecordAndNotRepeat_When_ExitRecordPresent() {
        val store = MemoryStore()
        val first = PostKillBannerController(store).also { it.offer(PostKillNotice("42:9")) }
        var viewed = 0
        rule.setContent { PostKillBannerHost(first, onViewLog = { viewed++ }) }

        rule.onNodeWithText("The app closed unexpectedly while annotating.").assertExists()
        rule.onNodeWithText("View log").performClick()
        assertEquals(1, viewed)
        rule.onNodeWithText("The app closed unexpectedly while annotating.").assertDoesNotExist()

        // Second launch with the same record: the controller refuses to show it.
        val relaunch = PostKillBannerController(store).also { it.offer(PostKillNotice("42:9")) }
        assertNull(relaunch.notice.value)
    }

    @Test
    fun dismiss_should_HideTheBanner() {
        val controller = PostKillBannerController(MemoryStore()).also { it.offer(PostKillNotice("1:9")) }
        rule.setContent { PostKillBannerHost(controller, onViewLog = {}) }

        rule.onNodeWithText("Dismiss").performClick()

        rule.onNodeWithText("The app closed unexpectedly while annotating.").assertDoesNotExist()
    }
}
