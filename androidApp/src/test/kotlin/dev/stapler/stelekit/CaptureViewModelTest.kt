// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves AC6's scope boundary: manually-typed text (routed through [CaptureViewModel.updateText])
 * is never passed through [CaptureActivity.normalizeShareWhitespace] — only share-sourced text
 * (routed through [CaptureActivity.buildShareText]) is normalized.
 *
 * `application = Application::class` overrides the manifest-declared `SteleKitApplication`
 * (`AndroidManifest.xml` sets `android:name`) with a plain Application, since only `updateText()`
 * is exercised here and `save()` (which needs `SteleKitApplication`'s `GraphManager`) is never
 * called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CaptureViewModelTest {

    @Test
    fun `updateText does not normalize manually typed whitespace`() {
        val viewModel = CaptureViewModel(ApplicationProvider.getApplicationContext())

        val rawText = "raw   text\u00A0here"
        viewModel.updateText(rawText)

        assertEquals(rawText, viewModel.captureText.value)
    }
}
