package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.export.HtmlStringSelection

@Composable
actual fun rememberClipboardProvider(clipboard: ClipboardManager): ClipboardProvider =
    remember(clipboard) {
        object : ClipboardProvider {
            override fun writeText(text: String) {
                clipboard.setText(AnnotatedString(text))
            }

            override fun writeHtml(html: String, plainFallback: String) {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(HtmlStringSelection(html, plainFallback), null)
            }
        }
    }
