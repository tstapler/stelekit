package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.platform.ClipboardManager
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.table.TableClipboard

/**
 * Adapter over the existing [ClipboardProvider] (copy) and [ClipboardManager] (paste, the only new part).
 * Swapping to the suspend `LocalClipboard` later is a one-file change (ADR-008). Wasm paste events are UNVERIFIED here.
 */
class ComposeTableClipboard(
    private val manager: ClipboardManager,
    private val provider: ClipboardProvider,
) : TableClipboard {
    override suspend fun copy(plainTsv: String, html: String?) {
        if (html != null) provider.writeHtml(html, plainTsv) else provider.writeText(plainTsv)
    }

    override suspend fun paste(): String? = manager.getText()?.text?.takeIf { it.isNotEmpty() }
}
