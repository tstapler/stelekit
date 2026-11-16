// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.sketch.v2

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.Density
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import dev.stapler.stelekit.ui.rememberSketchTextMetricsProvider
import dev.stapler.stelekit.wiring.DesktopWiredAppFixture
import dev.stapler.stelekit.wiring.HeadlessComposition
import dev.stapler.stelekit.wiring.ObservableEffect
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Task 2.1.2c: the root's `rememberSketchTextMetricsProvider()` (the one `ComposeTextMetricsProvider` construction)
 * measures real text on Desktop once the wired app composes.
 */
class TextMetricsDesktopEntryPointTest {
    private val dataDir: File = createTempDirectory("text_metrics_desktop_").toFile()
    private var fixture: DesktopWiredAppFixture? = null
    private var headless: HeadlessComposition? = null

    @AfterTest
    fun tearDown() {
        headless?.close()
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    @Test
    fun desktopRoot_should_MeasureTextWithTheBundledFonts_When_TheProviderComposes() {
        val wired = runBlocking { DesktopWiredAppFixture.desktop(dataDir, graphPath = "/test/text-metrics-${System.nanoTime()}") }
        fixture = wired
        var provider: TextMetricsProvider? = null
        headless = HeadlessComposition().also { composition ->
            composition.setContent {
                // The headless host has no window, so it supplies the density and font resolver a real window would.
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 1f),
                    LocalFontFamilyResolver provides createFontFamilyResolver(),
                ) {
                    rememberGraphContentVaultSetup(wired.graphManager, wired.ports.fileSystem, null)
                    provider = rememberSketchTextMetricsProvider()
                }
            }
        }
        val deadline = System.nanoTime() + FONT_LOAD_TIMEOUT_NANOS
        while (provider?.fontStatus.let { it == null || it == FontStatus.Loading }) {
            if (System.nanoTime() > deadline) fail("bundled fonts neither loaded nor failed")
            headless!!.advance()
            Thread.yield()
        }

        ObservableEffect.assert("text-metrics-measured-desktop") {
            val p = provider!!
            val text = "Authentication Service"
            val hand = p.advance(text, TextStyle(fontFamily = 1, fontSize = 20.0))
            val code = p.advance(text, TextStyle(fontFamily = 3, fontSize = 20.0))
            assertTrue(hand > 0.0 && code > 0.0, "measured widths are positive: $hand, $code")
            assertTrue(p.lineHeight(TextStyle(fontFamily = 2, fontSize = 20.0)) > 0.0)
            if (p.fontStatus == FontStatus.Loaded) assertNotEquals(hand, code, "bundled families measure differently")
        }
    }

    private companion object {
        const val FONT_LOAD_TIMEOUT_NANOS = 60_000_000_000L
    }
}
