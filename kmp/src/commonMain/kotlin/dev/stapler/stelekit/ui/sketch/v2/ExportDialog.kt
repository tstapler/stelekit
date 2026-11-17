// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportFormat
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportRequest
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportScope
import dev.stapler.stelekit.sketch.v2.runtime.export.ExportStyle
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Export as Strings

object ExportTags {
    const val DIALOG = "sketch.export"
    const val ERROR = "sketch.export.error"
    const val PREVIEW = "sketch.export.preview"
    const val FLATTEN = "sketch.export.flatten"
    const val EXPORT = "sketch.export.go"
    const val CANCEL = "sketch.export.cancel"
    const val USE_1X = "sketch.export.use1x"
    const val RETRY = "sketch.export.retry"
    const val ANOTHER_LOCATION = "sketch.export.location"
    const val TRY_AGAIN = "sketch.export.tryagain"
    const val BACKGROUND = "sketch.export.background"
    fun format(format: ExportFormat) = "sketch.export.format.${format.name.lowercase()}"
    fun scope(scope: ExportScope) = "sketch.export.scope.${scope.name.lowercase()}"
    fun scale(scale: Int) = "sketch.export.scale.$scale"
    fun style(style: ExportStyle) = "sketch.export.style.${style.name.lowercase()}"
    fun frame(index: Int) = "sketch.export.frame.$index"
}

private const val TARGET_DP = 44

/**
 * The export dialog (ux.md section 12, Stories 5.2.3a and b): format, scope, scale, background, style and a preview; an
 * impossible export keeps it open with the reason and its one-tap fix. State and behaviour live in [controller].
 */
@Composable
fun ExportDialog(controller: ExportDialogController) {
    val state by controller.state.collectAsState()
    val open = state as? ExportUiState.Open ?: return
    val model = controller.model() ?: return
    val request = open.request
    AlertDialog(
        onDismissRequest = controller::cancel,
        modifier = Modifier.testTag(ExportTags.DIALOG),
        title = { Text(Strings.TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                open.error?.let { ErrorPanel(it, controller) }
                FormatOptions(model, request, controller::update)
                if (model.usesScope(request.format)) ScopeOptions(model, request, controller::update)
                if (model.usesScale(request.format)) ScaleOptions(request, controller::update)
                if (model.usesBackground(request.format)) BackgroundOption(request, controller::update)
                if (model.usesStyle(request.format)) StyleOptions(request, controller::update)
                if (model.showsFlattenedNotice(request)) Text(Strings.LAYERS_FLATTENED, Modifier.testTag(ExportTags.FLATTEN), style = MaterialTheme.typography.bodySmall)
                PreviewLine(open.preview)
            }
        },
        dismissButton = { DialogButton(Strings.CANCEL, ExportTags.CANCEL, controller::cancel) },
        confirmButton = { DialogButton(Strings.EXPORT_BUTTON, ExportTags.EXPORT, controller::export, enabled = open.canExport) },
    )
}

@Composable
private fun FormatOptions(model: ExportDialogModel, request: ExportRequest, update: (ExportRequest) -> Unit) {
    OptionGroup(Strings.FORMAT) {
        for (format in ExportFormat.entries) {
            Option(
                label = formatLabel(format),
                selected = request.format == format,
                reason = model.disabledReason(format),
                tag = ExportTags.format(format),
                onSelect = { update(request.copy(format = format)) },
            )
        }
    }
}

@Composable
private fun ScopeOptions(model: ExportDialogModel, request: ExportRequest, update: (ExportRequest) -> Unit) {
    OptionGroup(Strings.SCOPE) {
        for (scope in ExportScope.entries) {
            Option(
                label = scopeLabel(scope),
                selected = request.scope == scope,
                reason = model.disabledReason(scope),
                tag = ExportTags.scope(scope),
                onSelect = { update(request.copy(scope = scope)) },
            )
        }
        if (request.scope == ExportScope.Frame) {
            model.frames.forEachIndexed { index, frame ->
                Option(frame.label, request.frameId == frame.id, null, ExportTags.frame(index)) { update(request.copy(frameId = frame.id)) }
            }
        }
    }
}

