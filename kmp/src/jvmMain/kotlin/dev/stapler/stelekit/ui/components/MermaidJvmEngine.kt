package dev.stapler.stelekit.ui.components

import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.util.XMLResourceDescriptor
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess

/** Thrown by [MermaidJvmEngine] on any GraalJS/render failure; always caught by [MermaidJvmEngine.render]. */
class MermaidEngineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Owns a lazily-initialized GraalJS [Context] hosting a bundled `mermaid.js` (11.4.1) plus a
 * headless-DOM shim (`resources/mermaid/browser-shim.js`, vendored from `aresstack/mermaid-java`,
 * MIT — see that file's header and ADR-001's Open Items) that supplies the
 * `SVGTextElement.getBBox()` / DOM surface mermaid.js's layout pass needs.
 *
 * Never a `rememberCoroutineScope()`-derived object — this class owns its GraalJS `Context` for
 * its own lifetime. It has **no internal concurrency guard**: [MermaidEngineActor] is the only
 * permitted caller, and it serializes all access onto one dedicated thread. Calling [render] from
 * more than one thread concurrently throws `IllegalStateException` (GraalJS `Context` rejects
 * concurrent multi-thread access by default) — see [MermaidEngineActor]'s docs for why that guard
 * exists.
 */
open class MermaidJvmEngine {
    private var context: Context? = null
    private val diagramCounter = AtomicInteger(0)

    /**
     * `browser-shim.js` predates mermaid.js's `createCssStyles`/`createUserStyles` code path,
     * which constructs a real `CSSStyleSheet` (`insertRule`/`replaceSync`) to inject the
     * per-diagram theme CSS — absent from the shim, this throws `ReferenceError: CSSStyleSheet
     * is not defined` before any rendering happens. A minimal stand-in (store rules, no real CSS
     * parsing) is enough since we only need the SVG's `<style>` text, never live style
     * application.
     */
    private val cssStyleSheetPolyfill = """
        function CSSStyleSheet() { this.cssRules = []; this.cssText = ''; }
        CSSStyleSheet.prototype.replaceSync = function(css) { this.cssText = css; };
        CSSStyleSheet.prototype.replace = function(css) { this.cssText = css; return Promise.resolve(this); };
        CSSStyleSheet.prototype.insertRule = function(rule, index) {
            this.cssRules.splice(index || this.cssRules.length, 0, { cssText: rule });
            return index || this.cssRules.length - 1;
        };
        CSSStyleSheet.prototype.deleteRule = function(index) { this.cssRules.splice(index, 1); };
        window.CSSStyleSheet = CSSStyleSheet;
        globalThis.CSSStyleSheet = CSSStyleSheet;
        if (!document.adoptedStyleSheets) { document.adoptedStyleSheets = []; }
    """.trimIndent()

    /** Renders [source] to an SVG string. Wrapped per this repo's native-load-failure rule (catch Throwable). */
    open fun render(source: String): String {
        return try {
            renderInternal(source)
        } catch (e: MermaidEngineException) {
            throw e
        } catch (e: Throwable) {
            throw MermaidEngineException(e.message ?: (e::class.simpleName ?: "unknown GraalJS failure"), e)
        }
    }

    private fun renderInternal(source: String): String {
        val ctx = context ?: buildContext().also { context = it }
        val id = "mmd-${diagramCounter.incrementAndGet()}"

        // Mermaid 11's render() returns a Promise<{svg}>. GraalJS drains pending microtasks at the
        // start of the *next* eval() call, so a no-op eval between the setup script and reading the
        // result is what lets the .then() callback fire before we read __svgResult.
        ctx.eval("js", renderSetupScript(id, source))
        ctx.eval("js", "void 0")
        val result = ctx.eval("js", "__svgResult || (__renderError ? ('ERROR:' + __renderError) : '')")
        return extractSvgOrThrow(result.asString().orEmpty())
    }

    private fun renderSetupScript(id: String, source: String): String {
        val escaped = source
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        return """
            var __svgResult = '';
            var __renderError = '';
            var __container = document.createElement('div');
            __container.id = '$id';
            document.body.appendChild(__container);
            try {
              var __result = window.mermaid.render('$id', '$escaped');
              if (__result && typeof __result.then === 'function') {
                __result.then(function(res) {
                  __svgResult = (res && res.svg) ? res.svg : (typeof res === 'string' ? res : '');
                })['catch'](function(err) {
                  __renderError = '' + err + (err && err.stack ? ('\n' + err.stack) : '');
                });
              } else if (__result && __result.svg) {
                __svgResult = __result.svg;
              } else if (typeof __result === 'string') {
                __svgResult = __result;
              }
            } catch (renderErr) {
              __renderError = '' + renderErr + (renderErr && renderErr.stack ? ('\n' + renderErr.stack) : '');
            }
        """.trimIndent()
    }

