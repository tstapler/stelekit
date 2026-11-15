// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlinx.serialization.Serializable

/** Where a photo's focal length came from, ranked by how far it can be trusted (ADR-003 decision 5). */
@Serializable
enum class FocalSource {
    /** A 35 mm-equivalent value the user typed in the height-offset focal field. */
    USER_CONFIRMED,

    /** A matched camera profile; it compares EXIF with EXIF, so it adds no independent evidence. */
    PROFILE,

    /** The sidecar lens record at zoom ratio 1. */
    SIDECAR_LENS_RECORD,

    /** Only the EXIF value, or none. */
    EXIF_ONLY,
}
