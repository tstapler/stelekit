package dev.stapler.stelekit.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.SearchScope
import dev.stapler.stelekit.ui.screens.ParsedQuery
import dev.stapler.stelekit.ui.screens.PreviewPanelContent
import dev.stapler.stelekit.ui.screens.SearchResultItem
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.util.toTitleCase
import kotlinx.coroutines.flow.Flow

private val REGEX_TAG_FILTER = Regex("""#\S+""")
private val REGEX_SCOPE_FILTER = Regex("""/(pages?|blocks?|journal|current)\b""", RegexOption.IGNORE_CASE)
private val REGEX_DATE_FILTER = Regex("""modified:(today|day|week|month|year)""", RegexOption.IGNORE_CASE)

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
    isIndexing: Boolean = false,
    loadPageBlocks: (String) -> Flow<Either<DomainError, List<Block>>> = { kotlinx.coroutines.flow.flowOf(Either.Right(emptyList())) },
) {
    if (!visible) return

    val placeholder = if (onPageSelected != null) "Search pages..." else "Search pages and blocks..."
    val uiState by viewModel.uiState.collectAsState()
    // Active list depends on whether we're in empty-query (recent pages) or active search state
    val activeList: List<SearchResultItem> = if (uiState.query.isBlank()) uiState.recentPages else uiState.results
    var selectedIndex by remember(activeList) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            try { focusRequester.requestFocus() } catch (_: IllegalStateException) {}
            viewModel.onQueryChange(initialQuery)
        }
    }

    LaunchedEffect(selectedIndex) {
        if (activeList.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex)
        }
        viewModel.onSelectionChange(selectedIndex)
    }

    /** Advance past consecutive Header items in the given direction (+1 or -1). */
    fun skipHeaders(start: Int, direction: Int): Int {
        if (activeList.isEmpty()) return start
        var idx = start
        var guard = 0
        while (activeList[idx] is SearchResultItem.Header && guard < activeList.size) {
            idx = ((idx + direction) + activeList.size) % activeList.size
            guard++
        }
        return idx
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val isMobile = LocalWindowSizeClass.current.isMobile

        val keyEventHandler: (KeyEvent) -> Boolean = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                when (keyEvent.key) {
                    Key.DirectionDown -> {
                        if (activeList.isNotEmpty()) {
                            val raw = (selectedIndex + 1) % activeList.size
                            selectedIndex = skipHeaders(raw, 1)
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (activeList.isNotEmpty()) {
                            val raw = if (selectedIndex <= 0) activeList.size - 1
                                      else selectedIndex - 1
                            selectedIndex = skipHeaders(raw, -1)
                        }
                        true
                    }
                    Key.Enter -> {
                        if (activeList.isNotEmpty()) {
                            when (val item = activeList[selectedIndex]) {
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

        val sharedDecoration = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .shadow(8.dp, RoundedCornerShape(6.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp)
            )
            .clickable(enabled = false) {}
            .onKeyEvent(keyEventHandler)

        val mobileModifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .then(sharedDecoration)

        val desktopModifier = Modifier
            .widthIn(min = 500.dp, max = 900.dp)
            .fillMaxWidth(0.85f)
            .heightIn(min = 400.dp, max = 700.dp)
            .fillMaxHeight(0.75f)
            .then(sharedDecoration)

        val indexingIndicator: @Composable () -> Unit = {
            if (isIndexing) {
                Text(
                    text = "Still indexing pages — some results may be missing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss)
        ) {

            val resultsList: @Composable ColumnScope.() -> Unit = {
                // Outer crossfade: empty-query state (recent pages) vs active search
                Crossfade(
                    targetState = uiState.query.isBlank(),
                    animationSpec = tween(180),
                    label = "recent-to-results"
                ) { showingEmpty ->
                    if (showingEmpty && uiState.recentPages.isNotEmpty()) {
                        // Show recent pages list
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
                            itemsIndexed(uiState.recentPages) { index, page ->
                                SearchResultRow(
                                    title = page.page.name,
                                    subtitle = page.breadcrumb ?: page.page.namespace?.replace("/", " / "),
                                    relativeDate = formatRelativeDate(page.visitedAt ?: page.page.updatedAt),
                                    inlineTags = page.tags.take(3),
                                    isSelected = index == selectedIndex,
                                    onClick = {
                                        if (onPageSelected != null) {
                                            onPageSelected(page.page.name)
                                        } else {
                                            onNavigateToPage(page.page.uuid)
                                        }
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    } else if (showingEmpty) {
                        // Empty state: no recent pages — show nothing
                    } else {
                        // Active search: skeleton or results
                        Crossfade(
                            targetState = uiState.isSkeletonVisible || uiState.isLoading,
                            animationSpec = tween(200),
                            label = "search-skeleton-fade"
                        ) { showingSkeleton ->
                            if (showingSkeleton) {
                                SearchSkeletonList(rowCount = 6)
                            } else {
                                Column {
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
                                                        subtitle = item.breadcrumb ?: item.page.namespace?.replace("/", " / "),
                                                        relativeDate = formatRelativeDate(item.page.updatedAt),
                                                        inlineTags = item.tags.take(3),
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
                                                        subtitle = item.breadcrumb ?: "Block",
                                                        relativeDate = formatRelativeDate(item.block.updatedAt),
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
                                    val showNoResults = uiState.results.isEmpty() && uiState.query.isNotEmpty()
                                        && !uiState.isLoading && !uiState.isSkeletonVisible
                                    if (showNoResults) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("No results found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isMobile) {
                Box(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(modifier = mobileModifier, verticalArrangement = Arrangement.Bottom) {
                        resultsList()
                        indexingIndicator()
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
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(modifier = desktopModifier) {
                        Column(modifier = Modifier.width(340.dp).fillMaxHeight()) {
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
                            ActivePrefixChipRow(
                                parsedQuery = uiState.parsedQuery,
                                onRemoveTag = {
                                    viewModel.onQueryChange(
                                        uiState.query.replace(REGEX_TAG_FILTER, "").trim()
                                    )
                                },
                                onRemoveScope = {
                                    viewModel.onQueryChange(
                                        uiState.query.replace(REGEX_SCOPE_FILTER, "").trim()
                                    )
                                },
                                onRemoveDate = {
                                    viewModel.onQueryChange(
                                        uiState.query.replace(REGEX_DATE_FILTER, "").trim()
                                    )
                                },
                                onRemoveProperty = { key ->
                                    viewModel.onQueryChange(
                                        uiState.query.replace(Regex("""$key::\w+"""), "").trim()
                                    )
                                }
                            )
                            FilterBar(
                                currentScope = uiState.scope,
                                onScopeChange = { viewModel.onScopeChange(it) },
                                showCurrentPage = currentPageUuid != null
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                            indexingIndicator()
                            resultsList()
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PreviewPanel(
                            content = uiState.previewContent,
                            loadPageBlocks = loadPageBlocks,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Rich result row showing title + optional relative date, breadcrumb subtitle,
 * inline tags (as "#tag1 \u00B7 #tag2"), and an FTS snippet.
 *
 * Layout (3-line budget):
 * - Line 1: [title] (left, bodyMedium) + [relativeDate] (right, labelSmall)
 * - Line 2: [subtitle] breadcrumb (labelSmall, dimmed) \u2014 only when non-null
 * - Line 3: inline tags "#tag1 \u00B7 #tag2" or [SnippetText] \u2014 tags take priority
 */
@Composable
fun SearchResultRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    relativeDate: String? = null,
    inlineTags: List<String> = emptyList(),
    snippet: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Line 1: title + relative date
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (relativeDate != null) {
                    Text(
                        text = relativeDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (isSelected) {
                    Text(
                        text = "Enter \u23CE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
            }
            // Line 2: breadcrumb subtitle
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
            // Line 3: inline tags preferred over snippet
            if (inlineTags.isNotEmpty()) {
                Text(
                    text = inlineTags.joinToString(" \u00B7 ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            } else {
                SnippetText(snippet = snippet)
            }
        }
    }
}

/** Backward-compatible wrapper for callers that use positional [subtitle] without rich fields. */
@Composable
fun SearchResultRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    snippet: String? = null
) = SearchResultRow(
    title = title,
    subtitle = subtitle,
    relativeDate = null,
    inlineTags = emptyList(),
    snippet = snippet,
    isSelected = isSelected,
    onClick = onClick
)

/**
 * Renders chips for active prefix filters parsed from the search query.
 * Shows nothing when [parsedQuery] is null or has no active filters.
 */
@Composable
fun ActivePrefixChipRow(
    parsedQuery: ParsedQuery?,
    onRemoveTag: () -> Unit,
    onRemoveScope: () -> Unit,
    onRemoveDate: () -> Unit,
    onRemoveProperty: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (parsedQuery == null) return
    val hasAnyFilter = parsedQuery.tagFilter != null
        || parsedQuery.scopeOverride != null
        || parsedQuery.dateRange != null
        || parsedQuery.propertyFilters.isNotEmpty()
    if (!hasAnyFilter) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        parsedQuery.tagFilter?.let { tag ->
            SuggestionChip(
                onClick = onRemoveTag,
                label = { Text("#$tag") },
                icon = {
                    Text(
                        text = "\u00D7",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }
        parsedQuery.scopeOverride?.let { scope ->
            val label = when (scope) {
                SearchScope.PAGES_ONLY -> "Pages"
                SearchScope.BLOCKS_ONLY -> "Blocks"
                SearchScope.JOURNAL -> "Journal"
                SearchScope.CURRENT_PAGE -> "This page"
                else -> scope.name
            }
            SuggestionChip(
                onClick = onRemoveScope,
                label = { Text(label) },
                icon = {
                    Text(
                        text = "\u00D7",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
        if (parsedQuery.dateRange != null) {
            SuggestionChip(
                onClick = onRemoveDate,
                label = { Text("date filter") },
                icon = {
                    Text(
                        text = "\u00D7",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        for ((key, value) in parsedQuery.propertyFilters) {
            SuggestionChip(
                onClick = { onRemoveProperty(key) },
                label = { Text("$key: $value") },
                icon = {
                    Text(
                        text = "\u00D7",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
    }
}
