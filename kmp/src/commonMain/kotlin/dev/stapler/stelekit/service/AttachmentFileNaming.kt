// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import okio.FileSystem
import okio.Path

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
 * @param fileSystem   okio [FileSystem] for existence checks (default: [FileSystem.SYSTEM]).
 * @return             unique filename string (e.g. "photo-2.jpg").
 */
fun uniqueFileName(
    assetsDir: Path,
    stem: String,
    ext: String,
    fileSystem: FileSystem = FileSystem.SYSTEM
): String {
    val base = if (ext.isBlank()) stem else "$stem.$ext"
    if (!fileSystem.exists(assetsDir / base)) return base
    var counter = 1
    while (true) {
        val candidate = if (ext.isBlank()) "$stem-$counter" else "$stem-$counter.$ext"
        if (!fileSystem.exists(assetsDir / candidate)) return candidate
        counter++
    }
}
