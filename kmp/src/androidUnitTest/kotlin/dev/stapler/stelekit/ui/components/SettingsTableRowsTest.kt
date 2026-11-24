package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.components.settings.TABLE_INLINE_EDIT_LABEL
import dev.stapler.stelekit.ui.components.settings.TABLE_SHEET_HELPER
import dev.stapler.stelekit.ui.components.settings.TABLE_SHEET_LABEL
import dev.stapler.stelekit.ui.components.settings.TableSettingsRows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsTableRowsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun flagRowsAppearAndFlipPrefs() {
        val holder = TablePreferences(null)
        rule.setContent { MaterialTheme { Column { TableSettingsRows(holder) } } }
        rule.onNodeWithText(TABLE_SHEET_HELPER, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(TABLE_INLINE_EDIT_LABEL, useUnmergedTree = true).performClick()
        assertEquals(true, holder.prefs.inlineEditEnabled)
        assertEquals(false, holder.prefs.sheetEnabled)
        rule.onNodeWithText(TABLE_SHEET_LABEL, useUnmergedTree = true).performClick()
        assertEquals(true, holder.prefs.sheetEnabled)
    }
}
