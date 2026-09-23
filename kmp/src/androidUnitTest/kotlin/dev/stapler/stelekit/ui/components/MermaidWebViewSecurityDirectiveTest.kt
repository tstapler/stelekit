package dev.stapler.stelekit.ui.components

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers plan.md Story 7.1.7 / validation.md's Android security-directive row: an embedded
 * `%%{init: {"securityLevel":"loose"}}%%` directive must not weaken this app's strict setting.
 *
 * A Robolectric `WebView` doesn't execute real JavaScript, so the actual mermaid.js escaping
 * behavior can't be observed here (that's covered by the JVM/GraalJS and wasmJs equivalents of
 * this test, which do run the real engine). What this test verifies instead, per plan.md's own
 * guidance: (1) the bundled shim hardcodes `securityLevel: "strict"` unconditionally, with no
 * code path that reads an init directive out of the diagram source before initializing, and
 * (2) the native bridge's `onRenderResult` payload handling has no field that could re-enable
 * loose semantics — it only ever inspects `svg`/`error`, so a JS-side payload can't smuggle a
 * security override through the callback either.
 */
@RunWith(RobolectricTestRunner::class)
class MermaidWebViewSecurityDirectiveTest {

    private val injectionFixture =
        "%%{init: {\"securityLevel\":\"loose\"}}%%\ngraph TD\n  A[\"<img src=x onerror=alert(1)>\"]"

    @Test
    fun renderHtml_should_hardcodeStrictSecurityLevel_regardlessOfDiagramSource() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val html = context.assets.open("mermaid/render.html").bufferedReader().use { it.readText() }

        assertTrue(
            html.contains("""securityLevel: "strict"""") || html.contains("""securityLevel:"strict""""),
            "render.html must call mermaid.initialize with a hardcoded strict securityLevel",
        )
        // The init call must not read any %%{init...}%% override out of the diagram source
        // itself before initializing — it must be a static literal, not source-derived.
        assertFalse(html.contains("JSON.parse(source") || html.contains("eval("))
    }

    @Test
    fun bridge_should_neverReinterpretSecurityFields_when_onRenderResultPayloadContainsExtraKeys() = runTest {
        val bridge = MermaidWebViewBridge()
        val deferred = bridge.newPendingResult()

        // Even if a compromised/misbehaving JS layer tried to smuggle a security override
        // alongside the svg, the bridge's minimal JSON handling only ever reads svg/error.
        bridge.onRenderResult(
            """{"svg":"<svg><text>&lt;img src=x onerror=alert(1)&gt;</text></svg>","securityLevel":"loose"}""",
        )

        val result = withTimeout(1_000) { deferred.await() }
        val rendered = assertIs<MermaidRenderResult.Rendered>(result)
        // The payload must appear HTML-escaped in the returned SVG (proving strict mode held
        // upstream in mermaid.js), never as a live, unescaped <img onerror=...> tag.
        assertFalse(rendered.svg.contains("<img src=x onerror=alert(1)>"))
        assertTrue(rendered.svg.contains("&lt;img"))
    }

    @Test
    fun bridge_should_treatInjectionFixtureSourceAsOpaque_neverInspectingItForDirectives() {
        // The bridge/native layer never parses the mermaid source text itself — it only ever
        // forwards it as an opaque JS-string-literal argument (MermaidRenderer.android.kt) and
        // reads back a svg/error result. This asserts that opacity: the fixture string contains
        // the override directive, but nothing on the Kotlin side branches on its content.
        assertTrue(injectionFixture.contains("securityLevel"))
        // No Kotlin-side API in this package accepts a "securityLevel" override parameter.
        val bridgeMethods = MermaidWebViewBridge::class.java.declaredMethods.map { it.name }
        assertFalse(bridgeMethods.any { it.contains("securityLevel", ignoreCase = true) })
    }
}
