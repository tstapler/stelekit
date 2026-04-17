package dev.stapler.stelekit.export

/**
 * Platform-specific clipboard write abstraction injected from the UI layer.
 *
 * For plain text formats (Markdown, plain text, JSON), callers use [writeText].
 * For HTML, [writeHtml] writes a multi-flavor transferable so rich-text editors
 * (Google Docs, Confluence, Apple Mail) receive styled content while plain-text
 * targets receive [plainFallback].
 *
 * JVM implementation uses AWT [java.awt.Toolkit.getDefaultToolkit().systemClipboard].
 * Must be called on the EDT (Compose Desktop's main dispatcher satisfies this).
 */
interface ClipboardProvider {
    fun writeText(text: String)
    fun writeHtml(html: String, plainFallback: String)
}
