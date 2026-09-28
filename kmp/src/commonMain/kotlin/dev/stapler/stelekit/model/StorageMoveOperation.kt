// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.model

/**
 * A requested move of a graph's content from one [StorageLocation] to another, per ADR-001.
 * `Relocate` and `Link` are modeled as distinct leaves — rather than one type with a boolean
 * flag — so a `Link` operation cannot carry a delete-the-source setting that makes no sense for it.
 */
sealed interface StorageMoveOperation {
    val graphId: String
    val source: StorageLocation
    val destination: StorageLocation

    /** Copies content to [destination], verifies it, then optionally deletes it from [source]. */
    data class Relocate(
        override val graphId: String,
        override val source: StorageLocation,
        override val destination: StorageLocation,
        val deleteSourceAfterVerify: Boolean,
    ) : StorageMoveOperation

    /** Connects [destination] as a synced copy of [source]; the source is never deleted. */
    data class Link(
        override val graphId: String,
        override val source: StorageLocation,
        override val destination: StorageLocation,
    ) : StorageMoveOperation
}
