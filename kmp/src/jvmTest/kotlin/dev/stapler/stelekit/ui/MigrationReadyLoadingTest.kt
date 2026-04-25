package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test

/**
 * Tests for the migrationReady loading pattern used in App.kt's GraphContent.
 *
 * The bug: LaunchedEffect(activeGraphId) sets migrationReady=false, then awaits a migration.
 * If the effect was cancelled (e.g. graph switch) without try/finally, migrationReady would
 * stay false forever — causing an infinite loading spinner.
 *
 * The fix: wrap the await in try/finally so migrationReady always resets to true on completion
 * OR cancellation.
 *
 * These tests verify the pattern in isolation, without depending on GraphManager or the database.
 */
class MigrationReadyLoadingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ─── Normal path ─────────────────────────────────────────────────────────

    @Test
    fun content_is_shown_when_migration_ready_is_true() {
        composeTestRule.setContent {
            MaterialTheme {
                // Simulate the GraphContent gating pattern
                val migrationReady = true
                if (migrationReady) Text("App Content") else Text("Loading...")
            }
        }

        composeTestRule.onNodeWithText("App Content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Loading...").assertDoesNotExist()
    }

    @Test
    fun loading_is_shown_when_migration_ready_is_false() {
        composeTestRule.setContent {
            MaterialTheme {
                val migrationReady = false
                if (migrationReady) Text("App Content") else Text("Loading...")
            }
        }

        composeTestRule.onNodeWithText("Loading...").assertIsDisplayed()
        composeTestRule.onNodeWithText("App Content").assertDoesNotExist()
    }

    // ─── try/finally pattern ──────────────────────────────────────────────────

    @Test
    fun migrationReady_becomes_true_after_fast_migration() {
        // Tests the happy path: migration completes immediately → content shown
        composeTestRule.setContent {
            MaterialTheme {
                var migrationReady by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    migrationReady = false
                    try {
                        // Instant migration — no suspension needed
                    } finally {
                        migrationReady = true
                    }
                }
                if (migrationReady) Text("Content Ready") else Text("Migrating...")
            }
        }

        // After the LaunchedEffect runs (synchronously in Compose test), content should appear
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            composeTestRule.onAllNodesWithText("Content Ready").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Content Ready").assertIsDisplayed()
    }

    @Test
    fun migrationReady_resets_to_true_after_key_change() {
        // Tests the graph-switch path:
        //   1. Effect starts with key=0, sets migrationReady=false, delays
        //   2. Key changes to key=1 → old effect cancelled → finally: migrationReady=true
        //   3. New effect starts, completes → migrationReady=true → content shows key=1
        var graphKey by mutableStateOf(0)

        composeTestRule.setContent {
            MaterialTheme {
                var migrationReady by remember { mutableStateOf(false) }
                LaunchedEffect(graphKey) {
                    migrationReady = false
                    try {
                        if (graphKey == 0) {
                            delay(10_000) // Long delay — will be cancelled by key change
                        }
                        // key=1: no delay, completes immediately
                    } finally {
                        migrationReady = true
                    }
                }
                if (migrationReady) Text("Content-$graphKey") else Text("Migrating")
            }
        }

        // Initial state: migrationReady=false (key=0 LaunchedEffect is still delaying)
        composeTestRule.onNodeWithText("Migrating").assertIsDisplayed()

        // Switch graph (key change) → cancels key=0 effect → finally runs → migrationReady=true
        // Then key=1 effect starts, completes immediately → migrationReady=true stays
        composeTestRule.runOnIdle { graphKey = 1 }

        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            composeTestRule.onAllNodesWithText("Content-1").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Content-1").assertIsDisplayed()
    }

    @Test
    fun migrationReady_without_finally_would_stay_false_after_cancellation() {
        // Demonstrates the bug pattern (WITHOUT try/finally) by showing migrationReady
        // stays false during cancellation. This is the inverse of the fix test above.
        // We simulate the broken pattern to show what the bug looked like.
        var graphKey by mutableStateOf(0)
        var observedMigrationReady = true // Track what we see

        composeTestRule.setContent {
            MaterialTheme {
                var migrationReady by remember { mutableStateOf(false) }
                LaunchedEffect(graphKey) {
                    migrationReady = false
                    // Bug: NO try/finally — if cancelled, migrationReady stays false
                    delay(10_000) // Will be cancelled
                    migrationReady = true // Never reached if cancelled
                }
                observedMigrationReady = migrationReady
                Text(if (migrationReady) "Ready" else "Stuck")
            }
        }

        // Key=0 effect is running (long delay), migrationReady=false
        composeTestRule.onNodeWithText("Stuck").assertIsDisplayed()

        // Cancel by key change — without finally, migrationReady stays false
        // until the new LaunchedEffect(key=1) runs its own delay and also gets stuck
        composeTestRule.runOnIdle { graphKey = 1 }

        // After key=1 effect starts, it immediately sets migrationReady=false again
        // and starts a new 10s delay. So it's stuck at "Stuck" indefinitely.
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Stuck").assertIsDisplayed()
    }
}
