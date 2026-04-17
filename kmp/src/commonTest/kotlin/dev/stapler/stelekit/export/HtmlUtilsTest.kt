package dev.stapler.stelekit.export

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlUtilsTest {

    @Test
    fun escapeXssScript() {
        assertEquals(
            "&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;",
            HtmlUtils.escape("<script>alert('xss')</script>")
        )
    }

    @Test
    fun escapeAmpersand() {
        assertEquals("a &amp; b", HtmlUtils.escape("a & b"))
    }

    @Test
    fun escapeEmpty() {
        assertEquals("", HtmlUtils.escape(""))
    }

    @Test
    fun escapeDoubleQuote() {
        assertEquals("&quot;hello&quot;", HtmlUtils.escape("\"hello\""))
    }

    @Test
    fun escapeAllSpecialChars() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HtmlUtils.escape("&<>\"'"))
    }

    @Test
    fun unicodePassesThrough() {
        val input = "Hello 🌍 مرحبا"
        assertEquals(input, HtmlUtils.escape(input))
    }

    @Test
    fun escapeAttrDelegatesToEscape() {
        assertEquals(HtmlUtils.escape("<test>"), HtmlUtils.escapeAttr("<test>"))
    }
}
