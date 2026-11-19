// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 5.1.1: the importer fetches nothing and imports no scene types. everything under `sketch/excalidraw/` may import only
 * `SceneLimits` and `CanonicalJson` from the engine, no HTTP client, and nothing from `ui`, `db`, `repository`,
 * `projectbridge`, `merge` or `platform`. `link` and `embeddable` URLs therefore stay inert text.
 */
class SketchImportImportDirectionTest {
    private val prefix = "sketch/excalidraw/"
    private val importLine = Regex("""(?m)^\s*import\s+([\w.]+)""")
    private val allowedEngine = setOf("dev.stapler.stelekit.sketch.v2.scene.SceneLimits", "dev.stapler.stelekit.sketch.v2.codec.CanonicalJson")
    private val forbiddenHead = listOf(
        "io.ktor", "java.net", "okhttp3", "javax.net", "org.apache.http", "platform.js", "kotlinx.browser", "org.w3c", "android.net",
        "dev.stapler.stelekit.ui", "dev.stapler.stelekit.db", "dev.stapler.stelekit.repository",
        "dev.stapler.stelekit.projectbridge", "dev.stapler.stelekit.merge", "dev.stapler.stelekit.platform",
        "dev.stapler.stelekit.network", "dev.stapler.stelekit.git",
    )

    private fun importerFiles(sources: Map<String, String>): Map<String, String> =
        sources.mapKeys { it.key.substringAfter("dev/stapler/stelekit/", "") }.filterKeys { it.startsWith(prefix) }

    private fun violations(sources: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        for ((path, text) in importerFiles(sources)) {
            for (m in importLine.findAll(text)) {
                val imported = m.groupValues[1]
                val engine = imported.startsWith("dev.stapler.stelekit.sketch.v2.")
                val bad = forbiddenHead.any { imported == it || imported.startsWith("$it.") } || (engine && imported !in allowedEngine)
                if (bad) out += "$path imports $imported"
            }
        }
        return out.sorted()
    }

    @Test
    fun detector_should_FlagPlantedImports() {
        val planted = mapOf(
            "commonMain/kotlin/dev/stapler/stelekit/sketch/excalidraw/A.kt" to "import io.ktor.client.HttpClient\nimport dev.stapler.stelekit.sketch.v2.scene.SceneLimits\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/excalidraw/B.kt" to "import dev.stapler.stelekit.sketch.v2.scene.RectElement\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/excalidraw/C.kt" to "import dev.stapler.stelekit.platform.Settings\nimport kotlinx.serialization.json.Json\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/v2/scene/D.kt" to "import io.ktor.client.HttpClient\n",
        )
        assertEquals(
            listOf(
                "sketch/excalidraw/A.kt imports io.ktor.client.HttpClient",
                "sketch/excalidraw/B.kt imports dev.stapler.stelekit.sketch.v2.scene.RectElement",
                "sketch/excalidraw/C.kt imports dev.stapler.stelekit.platform.Settings",
            ),
            violations(planted),
        )
    }

    @Test
    fun importer_should_ImportNoHttpClientAndNoSceneTypes_When_RealSourcesScanned() {
        val files = importerFiles(WiringInputs.productionSources)
        assertTrue(files.keys.any { it.endsWith("ExcalidrawParser.kt") }, "importer sources not loaded: ${files.keys}")
        assertEquals(emptyList(), violations(WiringInputs.productionSources))
    }
}
