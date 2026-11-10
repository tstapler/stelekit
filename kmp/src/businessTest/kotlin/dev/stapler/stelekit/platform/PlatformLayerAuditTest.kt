package dev.stapler.stelekit.platform

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `platform` package is a lower layer than `merge`: it must not import from it. Shared helpers such as
 * `AtomicFileWriter` take what they need from `merge` (for example the SAF flag) as constructor parameters.
 */
class PlatformLayerAuditTest {
    companion object {
        private const val PLATFORM_DIR = "/dev/stapler/stelekit/platform/"
        private val FORBIDDEN_IMPORT = Regex("""^import\s+dev\.stapler\.stelekit\.merge\b""")

        /** Violations as `file:line: import`, over every source set's `platform/` directory. */
        fun violations(sources: Map<String, String>): List<String> = buildList {
            for ((path, text) in sources.toSortedMap()) {
                if (!path.contains(PLATFORM_DIR)) continue
                text.lineSequence().forEachIndexed { index, line ->
                    if (FORBIDDEN_IMPORT.containsMatchIn(line.trim())) add("$path:${index + 1}: ${line.trim()}")
                }
            }
        }
    }

    @Test
    fun platformPackage_should_NotImportFromMerge() {
        val sources = WiringInputs.productionSources
        assertTrue(
            sources.keys.any { it.endsWith("${PLATFORM_DIR}AtomicFileWriter.kt") },
            "an empty scan would pass vacuously: AtomicFileWriter.kt not found",
        )
        assertEquals(emptyList(), violations(sources))
    }

    @Test
    fun audit_should_FlagAPlantedMergeImport() {
        val planted = mapOf(
            "commonMain/dev/stapler/stelekit/platform/Bad.kt" to "package x\nimport dev.stapler.stelekit.merge.Foo\n",
            "jvmMain/dev/stapler/stelekit/platform/Ok.kt" to "import dev.stapler.stelekit.error.DomainError\n",
            "commonMain/dev/stapler/stelekit/merge/Fine.kt" to "import dev.stapler.stelekit.merge.Foo\n",
        )
        assertEquals(
            listOf("commonMain/dev/stapler/stelekit/platform/Bad.kt:2: import dev.stapler.stelekit.merge.Foo"),
            violations(planted),
        )
    }
}
