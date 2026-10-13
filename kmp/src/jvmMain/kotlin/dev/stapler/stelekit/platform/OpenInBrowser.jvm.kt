// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import java.awt.Desktop
import java.net.URI

actual fun openInBrowser(url: String) = openInBrowserWithOs(url)

/**
 * [xdgOpenBinary] and [desktopBrowse] are injectable so tests can exercise the real launch path
 * without opening a browser. Failures are swallowed: the device-auth dialog's "Copy code" button
 * is the manual fallback.
 */
internal fun openInBrowserWithOs(
    url: String,
    osName: String = System.getProperty("os.name", ""),
    xdgOpenBinary: String = "xdg-open",
    desktopBrowse: (String) -> Unit = ::browseWithDesktop,
) {
    if (osName.contains("linux", ignoreCase = true)) {
        // Only http(s): a value starting with "-" would be parsed by xdg-open as an option.
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return
        // Desktop.browse() is unreliable on Linux (unsupported under Wayland/headless JDKs).
        // Spawned on a daemon thread so process start never blocks the Compose UI thread.
        Thread { runCatching { ProcessBuilder(xdgOpenBinary, url).start() } }.apply { isDaemon = true }.start()
    } else {
        runCatching { desktopBrowse(url) }
    }
}

private fun browseWithDesktop(url: String) {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
}
