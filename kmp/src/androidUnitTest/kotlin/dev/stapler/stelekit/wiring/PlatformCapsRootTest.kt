// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import dev.stapler.stelekit.platform.ml.AndroidPdfTextExtractor
import dev.stapler.stelekit.platform.ml.MlKitImageLabeler
import dev.stapler.stelekit.platform.ml.MlKitTextRecognizer
import dev.stapler.stelekit.platform.ml.androidPlatformCaps
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertIs

/**
 * The real Android root factory, not a fixture: a capable platform must get the real
 * implementations, never `NoOp*`. Construction only; no inference runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlatformCapsRootTest {
    @Test
    fun androidPlatformCaps_returnsTheRealMlKitLabelerPdfExtractorAndTextRecognizer() {
        val caps = androidPlatformCaps()

        assertIs<MlKitImageLabeler>(caps.imageLabeler)
        assertIs<AndroidPdfTextExtractor>(caps.pdfText)
        assertIs<MlKitTextRecognizer>(caps.textRecognizer)
    }
}
