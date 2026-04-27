// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.ui.onboarding.Onboarding
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertTrue

/**
 * End-to-end smoke tests covering startup initialization and the onboarding UI flow.
 *
 * Onboarding is tested via the [Onboarding] composable directly rather than through
 * [dev.stapler.stelekit.ui.StelekitApp], because StelekitApp shows a CircularProgressIndicator
 * while repositories initialize — that infinite animation keeps Compose permanently "busy"
 * and makes every waitForIdle() time out. Direct composable testing sidesteps this completely
 * and produces faster, more reliable assertions.
 *
 * Run locally: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var graphDir: File

    @Before
    fun setUp() {
        SteleKitContext.init(context)
        DriverFactory.setContext(context)
        PlatformSettings().putBoolean("onboardingCompleted", false)
        graphDir = File(context.cacheDir, "smoke-graph-${System.nanoTime()}").also {
            it.mkdirs()
            File(it, "pages").mkdirs()
            File(it, "journals").mkdirs()
            File(it, "pages/Hello.md").writeText("- Hello from smoke test\n  - Child block\n")
        }
    }

    @After
    fun tearDown() {
        graphDir.deleteRecursively()
    }

    // ─── Startup initialization ──────────────────────────────────────────────────

    /**
     * Instantiates the core Android components (PlatformSettings, DriverFactory, GraphManager).
     * This catches crashes in the initialization path that runs before the first Compose frame,
     * which is where a v0.13.0-style crash would appear.
     */
    @Test
    fun coreInitializationDoesNotCrash() {
        val settings = PlatformSettings()
        val driverFactory = DriverFactory()
        val fs = localFileSystem(graphDir.absolutePath)
        val graphManager = GraphManager(settings, driverFactory, fs)
        assertTrue(graphManager != null, "GraphManager must initialize without throwing")
    }

    // ─── Onboarding flow ─────────────────────────────────────────────────────────

    @Test
    fun onboardingWelcomeStepShownFirst() {
        composeTestRule.setContent {
            Onboarding(
                fileSystem = localFileSystem(graphDir.absolutePath),
                onComplete = {},
                onGraphSelected = {},
            )
        }
        composeTestRule.onNodeWithText("Welcome to SteleKit").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun nextButtonAdvancesToGraphSelectionStep() {
        composeTestRule.setContent {
            Onboarding(
                fileSystem = localFileSystem(graphDir.absolutePath),
                onComplete = {},
                onGraphSelected = {},
            )
        }
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Select Your Graph").assertIsDisplayed()
    }

    @Test
    fun backButtonReturnsToWelcomeStep() {
        composeTestRule.setContent {
            Onboarding(
                fileSystem = localFileSystem(graphDir.absolutePath),
                onComplete = {},
                onGraphSelected = {},
            )
        }
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Select Your Graph").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.onNodeWithText("Welcome to SteleKit").assertIsDisplayed()
    }

    @Test
    fun completingOnboardingCallsOnComplete() {
        var onboardingFinished = false
        composeTestRule.setContent {
            Onboarding(
                fileSystem = localFileSystem(graphDir.absolutePath),
                onComplete = { onboardingFinished = true },
                onGraphSelected = {},
            )
        }
        // Welcome → Graph Selection → Keymap Intro → finish
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Get Started").assertIsDisplayed()
        composeTestRule.onNodeWithText("Get Started").performClick()
        assertTrue(onboardingFinished, "onComplete must be invoked when the user taps Get Started")
    }

    // ─── Test FileSystem ──────────────────────────────────────────────────────────

    private fun localFileSystem(defaultPath: String): FileSystem = object : FileSystem {
        override fun getDefaultGraphPath() = defaultPath
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = runCatching { File(path).readText() }.getOrNull()
        override fun writeFile(path: String, content: String) =
            runCatching { File(path).also { it.parentFile?.mkdirs() }.writeText(content); true }
                .getOrDefault(false)
        override fun listFiles(path: String) =
            File(path).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
        override fun listDirectories(path: String) =
            File(path).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        override fun fileExists(path: String) = File(path).isFile
        override fun directoryExists(path: String) = File(path).isDirectory
        override fun createDirectory(path: String) = File(path).mkdirs()
        override fun deleteFile(path: String) = File(path).delete()
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String) = File(path).takeIf { it.exists() }?.lastModified()
    }
}
