package dev.stapler.stelekit.wiring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.4.3t (D-146): every production construction site of `StelekitAppPlatformIntegrations` names both sketch
 * storage fields, and none builds the defaults itself. The entry-point tests read the fixtures; this scan covers
 * `MainActivity`, the desktop `Main` and `App.kt` and both wasm sites. The fields have no default, so a forgotten
 * site also fails to compile; the scan additionally rejects a host that overrides the values.
 */
class SketchStorageHostSitesTest {
    private val storageFields = listOf("sketchWriterLease", "browserPersistence")

    @Test
    fun everyHostSite_should_NameBothStorageFields() {
        val sites = sitesIn(WiringInputs.productionSources)
        assertTrue(sites.size >= 5, "expected the Android, desktop (2) and wasm (2) sites at least, found ${sites.keys}")
        for ((site, args) in sites) {
            for (field in storageFields) {
                assertTrue(fieldRegex(field).containsMatchIn(args), "$site does not name $field")
            }
        }
    }

    @Test
    fun noProductionHost_should_ConstructTheDefaultsItself() {
        for ((site, args) in sitesIn(WiringInputs.productionSources)) {
            assertTrue("AlwaysHeldSketchWriterLease(" !in args, "$site constructs the lease default itself")
            assertTrue("UnsupportedBrowserPersistence" !in args, "$site constructs the persistence default itself")
        }
    }

    @Test
    fun wasmSites_should_PassTheDefaultsUntilTasks147eAnd147iSwapThemIn() {
        val wasm = sitesIn(WiringInputs.productionSources).filterKeys { it.startsWith("wasmJsMain/") }
        assertEquals(2, wasm.size, "browser/Main.kt has two construction sites, found ${wasm.keys}")
        for ((site, args) in wasm) {
            val lease = valueOf(args, "sketchWriterLease")
            val persistence = valueOf(args, "browserPersistence")
            assertTrue(
                lease.startsWith(DEFAULTS_PREFIX) || "WasmSketchWriterLease" in lease,
                "$site passes an unexpected lease: $lease",
            )
            assertTrue(
                persistence.startsWith(DEFAULTS_PREFIX) || "WasmBrowserPersistence" in persistence,
                "$site passes an unexpected persistence: $persistence",
            )
        }
    }

    @Test
    fun scanner_should_RejectAHostThatOmitsOrOverrides() {
        val omitting = mapOf("hostMain/a/Main.kt" to "val x = StelekitAppPlatformIntegrations(\n  taskRegistry = r,\n  sketchWriterLease = L,\n)\n")
        val overriding = mapOf(
            "hostMain/a/Main.kt" to "val x = StelekitAppPlatformIntegrations(sketchWriterLease = AlwaysHeldSketchWriterLease(), browserPersistence = P)\n",
        )
        assertTrue(!fieldRegex("browserPersistence").containsMatchIn(sitesIn(omitting).values.single()))
        assertTrue("AlwaysHeldSketchWriterLease(" in sitesIn(overriding).values.single())
    }

    @Test
    fun scanner_should_IgnoreCallsInCommentsAndStrings() {
        val src = mapOf("hostMain/a/Main.kt" to "// StelekitAppPlatformIntegrations(a = 1)\nval s = \"StelekitAppPlatformIntegrations(\"\n")
        assertEquals(emptyMap(), sitesIn(src))
    }

    private fun fieldRegex(field: String) = Regex("""\b$field\s*=""")

    private fun valueOf(args: String, field: String): String =
        Regex("""\b$field\s*=\s*([^,\n]+)""").find(args)?.groupValues?.get(1)?.trim() ?: error("no $field in $args")

    /** Maps "file#n" to the argument text of each `StelekitAppPlatformIntegrations(` call, comments and strings removed. */
    private fun sitesIn(sources: Map<String, String>): Map<String, String> {
        val out = sortedMapOf<String, String>()
        for ((file, raw) in sources) {
            if (file.endsWith("/StelekitAppDependencies.kt")) continue
            val text = stripCommentsAndStrings(raw)
            var from = 0
            var n = 0
            while (true) {
                val at = text.indexOf(CALL, from)
                if (at < 0) break
                val open = at + CALL.length
                var depth = 1
                var i = open
                while (i < text.length && depth > 0) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    i++
                }
                out["$file#${++n}"] = text.substring(open, i - 1)
                from = i
            }
        }
        return out
    }

    /** Blanks comments and string/char literal contents in one pass so a MIME-type literal cannot open a comment. */
    private fun stripCommentsAndStrings(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val next = raw.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> i = skipTo(raw, i, "\n", keep = false)
                c == '/' && next == '*' -> i = skipTo(raw, i + 2, "*/", keep = true)
                raw.startsWith(TRIPLE_QUOTE, i) -> { out.append(c).append(c); i = skipTo(raw, i + 3, TRIPLE_QUOTE, keep = true) }
                c == '"' || c == '\'' -> { out.append(c).append(c); i = skipLiteral(raw, i + 1, c) }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun skipTo(raw: String, from: Int, end: String, keep: Boolean): Int {
        val at = raw.indexOf(end, from)
        if (at < 0) return raw.length
        return if (keep) at + end.length else at
    }

    private fun skipLiteral(raw: String, from: Int, quote: Char): Int {
        var i = from
        while (i < raw.length && raw[i] != quote && raw[i] != '\n') i += if (raw[i] == '\\') 2 else 1
        return i + 1
    }

    private companion object {
        const val TRIPLE_QUOTE = "\"\"\""
        const val CALL = "StelekitAppPlatformIntegrations("
        const val DEFAULTS_PREFIX = "dev.stapler.stelekit.ui.SketchStorageDefaults."
    }
}
