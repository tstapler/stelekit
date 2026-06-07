// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import java.awt.Desktop
import java.net.URI

actual fun openInBrowser(url: String) {
    if (Desktop.isDesktopSupported()) {
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (_: Exception) {
            // Silently ignore if browser cannot be opened (e.g. headless environment)
        }
    }
}
