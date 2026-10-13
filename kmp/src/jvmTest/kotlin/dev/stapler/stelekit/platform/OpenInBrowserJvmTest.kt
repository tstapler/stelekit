// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class OpenInBrowserJvmTest {
    private val url = "https://github.com/login/device"

    private fun awaitFile(file: File, timeoutMs: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.length() > 0) return file.readText().trim()
            Thread.sleep(20)
        }
        return null
    }

    /** A stand-in "xdg-open" that records its argv, so the real ProcessBuilder path is exercised. */
    private fun recordingBinary(): Pair<File, File> {
        val dir = Files.createTempDirectory("openinbrowser").toFile().apply { deleteOnExit() }
        val out = File(dir, "argv.txt")
        val script = File(dir, "fake-xdg-open").apply {
            writeText("#!/bin/sh\necho \"\$@\" > '${out.path}.tmp' && mv '${out.path}.tmp' '${out.path}'\n")
            setExecutable(true)
            deleteOnExit()
        }
        return script to out
    }

    @Test
    fun `linux branch launches xdg-open with the url`() {
        assumeTrue(File("/bin/sh").exists())
        val (script, out) = recordingBinary()
        var desktopUrl: String? = null

        openInBrowserWithOs(url, osName = "Linux", xdgOpenBinary = script.path, desktopBrowse = { desktopUrl = it })

        assertEquals(url, awaitFile(out))
        assertNull(desktopUrl)
    }

    @Test
    fun `linux branch does not block the calling thread`() {
        // Slow binary: if the launch ran on the caller, the call would take ~2s.
        assumeTrue(File("/bin/sh").exists())
        val dir = Files.createTempDirectory("openinbrowser-slow").toFile().apply { deleteOnExit() }
        val slow = File(dir, "slow-xdg-open").apply {
            writeText("#!/bin/sh\nsleep 2\n")
            setExecutable(true)
            deleteOnExit()
        }

        val start = System.nanoTime()
        openInBrowserWithOs(url, osName = "Linux", xdgOpenBinary = slow.path)

        // ProcessBuilder.start() returns before the child exits, so this only guards against
        // someone later adding waitFor() on the calling thread.
        assertTrue((System.nanoTime() - start) < 1_500_000_000L)
    }

    @Test
    fun `macOS and Windows use Desktop browse and never launch xdg-open`() {
        assumeTrue(File("/bin/sh").exists())
        for (os in listOf("Mac OS X", "Windows 11")) {
            val (script, out) = recordingBinary()
            var desktopUrl: String? = null

            openInBrowserWithOs(url, osName = os, xdgOpenBinary = script.path, desktopBrowse = { desktopUrl = it })
            Thread.sleep(200)

            assertEquals(url, desktopUrl, os)
            assertTrue(!out.exists(), os)
        }
    }

    @Test
    fun `linux branch refuses non-http urls`() {
        assumeTrue(File("/bin/sh").exists())
        val (script, out) = recordingBinary()

        openInBrowserWithOs("--help", osName = "Linux", xdgOpenBinary = script.path)
        Thread.sleep(300)

        assertTrue(!out.exists())
    }

    @Test
    fun `actual openInBrowser rejects non-http urls using the real os name`() {
        // Linux-only: elsewhere the real call would reach Desktop.browse() and open a browser.
        if (!System.getProperty("os.name", "").contains("linux", ignoreCase = true)) return
        openInBrowser("--help")
    }

    @Test
    fun `missing xdg-open binary is absorbed`() {
        openInBrowserWithOs(url, osName = "Linux", xdgOpenBinary = "stelekit-no-such-xdg-open")
        Thread.sleep(200)
    }

    @Test
    fun `desktop launcher failures are absorbed`() {
        openInBrowserWithOs(url, osName = "Mac OS X", desktopBrowse = { throw java.io.IOException("boom") })
    }

    @Test
    fun `default desktop launcher never throws for a scheme-less value`() {
        // Not opened anywhere: headless JDKs report Desktop unsupported, and a scheme-less URI
        // is rejected by Desktop.browse() and absorbed by the caller.
        openInBrowserWithOs("not-a-url", osName = "Mac OS X")
    }
}
