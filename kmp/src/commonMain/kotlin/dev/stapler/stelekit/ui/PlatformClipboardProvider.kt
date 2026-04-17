package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipboardManager
import dev.stapler.stelekit.export.ClipboardProvider

/**
 * Returns a platform-specific [ClipboardProvider] backed by the given Compose [clipboard].
 * On JVM (desktop), the HTML flavor is written via AWT for rich-paste support.
 * On other platforms, all output is written as plain text.
 */
@Composable
expect fun rememberClipboardProvider(clipboard: ClipboardManager): ClipboardProvider
