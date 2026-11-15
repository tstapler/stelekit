@file:OptIn(ExperimentalMaterial3Api::class)

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.MeasurementUnit

/**
 * Background the toolbar renders on. Exposed as a constant (rather than inlined) so the
 * disabled-tint contrast ratio can be verified computationally in tests — see
 * `DisabledTintContrastTest`.
 */
internal val TOOLBAR_BACKGROUND_COLOR = Color(0xEE1A1A1A)

/**
 * Disabled-state icon/label tint. Must maintain WCAG contrast >= 4.5:1 against
 * [TOOLBAR_BACKGROUND_COLOR]. The previous value, Color(0xFF555555), measured ~2.33:1 (a
 * flagged accessibility failure); Color(0xFF9E9E9E) (Material "Grey 500") measures ~6.5:1
 * while still reading as visibly dimmer than the enabled-inactive tint.
 */
internal val DISABLED_TINT_COLOR = Color(0xFF9E9E9E)

/** Enabled-but-inactive icon and label tint. The toolbar is dark in both app themes, so this is a fixed token, not a theme colour. */
internal val TOOLBAR_INACTIVE_TINT_COLOR = Color(0xFFBBBBBB)

/** Active tool label: white on the dark toolbar. The icon fill does not extend behind the label, so `onPrimaryContainer` would be unmeasured. */
internal val TOOLBAR_ACTIVE_LABEL_COLOR = Color.White

/**
 * Fill behind the active tool icon and the checked Pan toggle. A fixed colour, not `primaryContainer`: the
 * toolbar is dark in every theme and the theme fills measured 1.85:1 against it (< 3:1) in dark and stone.
 * White content on this fill measures >= 4.5:1.
 */
internal val TOOLBAR_ACTIVE_FILL_COLOR = Color(0xFF2F7D79)

/** Zoom and pan wiring for the toolbar's second row; null hides both. */
internal class ToolbarZoom(
    val view: () -> AnnotationViewTransform,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onFit: () -> Unit,
    val panOn: Boolean,
    val onPanToggle: (Boolean) -> Unit,
    val onPan: (PanDirection) -> Unit,
    val panAtEdge: (PanDirection) -> Boolean,
    /** False when the zoom group is a rail beside the photo (window >= 600 dp). */
    val showZoomGroup: Boolean = true,
)

/** Keep tool wiring: [locked] is `stickiness == LOCKED`; null hides the toggle, the chip gestures and the TalkBack actions. */
internal class ToolbarKeep(val locked: Boolean, val onToggle: () -> Unit)

internal const val KEEP_TOOL_LABEL = "Keep tool"
internal const val RELEASE_TOOL_LABEL = "Release tool"
internal const val KEEP_TOOL_LOCKED_DESCRIPTION = "locked"
internal const val KEEP_TOOL_UNLOCKED_DESCRIPTION = "unlocked"
internal const val CALIBRATE_FIRST_DESCRIPTION = "Calibrate first"
internal const val TOOL_LOCK_BADGE_TAG = "tool-lock-badge"

/** Strip hint shown when a disabled chip is tapped before calibration. */
internal fun calibrateFirstHint(toolLabel: String) = "Calibrate first to use $toolLabel"

/** Display name of a tool, as on its chip. */
internal fun toolLabel(tool: AnnotationTool): String = TOOL_CHIPS.first { it.tool == tool }.label

/** Largest share of the window height the toolbar may take before the zoom group and pan row fold into a popup. */
internal const val TOOLBAR_MAX_HEIGHT_FRACTION = 0.4f

private val TARGET_SIZE = 48.dp

/**
 * Bottom toolbar providing tool selection, undo/redo, unit selection and annotation deletion.
 *
 * Measurement tools (DISTANCE, AREA, ANGLE, LABEL, GRID_REF) are disabled until the image is
 * calibrated ([isCalibrated] = true). SELECT and CALIBRATE are always enabled.
 */
