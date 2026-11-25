// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.components.sheet.ResolveChip
import dev.stapler.stelekit.ui.components.sheet.TableConflictBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** The three choices and the Resolve chip fire their own callback and nothing else (S14). Gradle-only (Compose host). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-xhdpi")
class TableConflictBannerTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun choices_should_EachCallTheirOwnCallback_AndTheChipReopens() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                TableConflictBanner("B3", { calls += "mine" }, { calls += "theirs" }, { calls += "markdown" })
                ResolveChip({ calls += "chip" })
            }
        }
        rule.onNodeWithTag("conflictKeepMine").performClick()
        rule.onNodeWithTag("conflictTakeTheirs").performClick()
        rule.onNodeWithTag("conflictEditMarkdown").performClick()
        rule.onNodeWithTag("resolveChip").performClick()
        assertEquals(listOf("mine", "theirs", "markdown", "chip"), calls)
    }
}
