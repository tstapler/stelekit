// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import arrow.core.right
import dev.stapler.stelekit.git.buildTestGitSyncService
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.screens.git.GitSetupScreen
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule

/**
 * Screenshot coverage for the "Add Graph via Git" wizard (Steps 1-5) — no prior coverage existed
 * (see PR #351 follow-up: reviewing this flow for UX gaps — autocomplete, remembered repos,
 * GitHub-account integration — needs a rendered reference for each step, not just the source).
 *
 * Redirects `user.home` per-test (JVM `PlatformSettings`/`GitCredentialConnectionStore` reads
 * `~/.stelekit/prefs.properties`) so a real dev machine's saved connections never leak into the
 * render, mirroring [dev.stapler.stelekit.ui.screens.git.GitSetupScreenCredentialPersistenceTest].
 */
class GitSetupScreenScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var originalUserHome: String
    private lateinit var tempHome: java.io.File

    @BeforeTest
    fun setUp() {
        originalUserHome = System.getProperty("user.home")
        tempHome = createTempDirectory("stelekit_git_setup_screenshot_").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalUserHome)
        tempHome.deleteRecursively()
    }

    private fun renderStep(step: Int) {
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                GitSetupScreen(
                    graphId = "screenshot-graph",
                    gitRepository = StubGitRepository(),
                    gitConfigRepository = StubConfigRepository(sampleConfig.right()),
                    gitSyncService = buildTestGitSyncService(),
                    fileSystem = FakeFileSystem(),
                    onDismiss = {},
                    initialStep = step,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/git_setup_step${step}.png")
    }

    @Test
    fun git_setup_step1_clone_mode() = renderStep(1)

    @Test
    fun git_setup_step2_repo_path() = renderStep(2)

    @Test
    fun git_setup_step3_auth() = renderStep(3)

    @Test
    fun git_setup_step4_branch() = renderStep(4)

    @Test
    fun git_setup_step5_test_and_save() = renderStep(5)
}
