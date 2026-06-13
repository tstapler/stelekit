// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

/**
 * Opens the given URL in the platform's default browser.
 * Platform actuals:
 * - androidMain: Intent.ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK
 * - jvmMain: Desktop.getDesktop().browse(URI(url))
 * - iosMain: UIApplication.sharedApplication.openURL
 * - wasmJsMain: no-op (could use window.open but that's blocked by pop-up blockers)
 */
expect fun openInBrowser(url: String)
