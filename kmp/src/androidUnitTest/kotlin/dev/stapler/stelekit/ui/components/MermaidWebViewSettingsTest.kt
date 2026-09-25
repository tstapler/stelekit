package dev.stapler.stelekit.ui.components

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the PR review's "no test reads back Android WebSettings" finding: both
 * WebViews this feature creates must actually carry the hardened settings their configure
 * functions claim to set, not just look correct on inspection.
 */
@RunWith(RobolectricTestRunner::class)
class MermaidWebViewSettingsTest {

    private fun newWebView() = WebView(ApplicationProvider.getApplicationContext())

    @Test
    fun configureMermaidRenderWebView_should_enableJsButLockDownFileAccess_whenApplied() {
        val webView = newWebView()

        configureMermaidRenderWebView(webView)

        assertTrue(webView.settings.javaScriptEnabled, "JS must be enabled to run the bundled mermaid.js")
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowFileAccessFromFileURLs)
        assertFalse(webView.settings.allowUniversalAccessFromFileURLs)
        assertFalse(webView.settings.allowContentAccess)
    }

    @Test
    fun configureMermaidWebViewForHosting_should_disableJsAndFileAccess_whenApplied() {
        val webView = newWebView()

        configureMermaidWebViewForHosting(webView)

        assertFalse(webView.settings.javaScriptEnabled, "hosted static SVG markup never needs script execution")
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
    }
}
