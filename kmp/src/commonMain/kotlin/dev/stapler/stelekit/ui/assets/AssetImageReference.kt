// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.assets

import dev.stapler.stelekit.asset.AssetEntry

private const val GRAPH_ASSET_PREFIX = "../assets/"
private const val URI_SCHEME_SEPARATOR = "://"

/** The reference [dev.stapler.stelekit.ui.components.rememberTieredImageModel] resolves tiers from. */
internal fun AssetEntry.imageReference(): String = assetImageReference(relativePath, filePath)

internal fun assetImageReference(relativePath: String, filePath: String): String =
    if (relativePath.startsWith(GRAPH_ASSET_PREFIX)) relativePath else filePath

/**
 * Makes a bare absolute path (an original or a cached derivative) loadable by Coil; graph-asset
 * references and URIs (`saf://`, `content://`, `file://`, `http(s)://`) already are.
 */
internal fun coilModelFor(model: String): String =
    if (model.startsWith(GRAPH_ASSET_PREFIX) || model.contains(URI_SCHEME_SEPARATOR)) model else "file://$model"
