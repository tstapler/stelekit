package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.ANNOTATOR_AUTO_RETURN_TO_SELECT_KEY
import dev.stapler.stelekit.platform.ANNOTATOR_STICKY_TOOLS_KEY
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.annotatorAutoReturnToSelect
import dev.stapler.stelekit.platform.annotatorStickyTools
import dev.stapler.stelekit.ui.components.TableDensity
import dev.stapler.stelekit.ui.components.TableOverflow
import dev.stapler.stelekit.ui.components.TablePreferences

const val ANNOTATOR_AUTO_RETURN_LABEL = "Return to Select after each measurement"
const val ANNOTATOR_STICKY_TOOLS_LABEL = "Keep tools active"
const val TABLE_WRAP_LABEL = "Wrap text in table cells"
const val TABLE_NUMERIC_RIGHT_ALIGN_LABEL = "Right-align number columns"
const val TABLE_COMPACT_LABEL = "Compact table rows"

@Composable
fun EditorSettings(annotatorSettings: Settings? = null, tablePreferences: TablePreferences? = null) {
    SettingsSection("Editor Behavior") {
        SettingsToggleRow("Logical Outdenting", true) {}
        SettingsToggleRow("Wide Mode", false) {}
        SettingsToggleRow("Show Brackets", true) {}
    }
    if (annotatorSettings != null) {
        SettingsSection("Image annotator") {
            AnnotatorAutoReturnRow(annotatorSettings)
        }
    }
    if (tablePreferences != null) {
        SettingsSection("Tables") { TableSettingsRows(tablePreferences) }
    }
}

/** Off for [TABLE_WRAP_LABEL] means cells keep their natural width and the table scrolls sideways. */
@Composable
fun TableSettingsRows(tablePreferences: TablePreferences, modifier: Modifier = Modifier) {
    val prefs = tablePreferences.prefs
    Column(modifier) {
        SettingsSwitchRow(TABLE_WRAP_LABEL, prefs.overflow == TableOverflow.WRAP) { wrap ->
            tablePreferences.update { it.copy(overflow = if (wrap) TableOverflow.WRAP else TableOverflow.SCROLL) }
        }
        SettingsSwitchRow(TABLE_NUMERIC_RIGHT_ALIGN_LABEL, prefs.numericRightAlign) { on ->
            tablePreferences.update { it.copy(numericRightAlign = on) }
        }
        SettingsSwitchRow(TABLE_COMPACT_LABEL, prefs.density == TableDensity.COMPACT) { compact ->
            tablePreferences.update { it.copy(density = if (compact) TableDensity.COMPACT else TableDensity.COMFORTABLE) }
        }
    }
}

@Composable
fun AnnotatorAutoReturnRow(settings: Settings) {
    var checked by remember(settings) { mutableStateOf(settings.annotatorAutoReturnToSelect()) }
    SettingsSwitchRow(ANNOTATOR_AUTO_RETURN_LABEL, checked) {
        checked = it
        settings.putBoolean(ANNOTATOR_AUTO_RETURN_TO_SELECT_KEY, it)
    }
}

@Composable
fun AnnotatorStickyToolsRow(settings: Settings) {
    var checked by remember(settings) { mutableStateOf(settings.annotatorStickyTools()) }
    SettingsSwitchRow(ANNOTATOR_STICKY_TOOLS_LABEL, checked) {
        checked = it
        settings.putBoolean(ANNOTATOR_STICKY_TOOLS_KEY, it)
    }
}

/** Whole row is the toggle target (48 dp min) so the label is tappable and TalkBack announces a switch. */
@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
