// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.components.LoadingOverlay
import dev.stapler.stelekit.ui.components.settings.SettingsCategory
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode

internal enum class InitializingDebugScreen { SETTINGS }

/**
 * Pre-repos "Initializing…" state — minimal stand-in for missing sidebar settings entry point.
 */
@Composable
internal fun InitializingScreenThemed(platformSettings: Settings) {
    var openScreen by remember { mutableStateOf<InitializingDebugScreen?>(null) }

    StelekitTheme(themeMode = StelekitThemeMode.SYSTEM) {
        Box(modifier = Modifier.fillMaxSize()) {
            LoadingOverlay("Initializing…")
            InitializingDebugAccessButtons(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp),
                onOpen = { openScreen = it },
            )
        }

        InitializingSettingsDialog(
            visible = openScreen == InitializingDebugScreen.SETTINGS,
            onDismiss = { openScreen = null },
            platformSettings = platformSettings,
        )
    }
}

@Composable
private fun InitializingDebugAccessButtons(onOpen: (InitializingDebugScreen) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { onOpen(InitializingDebugScreen.SETTINGS) }) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
internal fun InitializingSettingsDialog(visible: Boolean, onDismiss: () -> Unit, platformSettings: Settings) {
    var libsqlEnabled by remember {
        mutableStateOf(platformSettings.getBoolean("db.libsql.enabled", false))
    }
    SettingsDialog(
        visible = visible,
        onDismiss = onDismiss,
        currentTheme = StelekitThemeMode.SYSTEM,
        onThemeChange = {},
        currentLanguage = Language.ENGLISH,
        onLanguageChange = {},
        onReindex = {},
        initialCategory = SettingsCategory.DEVELOPER,
        isLibsqlDriverEnabled = libsqlEnabled,
        onLibsqlDriverToggle = { enabled ->
            libsqlEnabled = enabled
            platformSettings.putBoolean("db.libsql.enabled", enabled)
        },
    )
}