    private fun extractSvgOrThrow(output: String): String {
        if (output.startsWith("ERROR:")) {
            throw MermaidEngineException(output.removePrefix("ERROR:"))
        }
        if (output.isBlank()) {
            throw MermaidEngineException("mermaid.render() produced no SVG output")
        }
        return output
    }

    private fun buildContext(): Context {
        val ctx = Context.newBuilder("js")
            // The evaluated JS is built by interpolating user-authored diagram text (renderSetupScript)
            // into a script string — narrow this to only what mermaid.js/the shim actually need, not
            // allowAllAccess(true)'s unrestricted host reflection/IO/process access, so a future escaping
            // bug in that interpolation can't turn into unrestricted host access. HostAccess.EXPLICIT
            // matches JavaBridge's own @HostAccess.Export annotations, which allowAllAccess(true) made
            // meaningless (that annotation only restricts anything under EXPLICIT).
            .allowHostAccess(HostAccess.EXPLICIT)
            .allowIO(false)
            .allowCreateProcess(false)
            .allowHostClassLookup { false }
            // Stock JDK (not a full GraalVM install) — js-community runs interpreter-only,
            // which is fine for occasional diagram renders; this just silences the perf warning.
            .option("engine.WarnInterpreterOnly", "false")
            .build()
        ctx.getBindings("js").putMember("javaBridge", JavaBridge())
        ctx.eval("js", loadResource("/mermaid/browser-shim.js"))
        ctx.eval("js", cssStyleSheetPolyfill)
        evalBundleAndBindMermaid(ctx)

        // htmlLabels:false is load-bearing, not a style choice: the npm dist bundle's HTML-label
        // path (foreignObject + repeated DOM measurement round-trips through this headless shim)
        // was observed during the Story 3.1.1 spike to blow up a 3-node flowchart's output from a
        // sane ~32KB into a 16MB tree of duplicated <svg>/<marker>/<defs> elements — forcing plain
        // SVG <text> labels avoids that path entirely. See ADR-001's Open Items addendum.
        ctx.eval(
            "js",
            "window.mermaid.initialize({ startOnLoad: false, securityLevel: '$MERMAID_SECURITY_LEVEL', " +
                "htmlLabels: false, flowchart: { htmlLabels: false }, sequence: { htmlLabels: false } });",
        )
        return ctx
    }

    /**
     * Loads `mermaid.min.js` and binds its export onto the shim's `window.mermaid`.
     *
     * Two things make this more than a plain `ctx.eval()`:
     * 1. The npm dist bundle's own last line (`globalThis["mermaid"] = globalThis.<internal
     *    module var>["mermaid"].default;`) throws `TypeError: Cannot read property 'mermaid' of
     *    undefined` under GraalJS: once `browser-shim.js` reassigns the top-level `globalThis`
     *    binding to its own plain `window` object, later bare top-level `var` declarations in the
     *    *same* script (like the bundle's internal module variable) stop being visible as
     *    properties of that reassigned `globalThis` — confirmed by direct experiment, not just
     *    inferred. The bundle's own trailing line is stripped before eval and replaced with the
     *    Java-side binding below, which reads the real global scope directly.
     * 2. `Context.getBindings("js")` is that real global scope (bare `var`s *do* land here, per
     *    the same experiment), independent of whatever `browser-shim.js` did to `globalThis`.
     */
    private fun evalBundleAndBindMermaid(ctx: Context) {
        val bundle = loadResource("/mermaid/mermaid.min.js")
        val trailer = "globalThis[\"mermaid\"] = globalThis.__esbuild_esm_mermaid_nm[\"mermaid\"].default;"
        val trailerIndex = bundle.indexOf(trailer)
        val bundleBody = if (trailerIndex >= 0) bundle.substring(0, trailerIndex) else bundle
        ctx.eval("js", "var module = undefined; var exports = undefined; var define = undefined;\n$bundleBody")

        val globalBindings = ctx.getBindings("js")
        val moduleNamespace = globalBindings.getMember("__esbuild_esm_mermaid_nm")
            ?: throw MermaidEngineException("mermaid.min.js did not populate its expected module namespace")
        val mermaidExport = moduleNamespace.getMember("mermaid")?.getMember("default")
            ?: throw MermaidEngineException("mermaid.min.js's module namespace has no mermaid export")
        globalBindings.getMember("window").putMember("mermaid", mermaidExport)
    }

