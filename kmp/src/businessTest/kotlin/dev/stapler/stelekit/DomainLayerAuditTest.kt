package dev.stapler.stelekit

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-domain packages (`units`, and `fusion` once it exists) hold exact values only: no floating
 * point (ADR-002) and no dependency on UI, storage or integration packages. No allowlist; the one
 * `Double` converter lives outside these packages (`sketchimport/CalibratedLength`).
 */
class DomainLayerAuditTest {
    companion object {
        private const val BASE = "commonMain/dev/stapler/stelekit/"
        val DOMAIN_PACKAGES = listOf("units", "fusion")
        private val FLOATING_POINT = Regex("""\b(Double|Float)\b|toDouble|toFloat""")
        private val FORBIDDEN_IMPORT =
            Regex("""^import\s+dev\.stapler\.stelekit\.(sketch|ui|db|repository|projectbridge|sketchimport)\b""")

        /** Violations as `file:line: reason`, comment-only lines excluded from the floating-point scan. */
        fun violations(sources: Map<String, String>): List<String> = buildList {
            for ((path, text) in sources.toSortedMap()) {
                if (DOMAIN_PACKAGES.none { path.startsWith("$BASE$it/") }) continue
                text.lineSequence().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                    if (!isComment && FLOATING_POINT.containsMatchIn(line)) add("$path:${index + 1}: floating point in a domain package")
                    if (FORBIDDEN_IMPORT.containsMatchIn(trimmed)) add("$path:${index + 1}: forbidden import $trimmed")
                }
            }
        }
    }

    @Test
    fun domainPackages_should_HaveNoFloatingPointOrUpwardImports() {
        val sources = WiringInputs.productionSources
        assertTrue(
            sources.keys.any { it.startsWith("${BASE}units/") },
            "an empty scan would pass vacuously: no sources under units/",
        )
        assertEquals(emptyList(), violations(sources))
    }

    @Test
    fun audit_should_FlagPlantedDoubleAndForbiddenImport() {
        val planted = mapOf(
            "${BASE}units/Bad.kt" to "package dev.stapler.stelekit.units\nimport dev.stapler.stelekit.db.Foo\nval x: Double = 1.0\nfun f(r: Long) = r.toFloat()\n",
            "${BASE}fusion/Bad2.kt" to "package dev.stapler.stelekit.fusion\nimport dev.stapler.stelekit.ui.Bar\n",
            "${BASE}other/Fine.kt" to "val y: Double = 2.0\n",
            "${BASE}units/Ok.kt" to "// a Double mentioned in a comment is fine\npackage dev.stapler.stelekit.units\n",
        )
        assertEquals(
            listOf(
                "${BASE}fusion/Bad2.kt:2: forbidden import import dev.stapler.stelekit.ui.Bar",
                "${BASE}units/Bad.kt:2: forbidden import import dev.stapler.stelekit.db.Foo",
                "${BASE}units/Bad.kt:3: floating point in a domain package",
                "${BASE}units/Bad.kt:4: floating point in a domain package",
            ),
            violations(planted),
        )
    }
}
