package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.ui.screens.FormatAction

/**
 * Specification for a single formatting-overflow-row button.
 *
 * Extracted per `project_plans/rich-editing-experience/implementation/architecture-review.md`'s
 * nitpick threshold ("extract once a 4th call site needs the full icon+label+shortcut+enabled+
 * onClick tuple") — the formatting overflow row grew to 11 near-identical `TextButton` call
 * sites, well past that trigger. [label] carries the composable content (glyph/text styling
 * varies per button — bold "B", italic "I", monospace "</>", etc.) rather than a plain string,
 * since no single font-weight/style/family/color shape covers all 11 buttons.
 */
private data class FormatButtonSpec(
    val description: String,
    val onClick: () -> Unit,
    val label: @Composable () -> Unit
)

/**
 * ux.md (i)/criterion 14's exact wording for the Undo/Redo disabled state while a
 * [dev.stapler.stelekit.ui.DiskConflict] is pending resolution.
 */
private const val DiskConflictBlockedTooltip = "Resolve file conflict to continue"

@Composable
private fun FormatToolbarButton(spec: FormatButtonSpec) {
    TextButton(
        onClick = spec.onClick,
        modifier = Modifier.semantics { contentDescription = spec.description }
    ) {
        spec.label()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileBlockToolbar(
    editingBlockId: String?,
    onIndent: (String) -> Unit,
    onOutdent: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onAddBlock: (String) -> Unit = {},
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    /**
     * (i)/ux.md criterion 14: true while a [dev.stapler.stelekit.ui.DiskConflict] is pending
     * resolution. Undo/Redo render `enabled = false` with a "Resolve file conflict to continue"
     * tooltip/label instead of their normal Undo/Redo affordance, matching
     * `AnnotationToolbar`'s existing `canUndo`/`canRedo` grayed-disabled visual language. Callers
     * must pass a live value (e.g. `collectAsState()`-derived) so Undo/Redo re-enable
     * automatically once the conflict resolves — never a cached/stale snapshot.
     */
    hasDiskConflictPending: Boolean = false,
    onFormat: (FormatAction) -> Unit = {},
    onLinkPicker: (() -> Unit)? = null,
    onSuggestTags: (() -> Unit)? = null,
    onAttachImage: (() -> Unit)? = null,
    onCaptureImage: (() -> Unit)? = null,
    /** GAP-005 (Story D.2.1): toggles the currently-editing block's TODO/DOING/DONE marker. */
    onTodoToggle: () -> Unit = {},
    /** GAP-011 / Story D.4.2: explicit, discoverable, non-long-press entry point into block
     * multi-select — additive fallback alongside the existing long-press entry (`BlockItem.kt`),
     * not a replacement for it. */
    onEnterSelectionMode: (String) -> Unit = {},
    isInSelectionMode: Boolean = false,
    selectedCount: Int = 0,
    onCopyBlocks: () -> Unit = {},
    onCutBlocks: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    clipboardEmpty: Boolean = true,
    onPaste: () -> Unit = {},
    onClearClipboard: () -> Unit = {},
    isLeftHanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var formattingExpanded by remember { mutableStateOf(false) }

    // focusProperties(canFocus=false) prevents toolbar buttons from stealing focus
    // from the active BasicTextField when clicked.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp
    ) {
        if (isInSelectionMode) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row {
                    IconButton(onClick = onCopyBlocks) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy selected")
                    }
                    IconButton(onClick = onCutBlocks) {
                        Icon(Icons.Default.ContentCut, contentDescription = "Cut selected")
                    }
                    IconButton(onClick = onDeleteSelected) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                    }
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Default.Close, contentDescription = "Clear selection")
                    }
                }
            }
        } else {
            Column {
                // Formatting overflow row — shown when expanded and editing
                if (formattingExpanded && editingBlockId != null) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val formattingButtons = listOf(
                            FormatButtonSpec(
                                description = "Bold",
                                onClick = { onFormat(FormatAction.BOLD) }
                            ) {
                                Text("B", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            },
                            FormatButtonSpec(
                                description = "Italic",
                                onClick = { onFormat(FormatAction.ITALIC) }
                            ) {
                                Text(
                                    "I", fontWeight = FontWeight.Normal, fontSize = 16.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            },
                            FormatButtonSpec(
                                description = "Strikethrough",
                                onClick = { onFormat(FormatAction.STRIKETHROUGH) }
                            ) {
                                Text(
                                    "S", fontSize = 16.sp,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                )
                            },
                            FormatButtonSpec(
                                description = "Code",
                                onClick = { onFormat(FormatAction.CODE) }
                            ) {
                                Text(
                                    "</>", fontSize = 14.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            },
                            FormatButtonSpec(
                                description = "Highlight",
                                onClick = { onFormat(FormatAction.HIGHLIGHT) }
                            ) {
                                Text("H", fontSize = 16.sp, color = MaterialTheme.colorScheme.tertiary)
                            },
                            FormatButtonSpec(
                                description = "Quote",
                                onClick = { onFormat(FormatAction.QUOTE) }
                            ) {
                                Text(
                                    ">_", fontSize = 14.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            },
                            FormatButtonSpec(
                                description = "Numbered list",
                                onClick = { onFormat(FormatAction.NUMBERED_LIST) }
                            ) {
                                Text(
                                    "1.", fontSize = 14.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            },
                            FormatButtonSpec(
                                description = "Heading",
                                onClick = { onFormat(FormatAction.HEADING) }
                            ) {
                                Text("H1", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            },
                            // GAP-005 / D.2.1 (design/ux.md surface (a)): TODO is modeled via the
                            // dedicated TodoState/applyTodoToggle plumbing (Phase C.1), not a
                            // FormatAction case — dispatched through onTodoToggle, not onFormat.
                            FormatButtonSpec(
                                description = "Toggle TODO",
                                onClick = onTodoToggle
                            ) {
                                Text("☑ TODO", fontSize = 13.sp)
                            },
                            // GAP-008 / D.2.1: surfaces FormatAction.CODE_BLOCK (Phase C.2.1) to the
                            // mobile toolbar — previously keyboard-only.
                            FormatButtonSpec(
                                description = "Code block",
                                onClick = { onFormat(FormatAction.CODE_BLOCK) }
                            ) {
                                Text(
                                    "{ } Code block", fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            },
                            // GAP-009 / D.2.1: surfaces FormatAction.TABLE_INSERT (Phase C.2.2) to the
                            // mobile toolbar — previously no insertion affordance existed anywhere.
                            FormatButtonSpec(
                                description = "Table",
                                onClick = { onFormat(FormatAction.TABLE_INSERT) }
                            ) {
                                Text("▦ Table", fontSize = 13.sp)
                            }
                        )
                        formattingButtons.forEach { spec -> FormatToolbarButton(spec) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }

                // Primary actions row — only shown while editing a block
                if (editingBlockId != null) {
                    val overflowButton: @Composable () -> Unit = {
                        IconButton(
                            onClick = { formattingExpanded = !formattingExpanded },
                            modifier = Modifier.semantics { contentDescription = "Toggle formatting" }
                        ) {
                            Icon(
                                Icons.Default.MoreHoriz,
                                contentDescription = null,
                                tint = if (formattingExpanded)
                                    MaterialTheme.colorScheme.primary
                                else
                                    LocalContentColor.current
                            )
                        }
                    }
                    val primaryActions: @Composable RowScope.() -> Unit = {
                        IconButton(
                            onClick = { onOutdent(editingBlockId) },
                            modifier = Modifier.semantics { contentDescription = "Outdent" }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, contentDescription = null)
                        }
                        IconButton(
                            onClick = { onIndent(editingBlockId) },
                            modifier = Modifier.semantics { contentDescription = "Indent" }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, contentDescription = null)
                        }
                        TextButton(
                            onClick = { if (onLinkPicker != null) onLinkPicker() else onFormat(FormatAction.LINK) },
                            modifier = Modifier.semantics { contentDescription = "Insert wiki link" }
                        ) {
                            Text(
                                "[[]]", fontSize = 14.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        if (onSuggestTags != null) {
                            IconButton(
                                onClick = onSuggestTags,
                                modifier = Modifier.semantics { contentDescription = "Suggest tags" }
                            ) {
                                Icon(Icons.Default.Label, contentDescription = null)
                            }
                        }
                        if (onAttachImage != null) {
                            IconButton(
                                onClick = onAttachImage,
                                modifier = Modifier.semantics { contentDescription = "Attach image" }
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null)
                            }
                        }
                        if (onCaptureImage != null) {
                            IconButton(
                                onClick = onCaptureImage,
                                modifier = Modifier.semantics { contentDescription = "Capture photo" }
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLeftHanded) {
                            Row(verticalAlignment = Alignment.CenterVertically) { primaryActions() }
                            overflowButton()
                        } else {
                            overflowButton()
                            Row(verticalAlignment = Alignment.CenterVertically) { primaryActions() }
                        }
                    }
                }

                // Second row: undo/redo (always visible) + structural actions (editing only)
                val undoRedo: @Composable RowScope.() -> Unit = {
                    val undoDescription = if (hasDiskConflictPending) {
                        DiskConflictBlockedTooltip
                    } else {
                        "Undo"
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(undoDescription) } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = onUndo, enabled = !hasDiskConflictPending) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = undoDescription)
                        }
                    }
                    val redoDescription = if (hasDiskConflictPending) {
                        DiskConflictBlockedTooltip
                    } else {
                        "Redo"
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(redoDescription) } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = onRedo, enabled = !hasDiskConflictPending) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = redoDescription)
                        }
                    }
                }
                val structuralActions: @Composable RowScope.() -> Unit = {
                    if (editingBlockId != null) {
                        IconButton(onClick = { onMoveUp(editingBlockId) }) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                        }
                        IconButton(onClick = { onMoveDown(editingBlockId) }) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                        }
                        IconButton(onClick = { onAddBlock(editingBlockId) }) {
                            Icon(Icons.Default.Add, contentDescription = "New Block")
                        }
                        // GAP-011 / Story D.4.2: explicit, non-long-press entry point into
                        // multi-select — for mouse-only desktop sessions and assistive-tech users
                        // who cannot perform a long-press. Additive alongside the existing
                        // long-press entry (BlockItem.kt), which is unchanged.
                        IconButton(onClick = { onEnterSelectionMode(editingBlockId) }) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Select blocks")
                        }
                    }
                    if (!clipboardEmpty) {
                        IconButton(onClick = onPaste) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                        IconButton(onClick = onClearClipboard) {
                            Icon(Icons.Default.Close, contentDescription = "Clear clipboard")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .padding(4.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isLeftHanded) {
                        Row { structuralActions() }
                        Row { undoRedo() }
                    } else {
                        Row { undoRedo() }
                        Row { structuralActions() }
                    }
                }
            }
        }
    }
}
