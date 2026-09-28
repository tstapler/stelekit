package dev.stapler.stelekit.ui.components

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers validation.md REQ-11: a cache hit reloads the cached SVG directly (never re-invoking
 * `mermaid.render()`), and the hosted WebView never captures the parent `LazyColumn`'s scroll.
 */
@RunWith(RobolectricTestRunner::class)
class MermaidWebViewHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mermaidWebViewHost_should_loadDataDirectly_when_cacheHitExists() {
        val cachedSvg = "<svg><circle cx=\"5\" cy=\"5\" r=\"5\"/></svg>"
        composeRule.setContent {
            MermaidWebViewHost(result = MermaidRenderResult.Rendered(cachedSvg))
        }
        composeRule.waitForIdle()

        val webView = findWebView(composeRule.activity.window.decorView)
        assertNotNull(webView, "expected a WebView to be composed")

        val shadow = shadowOf(webView)
        val loaded = shadow.lastLoadDataWithBaseURL
        assertNotNull(loaded, "expected loadDataWithBaseURL to have been called directly")
        assertTrue(loaded.data.contains(cachedSvg), "expected the cached SVG to be loaded verbatim")
        assertEquals("text/html", loaded.mimeType)
    }

    @Test
    fun mermaidWebViewHost_should_disableInternalScroll_when_hostedInLazyColumn() {
        composeRule.setContent {
            LazyColumn {
                items(1) {
                    MermaidWebViewHost(result = MermaidRenderResult.Rendered("<svg></svg>"))
                }
            }
        }
        composeRule.waitForIdle()

        val webView = findWebView(composeRule.activity.window.decorView)
        assertNotNull(webView, "expected a WebView to be composed inside the LazyColumn")

        assertFalse(webView.isVerticalScrollBarEnabled, "internal vertical scroll must be disabled")
        assertFalse(webView.isHorizontalScrollBarEnabled, "internal horizontal scroll must be disabled")
        assertFalse(webView.isScrollContainer, "WebView must not register as a scroll container")
    }

    private fun findWebView(root: android.view.View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findWebView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