@Composable
private fun ScaleOptions(request: ExportRequest, update: (ExportRequest) -> Unit) {
    OptionGroup(Strings.SCALE) {
        for (scale in SCALES) {
            Option(scaleLabel(scale), request.scale == scale, null, ExportTags.scale(scale)) { update(request.copy(scale = scale)) }
        }
    }
}

@Composable
private fun BackgroundOption(request: ExportRequest, update: (ExportRequest) -> Unit) {
    Row(
        Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(ExportTags.BACKGROUND)
            .toggleable(value = request.background, role = Role.Checkbox) { update(request.copy(background = it)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = request.background, onCheckedChange = null)
        Text(Strings.BACKGROUND, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun StyleOptions(request: ExportRequest, update: (ExportRequest) -> Unit) {
    OptionGroup(Strings.STYLE) {
        for (style in ExportStyle.entries) {
            Option(styleLabel(style), request.style == style, null, ExportTags.style(style)) { update(request.copy(style = style)) }
        }
    }
}

@Composable
private fun PreviewLine(preview: ExportPreview) {
    val text = (preview as? ExportPreview.Size)?.text ?: return
    Text("${Strings.PREVIEW}: $text", Modifier.testTag(ExportTags.PREVIEW), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ErrorPanel(error: ExportError, controller: ExportDialogController) {
    Column(Modifier.testTag(ExportTags.ERROR).semantics { liveRegion = LiveRegionMode.Assertive }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(error.message, color = MaterialTheme.colorScheme.error)
        Row {
            when (error) {
                is ExportError.TooLarge -> DialogButton(Strings.USE_1X, ExportTags.USE_1X, controller::useOneX)
                ExportError.Unwritable -> DialogButton(Strings.CHOOSE_ANOTHER_LOCATION, ExportTags.ANOTHER_LOCATION, controller::retry)
                ExportError.Blocked -> DialogButton(Strings.TRY_AGAIN, ExportTags.TRY_AGAIN, controller::retry)
                ExportError.EncodeFailed, ExportError.Unexpected -> DialogButton(Strings.RETRY, ExportTags.RETRY, controller::retry)
                ExportError.NothingToExport -> Unit
            }
        }
    }
}

@Composable
private fun OptionGroup(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** One radio option. A disabled one shows its [reason] beside it and in its state description, so the reason is never hover-only. */
@Composable
private fun Option(label: String, selected: Boolean, reason: String?, tag: String, onSelect: () -> Unit) {
    val enabled = reason == null
    Row(
        Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(tag)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .semantics { reason?.let { stateDescription = it } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 4.dp, end = 8.dp)) {
            Text(label)
            reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private val SCALES = listOf(1, 2, 3)

private fun formatLabel(format: ExportFormat): String = when (format) {
    ExportFormat.Png -> Strings.FORMAT_PNG
    ExportFormat.Svg -> Strings.FORMAT_SVG
    ExportFormat.Excalidraw -> Strings.FORMAT_EXCALIDRAW
    ExportFormat.Library -> Strings.FORMAT_LIBRARY
    ExportFormat.Csv -> Strings.FORMAT_CSV
}

private fun scopeLabel(scope: ExportScope): String = when (scope) {
    ExportScope.Whole -> Strings.SCOPE_WHOLE
    ExportScope.Selection -> Strings.SCOPE_SELECTION
    ExportScope.Frame -> Strings.SCOPE_FRAME
}

private fun scaleLabel(scale: Int): String = when (scale) {
    1 -> Strings.SCALE_1X
    2 -> Strings.SCALE_2X
    else -> Strings.SCALE_3X
}

private fun styleLabel(style: ExportStyle): String = when (style) {
    ExportStyle.AsDrawn -> Strings.STYLE_AS_DRAWN
    ExportStyle.Clean -> Strings.STYLE_CLEAN
}
