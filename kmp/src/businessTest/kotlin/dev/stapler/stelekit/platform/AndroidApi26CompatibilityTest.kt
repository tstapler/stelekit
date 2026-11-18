// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * minSdk is 26, but the JVM running the tests has every JDK method, so a call that only exists from Android API 28 to 33
 * compiles, passes here and throws `NoSuchMethodError` (an `Error`, not caught by `catch (e: Exception)`) on an API 26 phone.
 * `InputStream.readNBytes(int)` and `readAllBytes()` (API 33) and `transferTo` (API 33) are the ones that crept in.
 */
class AndroidApi26CompatibilityTest {
    private val newerJdkStreamCalls = Regex("""\.(readNBytes|readAllBytes|transferTo)\(""")

    @Test
    fun detector_should_FlagThePlantedCalls() {
        for (line in listOf("stream.readNBytes(n)", "it.readAllBytes()", "a.transferTo(b)")) assertTrue(newerJdkStreamCalls.containsMatchIn(line), line)
    }

    @Test
    fun androidMain_should_NotCallInputStreamMethodsNewerThanApi26_When_Scanned() {
        val android = WiringInputs.productionSources.filterKeys { it.startsWith("androidMain/") }
        assertTrue(android.isNotEmpty(), "androidMain sources not loaded")
        val hits = ArrayList<String>()
        for ((key, text) in android.toSortedMap()) {
            text.lineSequence().forEachIndexed { i, line ->
                val code = line.trim()
                if (!code.startsWith("//") && !code.startsWith("*") && !code.startsWith("/*") && newerJdkStreamCalls.containsMatchIn(code)) hits += "$key:${i + 1} $code"
            }
        }
        assertEquals(emptyList(), hits, "use a bounded read loop instead")
    }
}
