// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import kotlinx.serialization.Serializable

@Serializable
data class PendingCaptureFile(
    val captureId: String,
    val text: String,
    val capturedAt: String
)