@Composable
fun AnnotationToolbar(
    currentTool: AnnotationTool,
    canUndo: Boolean,
    canRedo: Boolean,
    displayUnit: MeasurementUnit?,
    isCalibrated: Boolean,
    onToolSelect: (AnnotationTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteSelect: () -> Unit,
    onCalibrate: () -> Unit,
    onUnitSelect: (MeasurementUnit) -> Unit,
    hasSelection: Boolean,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) = AnnotationToolbar(
    currentTool, canUndo, canRedo, displayUnit, isCalibrated, onToolSelect, onUndo, onRedo, onDeleteSelect,
    onCalibrate, onUnitSelect, hasSelection, zoom = null, modifier = modifier, showLabels = showLabels,
)

/**
 * The editor's toolbar: a wrapping row of the seven labelled chips, then a wrapping second row (undo,
 * redo, unit, delete, zoom, Pan). Neither row scrolls horizontally. The pan arrows get their own row above
 * the chips. Past [TOOLBAR_MAX_HEIGHT_FRACTION] of the window the zoom group and pan row fold into one "Zoom" popup.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnnotationToolbar(
    currentTool: AnnotationTool,
    canUndo: Boolean,
    canRedo: Boolean,
    displayUnit: MeasurementUnit?,
    isCalibrated: Boolean,
    onToolSelect: (AnnotationTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteSelect: () -> Unit,
    onCalibrate: () -> Unit,
    onUnitSelect: (MeasurementUnit) -> Unit,
    hasSelection: Boolean,
    zoom: ToolbarZoom?,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    keep: ToolbarKeep? = null,
    onDisabledToolTap: (AnnotationTool) -> Unit = {},
) {
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    // Sticky until the window or text size changes, so folding the controls away cannot flap the layout back.
    var folded by remember(windowHeightPx, density.density, density.fontScale) { mutableStateOf(false) }
    val showFoldedZoom = zoom != null && folded
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TOOLBAR_BACKGROUND_COLOR)
            .onSizeChanged {
                if (zoom != null && windowHeightPx > 0 && it.height > TOOLBAR_MAX_HEIGHT_FRACTION * windowHeightPx) folded = true
            }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (zoom != null && !showFoldedZoom) PanRow(zoom)
        ToolRow(currentTool, isCalibrated, onToolSelect, onCalibrate, showLabels, keep, onDisabledToolTap)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (keep != null && currentTool != AnnotationTool.SELECT) KeepToolToggle(keep)
            UndoRedoButtons(canUndo, canRedo, onUndo, onRedo)
            if (displayUnit != null) UnitDropdown(currentUnit = displayUnit, onUnitSelect = onUnitSelect)
            if (hasSelection) {
                IconButton(onClick = onDeleteSelect, modifier = Modifier.size(TARGET_SIZE)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete selected annotation", tint = Color(0xFFEF5350))
                }
            }
            if (zoom != null) {
                if (showFoldedZoom) {
                    FoldedZoomButton(zoom)
                } else {
                    if (zoom.showZoomGroup) AnnotationZoomControls(zoom.view, zoom.onZoomIn, zoom.onZoomOut, zoom.onFit)
                    PanToggle(zoom)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolRow(
    currentTool: AnnotationTool,
    isCalibrated: Boolean,
    onToolSelect: (AnnotationTool) -> Unit,
    onCalibrate: () -> Unit,
    showLabels: Boolean,
    keep: ToolbarKeep?,
    onDisabledToolTap: (AnnotationTool) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TOOL_CHIPS.forEach { chip ->
            ToolButton(
                tool = chip.tool,
                icon = chip.icon,
                label = chip.label,
                isActive = currentTool == chip.tool,
                onSelect = onToolSelect,
                enabled = chip.tool == AnnotationTool.SELECT || isCalibrated,
                showLabels = showLabels,
                keep = keep,
                onDisabledTap = onDisabledToolTap,
            )
        }
        CalibrateButton(onClick = onCalibrate, showLabels = showLabels)
    }
}

private class ToolChip(val tool: AnnotationTool, val icon: ImageVector, val label: String)

private val TOOL_CHIPS = listOf(
    ToolChip(AnnotationTool.SELECT, Icons.Default.PanTool, "Select"),
    ToolChip(AnnotationTool.DISTANCE, Icons.Default.LinearScale, "Distance"),
    ToolChip(AnnotationTool.AREA, Icons.Default.Crop, "Area"),
    ToolChip(AnnotationTool.ANGLE, Icons.Default.GridOn, "Angle"),
    ToolChip(AnnotationTool.LABEL, Icons.Default.Label, "Label"),
    ToolChip(AnnotationTool.GRID_REF, Icons.Default.Straighten, "Reference"),
)

@Composable
private fun UndoRedoButtons(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Undo (Ctrl+Z)") } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(TARGET_SIZE)) {
            Icon(Icons.Default.Undo, contentDescription = "Undo", tint = if (canUndo) Color.White else DISABLED_TINT_COLOR)
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Redo (Ctrl+Shift+Z)") } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(TARGET_SIZE)) {
            Icon(Icons.Default.Redo, contentDescription = "Redo", tint = if (canRedo) Color.White else DISABLED_TINT_COLOR)
        }
    }
}

/** The pan arrows, in their own row between the canvas and the chips; absent unless Pan is on and there is something to pan. */
@Composable
private fun PanRow(zoom: ToolbarZoom) {
    if (zoom.panOn && zoom.view().canPan) {
        AnnotationPanPad(atEdge = zoom.panAtEdge, onPan = zoom.onPan, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/** Pan on/off; enabled exactly when the view is at or above Fit (ADR-006 decision 2). */
@Composable
private fun PanToggle(zoom: ToolbarZoom) {
    val canPan = zoom.view().canPan
    val checked = zoom.panOn && canPan
    val tint = if (canPan) Color.White else DISABLED_TINT_COLOR
    Row(
        modifier = Modifier
            .sizeIn(minWidth = TARGET_SIZE, minHeight = TARGET_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) TOOLBAR_ACTIVE_FILL_COLOR else Color.Transparent)
            .toggleable(value = checked, enabled = canPan, role = Role.Switch, onValueChange = zoom.onPanToggle)
            .semantics { if (!canPan) stateDescription = PAN_NOTHING_TO_PAN_DESCRIPTION }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PanTool, contentDescription = null, tint = tint)
        Text("Pan", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** Over the height budget, the zoom group, Pan toggle and arrows live together in this popup. */
@Composable
private fun FoldedZoomButton(zoom: ToolbarZoom) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.sizeIn(minWidth = TARGET_SIZE, minHeight = TARGET_SIZE)) {
            Text("Zoom", color = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnnotationZoomControls(zoom.view, zoom.onZoomIn, zoom.onZoomOut, zoom.onFit)
                PanToggle(zoom)
                PanRow(zoom)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    tool: AnnotationTool,
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onSelect: (AnnotationTool) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showLabels: Boolean = true,
    keep: ToolbarKeep? = null,
    onDisabledTap: (AnnotationTool) -> Unit = {},
) {
    val bg = if (isActive && enabled) TOOLBAR_ACTIVE_FILL_COLOR else Color.Transparent
    val iconTint = when {
        isActive && enabled -> TOOLBAR_ACTIVE_LABEL_COLOR
        !enabled -> DISABLED_TINT_COLOR
        else -> TOOLBAR_INACTIVE_TINT_COLOR
    }
    // The fill sits behind the icon only, so the label is measured against the toolbar, not onPrimaryContainer.
    val labelColor = when {
        isActive && enabled -> TOOLBAR_ACTIVE_LABEL_COLOR
        !enabled -> DISABLED_TINT_COLOR
        else -> TOOLBAR_INACTIVE_TINT_COLOR
    }
    val keepable = keep != null && enabled && tool != AnnotationTool.SELECT
    val toggleKeep = keep?.onToggle
    val showLockBadge = keepable && isActive && keep?.locked == true
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("$label (${toolShortcut(tool)})") } },
        state = rememberTooltipState(),
    ) {
        // A disabled chip keeps its click so a tap can explain why; the disabled flag is semantic only.
        Column(
            modifier = modifier
                .sizeIn(minWidth = TARGET_SIZE, minHeight = TARGET_SIZE)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    role = Role.RadioButton,
                    onClick = { if (enabled) onSelect(tool) else onDisabledTap(tool) },
                    onLongClick = if (keepable) ({ toggleKeep?.invoke() }) else null,
                    onDoubleClick = if (keepable) ({ toggleKeep?.invoke() }) else null,
                )
                .semantics {
                    contentDescription = label
                    selected = isActive
                    if (!enabled) {
                        disabled()
                        stateDescription = CALIBRATE_FIRST_DESCRIPTION
                    }
                    if (keepable) {
                        val action = if (keep?.locked == true) RELEASE_TOOL_LABEL else KEEP_TOOL_LABEL
                        customActions = listOf(CustomAccessibilityAction(action) { toggleKeep?.invoke(); true })
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(TARGET_SIZE)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint)
                if (showLockBadge) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = TOOLBAR_ACTIVE_LABEL_COLOR,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp).testTag(TOOL_LOCK_BADGE_TAG),
                    )
                }
            }
            if (showLabels) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isActive && enabled) FontWeight.SemiBold else null,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/** The 48 dp "Keep tool" switch in the action row; the caller shows it only while a non-Select tool is armed. */
