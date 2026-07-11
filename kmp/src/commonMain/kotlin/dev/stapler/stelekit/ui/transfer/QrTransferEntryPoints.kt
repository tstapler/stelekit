package dev.stapler.stelekit.ui.transfer

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings

/**
 * Flag-gated page-menu action that launches [QrEncodeScreen] (Story 3.1.4, S1).
 *
 * Renders nothing at all — not a disabled item — when [QrTransferSettings.enabled] is false,
 * per the story's AC ("absent, not disabled/greyed"): the feature has zero surface area when off.
 * Wired into the page context/share menu in `ui/screens/PageView.kt`.
 */
@Composable
fun SendViaQrMenuItem(
    settings: QrTransferSettings,
    onClick: () -> Unit,
) {
    if (!settings.enabled) return
    DropdownMenuItem(
        text = { Text("📷  Send via QR") },
        onClick = onClick,
    )
}
