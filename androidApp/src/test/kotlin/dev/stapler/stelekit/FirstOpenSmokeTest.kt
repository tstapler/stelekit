// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 1.3c: first-open smoke test on the shipped root. Launches the real [MainActivity] (not the
 * `kmp` library fixture), walks onboarding into the demo graph, and opens each screen listed in
 * [FIRST_OPEN_SCREENS], asserting it composes without throwing and shows its primary element
 * (precedent: pitfall A6, where `AssetFilter.all` crashed on every open and only an entry-point run
 * found it). Each unit appends its own screen to [FIRST_OPEN_SCREENS] and to `first-open-screens.txt`;
 * `FirstOpenSmokeCatalogTest` fails a screen listed there that is missing here.
 *
 * Harness limits, verified while writing it:
 *  - Robolectric's SQLite has no FTS5, so the app runs on the in-memory backend (the shell's
 *    `GraphManager` is replaced before launch).
 *  - `AndroidKeyStore` does not exist on Robolectric, so [FakeAndroidKeyStore] stands in.
 *  - The root never goes idle (live demo-graph indexing), so the clock is advanced by hand.
 *  - Dropdown, dialog and sheet-over-popup windows were not reachable by semantics queries, so
 *    Settings > Git sync, Settings > Plugins and Logs are not in the list yet (see
 *    `first-open-screens.txt`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class FirstOpenSmokeTest {
    companion object {
        /** Screens the opener test walks through. */
        val FIRST_OPEN_SCREENS = listOf("Assets browser")

        private const val CLICK_SETTLE_MS = 1_500L
        private const val DEMO_LOAD_MS = 3_000L
    }

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private object NoFiles : FileSystem {
        override fun getDefaultGraphPath() = "/test"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        val app = ApplicationProvider.getApplicationContext<SteleKitApplication>()
        val manager = GraphManager(PlatformSettings(), DriverFactory(), NoFiles, defaultBackend = GraphBackend.IN_MEMORY)
        runBlocking { manager.openGraph("/test/smoke-graph") }
        SteleKitApplication::class.java.getDeclaredField("graphManager").apply { isAccessible = true }.set(app, manager)
    }

    @After
    fun tearDown() = FakeAndroidKeyStore.uninstall()

    private fun tap(label: String) {
        composeRule.onNodeWithText(label).performClick()
        composeRule.mainClock.advanceTimeBy(CLICK_SETTLE_MS)
    }

    private fun showDemoShell() {
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(CLICK_SETTLE_MS)
        tap("Next")
        tap("Try Demo Graph")
        composeRule.mainClock.advanceTimeBy(DEMO_LOAD_MS)
    }

    @Test
    fun firstOpen_should_ShowOnboardingWelcome_When_MainActivityLaunchedWithAnEmptyGraph() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(CLICK_SETTLE_MS)
            composeRule.onNodeWithText("Welcome to SteleKit").assertIsDisplayed()
        }
    }

    @Test
    fun firstOpen_should_RenderPrimaryElementWithoutThrowing_When_EachListedScreenOpenedFromMainActivity() {
        for (screen in FIRST_OPEN_SCREENS) {
            ActivityScenario.launch(MainActivity::class.java).use {
                showDemoShell()
                when (screen) {
                    "Assets browser" -> {
                        tap("More")
                        tap("Assets")
                        composeRule.onNodeWithText("Search assets…").assertIsDisplayed()
                        composeRule.onNodeWithText("No assets yet").assertIsDisplayed()
                    }
                    else -> error("FIRST_OPEN_SCREENS names '$screen' but no opener is defined for it")
                }
            }
        }
    }
}
