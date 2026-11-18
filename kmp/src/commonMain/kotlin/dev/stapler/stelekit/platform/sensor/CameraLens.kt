// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

enum class LensFacing { BACK, FRONT, EXTERNAL }

enum class LensKind { ULTRA_WIDE, WIDE, TELE, ZOOM_STOP }

/**
 * What the platform reports for one camera id (Camera2 characteristics plus CameraX zoom state).
 * Every field but the id and facing is nullable: an OEM may omit any characteristic.
 *
 * [boundPhysicalIds] holds only the physical ids whose `setPhysicalCameraId` bind succeeded at runtime;
 * the classifier never lists a physical lens that is not in it (ADR-004 decision 2).
 */
data class CameraLensInfo(
    val cameraId: String,
    val facing: LensFacing,
    val focalLengthMm: Double?,
    val sensorWidthMm: Double?,
    val sensorHeightMm: Double?,
    val physicalIds: List<String> = emptyList(),
    val physicalFocalsMm: Map<String, Double> = emptyMap(),
    val boundPhysicalIds: Set<String> = emptySet(),
    val minZoomRatio: Double? = null,
    val maxZoomRatio: Double? = null,
)

/**
 * One chip: a separately bindable camera, a bind-tested physical lens, or a zoom-ratio stop.
 * [zoomRatio] is the zoom to apply after binding (1.0 for a lens chip); [labelRatio] is the figure on the chip,
 * relative to the main lens. [optical] is false for a stop that is only a digital crop.
 */
data class CameraLens(
    val id: String,
    val cameraId: String,
    val physicalId: String?,
    val facing: LensFacing,
    val focalLengthMm: Double?,
    val focal35mmEq: Double?,
    val zoomRatio: Double,
    val labelRatio: Double,
    val isLogicalMulti: Boolean,
    val kind: LensKind,
    val optical: Boolean,
    val label: String,
    val secondaryLabel: String?,
    val hint: String?,
)

/** Stands in before the catalog has answered, or when it finds no lens: the platform's default back camera at 1x. */
fun defaultBackLens(): CameraLens = CameraLens(
    id = "default", cameraId = "", physicalId = null, facing = LensFacing.BACK, focalLengthMm = null, focal35mmEq = null,
    zoomRatio = 1.0, labelRatio = 1.0, isLogicalMulti = false, kind = LensKind.WIDE, optical = true,
    label = "1x", secondaryLabel = null, hint = null,
)
