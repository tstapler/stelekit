// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import java.awt.Desktop
import java.net.URI

actual fun openInBrowser(url: String) = openInBrowserWithOs(url)

/**
 * Launchers are injectable so tests can assert dispatch without spawning a process or a browser.
 * Failures are swallowed: the device-auth dialog's "Copy code" button is the manual fallback.
 */
internal fun openInBrowserWithOs(
    url: String,
    osName: String = System.getProperty("os.name", ""),
    xdgOpen: (String) -> Unit = { u -> ProcessBuilder("xdg-open", u).start() },
    desktopBrowse: (String) -> Unit = { u ->
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(u))
    },
) {
    if (osName.contains("linux", ignoreCase = true)) {
        // Desktop.browse() is unreliable on Linux (unsupported under Wayland/headless JDKs).
        // Spawned on a daemon thread so process start never blocks the Compose UI thread.
        Thread { runCatching { xdgOpen(url) } }.apply { isDaemon = true }.start()
    } else {
        runCatching { desktopBrowse(url) }
    }
}
