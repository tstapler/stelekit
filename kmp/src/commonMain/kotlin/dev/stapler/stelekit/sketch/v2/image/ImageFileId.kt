// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.image

/** An image `fileId` is the lowercase hex SHA-256 of its bytes; anything else (such as `../../x`) never names a file. */
object ImageFileId {
    private const val LENGTH = 64

    fun isValid(id: String): Boolean = id.length == LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }
}
