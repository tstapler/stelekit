package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onSearch: () -> Unit,
    isLeftHanded: Boolean,
    voiceCaptureButton: @Composable () -> Unit,
) { /* Desktop uses sidebar navigation */ }
