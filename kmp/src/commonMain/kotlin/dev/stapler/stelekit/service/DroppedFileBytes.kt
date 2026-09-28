// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

/** An in-memory dropped file with no filesystem path (browser File/Blob). */
data class DroppedFileBytes(val suggestedName: String, val bytes: ByteArray)
