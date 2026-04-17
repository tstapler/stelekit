package dev.stapler.stelekit.export

object HtmlUtils {
    fun escape(text: String): String = buildString(text.length + 16) {
        for (ch in text) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    fun escapeAttr(text: String): String = escape(text)
}
