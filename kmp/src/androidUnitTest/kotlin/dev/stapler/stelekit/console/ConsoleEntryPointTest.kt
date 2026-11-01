// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.ui.GraphContentConsoleStack
import dev.stapler.stelekit.ui.rememberGraphContentConsoleSetup
import dev.stapler.stelekit.wiring.ObservableEffect
import dev.stapler.stelekit.wiring.WiredAppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Catalog entry `ConsoleCommand  ConsoleEntryPointTest  effect:console-help`: the real
 * `rememberGraphContentConsoleSetup` root registers the built-in verbs, so `help` through the wired
 * session prints them. Limit (U1 finding): Robolectric cannot reach Dialog or DropdownMenu windows,
 * so this drives the session the Settings and Logs-overflow entries open rather than clicking them;
 * those entries are covered by `SettingsDeveloperCategoryTest` where a Dialog is queryable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ConsoleEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var fixture: WiredAppFixture? = null
    private val dataDir: File = createTempDirectory("console_entry_point_").toFile()

    @After
    fun tearDown() {
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    @Test
    fun consoleHelp_should_ListBuiltInVerbs_When_DeveloperModeIsOnInTheWiredStack() {
        val wired = runBlocking { WiredAppFixture.android(ApplicationProvider.getApplicationContext<Context>(), dataDir) }
        fixture = wired
        wired.ports.settings.putBoolean(DeveloperMode.KEY, true)

        var stack: GraphContentConsoleStack? = null
        composeRule.setContent { stack = rememberGraphContentConsoleSetup(wired.deps, null) }
        composeRule.waitForIdle()

        val session = assertNotNull(stack, "the console setup must compose").session
        val result = runBlocking { session.run("help") }

        ObservableEffect.assert("console-help") {
            assertTrue(result.isRight(), "help must succeed: $result")
            assertEquals(listOf("backups", "diag", "graph", "help", "logs", "schema", "sql", "sql!", "tables"), session.scrollback.lines.value.drop(1).map { it.text.substringBefore(' ') })
            // Writes are off in any build that did not mark itself internal; the verbs are listed with the reason.
            listOf("sql!", "graph").forEach { verb ->
                assertTrue(
                    session.scrollback.lines.value.any { it.text.startsWith(verb) && it.text.contains("disabled: writes not enabled in this build") },
                    "$verb must be listed disabled: ${session.scrollback.lines.value.map { it.text }}",
                )
            }
        }
    }

    @Test
    fun consoleRun_should_BeDenied_When_DeveloperModeIsOffInTheWiredStack() {
        val wired = runBlocking { WiredAppFixture.android(ApplicationProvider.getApplicationContext<Context>(), dataDir) }
        fixture = wired

        var stack: GraphContentConsoleStack? = null
        composeRule.setContent { stack = rememberGraphContentConsoleSetup(wired.deps, null) }
        composeRule.waitForIdle()

        val result = runBlocking { assertNotNull(stack).session.run("diag") }
        assertEquals(ConsoleError.Denied("developer mode off"), result.leftOrNull())
    }

    /**
     * Limit: the Robolectric fixture has no file database, so `sql` is listed disabled here and the real
     * `select 1` round trip is `SqlConsoleReaderAndroidTest` on the emulator. What this pins is the audit
     * contract of the wired stack: one line per run naming the verb, never the arguments.
     */
    @Test
    fun consoleRun_should_AuditOnlyTheVerb_When_RunThroughTheWiredStack() {
        val wired = runBlocking { WiredAppFixture.android(ApplicationProvider.getApplicationContext<Context>(), dataDir) }
        fixture = wired
        wired.ports.settings.putBoolean(DeveloperMode.KEY, true)

        var stack: GraphContentConsoleStack? = null
        composeRule.setContent { stack = rememberGraphContentConsoleSetup(wired.deps, null) }
        composeRule.waitForIdle()

        val session = assertNotNull(stack).session
        val secret = "ghp_SECRETSECRETSECRETSECRET1234567890"
        assertTrue(runBlocking { session.run("help") }.isRight())
        assertTrue(runBlocking { session.run("backups $secret") }.isLeft(), "a rejected line is a Left")
        val sql = runBlocking { session.run("sql \"select 1\"") }
        assertEquals("disabled: no read-only connection on this platform", sql.leftOrNull()?.message)

        val audit = LogManager.logs.value.filter { it.tag == "ConsoleAudit" }.map { it.message }
        assertTrue(audit.contains("console run verb=help"), audit.toString())
        assertTrue(audit.none { it.contains(secret) || it.contains("select") }, "the audit line carries the verb only: $audit")
        assertTrue(!audit.contains("console run verb=sql"), "a disabled verb never runs, so it is not audited as a run: $audit")
    }
}
