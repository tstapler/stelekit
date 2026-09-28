// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

/** An in-memory dropped file with no filesystem path (browser File/Blob). */
class DroppedFileBytes(val suggestedName: String, val bytes: ByteArray) {
    // Not a data class: the generated equals/hashCode would compare `bytes` by array
    // reference, not content — content equality is what every caller actually expects.
    override fun equals(other: Any?): Boolean =
        other is DroppedFileBytes && suggestedName == other.suggestedName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * suggestedName.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "DroppedFileBytes(suggestedName=$suggestedName, bytes=${bytes.size} bytes)"
}
