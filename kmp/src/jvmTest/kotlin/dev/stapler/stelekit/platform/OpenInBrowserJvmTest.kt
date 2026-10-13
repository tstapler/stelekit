// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenInBrowserJvmTest {
    private val url = "https://github.com/login/device"

    @Test
    fun `linux branch invokes xdg-open off the calling thread`() {
        val xdgCalls = LinkedBlockingQueue<Pair<String, Thread>>()
        val desktopCalls = mutableListOf<String>()

        openInBrowserWithOs(
            url = url,
            osName = "Linux",
            xdgOpen = { xdgCalls.add(it to Thread.currentThread()) },
            desktopBrowse = { desktopCalls.add(it) },
        )

        val (calledUrl, thread) = assertNotNull(xdgCalls.poll(5, TimeUnit.SECONDS))
        assertEquals(url, calledUrl)
        assertEquals(true, thread != Thread.currentThread())
        assertEquals(emptyList(), desktopCalls)
    }

    @Test
    fun `macOS and Windows use Desktop browse and not xdg-open`() {
        for (os in listOf("Mac OS X", "Windows 11")) {
            var xdgUrl: String? = null
            var desktopUrl: String? = null

            openInBrowserWithOs(
                url = url,
                osName = os,
                xdgOpen = { xdgUrl = it },
                desktopBrowse = { desktopUrl = it },
            )

            assertNull(xdgUrl, os)
            assertEquals(url, desktopUrl, os)
        }
    }

    @Test
    fun `launcher failures are absorbed`() {
        openInBrowserWithOs(url, "Mac OS X", desktopBrowse = { throw java.io.IOException("boom") })
        val ran = LinkedBlockingQueue<Boolean>()
        openInBrowserWithOs(url, "Linux", xdgOpen = { ran.add(true); throw java.io.IOException("no xdg-open") })
        assertNotNull(ran.poll(5, TimeUnit.SECONDS))
    }
}
