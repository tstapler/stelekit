// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.ui.components.sheet.ComposeTableClipboard
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeTableClipboardTest {
    private class FakeManager(var text: String? = null) : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) { text = annotatedString.text }
        override fun getText(): AnnotatedString? = text?.let { AnnotatedString(it) }
    }

    private class RecordingProvider : ClipboardProvider {
        val calls = mutableListOf<String>()
        override fun writeText(text: String) { calls += "text:$text" }
        override fun writeHtml(html: String, plainFallback: String) { calls += "html:$html|$plainFallback" }
    }

    @Test
    fun copy_should_WriteHtmlWithTsvFallback_When_HtmlProvided() = runTest {
        val provider = RecordingProvider()
        ComposeTableClipboard(FakeManager(), provider).copy("a\tb", "<table></table>")
        assertEquals(listOf("html:<table></table>|a\tb"), provider.calls)
    }

    @Test
    fun copy_should_WritePlainText_When_NoHtml() = runTest {
        val provider = RecordingProvider()
        ComposeTableClipboard(FakeManager(), provider).copy("a\tb", null)
        assertEquals(listOf("text:a\tb"), provider.calls)
    }

    @Test
    fun paste_should_ReturnClipboardText_When_Present() = runTest {
        assertEquals("1\t2\r\n3\t4", ComposeTableClipboard(FakeManager("1\t2\r\n3\t4"), RecordingProvider()).paste())
    }

    @Test
    fun paste_should_ReturnNull_When_ClipboardEmpty() = runTest {
        assertNull(ComposeTableClipboard(FakeManager(null), RecordingProvider()).paste())
        assertNull(ComposeTableClipboard(FakeManager(""), RecordingProvider()).paste())
    }
}
