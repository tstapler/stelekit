package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onSearch: () -> Unit = {},
    onToggleSidebar: () -> Unit = {},
    isLeftHanded: Boolean = false,
    voiceCaptureButton: @Composable () -> Unit = {},
)
