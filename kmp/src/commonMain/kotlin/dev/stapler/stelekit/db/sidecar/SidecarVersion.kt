// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.PlaneKind

/**
 * The one place that decides a sidecar's `schemaVersion` (ADR-003 decision 7): 2 only when the file carries
 * data an older reader would not understand, so files without such data stay byte-identical to v0.113.3's.
 * New v2 keys (E4's lens record) add their condition to [forContent] instead of re-implementing the rule.
 */
object SidecarVersion {
    const val V1 = 1
    const val V2 = 2

    fun forContent(annotation: SidecarImageAnnotation, measurements: List<SidecarMeasurement>): Int =
        if (hasV2Calibration(annotation) || annotation.lensRecord != null || measurements.any(::hasV2Value)) V2 else V1

    private fun hasV2Calibration(annotation: SidecarImageAnnotation): Boolean {
        val geometry = annotation.calibrationGeometry ?: return false
        return geometry.constraints.isNotEmpty() || geometry.fit != null ||
            (geometry.plane?.kind ?: PlaneKind.PAPER_REFERENCE) != PlaneKind.PAPER_REFERENCE
    }

    private fun hasV2Value(m: SidecarMeasurement): Boolean =
        m.valueSource != SidecarMeasurement.SOURCE_COMPUTED || m.originalValueMeters != null
}
