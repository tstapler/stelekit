// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Story 5.1.3's `beforeunload` gating decision.
 *
 * The real `shouldWarnOnUnload` lives in `dev.stapler.stelekit.browser.Main.kt` (`wasmJsMain`
 * only — it backs a `window.addEventListener("beforeunload", ...)` JS interop listener that
 * cannot run under the JVM test runner), so — per this repo's established
 * `WasmGitWriteServiceAlgorithmsTest`/`WasmSectionSyncServiceTest` precedent for wasmJsMain-only
 * orchestration logic — this test re-implements the identical one-line contract as a pure-Kotlin
 * double so it is exercisable from `commonTest`/`jvmTest` without a browser. Keep this double in
 * lockstep with the real `shouldWarnOnUnload` if that function's logic ever changes.
 *
 * Asserting `preventDefault()`/`event.returnValue` DOM behavior itself is explicitly out of scope
 * per `validation.md`'s Surface 6 note — no headless-browser harness exists in this project's
 * testing infrastructure for that; see `e2e/tests/git-tab-close-warning.spec.ts` (Playwright,
 * not built in this pass).
 */
class BeforeUnloadWarningTest {

    /** Pure-Kotlin double of `Main.kt`'s `shouldWarnOnUnload(dirtyCount: Int): Boolean = dirtyCount > 0`. */
    private fun modelledShouldWarnOnUnload(dirtyCount: Int): Boolean = dirtyCount > 0

    @Test
    fun `TC-5_1_3-A shouldWarnOnUnload is false when dirtyCount is 0`() {
        assertFalse(modelledShouldWarnOnUnload(0))
    }

    @Test
    fun `TC-5_1_3-A shouldWarnOnUnload is true when dirtyCount is 1`() {
        assertTrue(modelledShouldWarnOnUnload(1))
    }
}
