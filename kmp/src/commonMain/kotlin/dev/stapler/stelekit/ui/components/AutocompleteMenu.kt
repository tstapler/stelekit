package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import dev.stapler.stelekit.ui.screens.SearchResultItem

@Composable
fun AutocompleteMenu(
    items: List<SearchResultItem>,
    selectedIndex: Int,
    onItemSelected: (SearchResultItem) -> Unit,
    onDismiss: () -> Unit,
    cursorRect: Rect?,
    filterText: String = "",
    isFilterActive: Boolean = false,
    onFilterTextChange: (String) -> Unit = {},
    onFilterFocusChange: (Boolean) -> Unit = {},
    onDeactivateFilter: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (items.isEmpty() || cursorRect == null) return

    val listState = rememberLazyListState()
    val filterFocusRequester = remember { FocusRequester() }

    // Scroll selected item into view whenever selectedIndex changes
    LaunchedEffect(selectedIndex) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex.coerceIn(0, items.lastIndex))
        }
    }

    // Auto-focus the filter field when filter mode activates
    LaunchedEffect(isFilterActive) {
        if (isFilterActive) {
            try { filterFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
        }
    }

    val popupPositionProvider = remember(cursorRect) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = cursorRect.left.toInt() + anchorBounds.left
                val yBelow = cursorRect.bottom.toInt() + anchorBounds.top + 10
                val yAbove = cursorRect.top.toInt() + anchorBounds.top - popupContentSize.height - 10
                val y = if (yBelow + popupContentSize.height > windowSize.height) {
                    yAbove.coerceAtLeast(0)
                } else {
                    yBelow
                }
                return IntOffset(x, y)
            }
        }
    }

    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = modifier
                .width(320.dp)
                .heightIn(max = 320.dp)
                .shadow(4.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column {
                // Filter input row (shown when filter mode is active)
                if (isFilterActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Filter: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BasicTextField(
                            value = filterText,
                            onValueChange = onFilterTextChange,
                            textStyle = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(filterFocusRequester)
                                .onFocusChanged { onFilterFocusChange(it.isFocused) }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.Escape -> {
                                                onDeactivateFilter()
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                        )
                        if (filterText.isNotEmpty()) {
                            Text(
                                text = "${items.count { it !is SearchResultItem.CreatePageItem }} match${if (items.count { it !is SearchResultItem.CreatePageItem } != 1) "es" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                LazyColumn(state = listState) {
                    itemsIndexed(items) { index, item ->
                        AutocompleteItem(
                            item = item,
                            isSelected = index == selectedIndex,
                            onClick = { onItemSelected(item) }
                        )
                    }
                }

                // Footer hint when filter is not active
                if (!isFilterActive) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Text(
                        text = "Tab — filter results  •  Ctrl+Enter — create new page",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AutocompleteItem(
    item: SearchResultItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surface

    val contentColor = if (isSelected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        when (item) {
            is SearchResultItem.Header -> {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is SearchResultItem.PageItem -> {
                Text(
                    text = item.page.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 1
                )
                if (item.page.namespace != null) {
                    Text(
                        text = item.page.namespace,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                }
            }
            is SearchResultItem.AliasItem -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.alias,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "→ ${item.page.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                }
            }
            is SearchResultItem.BlockItem -> {
                Text(
                    text = item.block.content.take(80),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 1
                )
                if (item.snippet != null) {
                    SnippetText(snippet = item.snippet, maxLines = 1)
                }
            }
            is SearchResultItem.CreatePageItem -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Create page: ${item.query}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Ctrl+Enter \u23CE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
