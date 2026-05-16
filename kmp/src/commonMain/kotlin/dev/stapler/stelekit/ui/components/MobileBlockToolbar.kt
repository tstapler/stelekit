package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
    onFormat: (FormatAction) -> Unit = {},
    onLinkPicker: (() -> Unit)? = null,
    onAttachImage: (() -> Unit)? = null,
    isInSelectionMode: Boolean = false,
    selectedCount: Int = 0,
    onDeleteSelected: () -> Unit = {},
    onClearSelection: () -> Unit = {},
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
                        TextButton(
                            onClick = { onFormat(FormatAction.BOLD) },
                            modifier = Modifier.semantics { contentDescription = "Bold" }
                        ) {
                            Text("B", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        TextButton(
                            onClick = { onFormat(FormatAction.ITALIC) },
                            modifier = Modifier.semantics { contentDescription = "Italic" }
                        ) {
                            Text(
                                "I", fontWeight = FontWeight.Normal, fontSize = 16.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                        TextButton(
                            onClick = { onFormat(FormatAction.STRIKETHROUGH) },
                            modifier = Modifier.semantics { contentDescription = "Strikethrough" }
                        ) {
                            Text(
                                "S", fontSize = 16.sp,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                            )
                        }
                        TextButton(
                            onClick = { onFormat(FormatAction.CODE) },
                            modifier = Modifier.semantics { contentDescription = "Code" }
                        ) {
                            Text(
                                "</>", fontSize = 14.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        TextButton(
                            onClick = { onFormat(FormatAction.HIGHLIGHT) },
                            modifier = Modifier.semantics { contentDescription = "Highlight" }
                        ) {
                            Text("H", fontSize = 16.sp, color = MaterialTheme.colorScheme.tertiary)
                        }
                        TextButton(
                            onClick = { onFormat(FormatAction.QUOTE) },
                            modifier = Modifier.semantics { contentDescription = "Quote" }
                        ) {
                            Text(">_", fontSize = 14.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        TextButton(
                            onClick = { onFormat(FormatAction.NUMBERED_LIST) },
                            modifier = Modifier.semantics { contentDescription = "Numbered list" }
                        ) {
                            Text("1.", fontSize = 14.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        TextButton(
                            onClick = { onFormat(FormatAction.HEADING) },
                            modifier = Modifier.semantics { contentDescription = "Heading" }
                        ) {
                            Text("H1", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
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
                        if (onAttachImage != null) {
                            IconButton(
                                onClick = onAttachImage,
                                modifier = Modifier.semantics { contentDescription = "Attach image" }
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null)
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
                    IconButton(onClick = onUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = onRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
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
