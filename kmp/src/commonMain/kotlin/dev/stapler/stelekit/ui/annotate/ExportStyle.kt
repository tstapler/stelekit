// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.error.DomainError

/** Image width at which exported strokes, dots and label pills have their base sizes. */
const val EXPORT_REFERENCE_WIDTH_PX: Int = 1000

private const val MIN_EXPORT_SCALE = 0.5f

/** Multiplier for stroke, dot and label sizes so a 4000 px export looks like a 1000 px one, not hairline. */
fun exportScale(imageWidthPx: Int): Float =
    (imageWidthPx.toFloat() / EXPORT_REFERENCE_WIDTH_PX).coerceAtLeast(MIN_EXPORT_SCALE)

const val DRIVE_OFFLINE_MESSAGE = "Offline. Saved on this device. Try again when connected."
const val DRIVE_SIGN_IN_MESSAGE = "Sign in to Google Drive to export."

private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val TRANSPORT_FAILURE_STATUS = -1

/**
 * User-facing text for a failed Drive export. A transport failure arrives as `HttpError(-1)` or a
 * timeout (the client wraps connection exceptions that way), so those read as offline; a non-network
 * exception also mapped to -1 would be shown as offline too.
 */
fun driveExportMessage(error: DomainError): String = when {
    error is DomainError.NetworkError.Timeout || error is DomainError.NetworkError.CircuitOpen -> DRIVE_OFFLINE_MESSAGE
    error is DomainError.NetworkError.HttpError && error.statusCode == TRANSPORT_FAILURE_STATUS -> DRIVE_OFFLINE_MESSAGE
    error is DomainError.NetworkError.HttpError && (error.statusCode == UNAUTHORIZED || error.statusCode == FORBIDDEN) ->
        "$DRIVE_SIGN_IN_MESSAGE (${error.message})"
    else -> error.message
}
