package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable

@Composable
fun EditorSettings() {
    SettingsSection("Editor Behavior") {
        SettingsToggleRow("Logical Outdenting", true) {}
        SettingsToggleRow("Wide Mode", false) {}
        SettingsToggleRow("Show Brackets", true) {}
    }
}
