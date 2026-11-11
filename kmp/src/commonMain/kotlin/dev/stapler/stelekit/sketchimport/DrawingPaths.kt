// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.asset.DrawingLocationRule

/** Where an imported drawing lives, shared by the importer (which writes it) and the project's sheet reader (which finds it again). */
object DrawingPaths {
    private const val HASH_PREFIX = 8

    fun directory(assetsDir: String, slug: String): String = "$assetsDir/${DrawingLocationRule.SKETCH_DIR}/$slug/drawings"

    fun file(assetsDir: String, slug: String, contentHash: String, extension: String): String =
        "${directory(assetsDir, slug)}/${contentHash.take(HASH_PREFIX)}.$extension"
}
