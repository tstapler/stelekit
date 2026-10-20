// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.Settings

/**
 * Setting holding the active graph's effective notes folder (repo root + wiki subdir), never the
 * raw repo root. Reach it only through the typed accessors below, so a repo root cannot be
 * written without an explicit conversion to [EffectiveNotesPath].
 */
internal const val LAST_NOTES_PATH_KEY = "lastNotesPath"

/** Pre-rename name of [LAST_NOTES_PATH_KEY]; read-only fallback for upgrades and the sync CLI. */
private const val LEGACY_LAST_GRAPH_PATH_KEY = "lastGraphPath"

/** The last-opened graph's notes folder, or null on a fresh install. Falls back to the legacy key. */
fun Settings.lastNotesPath(): EffectiveNotesPath? =
    getString(LAST_NOTES_PATH_KEY, "").ifEmpty { getString(LEGACY_LAST_GRAPH_PATH_KEY, "") }
        .ifEmpty { null }
        ?.let(::EffectiveNotesPath)

internal fun Settings.writeLastNotesPath(path: EffectiveNotesPath) {
    if (getString(LAST_NOTES_PATH_KEY, "") != path.value) putString(LAST_NOTES_PATH_KEY, path.value)
}

/** Forgets the last-opened graph under both the current and legacy key. */
fun Settings.clearLastNotesPath() {
    putString(LAST_NOTES_PATH_KEY, "")
    putString(LEGACY_LAST_GRAPH_PATH_KEY, "")
}
