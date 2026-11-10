// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.fixtures

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.model.ImageAnnotation

/**
 * Synthetic Library fixture shared by the Android benchmark wrapper and the row-count test.
 * Every name is `synthetic-<n>` and every path sits under [GRAPH_PATH], so nothing here can carry
 * user content into CI artifacts (plan B.6d). The 5,000 rows each have a unique path but share
 * [DISTINCT_JPEGS] real 4000x3000 files (the instrumented side links each row to its base file).
 */
object FixtureGraphSeeds {
    const val GRAPH_PATH = "/synthetic-graph"
    const val ANNOTATION_ROWS = 5_000
    const val ASSET_ROWS = 5_000
    const val DISTINCT_JPEGS = 20
    const val JPEG_WIDTH_PX = 4_000
    const val JPEG_HEIGHT_PX = 3_000

    /** Which of the [DISTINCT_JPEGS] real files row [index] decodes. */
    fun baseIndex(index: Int): Int = index % DISTINCT_JPEGS

    fun baseName(baseIndex: Int): String = "synthetic-base-$baseIndex.jpg"

    /** Unique per row (asset_index.file_path is unique); the instrumented side links it to a base file. */
    fun rowName(index: Int): String = "synthetic-$index.jpg"

    fun imageAnnotations(count: Int = ANNOTATION_ROWS, graphPath: String = GRAPH_PATH): List<ImageAnnotation> =
        List(count) { n ->
            ImageAnnotation(
                uuid = "synthetic-img-$n",
                blockUuid = "synthetic-blk-$n",
                pageUuid = "synthetic-page-${n % 100}",
                graphPath = graphPath,
                filePath = "$graphPath/assets/${rowName(n)}",
                importedAtMs = n.toLong(),
            )
        }

    fun assetEntries(count: Int = ASSET_ROWS, graphPath: String = GRAPH_PATH): List<AssetEntry> =
        List(count) { n ->
            AssetEntry(
                uuid = AssetUuid("synthetic-asset-$n"),
                filePath = "$graphPath/assets/${rowName(n)}",
                relativePath = "assets/${rowName(n)}",
                mediaType = AssetMediaType.IMAGE,
                subfolder = "assets",
                tags = emptyList(),
                autoLabels = emptyList(),
                ocrText = null,
                cloudDescription = null,
                pageUuids = emptyList(),
                sizeBytes = 0L,
                importedAtMs = n.toLong(),
                mlProcessed = false,
                mlAttemptedAt = null,
                mlFailed = false,
                contentHash = "synthetic-hash-$n",
                isOrphan = false,
                mlTagsSource = "",
            )
        }
}
