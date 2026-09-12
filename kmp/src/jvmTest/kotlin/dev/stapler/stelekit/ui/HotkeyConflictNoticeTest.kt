// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.stapler.stelekit.capture.HotkeyRegistrationFailure
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Coverage for Story 1.4.3 — [HotkeyConflictNotice]'s cause-specific messaging. */
class HotkeyConflictNoticeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun currentMessageText(): String {
        val node = composeTestRule.onNodeWithTag("hotkeyConflictNoticeMessage").fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text.orEmpty()
    }

    @Test
    fun hotkeyConflictNotice_should_ShowCauseSpecificMessage_When_RegistrationFailsInUseVsUnsupportedSession() {
        var failure by mutableStateOf(HotkeyRegistrationFailure.AlreadyInUse)

        composeTestRule.setContent {
            MaterialTheme {
                HotkeyConflictNotice(
                    failure = failure,
                    onDismiss = {},
                    hotkeyCombo = "Ctrl+Shift+Space",
                )
            }
        }

        val inUseMessage = currentMessageText()
        composeTestRule.runOnIdle { failure = HotkeyRegistrationFailure.UnsupportedSession }
        val unsupportedMessage = currentMessageText()

        assertTrue(inUseMessage.contains("already in use", ignoreCase = true), "expected an 'already in use' message, got: $inUseMessage")
        assertTrue(
            unsupportedMessage.contains("doesn't support", ignoreCase = true) ||
                unsupportedMessage.contains("does not support", ignoreCase = true),
            "expected an 'unsupported session' message, got: $unsupportedMessage",
        )
        assertNotEquals(inUseMessage, unsupportedMessage, "the two causes must render different, plain-language messages")
    }
}
