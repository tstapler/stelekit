package dev.stapler.stelekit.export

import arrow.core.getOrElse
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class TrackingParamStripperTest {

    private val all17 = listOf(
        "utm_source", "utm_campaign", "utm_medium", "utm_term", "utm_content", "utm_id",
        "fbclid", "gclid", "msclkid", "dclid", "twclid", "mc_eid", "mc_cid", "igshid",
        "yclid", "wbraid", "gbraid",
    )

    @Test
    fun strip_removesEachKnownTrackingParam() {
        assertEquals(17, all17.size)
        all17.forEach { p ->
            assertEquals("https://a.com/x", TrackingParamStripper.strip("https://a.com/x?$p=1"), p)
            assertEquals("https://a.com/x?id=7", TrackingParamStripper.strip("https://a.com/x?id=7&$p=1"), p)
        }
    }

    @Test
    fun strip_keepsNonTrackingParamsVerbatim() {
        val url = "https://a.com/x?page=1&ref=footer&q=a%20b"
        assertEquals(url, TrackingParamStripper.strip(url))
    }

    @Test
    fun strip_mixedParams_keepsOrderAndFragment() {
        assertEquals(
            "https://a.com/x?a=1&b=2#frag",
            TrackingParamStripper.strip("https://a.com/x?a=1&utm_source=n&b=2&fbclid=z#frag"),
        )
        assertEquals("https://a.com/x#frag", TrackingParamStripper.strip("https://a.com/x?gclid=1#frag"))
    }

    @Test
    fun strip_noQuery_orFragmentOnlyQuestionMark_unchanged() {
        assertEquals("https://a.com/x", TrackingParamStripper.strip("https://a.com/x"))
        assertEquals("https://a.com/#/route?utm_source=a", TrackingParamStripper.strip("https://a.com/#/route?utm_source=a"))
        assertEquals("assets://img.png", TrackingParamStripper.strip("assets://img.png"))
    }

    @Test
    fun strip_malformedInput_passesThroughWithoutThrowing() {
        listOf("not-a-url?", "?", "??&&", "", "://?=&", "%zz?utm_source").forEach { bad ->
            TrackingParamStripper.strip(bad) // must not throw
        }
        assertEquals("not-a-url?", TrackingParamStripper.strip("not-a-url?"))
        assertEquals("?", TrackingParamStripper.strip("?"))
    }

    @Test
    fun strip_isIdempotentAndPreservesNonTrackingPairs() = runTest {
        val keys = Arb.element(all17 + listOf("page", "ref", "q", "id", "UTM_SOURCE"))
        checkAll(Arb.list(keys, 0..6), Arb.string(0..4, Codepoint.alphanumeric())) { ks, v ->
            val url = "https://a.com/p?" + ks.joinToString("&") { "$it=$v" }
            val once = TrackingParamStripper.strip(url)
            assertEquals(once, TrackingParamStripper.strip(once))
            ks.filter { it.lowercase() !in TrackingParamStripper.TRACKING_PARAMS }.forEach { k ->
                assertTrue(once.contains("$k=$v"), "lost $k=$v in $once")
            }
            ks.filter { it.lowercase() in TrackingParamStripper.TRACKING_PARAMS }.forEach { k ->
                assertFalse(once.contains("$k="), "kept $k in $once")
            }
        }
    }

    @Test
    fun stripInContent_coversUrlLinkMdLinkAndImageNodes() {
        val content = "<https://a.com/1?utm_source=x&k=v> [t](https://b.com/2?fbclid=1) " +
            "![alt](https://c.com/3.png?gclid=9) and plain https://d.com/4?utm_id=4"
        val out = TrackingParamStripper.stripInContent(content)
        assertTrue(out.contains("https://a.com/1?k=v"), out)
        assertTrue(out.contains("](https://b.com/2)"), out)
        assertTrue(out.contains("](https://c.com/3.png)"), out)
        assertFalse(out.contains("utm_source") || out.contains("fbclid") || out.contains("gclid"), out)
    }

    @Test
    fun stripInContent_prefixUrls_andCodeSpans_areNotCorrupted() {
        val a = "https://x.com/?utm_source=a"
        val b = "https://x.com/?utm_source=a&id=1"
        val out = TrackingParamStripper.stripInContent("<$a> <$b> `curl $a`")
        assertEquals("<https://x.com/> <https://x.com/?id=1> `curl $a`", out)
    }

    @Test
    fun stripInContent_leavesTextWithoutLinksAlone() {
        val text = "what? utm_source=nothing here"
        assertEquals(text, TrackingParamStripper.stripInContent(text))
    }

    // ── Persistence (AC 2, 3) ──────────────────────────────────────────────────

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    @Test
    fun shareExportSettings_defaultsOff_andPersistsAcrossInstances() {
        val backing = MapSettings()
        assertFalse(ShareExportSettings(backing).getStripTrackingParams())
        ShareExportSettings(backing).setStripTrackingParams(true)
        assertTrue(ShareExportSettings(backing).getStripTrackingParams())
    }

    // ── All four export formats (AC 6) ─────────────────────────────────────────

    private val now = Clock.System.now()
    private val page = Page(uuid = PageUuid("p"), name = "P", createdAt = now, updatedAt = now)
    private val blocks = listOf(
        Block(
            uuid = BlockUuid("b1"), pageUuid = PageUuid("p"), parentUuid = null,
            content = "see [x](https://a.com/?utm_source=n&keep=1) <https://b.com/?fbclid=2>",
            level = 0, position = "a0", createdAt = now, updatedAt = now,
        )
    )
    private val service = ExportService(
        listOf(MarkdownExporter(), PlainTextExporter(), HtmlExporter(), JsonExporter()),
        object : ClipboardProvider {
            override fun writeText(text: String) {}
            override fun writeHtml(html: String, plainFallback: String) {}
        },
        InMemoryBlockRepository(),
    )

    @Test
    fun exportService_stripOn_removesTrackingInAllFormats_stripOff_leavesThem() = runTest {
        listOf("markdown", "plain-text", "html", "json").forEach { fmt ->
            val on = service.exportToString(page, blocks, fmt, stripTrackingParams = true).getOrElse { error(it.message) }
            val off = service.exportToString(page, blocks, fmt).getOrElse { error(it.message) }
            assertFalse(on.contains("utm_source") || on.contains("fbclid"), "$fmt on: $on")
            assertTrue(off.contains("utm_source") || fmt == "plain-text", "$fmt off: $off")
        }
        val md = service.exportToString(page, blocks, "markdown", true).getOrElse { error(it.message) }
        assertTrue(md.contains("https://a.com/?keep=1"), md)
    }
}
