// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.Serializable

/**
 * Small metadata envelope persisted alongside the opaque `FileSystemDirectoryHandle` stored in
 * IndexedDB (`HostDirectoryInterop.kt`'s `idbPutHandle`/`idbGetHandle`) — so debugging/display
 * has `graphId`/`dirName`/`storedAtMillis` available without deserializing the handle object
 * itself, which is a structured-clone-only opaque value with no meaningful JSON shape.
 *
 * Colocated with [DirtySetMarker] since both are small persisted-JSON envelopes for this and
 * neighboring projects, though this one is IndexedDB- not OPFS-backed. Encode/decode with the
 * existing [gitApiJson] instance — no new `Json` configuration needed.
 */
@Serializable
data class HostHandleEnvelope(
    val graphId: String,
    val dirName: String,
    val storedAtMillis: Long,
)
