// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

actual fun openInBrowser(url: String) {
    // No-op: WASM/JS target — window.open is blocked by pop-up blockers in most browsers
}
