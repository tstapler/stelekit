// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UrlLinkRepairTest {

    @Test
    fun repairsHostCorruptionFromScreenshot() {
        assertEquals(
            "https://github.com/PacktPublishing/Distributed-Computing-with-Go",
            UrlLinkRepair.repair("https://[[GitHub]].com/PacktPublishing/Distributed-Computing-with-Go"),
        )
        assertEquals("https://github.com/lni/dragonboat", UrlLinkRepair.repair("https://[[GitHub]].com/lni/dragonboat"))
    }

    @Test
    fun repairsWwwForm() {
        assertEquals("www.github.com/x", UrlLinkRepair.repair("www.[[GitHub]].com/x"))
        assertEquals("see www.github.com.", UrlLinkRepair.repair("see www.[[GitHub]].com."))
    }

    @Test
    fun repairsLinksAnywhereInsideUrl_keepingPathCase() {
        assertEquals(
            "https://example.com/Go/x?q=Treacle&r=1",
            UrlLinkRepair.repair("https://example.com/[[Go]]/x?q=[[Treacle]]&r=1"),
        )
        assertEquals("https://sub.github.com/x", UrlLinkRepair.repair("https://sub.[[GitHub]].com/x"))
    }

    @Test
    fun repairsNestedBrackets() {
        assertEquals("https://x.com/a", UrlLinkRepair.repair("https://x.com/[[[[a]]]]"))
    }

    @Test
    fun leavesCleanTextUntouched_sameInstance() {
        val texts = listOf(
            "plain text",
            "[[Treacle]] is at https://github.com/x and #[[Go]]",
            "https://github.com/lni/dragonboat #GitHub",
            "[[GitHub]] [repo](https://github.com/lni/dragonboat)",
            "link [[Multi Word]] then https://a.com/b",
            "url followed by link https://a.com/b [[Go]]",
        )
        for (t in texts) assertSame(t, UrlLinkRepair.repair(t), "changed clean text: $t")
    }

    @Test
    fun repairsEveryCorruptedUrlInOneBlock_leavesTheRest() {
        assertEquals(
            "[[Treacle]] https://github.com/a and https://github.com/b",
            UrlLinkRepair.repair("[[Treacle]] https://[[GitHub]].com/a and https://[[GitHub]].com/b"),
        )
    }

    @Test
    fun property_repairIsIdempotent_andRoundTripsCorruptedUrls() = runTest {
        val hostLabels = listOf("github", "example", "dragonboat", "go", "x1")
        val segs = listOf("a", "Go", "b-c", "lni", "Treacle", "x%2Fy", "q=1", "z_9")
        val urlGen = Arb.list(Arb.element(segs), 0..5)
        checkAll(Arb.element(hostLabels), Arb.element(listOf("com", "org", "io")), urlGen, Arb.int(0..8)) { label, tld, path, pick ->
            val url = "https://$label.$tld/" + path.joinToString("/")
            val tokens = Regex("""[A-Za-z0-9]+""").findAll(url).filter { it.value != "https" }.toList()
            val victim = tokens[pick % tokens.size]
            val corrupted = url.substring(0, victim.range.first) + "[[" + victim.value + "]]" + url.substring(victim.range.last + 1)
            val expected = if (victim.range.first < url.indexOf('/', 8)) {
                url.substring(0, victim.range.first) + victim.value.lowercase() + url.substring(victim.range.last + 1)
            } else {
                url
            }
            val fixed = UrlLinkRepair.repair(corrupted)
            assertEquals(expected, fixed, "round trip failed for $corrupted")
            assertEquals(fixed, UrlLinkRepair.repair(fixed), "not idempotent for $corrupted")
            assertEquals(url, UrlLinkRepair.repair(url), "changed a clean URL")
        }
    }

    @Test
    fun property_neverChangesTextWithoutLinkInsideAUrl() = runTest {
        val pieces = listOf("a", " ", "[[Go]]", "#tag", "https://x.com/p", "(", ")", "\n", "[[Multi Word]]", "www.y.org")
        checkAll(Arb.list(Arb.element(pieces), 0..10)) { parts ->
            // No two adjacent pieces: a "[[..]]" glued to a URL would legitimately count as inside it.
            val text = parts.joinToString(" ")
            assertEquals(text, UrlLinkRepair.repair(text))
        }
    }
}
