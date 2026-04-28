package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.isMobile
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.repository.SearchScope
import dev.stapler.stelekit.ui.screens.SearchResultItem
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.util.toTitleCase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigateToPage: (String) -> Unit,
    onNavigateToBlock: (String) -> Unit,
    onCreatePage: (String) -> Unit,
    viewModel: SearchViewModel,
    currentPageUuid: String? = null,
    onPageSelected: ((String) -> Unit)? = null,
    initialQuery: String = "",
) {
    if (!visible) return

    val placeholder = if (onPageSelected != null) "Search pages..." else "Search pages and blocks..."
    val uiState by viewModel.uiState.collectAsState()
    var selectedIndex by remember(uiState.results) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            try { focusRequester.requestFocus() } catch (_: IllegalStateException) {}
            viewModel.onQueryChange(initialQuery)
        }
    }

    LaunchedEffect(selectedIndex) {
        if (uiState.results.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    /** Advance past consecutive Header items in the given direction (+1 or -1). */
    fun skipHeaders(start: Int, direction: Int): Int {
        if (uiState.results.isEmpty()) return start
        var idx = start
        var guard = 0
        while (uiState.results[idx] is SearchResultItem.Header && guard < uiState.results.size) {
            idx = ((idx + direction) + uiState.results.size) % uiState.results.size
            guard++
        }
        return idx
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val isMobile = LocalWindowSizeClass.current.isMobile

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss)
        ) {
            val sharedColumnModifier = Modifier
                .widthIn(max = 600.dp)
                .let { if (isMobile) it.fillMaxWidth() else it.fillMaxWidth(0.8f) }
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .shadow(8.dp, RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(6.dp)
                )
                .clickable(enabled = false) {}
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.DirectionDown -> {
                                if (uiState.results.isNotEmpty()) {
                                    val raw = (selectedIndex + 1) % uiState.results.size
                                    selectedIndex = skipHeaders(raw, 1)
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                if (uiState.results.isNotEmpty()) {
                                    val raw = if (selectedIndex <= 0) uiState.results.size - 1
                                              else selectedIndex - 1
                                    selectedIndex = skipHeaders(raw, -1)
                                }
                                true
                            }
                            Key.Enter -> {
                                if (uiState.results.isNotEmpty()) {
                                    when (val item = uiState.results[selectedIndex]) {
                                        is SearchResultItem.PageItem -> {
                                            if (onPageSelected != null) {
                                                onPageSelected(item.page.name)
                                            } else {
                                                onNavigateToPage(item.page.uuid)
                                            }
                                            onDismiss()
                                        }
                                        is SearchResultItem.AliasItem -> {
                                            if (onPageSelected != null) {
                                                onPageSelected(item.page.name)
                                            } else {
                                                onNavigateToPage(item.page.uuid)
                                            }
                                            onDismiss()
                                        }
                                        is SearchResultItem.BlockItem -> {
                                            onNavigateToBlock(item.block.uuid)
                                            onDismiss()
                                        }
                                        is SearchResultItem.CreatePageItem -> {
                                            onCreatePage(item.query)
                                            onDismiss()
                                        }
                                        else -> {}
                                    }
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

            val resultsList: @Composable ColumnScope.() -> Unit = {
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (uiState.query.isBlank() && uiState.recentQueries.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp).fillMaxWidth()
                    ) {
                        item {
                            Text(
                                text = "Recent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        items(uiState.recentQueries.size) { i ->
                            val q = uiState.recentQueries[i]
                            SearchResultRow(
                                title = q,
                                subtitle = "Recent search",
                                isSelected = false,
                                onClick = { viewModel.onQueryChange(q) }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = 400.dp).fillMaxWidth()
                    ) {
                        itemsIndexed(uiState.results) { index, item ->
                            when (item) {
                                is SearchResultItem.Header -> {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                                is SearchResultItem.PageItem -> {
                                    SearchResultRow(
                                        title = item.page.name,
                                        subtitle = item.page.namespace ?: "Page",
                                        snippet = item.snippet,
                                        isSelected = index == selectedIndex,
                                        onClick = {
                                            if (onPageSelected != null) {
                                                onPageSelected(item.page.name)
                                            } else {
                                                onNavigateToPage(item.page.uuid)
                                            }
                                            onDismiss()
                                        }
                                    )
                                }
                                is SearchResultItem.AliasItem -> {
                                    SearchResultRow(
                                        title = item.alias,
                                        subtitle = "Alias for ${item.page.name}",
                                        isSelected = index == selectedIndex,
                                        onClick = {
                                            if (onPageSelected != null) {
                                                onPageSelected(item.page.name)
                                            } else {
                                                onNavigateToPage(item.page.uuid)
                                            }
                                            onDismiss()
                                        }
                                    )
                                }
                                is SearchResultItem.BlockItem -> {
                                    SearchResultRow(
                                        title = item.block.content.take(100),
                                        subtitle = "Block",
                                        snippet = item.snippet,
                                        isSelected = index == selectedIndex,
                                        onClick = {
                                            onNavigateToBlock(item.block.uuid)
                                            onDismiss()
                                        }
                                    )
                                }
                                is SearchResultItem.CreatePageItem -> {
                                    SearchResultRow(
                                        title = "Create page \"${item.query}\"",
                                        subtitle = "New Page",
                                        isSelected = index == selectedIndex,
                                        onClick = {
                                            onCreatePage(item.query)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.results.isEmpty() && uiState.query.isNotEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No results found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (isMobile) {
                Box(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(modifier = sharedColumnModifier, verticalArrangement = Arrangement.Bottom) {
                        resultsList()
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilterBar(
                            currentScope = uiState.scope,
                            onScopeChange = { viewModel.onScopeChange(it) },
                            showCurrentPage = currentPageUuid != null
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = uiState.query,
                                onValueChange = { viewModel.onQueryChange(it) },
                                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                                placeholder = { Text(placeholder) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                )
                            )
                            if (uiState.query.isNotEmpty()) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text("Title Case") } },
                                    state = rememberTooltipState()
                                ) {
                                    TextButton(onClick = { viewModel.onQueryChange(uiState.query.toTitleCase()) }) {
                                        Text("Tt", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(modifier = sharedColumnModifier.padding(top = 100.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = uiState.query,
                                onValueChange = { viewModel.onQueryChange(it) },
                                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                                placeholder = { Text(placeholder) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                )
                            )
                            if (uiState.query.isNotEmpty()) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text("Title Case") } },
                                    state = rememberTooltipState()
                                ) {
                                    TextButton(onClick = { viewModel.onQueryChange(uiState.query.toTitleCase()) }) {
                                        Text("Tt", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilterBar(
                            currentScope = uiState.scope,
                            onScopeChange = { viewModel.onScopeChange(it) },
                            showCurrentPage = currentPageUuid != null
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        resultsList()
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    snippet: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            SnippetText(snippet = snippet)
        }
        if (isSelected) {
            Text(
                text = "Enter \u23CE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        }
    }
}
