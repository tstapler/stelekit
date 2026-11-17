// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.RetentionPolicy
import dev.stapler.stelekit.ui.sketch.v2.BackupsSectionModel
import dev.stapler.stelekit.ui.sketch.v2.DeleteBackupsDialog
import dev.stapler.stelekit.ui.sketch.v2.RestoreCopy
import kotlinx.coroutines.launch

object BackupsSectionTags {
    const val ROW = "sketch.settings.backups"
    const val DELETE = "sketch.settings.backups.delete"
}

/** The v1 backups of every sketch project in the graph, as the one size row and one delete the settings section shows. */
class GraphBackups(private val store: () -> ProjectSidecarStore?) {
    suspend fun size(): RetentionPolicy.Size {
        val sidecar = store() ?: return EMPTY
        return sidecar.slugsOnDisk().map { sidecar.retentionSize(it) }.fold(EMPTY) { total, each ->
            RetentionPolicy.Size(
                total.backupFiles + each.backupFiles, total.backupBytes + each.backupBytes,
                total.conflictFiles + each.conflictFiles, total.conflictBytes + each.conflictBytes,
            )
        }
    }

    /** Deletes every project's `backup/`; stops at the first failure and returns it (what was already deleted stays deleted). */
    suspend fun delete(): Either<DomainError, Int> {
        val sidecar = store() ?: return 0.right()
        var deleted = 0
        for (slug in sidecar.slugsOnDisk()) {
            when (val result = sidecar.deleteBackups(slug)) {
                is Either.Left -> return result.value.left()
                is Either.Right -> deleted += result.value
            }
        }
        return deleted.right()
    }

    fun model() = BackupsSectionModel(size = ::size, delete = ::delete)

    private companion object {
        val EMPTY = RetentionPolicy.Size(0, 0, 0, 0)
    }
}

/** Settings > Sketches "Backups": the size row, and "Delete backups" behind a confirmation whose Cancel has the initial focus. */
@Composable
fun BackupsSection(model: BackupsSectionModel, modifier: Modifier = Modifier) {
    var revision by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableIntStateOf(0) }
    val confirming by model.isConfirming.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model, revision) {
        val row = model.row()
        text = row.text
        files = row.files
    }
    val rowText = text ?: return
    Column(modifier.testTag(BackupsSectionTags.ROW), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(rowText)
        if (files > 0) {
            TextButton(onClick = model::requestDelete, modifier = Modifier.testTag(BackupsSectionTags.DELETE)) { Text(RestoreCopy.DELETE_ACTION) }
        }
    }
    if (confirming) {
        DeleteBackupsDialog(onDelete = { scope.launch { model.confirmDelete(); revision++ } }, onCancel = model::cancel)
    }
}
