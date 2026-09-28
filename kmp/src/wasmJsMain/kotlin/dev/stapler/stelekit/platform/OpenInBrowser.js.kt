// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

private fun jsOpenInBrowser(url: String): Unit = js("window.open(url, '_blank')")

actual fun openInBrowser(url: String) {
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
        jsOpenInBrowser(url)
    }
}