@Composable
private fun KeepToolToggle(keep: ToolbarKeep) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("$KEEP_TOOL_LABEL ($KEEP_TOOL_SHORTCUT_LABEL)") } },
        state = rememberTooltipState(),
    ) {
        Row(
            modifier = Modifier
                .sizeIn(minWidth = TARGET_SIZE, minHeight = TARGET_SIZE)
                .clip(RoundedCornerShape(8.dp))
                .background(if (keep.locked) TOOLBAR_ACTIVE_FILL_COLOR else Color.Transparent)
                .toggleable(value = keep.locked, role = Role.Switch, onValueChange = { keep.onToggle() })
                .semantics { stateDescription = if (keep.locked) KEEP_TOOL_LOCKED_DESCRIPTION else KEEP_TOOL_UNLOCKED_DESCRIPTION }
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (keep.locked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, tint = Color.White)
            Text(KEEP_TOOL_LABEL, style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

/** The single shortcut map: a tool key arms that tool; [KEEP_TOOL_KEY] toggles Keep tool while one is armed. */
private class ToolShortcut(val tool: AnnotationTool, val key: Key, val label: String)

private val TOOL_SHORTCUTS = listOf(
    ToolShortcut(AnnotationTool.SELECT, Key.S, "S"),
    ToolShortcut(AnnotationTool.DISTANCE, Key.D, "D"),
    ToolShortcut(AnnotationTool.AREA, Key.A, "A"),
    ToolShortcut(AnnotationTool.ANGLE, Key.G, "G"),
    ToolShortcut(AnnotationTool.LABEL, Key.L, "L"),
    ToolShortcut(AnnotationTool.GRID_REF, Key.R, "R"),
)

internal val KEEP_TOOL_KEY: Key = Key.K
private const val KEEP_TOOL_SHORTCUT_LABEL = "K"

internal fun toolForShortcutKey(key: Key): AnnotationTool? = TOOL_SHORTCUTS.firstOrNull { it.key == key }?.tool

private fun toolShortcut(tool: AnnotationTool): String = TOOL_SHORTCUTS.first { it.tool == tool }.label

@Composable
private fun CalibrateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Calibrate (C)") } },
        state = rememberTooltipState(),
    ) {
        Column(
            modifier = modifier.sizeIn(minWidth = TARGET_SIZE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = onClick, enabled = true, modifier = Modifier.size(TARGET_SIZE)) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Calibrate",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (showLabels) {
                Text(
                    text = "Calibrate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun UnitDropdown(
    currentUnit: MeasurementUnit,
    onUnitSelect: (MeasurementUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.sizeIn(minWidth = TARGET_SIZE, minHeight = TARGET_SIZE)) {
            Text(currentUnit.symbol(), color = Color.White)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MeasurementUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit.displayName()) },
                    onClick = {
                        onUnitSelect(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}
