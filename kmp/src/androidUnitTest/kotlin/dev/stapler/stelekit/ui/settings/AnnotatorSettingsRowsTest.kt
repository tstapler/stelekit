package dev.stapler.stelekit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.ANNOTATOR_AUTO_RETURN_TO_SELECT_KEY
import dev.stapler.stelekit.platform.ANNOTATOR_STICKY_TOOLS_KEY
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.annotatorAutoReturnToSelect
import dev.stapler.stelekit.platform.annotatorStickyTools
import dev.stapler.stelekit.ui.components.settings.ANNOTATOR_AUTO_RETURN_LABEL
import dev.stapler.stelekit.ui.components.settings.ANNOTATOR_STICKY_TOOLS_LABEL
import dev.stapler.stelekit.ui.components.settings.AnnotatorAutoReturnRow
import dev.stapler.stelekit.ui.components.settings.AnnotatorStickyToolsRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AnnotatorSettingsRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private fun row(label: String) =
        rule.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    private fun show(settings: Settings) = rule.setContent {
        MaterialTheme {
            Column {
                AnnotatorAutoReturnRow(settings)
                AnnotatorStickyToolsRow(settings)
            }
        }
    }

    @Test
    fun settingsRows_should_ToggleAutoReturnDefaultOnAndStickyDefaultOff() {
        val settings = MapSettings()
        show(settings)
        row(ANNOTATOR_AUTO_RETURN_LABEL).assertIsOn()
        row(ANNOTATOR_STICKY_TOOLS_LABEL).assertIsOff()
        assertTrue(settings.annotatorAutoReturnToSelect())
        assertFalse(settings.annotatorStickyTools())

        row(ANNOTATOR_AUTO_RETURN_LABEL).performClick()
        row(ANNOTATOR_STICKY_TOOLS_LABEL).performClick()

        assertEquals(false, settings.getBoolean(ANNOTATOR_AUTO_RETURN_TO_SELECT_KEY, true))
        assertEquals(true, settings.getBoolean(ANNOTATOR_STICKY_TOOLS_KEY, false))
        row(ANNOTATOR_AUTO_RETURN_LABEL).assertIsOff()
        row(ANNOTATOR_STICKY_TOOLS_LABEL).assertIsOn()
    }

    @Test
    fun settingsRows_should_Be48dpTargets() {
        show(MapSettings())
        row(ANNOTATOR_AUTO_RETURN_LABEL).assertHeightIsAtLeast(48.dp)
        row(ANNOTATOR_STICKY_TOOLS_LABEL).assertHeightIsAtLeast(48.dp)
    }
}
