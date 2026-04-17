package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.ui.Command
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.isMobile

@Composable
fun CommandPalette(
    visible: Boolean,
    commands: List<Command>,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var searchQuery by remember { mutableStateOf("") }
    val filteredCommands = remember(searchQuery, commands) {
        if (searchQuery.isEmpty()) {
            commands
        } else {
            commands.filter { command ->
                var queryIdx = 0
                val label = command.label.lowercase()
                val query = searchQuery.lowercase()
                for (char in label) {
                    if (queryIdx < query.length && char == query[queryIdx]) {
                        queryIdx++
                    }
                }
                queryIdx == query.length
            }.sortedBy { command ->
                val label = command.label.lowercase()
                val query = searchQuery.lowercase()
                if (label.startsWith(query)) 0
                else if (label.contains(query)) 1
                else 2
            }
        }
    }

    var selectedIndex by remember(filteredCommands) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(selectedIndex) {
        if (filteredCommands.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    val isMobile = LocalWindowSizeClass.current.isMobile

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val sharedColumnModifier = Modifier
            .widthIn(max = 600.dp)
            .let { if (isMobile) it.fillMaxWidth() else it.fillMaxWidth(0.8f) }
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = false) {} // Prevent clicks from dismissing

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = if (isMobile) Alignment.BottomCenter else Alignment.TopCenter
        ) {
            Column(
                modifier = if (isMobile) {
                    sharedColumnModifier.imePadding()
                } else {
                    sharedColumnModifier.padding(top = 100.dp)
                }.onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.DirectionDown -> {
                                    if (filteredCommands.isNotEmpty()) {
                                        selectedIndex = (selectedIndex + 1) % filteredCommands.size
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (filteredCommands.isNotEmpty()) {
                                        selectedIndex = if (selectedIndex <= 0) filteredCommands.size - 1 else selectedIndex - 1
                                    }
                                    true
                                }
                                Key.Enter -> {
                                    if (filteredCommands.isNotEmpty()) {
                                        filteredCommands[selectedIndex].action()
                                        onDismiss()
                                    }
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("Search commands...") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    )
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .fillMaxWidth()
                ) {
                    itemsIndexed(filteredCommands) { index, command ->
                        CommandItem(
                            command = command,
                            isSelected = index == selectedIndex,
                            onClick = {
                                command.action()
                                onDismiss()
                            }
                        )
                    }
                }

                if (filteredCommands.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No commands found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CommandItem(
    command: Command,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = command.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )

        if (command.shortcut != null) {
            Surface(
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = command.shortcut,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