    private fun loadResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw MermaidEngineException("Missing bundled resource: $path")

    /**
     * Exposed to the GraalJS context as `javaBridge`. Headless GraalJS has no DOM/font API, so
     * `browser-shim.js`'s `getBBox()`/text-layout code calls back into these methods for real
     * measurements: [measureTextWidth]/[measureTextFull] via `java.awt.FontMetrics`, and
     * [computeSvgBBox] via Apache Batik's GVT tree for elements too complex for a font-metrics
     * heuristic (text with `<tspan>` children, transformed groups).
     */
    class JavaBridge {
        private val measureImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        private val measureGraphics: Graphics2D = measureImage.createGraphics()
        private val bboxCache = LinkedHashMap<String, String>()
        private var svgDocumentFactory: SAXSVGDocumentFactory? = runCatching {
            SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
        }.getOrNull()

        @HostAccess.Export
        @Suppress("unused") // called from JS
        fun log(message: String) {
            System.err.println("[mermaid-js] $message")
        }

        @HostAccess.Export
        @Suppress("unused") // called from JS
        fun measureTextWidth(text: String?, fontFamily: String?, fontSize: Double): Double {
            if (text.isNullOrEmpty()) return 0.0
            val font = resolveFont(fontFamily, fontSize)
            return measureGraphics.getFontMetrics(font).stringWidth(text).toDouble()
        }

        /** Returns "width,ascent,descent,height". */
        @HostAccess.Export
        @Suppress("unused") // called from JS
        fun measureTextFull(text: String?, fontFamily: String?, fontSize: Double): String {
            if (text.isNullOrEmpty()) return "0,0,0,0"
            val font = resolveFont(fontFamily, fontSize)
            val fm = measureGraphics.getFontMetrics(font)
            return "${fm.stringWidth(text)},${fm.ascent},${fm.descent},${fm.height}"
        }

        /** Returns "x,y,width,height" for [svgFragment], or "" if Batik can't compute it. */
        @HostAccess.Export
        @Suppress("unused") // called from JS
        fun computeSvgBBox(svgFragment: String?): String {
            if (svgFragment.isNullOrEmpty()) return ""
            val factory = svgDocumentFactory ?: return ""
            bboxCache[svgFragment]?.let { return it }
            return try {
                val wrapped = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4000\" height=\"4000\">" +
                    sanitizeForBatik(svgFragment) + "</svg>"
                val document = factory.createDocument("http://localhost/bbox.svg", java.io.StringReader(wrapped))
                val bridgeContext = BridgeContext(UserAgentAdapter()).apply { isDynamic = false }
                try {
                    val gvtRoot = GVTBuilder().build(bridgeContext, document)
                    val bounds = gvtRoot?.geometryBounds ?: gvtRoot?.sensitiveBounds
                    if (bounds == null || bounds.width.isNaN() || bounds.height.isNaN()) {
                        ""
                    } else {
                        "${round2(bounds.x)},${round2(bounds.y)},${round2(bounds.width)},${round2(bounds.height)}"
                            .also { bboxCache[svgFragment] = it }
                    }
                } finally {
                    bridgeContext.dispose()
                }
            } catch (e: Exception) {
                ""
            }
        }

        private fun sanitizeForBatik(svg: String): String =
            svg
                .replace(Regex("\\s*alignment-baseline\\s*=\\s*\"[^\"]*\""), "")
                .replace(Regex("\\s*dominant-baseline\\s*=\\s*\"[^\"]*\""), "")
                .replace(Regex("hsl\\([^)]*\\)"), "#333333")
                .replace(Regex("rgba\\([^)]*\\)"), "#333333")
                .replace(Regex("\\s*filter\\s*=\\s*\"[^\"]*\""), "")

        private fun resolveFont(fontFamily: String?, size: Double): Font {
            fontFamily?.split(",")?.forEach { raw ->
                val name = raw.trim().trim('"', '\'').let {
                    when {
                        it.equals("sans-serif", true) -> Font.SANS_SERIF
                        it.equals("serif", true) -> Font.SERIF
                        it.equals("monospace", true) -> Font.MONOSPACED
                        else -> it
                    }
                }
                if (name.isNotEmpty()) return Font(name, Font.PLAIN, 1).deriveFont(size.toFloat())
            }
            return Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size.toFloat())
        }

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}
