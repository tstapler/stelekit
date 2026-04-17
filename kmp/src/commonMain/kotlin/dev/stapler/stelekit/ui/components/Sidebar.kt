package dev.stapler.stelekit.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.isMobile
import dev.stapler.stelekit.ui.theme.StelekitTheme

/**
 * Main sidebar component for the application.
 * Updated with multi-graph support.
 */
@Composable
fun LeftSidebar(
    expanded: Boolean,
    isLoading: Boolean,
    favoritePages: List<Page>,
    recentPages: List<Page>,
    currentScreen: Screen,
    currentGraphName: String = "",
    availableGraphs: List<GraphInfo> = emptyList(),
    activeGraphId: String? = null,
    onPageClick: (Page) -> Unit,
    onNavigate: (Screen) -> Unit,
    onToggleFavorite: (Page) -> Unit,
    onGraphSelected: (String) -> Unit = {},
    onAddGraph: () -> Unit = {},
    onRemoveGraph: (String) -> Unit = {},
    onCollapse: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isMobile = LocalWindowSizeClass.current.isMobile

    AnimatedVisibility(
        visible = expanded,
        enter = expandHorizontally() + fadeIn(),
        exit = shrinkHorizontally() + fadeOut()
    ) {
        Column(
            modifier = modifier
                .width(250.dp)
                .fillMaxHeight()
                .background(StelekitTheme.colors.sidebarBackground)
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Collapse button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close sidebar",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Graph Switcher Section
            GraphSwitcher(
                currentGraphName = currentGraphName,
                availableGraphs = availableGraphs,
                activeGraphId = activeGraphId,
                onGraphSelected = onGraphSelected,
                onAddGraph = onAddGraph,
                onRemoveGraph = onRemoveGraph
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Navigation Section — hidden on mobile since bottom nav covers these destinations
            if (!isMobile) {
                Text(
                    "Navigation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                NavigationItem("Journals", Icons.Default.DateRange, currentScreen is Screen.Journals) { onNavigate(Screen.Journals) }
                NavigationItem("Flashcards", Icons.Default.Style, currentScreen is Screen.Flashcards) { onNavigate(Screen.Flashcards) }
                NavigationItem("All Pages", Icons.AutoMirrored.Filled.List, currentScreen is Screen.AllPages) { onNavigate(Screen.AllPages) }
                NavigationItem("Unlinked References", Icons.Default.Link, currentScreen is Screen.GlobalUnlinkedReferences) { onNavigate(Screen.GlobalUnlinkedReferences) }
                NavigationItem("Notifications", Icons.Default.Notifications, currentScreen is Screen.Notifications) { onNavigate(Screen.Notifications) }

                // Developer tools — accessible via Settings → Debug on mobile
                if (currentScreen is Screen.Logs || currentScreen is Screen.Performance) {
                    NavigationItem("Logs", Icons.Default.Info, currentScreen is Screen.Logs) { onNavigate(Screen.Logs) }
                    NavigationItem("Performance", Icons.Default.Settings, currentScreen is Screen.Performance) { onNavigate(Screen.Performance) }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                // Favorites Section
                if (favoritePages.isNotEmpty()) {
                    Text(
                        "Favorites",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    favoritePages.forEach { page ->
                        SidebarItem(
                            title = page.name,
                            isSelected = (currentScreen as? Screen.PageView)?.page?.uuid == page.uuid,
                            icon = Icons.Default.Star,
                            isFavorite = true,
                            onFavoriteClick = { onToggleFavorite(page) },
                            onClick = { onPageClick(page) }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Recent Section
                if (recentPages.isNotEmpty()) {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    recentPages.forEach { page ->
                        SidebarItem(
                            title = page.name,
                            isSelected = (currentScreen as? Screen.PageView)?.page?.uuid == page.uuid,
                            icon = Icons.Default.Description,
                            isFavorite = page.isFavorite,
                            onFavoriteClick = { onToggleFavorite(page) },
                            onClick = { onPageClick(page) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Graph switcher component for selecting and managing graphs.
 */
@Composable
fun GraphSwitcher(
    currentGraphName: String,
    availableGraphs: List<GraphInfo>,
    activeGraphId: String? = null,
    onGraphSelected: (String) -> Unit,
    onAddGraph: () -> Unit,
    onRemoveGraph: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var graphToRemove by remember { mutableStateOf<GraphInfo?>(null) }
    
    Column(modifier = modifier) {
        // Current graph button
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    contentDescription = if (expanded)
                        "Graph: $currentGraphName, expanded"
                    else
                        "Graph: $currentGraphName, tap to switch graph"
                }
                .clickable { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = currentGraphName.ifEmpty { "Select Graph" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
        }
        
        // Dropdown with graph list
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableGraphs.forEach { graph ->
                DropdownMenuItem(
                    text = {
                        GraphItem(
                            graph = graph,
                            isActive = graph.id == activeGraphId,
                            onSelect = {
                                onGraphSelected(graph.id)
                                expanded = false
                            },
                            onRemove = if (availableGraphs.size > 1) {
                                { graphToRemove = graph }
                            } else null
                        )
                    },
                    onClick = {
                        onGraphSelected(graph.id)
                        expanded = false
                    },
                    contentPadding = PaddingValues(0.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add Graph...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                onClick = {
                    onAddGraph()
                    expanded = false
                },
                contentPadding = PaddingValues(0.dp)
            )
        }
    }
    
    // Confirmation dialog for removing a graph
    if (graphToRemove != null) {
        AlertDialog(
            onDismissRequest = { graphToRemove = null },
            title = { Text("Remove Graph") },
            text = { Text("Remove \"${graphToRemove?.displayName}\" from the graph list?\n\nThe graph files will not be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        graphToRemove?.let { onRemoveGraph(it.id) }
                        graphToRemove = null
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { graphToRemove = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Individual graph item in the dropdown.
 */
@Composable
fun GraphItem(
    graph: GraphInfo,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isActive) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = graph.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove graph",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun RightSidebar(
    expanded: Boolean,
    onClose: () -> Unit,
    currentPageName: String? = null,
    linkedReferences: List<Block> = emptyList(),
    onNavigateToPage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
        exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
    ) {
        Column(
            modifier = modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Right Sidebar",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close Sidebar")
                }
            }

            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Linked References",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            when {
                currentPageName == null -> {
                    Text(
                        "Open a page to see references",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                linkedReferences.isEmpty() -> {
                    Text(
                        "No linked references",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn {
                        items(linkedReferences) { block ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToPage(block.pageUuid) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    text = block.content.take(80),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = block.pageUuid.take(8),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarItem(
    title: String,
    isSelected: Boolean,
    icon: ImageVector,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                    modifier = Modifier.size(18.dp),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun NavigationItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
