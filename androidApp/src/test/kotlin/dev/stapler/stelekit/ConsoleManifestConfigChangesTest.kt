// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Story 3.1 "Config-change retention": the console keeps its state across rotation only because
 * `MainActivity` handles `orientation|screenSize|uiMode` itself instead of being recreated. If one of
 * them is dropped from the manifest, an orientation change would recreate the Activity and the
 * console would lose its pending input.
 */
class ConsoleManifestConfigChangesTest {
    private fun manifest(): File? = listOf("src/main/AndroidManifest.xml", "androidApp/src/main/AndroidManifest.xml")
        .map(::File).firstOrNull { it.isFile }

    @Test
    fun mainActivity_should_DeclareOrientationScreenSizeAndUiModeConfigChanges() {
        val file = manifest()
        assertNotNull("AndroidManifest.xml not found from ${File(".").absoluteFile}", file)
        val text = file!!.readText()
        val activity = Regex("""<activity[^>]*android:name="[^"]*MainActivity"[^>]*>""").find(text)?.value
        assertNotNull("MainActivity not declared", activity)
        val declared = Regex("""android:configChanges="([^"]*)"""").find(activity!!)
            ?.groupValues?.get(1)?.split('|').orEmpty().toSet()
        assertTrue("configChanges was: $declared", declared.containsAll(setOf("orientation", "screenSize", "uiMode")))
    }
}
