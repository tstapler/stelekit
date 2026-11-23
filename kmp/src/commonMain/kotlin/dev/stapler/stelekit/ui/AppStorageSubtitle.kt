// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.ui.components.PLAIN_GRAPH_APP_OWNED_WARNING_WEB_COPY

/**
 * Pure platform-copy logic for `UnifiedLocationPicker`'s "App storage" row subtitle (Story 2.1.1,
 * `design/ux.md` §2) — factored out so the Android/Web wording is testable without mounting the
 * whole composable tree, mirroring [addGraphFlowMode] above. Shared by
 * `GraphContentNewGraphFlow.kt`'s `UnifiedLocationPicker` call site (new-graph flow) and
 * `GitSetupScreen.kt`'s `Step2RepoPath` call site. Uses the same [platform] string convention as
 * that same file's `warningCopy` (see `getDeviceInfo`'s `platform` field and
 * `SloChecker.diskThresholdsFor`).
 */
internal fun appStorageSubtitleFor(platform: String): String = if (platform == "Android") {
    "Kept inside SteleKit only — not visible in your device's file manager, and removed if you " +
        "uninstall the app."
} else {
    PLAIN_GRAPH_APP_OWNED_WARNING_WEB_COPY
}
