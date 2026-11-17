// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.ui.sketch.rememberModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorAction
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorDetails
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorModel
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorScreen
import dev.stapler.stelekit.ui.sketch.v2.migration.PendingModel
import dev.stapler.stelekit.ui.sketch.v2.migration.PendingScreen
import kotlinx.coroutines.Dispatchers

/**
 * Where the app opens a project's sheet (Story 6.2.0b): the router's outcome for the sheet decides between the v2 editor
 * ([SketchEditorV2Host]), the no-editor and pending screens, and [legacy] for a classic sheet. [legacy] is composed only for a
 * classic outcome, so the classic editor never takes the edit lease of a sheet the new editor holds. With no [deps] (a platform
 * without the sketch project bridge) the classic editor opens as it always did.
 */
@Composable
fun SketchEditorV2Route(
    deps: V2EditorDeps?,
    projectUuid: ProjectUuid?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pointer: PointerClass = PointerClass.MouseOnly,
    legacy: @Composable () -> Unit,
) {
    val environment = rememberModernPreviewEnvironment()
    val opener = remember(deps) { deps?.let { V2EditorOpener(it, Dispatchers.Default) } }
    var attempt by remember(projectUuid) { mutableIntStateOf(0) }
    var opening by remember(projectUuid, deps) { mutableStateOf<EditorOpening?>(if (deps == null) EditorOpening.Legacy else null) }
    LaunchedEffect(projectUuid, deps, attempt) { opener?.let { opening = it.open(projectUuid, environment.metrics) } }
    when (val current = opening) {
        null -> SketchEditorLoading(modifier)
        EditorOpening.Legacy -> legacy()
        EditorOpening.Pending -> PendingScreen(PendingModel.at(0), onClose = onBack, modifier = modifier)
        is EditorOpening.NoEditor -> NoEditor(current, onBack, { attempt += 1 }, modifier)
        is EditorOpening.Editor -> SketchEditorV2Host(current.session, environment, onBack, modifier, pointer)
    }
}

@Composable
private fun NoEditor(opening: EditorOpening.NoEditor, onBack: () -> Unit, onReload: () -> Unit, modifier: Modifier = Modifier) {
    val model = NoEditorModel.of(opening.reason, hasBackup = false)
    val clipboard = LocalClipboardManager.current
    NoEditorScreen(
        model,
        onAction = { action ->
            when (action) {
                NoEditorAction.Close -> onBack()
                NoEditorAction.Reload -> onReload()
                NoEditorAction.CopyDetails ->
                    clipboard.setText(AnnotatedString(NoEditorDetails(opening.sketchName, opening.projectName, SCHEMA_V2, PLATFORM_UNKNOWN).text(model)))
                else -> Unit
            }
        },
        modifier = modifier,
    )
}

private const val SCHEMA_V2 = "2"
private const val PLATFORM_UNKNOWN = ""
