package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Story 3.1.4: the "Send via QR" page-menu action is flag-gated on [QrTransferSettings.enabled] —
 * present (and wired to launch the encoder) when true, **absent** (not disabled/greyed) when
 * false, per the story's AC.
 */
class QrTransferEntryPointsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    @Test
    fun pageMenu_should_ShowSendViaQrAction_When_QrTransferSettingsEnabled() {
        val settings = QrTransferSettings(MapSettings()).apply { enabled = true }
        var launched = false

        composeTestRule.setContent {
            SendViaQrMenuItem(settings = settings, onClick = { launched = true })
        }

        composeTestRule.onNodeWithText("Send via QR", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Send via QR", substring = true).performClick()
        assertTrue(launched, "tapping the menu item must invoke onClick (which launches QrEncodeScreen)")
    }

    @Test
    fun pageMenu_should_OmitSendViaQrAction_When_QrTransferSettingsDisabled() {
        val settings = QrTransferSettings(MapSettings()) // enabled defaults to false

        composeTestRule.setContent {
            SendViaQrMenuItem(settings = settings, onClick = {})
        }

        composeTestRule.onNodeWithText("Send via QR", substring = true).assertDoesNotExist()
    }
}
