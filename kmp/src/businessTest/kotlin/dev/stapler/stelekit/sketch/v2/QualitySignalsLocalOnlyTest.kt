// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 6.2.3b: the adoption and quality counters never leave the device. The sources of the counters, their log lines and
 * the Settings readout import no network, HTTP, upload or telemetry seam; the only platform port they touch is `Settings`.
 */
class QualitySignalsLocalOnlyTest {
    private val files = listOf(
        "sketch/v2/runtime/telemetry/QualitySignals.kt",
        "sketch/v2/runtime/telemetry/SketchLog.kt",
        "ui/sketch/v2/migration/SketchSignalsReadout.kt",
    )

    private val transmitting = Regex("""^import .*(?:\.(?:http|net|network|ktor|okhttp|socket|upload|telemetry\.remote)\b|NetworkGate|HttpClient|Analytics)""", RegexOption.IGNORE_CASE)

    private fun source(rel: String): String =
        WiringInputs.productionSources.entries.firstOrNull { it.key.endsWith("dev/stapler/stelekit/$rel") }?.value
            ?: error("source not loaded: $rel")

    @Test
    fun counterSources_should_ImportNoNetworkSeam() {
        for (rel in files) {
            val offending = source(rel).lineSequence().filter { transmitting.containsMatchIn(it.trim()) }.toList()
            assertEquals(emptyList(), offending, "$rel references a network seam")
        }
    }

    @Test
    fun counterStore_should_UseOnlySettingsOfThePlatformPackage() {
        val platformImports = source(files.first()).lineSequence().filter { it.startsWith("import dev.stapler.stelekit.platform.") }.toList()
        assertTrue(platformImports == listOf("import dev.stapler.stelekit.platform.Settings"), platformImports.toString())
    }
}
