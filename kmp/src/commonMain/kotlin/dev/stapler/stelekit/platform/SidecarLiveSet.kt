// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

/**
 * The files of the sidecar root a preloading file system reads into memory (D-143): each project's
 * `<slug>/project-model.json` and `<slug>/sheets/<name>.json`. Everything else under the root (`backup/`, `conflicts/`,
 * `sources/`, `exports/`, `preview.svg`, temp files) is listed by name only and read on demand.
 */
object SidecarLiveSet {
    /** Equal to `FileProjectSidecarStore.ROOT_DIR` (asserted by `SidecarLiveSetTest`; `platform` does not import `projectbridge`). */
    const val ROOT_DIR = "assets/stelekit-sketch"
    private const val MODEL_FILE = "project-model.json"
    private const val SHEETS_DIR = "sheets"
    private const val JSON_SUFFIX = ".json"
    private const val MODEL_DEPTH = 2
    private const val SHEET_DEPTH = 3

    /** The sidecar root of the graph at [graphPath]. */
    fun rootOf(graphPath: String): String = "${graphPath.trimEnd('/')}/$ROOT_DIR"

    /** True when [path] is [graphPath]'s sidecar root or lies under it. */
    fun isUnderRoot(graphPath: String, path: String): Boolean {
        val root = rootOf(graphPath)
        return path == root || path.startsWith("$root/")
    }

    /** True when [path] (absolute) is a live file of the sidecar root of [graphPath]. */
    fun isLive(graphPath: String, path: String): Boolean {
        val prefix = rootOf(graphPath) + "/"
        if (!path.startsWith(prefix)) return false
        val parts = path.removePrefix(prefix).split('/')
        if (parts.any { it.isEmpty() }) return false
        return when (parts.size) {
            MODEL_DEPTH -> parts[1] == MODEL_FILE
            SHEET_DEPTH -> parts[1] == SHEETS_DIR && parts[2].endsWith(JSON_SUFFIX)
            else -> false
        }
    }
}
