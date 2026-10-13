// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

/**
 * Opens the given URL in the platform's default browser.
 * Platform actuals:
 * - androidMain: Intent.ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK
 * - jvmMain: xdg-open on Linux; Desktop.getDesktop().browse(URI(url)) on macOS/Windows
 * - iosMain: UIApplication.sharedApplication.openURL
 * - wasmJsMain: window.open(url, '_blank') for http(s) URLs; may be blocked by pop-up blockers
 */
expect fun openInBrowser(url: String)
