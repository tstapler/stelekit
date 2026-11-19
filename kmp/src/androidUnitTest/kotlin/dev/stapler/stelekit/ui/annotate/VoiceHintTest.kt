// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.model.ImageAnnotationUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.VoiceCaptureButtonSlot
import dev.stapler.stelekit.ui.shouldShowVoiceCaptureButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VoiceHintTest {
    @get:Rule
    val rule = createComposeRule()

    private class MapSettings : Settings {
        val map = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getString(key: String, defaultValue: String) = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private val editor = Screen.AnnotationEditor(ImageAnnotationUuid("a"))

    @Test
    fun hintShowsForFirstThreeOpensOnlyAndTextIsExact() {
        val settings = MapSettings()
        val shown = (1..5).map { shouldShowVoiceHint(recordEditorOpen(settings)) }
        assertEquals(listOf(true, true, true, false, false), shown)
        assertEquals("5", settings.map["annotator_voice_hint_count"])
        assertEquals("Voice notes: go Back to use the mic", VOICE_HINT_TEXT)
    }

    @Test
    fun nullOrCorruptSettingsNeverThrowAndCorruptRestartsAtOne() {
        assertEquals(1, recordEditorOpen(null))
        val settings = MapSettings().also { it.map["annotator_voice_hint_count"] = "x" }
        assertEquals(1, recordEditorOpen(settings))
        assertFalse(shouldShowVoiceHint(0))
        assertFalse(shouldShowVoiceHint(4))
    }

    @Test
    fun micFabIsHiddenInTheAnnotationAndSketchEditors() {
        assertFalse(shouldShowVoiceCaptureButton(editor))
        assertFalse(shouldShowVoiceCaptureButton(Screen.Sketch()), "the sketch toolbar sits where the mic FAB floats")
        assertTrue(shouldShowVoiceCaptureButton(Screen.Journals))
    }

    @Test
    fun fabSlotComposesNothingInEditorAndContentElsewhere() {
        var screen: Screen by mutableStateOf(editor)
        rule.setContent { VoiceCaptureButtonSlot(screen) { Text("MIC") } }
        rule.onNodeWithText("MIC").assertDoesNotExist()
        screen = Screen.Journals
        rule.waitForIdle()
        rule.onNodeWithText("MIC").assertExists()
    }
}
