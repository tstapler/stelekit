// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import okio.FileSystem
import okio.Path

/**
 * Strips characters that are unsafe in a filename component.
 * Retains only alphanumerics, hyphens, underscores, and dots.
 * Callers that pass an empty [fallback] should supply a non-empty default.
 */
internal fun sanitizeFileNameComponent(value: String, fallback: String = "attachment"): String {
    val sanitized = value.replace(Regex("[^A-Za-z0-9_.\\-]"), "")
    return if (sanitized.isBlank()) fallback else sanitized
}

/**
 * Returns a unique filename under [assetsDir] using a suffix-counter strategy:
 *   photo.jpg → photo-1.jpg → photo-2.jpg → …
 *
 * Checks for file existence using the provided [fileSystem] (injectable for tests).
 * The check-and-name loop must be called within a single-coroutine serialized context
 * to eliminate TOCTOU races (the caller is responsible for this).
 *
 * @param assetsDir    absolute okio [Path] to the assets directory.
 * @param stem         filename stem without extension (e.g. "photo").
 * @param ext          file extension without leading dot (e.g. "jpg").
 * @param fileSystem   okio [FileSystem] for existence checks.
 * @return             unique filename string (e.g. "photo-2.jpg").
 */
fun uniqueFileName(
    assetsDir: Path,
    stem: String,
    ext: String,
    fileSystem: FileSystem,
): String {
    val safeStem = sanitizeFileNameComponent(stem, fallback = "attachment")
    val safeExt = sanitizeFileNameComponent(ext, fallback = "")
    val base = if (safeExt.isBlank()) safeStem else "$safeStem.$safeExt"
    if (!fileSystem.exists(assetsDir / base)) return base
    var counter = 1
    while (true) {
        val candidate = if (safeExt.isBlank()) "$safeStem-$counter" else "$safeStem-$counter.$safeExt"
        if (!fileSystem.exists(assetsDir / candidate)) return candidate
        counter++
    }
}
