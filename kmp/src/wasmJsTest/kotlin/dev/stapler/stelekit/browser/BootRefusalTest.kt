// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.browser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun jsInstallLoading(): Unit = js(
    "(function(){ var el = document.getElementById('loading'); if (el) el.remove(); el = document.createElement('div'); el.id = 'loading'; document.body.appendChild(el); })()"
)

private fun jsRemoveLoading(): Unit = js("(function(){ var el = document.getElementById('loading'); if (el) el.remove(); })()")

private fun jsText(id: String): String = js("(function(){ var e = document.getElementById(id); return e ? e.textContent : '<missing>'; })()")

private fun jsAttr(id: String, name: String): String =
    js("(function(){ var e = document.getElementById(id); return e && e.getAttribute(name) !== null ? e.getAttribute(name) : '<none>'; })()")

private fun jsTagOrder(): String = js(
    "(function(){ var s = document.getElementById('boot-refusal'); return Array.prototype.map.call(s.querySelectorAll('h1, p, button, a'), function(n){ return n.tagName.toLowerCase() + ':' + n.id; }).join(','); })()"
)

private fun jsFocusOnBody(): Boolean = js("document.activeElement === document.body || document.activeElement === null")

private fun jsExists(id: String): Boolean = js("document.getElementById(id) !== null")

/** Task 1.4.6k (UX-152, D-149): the plain-DOM boot-refusal screen replaces the `<p>` of the old boot error. */
class BootRefusalTest {
    @BeforeTest
    fun setUp() = jsInstallLoading()

    @AfterTest
    fun tearDown() = jsRemoveLoading()

    @Test
    fun theScreen_should_BeARegionNamedByItsHeading_WithTheAlertAsTheOneAnnouncement() {
        renderBootRefusal(retryAvailable = false)
        assertEquals("Graph open in another tab", jsText("boot-refusal-heading"))
        assertEquals("boot-refusal-heading", jsAttr("boot-refusal", "aria-labelledby"))
        assertEquals("alert", jsAttr("boot-refusal-message", "role"))
        assertTrue(jsFocusOnBody(), "focus is not moved to the heading: the alert is the one announcement")
    }

    @Test
    fun reloadAndOpenTemporarily_should_BeTheFirstAndSecondTabStops() {
        renderBootRefusal(retryAvailable = false)
        assertEquals("h1:boot-refusal-heading,p:boot-refusal-message,button:boot-refusal-reload,a:boot-refusal-temporary,p:boot-refusal-status", jsTagOrder())
        assertEquals("Reload this page", jsAttr("boot-refusal-reload", "aria-label"))
        assertEquals("Open a temporary graph instead", jsAttr("boot-refusal-temporary", "aria-label"))
        assertEquals("?mode=ephemeral", jsAttr("boot-refusal-temporary", "href"))
    }

    @Test
    fun withoutAnAbortableLockRequest_should_ShowTheNoRetryMessageAndNoWaitingLine() {
        renderBootRefusal(retryAvailable = false)
        assertEquals(
            "This graph is already open in another browser tab. Switch to that tab, or close it and reload this page.",
            jsText("boot-refusal-message"),
        )
        assertTrue(!jsExists("boot-refusal-waiting"))
    }

    @Test
    fun withAnAbortableLockRequest_should_PromiseTheAutomaticReloadAndShowTheWaitingLine() {
        renderBootRefusal(retryAvailable = true)
        assertEquals(
            "This graph is already open in another browser tab. Switch to that tab, or close it and this page reloads by itself.",
            jsText("boot-refusal-message"),
        )
        assertEquals("Waiting for the other tab to close.", jsText("boot-refusal-waiting"))
    }
}
