// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import kotlin.test.Test
import kotlin.test.assertTrue

/** Task 6.2.0b: a banner row the editor raises is refused when its fixed strings break the text budget, so each one is checked here. */
class SketchEditorStringsTest {
    @Test
    fun everyRowTheEditorOwns_should_FitTheBannerTextBudget() {
        val rows = listOf(BannerRowId.SaveFailed, BannerRowId.SaveConflict, BannerRowId.ReadOnly, BannerRowId.LeaseConflict)
        for (row in rows) {
            val candidate = BannerCandidate.of(row, SketchEditorStrings.Banners, "details")
            assertTrue(candidate.isRight(), "$row does not fit the banner budget: ${candidate.leftOrNull()}")
        }
    }
}
