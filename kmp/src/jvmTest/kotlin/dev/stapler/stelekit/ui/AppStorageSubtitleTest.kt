// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.ui.components.PlainGraphAppOwnedWarningWebCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Story 2.1.1: `UnifiedLocationPicker`'s "App storage" row subtitle must be worded per-platform
 * (`design/ux.md` §2) at both real call sites — `App.kt`'s `AddGraphDialog` and
 * `GitSetupScreen.kt`'s `Step2RepoPath` — which both delegate to [appStorageSubtitleFor]. Mirrors
 * [addGraphFlowMode]'s pure-function test approach (see `AddGraphAppOwnedTest.kt`) since
 * `getDeviceInfo()` always resolves to the Desktop actual on the JVM target, so the platform check
 * itself must be exercised via the extracted, directly-callable `platform: String` parameter
 * rather than by mounting the real composable.
 */
class AppStorageSubtitleTest {

    @Test
    fun `appStorageSubtitleFor should ReturnAndroidCopy when platform is Android`() {
        assertEquals(
            "Kept inside SteleKit only — not visible in your device's file manager, and removed " +
                "if you uninstall the app.",
            appStorageSubtitleFor("Android"),
        )
    }

    @Test
    fun `appStorageSubtitleFor should ReturnWebCopy when platform is Web`() {
        assertEquals(PlainGraphAppOwnedWarningWebCopy, appStorageSubtitleFor("Web"))
    }

    @Test
    fun `appStorageSubtitleFor should ReturnWebCopy when platform is neither Android nor Web`() {
        // Desktop/iOS never show UnifiedLocationPicker's App-storage row today (no platform
        // currently reports "Desktop" here since fileSystem.supportsAppOwnedStorage gates the
        // whole picker) — falling through to the Web copy is the safer default over Android's.
        assertEquals(PlainGraphAppOwnedWarningWebCopy, appStorageSubtitleFor("Desktop"))
    }

    @Test
    fun `appStorageSubtitleFor should DifferBetweenAndroidAndWeb`() {
        assertNotEquals(appStorageSubtitleFor("Android"), appStorageSubtitleFor("Web"))
    }
}
