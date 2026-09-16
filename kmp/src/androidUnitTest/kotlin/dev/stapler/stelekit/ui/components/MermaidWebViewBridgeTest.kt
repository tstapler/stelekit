package dev.stapler.stelekit.ui.components

import android.webkit.JavascriptInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers validation.md REQ-12: the bridge's minimal-JS-surface security invariant, and that a
 * malformed JS-to-native payload (untrusted diagram content, indirectly) never crashes it.
 */
@RunWith(RobolectricTestRunner::class)
class MermaidWebViewBridgeTest {

    @Test
    fun bridge_should_exposeExactlyOneJavascriptInterfaceMethod_when_classInspected() {
        val annotated = MermaidWebViewBridge::class.java.declaredMethods
            .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }

        assertEquals(1, annotated.size, "exactly one @JavascriptInterface method must be exposed")
        assertEquals("onRenderResult", annotated.single().name)
    }

    @Test
    fun bridge_should_completeDeferredWithFailed_when_onRenderResultReceivesMalformedJson() = runTest {
        val bridge = MermaidWebViewBridge()
        val deferred = bridge.newPendingResult()

        bridge.onRenderResult("{not valid json at all")

        // withTimeout in runTest uses virtual time (expires instantly); the bridge completes the
        // deferred on a real Dispatchers.Default coroutine, so switch to a real-clock timeout.
        val result = withContext(Dispatchers.Default) { withTimeout(1_000) { deferred.await() } }
        assertIs<MermaidRenderResult.Failed>(result)
    }

    @Test
    fun bridge_should_completeDeferredWithFailed_when_onRenderResultReceivesNeitherSvgNorError() = runTest {
        val bridge = MermaidWebViewBridge()
        val deferred = bridge.newPendingResult()

        bridge.onRenderResult("""{"unexpected":"field"}""")

        val result = withContext(Dispatchers.Default) { withTimeout(1_000) { deferred.await() } }
        assertIs<MermaidRenderResult.Failed>(result)
    }

    @Test
    fun bridge_should_completeDeferredWithRendered_when_onRenderResultReceivesValidSvgPayload() = runTest {
        val bridge = MermaidWebViewBridge()
        val deferred = bridge.newPendingResult()

        bridge.onRenderResult("""{"svg":"<svg><circle/></svg>"}""")

        val result = withContext(Dispatchers.Default) { withTimeout(1_000) { deferred.await() } }
        val rendered = assertIs<MermaidRenderResult.Rendered>(result)
        assertEquals("<svg><circle/></svg>", rendered.svg)
    }
}
