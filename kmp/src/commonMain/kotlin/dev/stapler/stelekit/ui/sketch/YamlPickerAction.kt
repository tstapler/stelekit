// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.projectbridge.YamlImportOutcome
import dev.stapler.stelekit.projectbridge.YamlSnapshotImporter
import dev.stapler.stelekit.projectbridge.YamlSnapshotRules
import dev.stapler.stelekit.projectbridge.YamlSourceEntry
import dev.stapler.stelekit.repository.SketchRepositories

/**
 * The "choose YAML file" action (Story 5.9c). [pick] returns the picked file's text, or null when the owner cancelled or the
 * platform has no file picker; the picked path or name never leaves it, so the importer cannot store one.
 */
class YamlPickerAction(
    private val importer: YamlSnapshotImporter,
    private val repositories: SketchRepositories,
    private val pick: suspend () -> String?,
) {
    /** Returns a message for the owner, or null when the snapshot was recorded or the picker was cancelled. */
    suspend fun run(projectUuid: String): String? {
        val text = pick() ?: return null
        return YamlSnapshotRules.message(importer.import(projectUuid, text.encodeToByteArray()))
    }

    suspend fun hasSnapshot(projectUuid: String): Boolean =
        repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?.let { YamlSourceEntry.snapshotOf(it.settings) } != null
}

/** Display text for the YAML source row. */
object YamlPickerText {
    fun status(set: Boolean) = if (set) "YAML source: saved" else "YAML source: none"
    fun action(set: Boolean) = if (set) "Replace YAML file" else "Choose YAML file"
}
