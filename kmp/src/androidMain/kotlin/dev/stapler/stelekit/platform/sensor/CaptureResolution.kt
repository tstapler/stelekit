// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.sensor

import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/** 4032x3024 (~12 MP) — matches the decode budget so capture, EXIF fix and annotation stay bounded. */
private val MAX_CAPTURE_SIZE = Size(4032, 3024)

/**
 * Caps still-capture resolution at ~12 MP. Without it MAXIMIZE_QUALITY picks the sensor's largest
 * output (up to 50-200 MP on current phones), which is slow to encode and costs 200 MB+ to decode.
 */
internal fun cappedCaptureResolutionSelector(): ResolutionSelector =
    ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(MAX_CAPTURE_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
        )
        .build()
