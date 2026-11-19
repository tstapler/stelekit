// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.sketch.v2

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import dev.stapler.stelekit.ui.rememberSketchTextMetricsProvider
import dev.stapler.stelekit.wiring.FakePlatformPorts
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
import kotlin.test.assertTrue

/**
 * Task 2.1.2c: the root's `rememberSketchTextMetricsProvider()` (the one `ComposeTextMetricsProvider` construction)
 * measures real text on Android once the wired app composes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TextMetricsAndroidEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File = createTempDirectory("text_metrics_android_").toFile()
    private var fixture: WiredAppFixture? = null

    @After
    fun tearDown() {
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    @Test
    fun androidRoot_should_MeasureTextWithTheBundledFonts_When_TheProviderComposes() {
        val wired = runBlocking { WiredAppFixture.android(context, dataDir, ports = FakePlatformPorts()) }
        fixture = wired
        var provider: TextMetricsProvider? = null
        composeRule.setContent {
            rememberGraphContentVaultSetup(wired.graphManager, wired.ports.fileSystem, null)
            provider = rememberSketchTextMetricsProvider()
        }
        composeRule.waitUntil(60_000) { provider?.fontStatus.let { it != null && it != FontStatus.Loading } }

        ObservableEffect.assert("text-metrics-measured-android") {
            val p = provider!!
            val text = "Authentication Service"
            val hand = p.advance(text, TextStyle(fontFamily = 1, fontSize = 20.0))
            val code = p.advance(text, TextStyle(fontFamily = 3, fontSize = 20.0))
            assertTrue(hand > 0.0 && code > 0.0, "measured widths are positive: $hand, $code")
            assertTrue(p.lineHeight(TextStyle(fontFamily = 2, fontSize = 20.0)) > 0.0)
            // Robolectric measures every glyph at a fixed advance, so family differences are asserted on Desktop only.
        }
    }
}
