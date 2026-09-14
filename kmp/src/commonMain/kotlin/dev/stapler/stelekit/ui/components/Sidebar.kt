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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.stapler.stelekit.db.StorageLocationResolver
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.platform.isCurrentSessionEphemeral
import dev.stapler.stelekit.platform.isEphemeralWebModeAvailable
import dev.stapler.stelekit.platform.startEphemeralSession
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.isMobile
import dev.stapler.stelekit.ui.theme.StelekitTheme
import kotlinx.coroutines.launch

/** Amber warning treatment for an unresolved disk conflict — matches [SyncStatusBadge]'s
 * `ConflictPending` color exactly (deliberately, for visual consistency between the two
 * conflict types), but kept as a separate constant since these are functionally distinct
 * indicators, not a shared component. */
private val DiskConflictWarningColor = Color(0xFFF59E0B)

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
    pendingConflictFilePaths: Set<String> = emptySet(),
    isDemoActive: Boolean = false,
    demoBannerDismissed: Boolean = false,
    onDismissDemoBanner: () -> Unit = {},
    onPageClick: (Page) -> Unit,
    onNavigate: (Screen) -> Unit,
    onToggleFavorite: (Page) -> Unit,
    onGraphSelected: (String) -> Unit = {},
    onAddGraph: () -> Unit = {},
    onRemoveGraph: (String) -> Unit = {},
    onCollapse: () -> Unit = {},
    syncState: SyncState = SyncState.Idle,
    /** Epoch-millis of the last successful git sync, persisted across restarts. Null when never
     * synced or no git sync service is active — see [GitSyncService.lastSyncAt]. */
    gitLastSyncAt: Long? = null,
    onSyncClick: () -> Unit = {},
    onGitSetup: () -> Unit = {},
    isGitConfigured: Boolean = false,
    onAuthError: (() -> Unit)? = null,
    /** Epic 2.3: current web-local-folder-livesync [HostAccessState]. [HostAccessState.NotApplicable]
     * (the default) renders [FolderSyncStatusBadge] as nothing — matches every non-web platform. */
    hostAccessState: HostAccessState = HostAccessState.NotApplicable,
    hostPendingWriteCount: Int = 0,
    /** Epic 4.4 (Task 4.4.1c): true while a write-through flush is stuck mid-`Granted` — drives
     * [FolderSyncStatusBadge]'s `SyncDegraded` row (ux.md Surface 3, row 3). */
    hostWriteStuck: Boolean = false,
    onReconnectHostDirectory: () -> Unit = {},
    onCloneGraph: () -> Unit = {},
    onUpdateGraphPath: (String, String) -> Unit = { _, _ -> },
    onRenameGraph: (String, String) -> Unit = { _, _ -> },
    onRelinkHostDirectory: (String) -> Unit = {},
    supportsHostDirectoryLink: Boolean = false,
    /** Story 3.2.2 — see [GraphSwitcher]'s parameter doc. */
    storageLocationResolver: StorageLocationResolver? = null,
    onBrowseRequestedForMove: suspend (String) -> StorageLocation? = { null },
    /** See [GraphSwitcher]'s parameter doc. */
    onBrowseClickedForMove: () -> Unit = {},
    moveStorageLocationPlatformCapabilities: Boolean = false,
    /** Epic 4.2 (Story 4.2.1): resolves whether [StorageMoveChoiceDialog]'s "Link" option should
     * be offered for a graph's move flow — real signal is `GitRepository.isGitRepo(graph.path)`
     * (already resolves through Android's shadow-worktree for `saf://` paths, see
     * `AndroidGitRepository.isGitRepo`), wired in by the composition root. Defaults to `true` so
     * platforms/tests that don't wire a real check keep prior (Epic 3.4) behavior — Link is only
     * ever gated OFF, never gated on, by a caller opting in to a real check. */
    isGraphGitCloned: suspend (path: String) -> Boolean = { true },
    /** Epic 3.4: fires once the user has chosen Relocate/Link in [StorageMoveChoiceDialog] and
     * confirmed in [StorageMoveConfirmDialog] — the composition root's hand-off point to drive
     * `GraphRelocationCoordinator.relocate()` (Relocate) or `connectHostDirectory` (Link, Epic
     * 4.1). Null (the default) means nothing happens once the user confirms — this file has no
     * coordinator instance to invoke itself, matching the composition-root wiring gap Epics 3.2/
     * 3.3 already left for [storageLocationResolver] and [onBrowseRequestedForMove]. */
    onStorageLocationChosen: (operation: StorageMoveOperation) -> Unit = {},
    gitSyncedGraphId: String? = null,
    /** Pages captured from another graph by [onExportPagesForMerge], not yet merged in here.
     * Cross-graph page/journal recovery — see GraphMergeService's class doc. */
    mergePendingPageCount: Int = 0,
    onExportPagesForMerge: () -> Unit = {},
    onImportMergedPages: () -> Unit = {},
    onNewSectionJournalEntry: (() -> Unit)? = null,
    sectionManifest: SectionManifest? = null,
    defaultSection: String = "",
    onSectionIndicatorClick: () -> Unit = {},
    modifier: Modifier = Modifier,
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
                availableGraphs = availableGraphs.filter { !it.isDemo },
                activeGraphId = activeGraphId,
                onGraphSelected = onGraphSelected,
                onAddGraph = onAddGraph,
                onRemoveGraph = onRemoveGraph,
                onCloneGraph = onCloneGraph,
                onUpdateGraphPath = onUpdateGraphPath,
                onRenameGraph = onRenameGraph,
                onRelinkHostDirectory = onRelinkHostDirectory,
                supportsHostDirectoryLink = supportsHostDirectoryLink,
                storageLocationResolver = storageLocationResolver,
                onBrowseRequestedForMove = onBrowseRequestedForMove,
                onBrowseClickedForMove = onBrowseClickedForMove,
                moveStorageLocationPlatformCapabilities = moveStorageLocationPlatformCapabilities,
                isGraphGitCloned = isGraphGitCloned,
                onStorageLocationChosen = onStorageLocationChosen,
                gitSyncedGraphId = gitSyncedGraphId,
                isDemoActive = isDemoActive,
                hostAccessState = hostAccessState,
                mergePendingPageCount = mergePendingPageCount,
                onExportPagesForMerge = onExportPagesForMerge,
                onImportMergedPages = onImportMergedPages,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            AnimatedVisibility(
                visible = isDemoActive && !demoBannerDismissed,
                exit = fadeOut() + shrinkVertically()
            ) {
                DemoBanner(
                    onDismiss = onDismissDemoBanner,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Section context indicator — shown when a manifest with sections exists
            if (sectionManifest != null && sectionManifest.sections.isNotEmpty()) {
                SectionContextIndicator(
                    defaultSection = defaultSection,
                    manifest = sectionManifest,
                    onClick = onSectionIndicatorClick,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            SyncStatusBadge(
                status = GitSyncStatus(state = syncState, lastSyncAt = gitLastSyncAt),
                onSyncClick = onSyncClick,
                isGitConfigured = isGitConfigured,
                onAuthError = onAuthError,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )

            // Epic 2.3 (Story 2.3.1): sibling badge for the web-local-folder-livesync host
            // directory connection — distinct subsystem from the git SyncStatusBadge above,
            // renders nothing (NotApplicable) on every non-web platform.
            FolderSyncStatusBadge(
                state = hostAccessState,
                dirName = currentGraphName.ifEmpty { null },
                pendingWriteCount = hostPendingWriteCount,
                hostWriteStuck = hostWriteStuck,
                onReconnect = onReconnectHostDirectory,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )

            if (pendingConflictFilePaths.isNotEmpty()) {
                PendingConflictsBanner(
                    count = pendingConflictFilePaths.size,
                    onClick = { onNavigate(Screen.AllPages(conflictsOnly = true)) },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // Navigation Section
            Text(
                "Navigation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            // Primary — daily-use screens
            NavigationItem("Journals", Icons.Default.DateRange, currentScreen is Screen.Journals) { onNavigate(Screen.Journals) }
            if (onNewSectionJournalEntry != null) {
                val sectionLabel = sectionManifest?.sections?.find { it.id == defaultSection }?.displayName ?: "Work"
                NavigationItem("New $sectionLabel Journal", Icons.Default.DateRange, false) { onNewSectionJournalEntry() }
            }
            NavigationItem("All Pages", Icons.AutoMirrored.Filled.List, currentScreen is Screen.AllPages) { onNavigate(Screen.AllPages()) }
            NavigationItem("Flashcards", Icons.Default.Style, currentScreen is Screen.Flashcards) { onNavigate(Screen.Flashcards) }

            Spacer(Modifier.height(4.dp))

            // Note-taking tools
            NavigationItem("Unlinked References", Icons.Default.Link, currentScreen is Screen.GlobalUnlinkedReferences) { onNavigate(Screen.GlobalUnlinkedReferences) }
            NavigationItem("Notifications", Icons.Default.Notifications, currentScreen is Screen.Notifications) { onNavigate(Screen.Notifications) }

            Spacer(Modifier.height(4.dp))

            // Media / Library
            NavigationItem("Gallery", Icons.Default.PhotoLibrary, currentScreen is Screen.Gallery) { onNavigate(Screen.Gallery) }
            NavigationItem("Assets", Icons.Default.FolderOpen, currentScreen is Screen.AssetBrowser) { onNavigate(Screen.AssetBrowser) }

            Spacer(Modifier.height(4.dp))

            // Admin / Dev
            NavigationItem("Library Stats", Icons.Default.BarChart, currentScreen is Screen.LibraryStats) { onNavigate(Screen.LibraryStats) }
            NavigationItem("Git Setup", Icons.Default.Sync, false) { onGitSetup() }

            // Developer tools — only visible when already on those screens
            if (currentScreen is Screen.Logs || currentScreen is Screen.Performance) {
                NavigationItem("Logs", Icons.Default.Info, currentScreen is Screen.Logs) { onNavigate(Screen.Logs) }
                NavigationItem("Performance", Icons.Default.Settings, currentScreen is Screen.Performance) { onNavigate(Screen.Performance) }
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
                            onClick = { onPageClick(page) },
                            hasPendingConflict = page.filePath in pendingConflictFilePaths
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
                            onClick = { onPageClick(page) },
                            hasPendingConflict = page.filePath in pendingConflictFilePaths
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PendingConflictsBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = DiskConflictWarningColor.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Unresolved disk conflicts",
                modifier = Modifier.size(16.dp),
                tint = DiskConflictWarningColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = if (count == 1) "1 page has an unresolved conflict" else "$count pages have unresolved conflicts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Tap to view pages · cleared on app restart",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Single-line icon+label row for a [DropdownMenuItem] in [GraphSwitcher]'s menu. */
@Composable
private fun GraphMenuActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
    )
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
    onCloneGraph: () -> Unit = {},
    onUpdateGraphPath: (String, String) -> Unit = { _, _ -> },
    onRenameGraph: (String, String) -> Unit = { _, _ -> },
    /** Re-points a graph's host-folder link at a newly-picked folder (web-local-folder-livesync
     * only). Must call the platform's directory-picker synchronously from this click before
     * launching a coroutine — same transient-user-activation constraint as [onAddGraph]. No-op
     * (button hidden) on platforms without a native directory picker. */
    onRelinkHostDirectory: (String) -> Unit = {},
    supportsHostDirectoryLink: Boolean = false,
    /**
     * Story 3.2.2: resolves/backfills a graph's real source [StorageLocation] before "Move
     * storage location…" opens [UnifiedLocationPicker] (Story 1.1.4's `resolveOrBackfill`, so a
     * graph that predates `storage_locations` still has a real source to relocate from). Null
     * (the default) hides the button entirely — set by the platform-specific composition root
     * once it has a real resolver to inject.
     */
    storageLocationResolver: StorageLocationResolver? = null,
    /** Threaded into [UnifiedLocationPicker]'s `onBrowseRequested` for the relocate flow. */
    onBrowseRequestedForMove: suspend (String) -> StorageLocation? = { null },
    /** Must run synchronously in the "Browse…" row's own click handler — same transient-user-
     * activation constraint as [UnifiedLocationPicker]'s `onBrowseClicked`. */
    onBrowseClickedForMove: () -> Unit = {},
    /** Whether the relocate flow's [UnifiedLocationPicker] shows a "Browse…" row. */
    moveStorageLocationPlatformCapabilities: Boolean = false,
    /** See [LeftSidebar]'s parameter doc — gates [StorageMoveChoiceDialog]'s "Link" option. */
    isGraphGitCloned: suspend (path: String) -> Boolean = { true },
    /**
     * Epic 3.4: fires once the user has picked a destination in [UnifiedLocationPicker], chosen
     * Relocate/Link in [StorageMoveChoiceDialog], and confirmed in [StorageMoveConfirmDialog] —
     * see [Sidebar]'s parameter doc for why this is still a hand-off point rather than a direct
     * coordinator call.
     */
    onStorageLocationChosen: (operation: StorageMoveOperation) -> Unit = {},
    gitSyncedGraphId: String? = null,
    isDemoActive: Boolean = false,
    /** Epic 2.3: host-directory connection state for [activeGraphId] only — used to show a
     * "linked to local folder" indicator distinct from the graph's internal OPFS path. */
    hostAccessState: HostAccessState = HostAccessState.NotApplicable,
    mergePendingPageCount: Int = 0,
    onExportPagesForMerge: () -> Unit = {},
    onImportMergedPages: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var graphToRemove by remember { mutableStateOf<GraphInfo?>(null) }
    var graphToEdit by remember { mutableStateOf<GraphInfo?>(null) }
    // Story 3.2.2: "Move storage location…" flow — resolveOrBackfill runs first (so a graph that
    // predates storage_locations still has a real source location), then UnifiedLocationPicker
    // opens once it resolves. Separate from graphToEdit so the picker survives the Edit dialog
    // closing (the button closes it immediately on tap, matching onRelinkHostDirectory's pattern).
    var movingStorageForGraph by remember { mutableStateOf<GraphInfo?>(null) }
    // Epic 3.4: the real source resolveOrBackfill(graphId) returned for movingStorageForGraph —
    // carried forward into StorageMoveChoiceDialog/StorageMoveConfirmDialog once the picker
    // resolves a destination, so both name the exact "from"/"to" locations (never a placeholder).
    var movingStorageSource by remember { mutableStateOf<StorageLocation?>(null) }
    // Epic 4.2 (Story 4.2.1): resolved alongside movingStorageSource so choosingMoveFor can gate
    // StorageMoveChoiceDialog's "Link" option — true (Link offered) until proven otherwise, since
    // isGraphGitCloned defaults to { true } and a plain graph is the only case that flips it off.
    var movingStorageIsLinkAvailable by remember { mutableStateOf(true) }
    var choosingMoveFor by remember { mutableStateOf<PendingStorageMove?>(null) }
    var confirmingMove by remember { mutableStateOf<PendingStorageMove?>(null) }
    val moveStorageScope = rememberCoroutineScope()

    Column(modifier = modifier) {
        // Current graph button
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    contentDescription = if (isDemoActive)
                        "Graph: $currentGraphName (demo), tap to switch graph"
                    else if (expanded)
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
                    imageVector = if (hostAccessState == HostAccessState.Granted) Icons.Default.FolderOpen else Icons.Default.Folder,
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
                if (hostAccessState == HostAccessState.Granted) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Connected to local folder",
                        modifier = Modifier.size(14.dp).padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
                            isActive = graph.id.value == activeGraphId,
                            isSynced = graph.id.value == gitSyncedGraphId,
                            isHostConnected = graph.id.value == activeGraphId && hostAccessState == HostAccessState.Granted,
                            onSelect = {
                                onGraphSelected(graph.id.value)
                                expanded = false
                            },
                            // GraphManager.removeGraph now allows removing the last real graph
                            // (falls back to activeGraphId = null, surfacing the empty-state
                            // prompt) — no longer gated on availableGraphs.size > 1.
                            onRemove = { graphToRemove = graph },
                            // Re-pointing an ephemeral graph at a real folder would defeat the
                            // whole point of the mode — an ephemeral session only ever has this
                            // one graph, so gating on the session (not per-graph) is sufficient.
                            onEditPath = if (!graph.isDemo && !isCurrentSessionEphemeral()) {
                                { graphToEdit = graph }
                            } else null
                        )
                    },
                    onClick = {
                        onGraphSelected(graph.id.value)
                        expanded = false
                    },
                    contentPadding = PaddingValues(0.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Not offered inside an already-ephemeral session — connecting a local OPFS folder
            // would introduce the exact persistent storage side channel that mode exists to avoid.
            if (!isCurrentSessionEphemeral()) {
                GraphMenuActionItem(Icons.Default.Add, "Open local folder...") { onAddGraph(); expanded = false }
            }

            GraphMenuActionItem(Icons.Default.CloudDownload, "Clone from URL...") { onCloneGraph(); expanded = false }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Cross-graph page/journal recovery: capture this graph's pages, switch to another
            // graph via the list above, then paste them in. See GraphMergeService's class doc.
            GraphMenuActionItem(Icons.Default.ContentCopy, "Copy pages from this graph...") {
                onExportPagesForMerge(); expanded = false
            }
            if (mergePendingPageCount > 0) {
                GraphMenuActionItem(Icons.Default.ContentPaste, "Merge $mergePendingPageCount captured page(s) here") {
                    onImportMergedPages(); expanded = false
                }
            }

            if (isEphemeralWebModeAvailable() && !isCurrentSessionEphemeral()) {
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Open temporarily...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                "Checked-out files live in memory only — nothing is saved on this computer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 26.dp),
                            )
                        }
                    },
                    onClick = { startEphemeralSession(); expanded = false },
                    contentPadding = PaddingValues(0.dp),
                )
            }
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
                        graphToRemove?.let { onRemoveGraph(it.id.value) }
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

    // Edit dialog: rename, move the graph's storage location (guided flow, Story 3.2.2), and/or
    // re-link the real host folder (web-local-folder-livesync only) — independent actions, each
    // with its own trigger so an action that requires migration/re-linking only runs that work.
    val editingGraph = graphToEdit
    if (editingGraph != null) {
        var newName by remember(editingGraph.id.value) { mutableStateOf(editingGraph.displayName) }
        AlertDialog(
            onDismissRequest = { graphToEdit = null },
            title = { Text("Edit Graph") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Move \"${editingGraph.displayName}\"'s files to a different storage location on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            val graphId = editingGraph.id.value
                            graphToEdit = null
                            moveStorageScope.launch {
                                // Story 1.1.4: backfills a real source location for a graph that
                                // predates storage_locations, so the picker/choice dialog never
                                // names a blank source. A null resolver means this platform's
                                // composition root hasn't wired one in yet (see GraphSwitcher's
                                // storageLocationResolver doc) — still open the picker so the
                                // "Browse…" row keeps working, just without the backfill side effect.
                                movingStorageSource = storageLocationResolver?.resolveOrBackfill(graphId)
                                movingStorageIsLinkAvailable = isGraphGitCloned(editingGraph.path)
                                movingStorageForGraph = editingGraph
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("Move storage location…")
                    }
                    if (supportsHostDirectoryLink) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            editingGraph.hostDirName?.let { "Linked local folder: $it" }
                                ?: "No local folder linked yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                onRelinkHostDirectory(editingGraph.id.value)
                                graphToEdit = null
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Text(if (editingGraph.hostDirName != null) "Change linked folder…" else "Link a local folder…")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank() && newName != editingGraph.displayName) {
                            onRenameGraph(editingGraph.id.value, newName)
                        }
                        graphToEdit = null
                    },
                    enabled = newName.isNotBlank() && newName != editingGraph.displayName,
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { graphToEdit = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Story 3.2.2: UnifiedLocationPicker for the "Move storage location…" flow, opened once
    // resolveOrBackfill (above) completes. Epic 3.4: once a destination is picked, control passes
    // to StorageMoveChoiceDialog (Relocate vs Link) below, not straight to onStorageLocationChosen.
    val movingGraph = movingStorageForGraph
    if (movingGraph != null) {
        UnifiedLocationPicker(
            title = "Move \"${movingGraph.displayName}\" to…",
            graphId = movingGraph.id.value,
            appStorageSubtitle = "Kept inside SteleKit only — not visible in your device's file manager.",
            platformCapabilities = moveStorageLocationPlatformCapabilities,
            onBrowseClicked = onBrowseClickedForMove,
            onBrowseRequested = { onBrowseRequestedForMove(movingGraph.id.value) },
            onConfirm = { destination ->
                choosingMoveFor = PendingStorageMove(
                    graph = movingGraph,
                    // A null resolver (composition root hasn't wired one in yet, per
                    // storageLocationResolver's doc) leaves the real source unknown — AppOwned is
                    // the least-wrong placeholder available here, not a claim about where the
                    // graph actually lives.
                    source = movingStorageSource ?: StorageLocation.AppOwned(movingGraph.id.value),
                    destination = destination,
                    isLinkAvailable = movingStorageIsLinkAvailable,
                )
                movingStorageForGraph = null
                movingStorageSource = null
            },
            onDismiss = { movingStorageForGraph = null; movingStorageSource = null },
        )
    }

    // Epic 3.4 (Story 3.4.1): Relocate-vs-Link choice, opened once UnifiedLocationPicker resolves
    // a destination above.
    val choosing = choosingMoveFor
    if (choosing != null) {
        StorageMoveChoiceDialog(
            graphName = choosing.graph.displayName,
            source = choosing.source,
            destination = choosing.destination,
            // Epic 4.2 (Story 4.2.1): gated by isGraphGitCloned(graph.path), resolved when the
            // move flow started (see movingStorageIsLinkAvailable above) — per ADR-003, Link is
            // only a real continuous mirror for git-cloned Android graphs (the existing
            // shadow-worktree write-back mechanism); plain graphs get the disabled note instead.
            isLinkAvailable = choosing.isLinkAvailable,
            onRelocateChosen = {
                confirmingMove = choosing.copy(isRelocate = true)
                choosingMoveFor = null
            },
            onLinkChosen = {
                confirmingMove = choosing.copy(isRelocate = false)
                choosingMoveFor = null
            },
            onDismissRequest = { choosingMoveFor = null },
        )
    }

    // Epic 3.4 (Story 3.4.2): names the exact source/destination before handing off to
    // onStorageLocationChosen — the composition root's job (not this file's) is to actually drive
    // GraphRelocationCoordinator.relocate()/connectHostDirectory from there and show
    // StorageMoveProgressDialog for the result; see onStorageLocationChosen's doc above.
    val confirming = confirmingMove
    if (confirming != null) {
        StorageMoveConfirmDialog(
            graphName = confirming.graph.displayName,
            source = confirming.source,
            destination = confirming.destination,
            confirmLabel = if (confirming.isRelocate) "Move" else "Link",
            onConfirm = {
                val operation = if (confirming.isRelocate) {
                    StorageMoveOperation.Relocate(
                        graphId = confirming.graph.id.value,
                        source = confirming.source,
                        destination = confirming.destination,
                        // AC34: never auto-delete the source — that choice belongs to the
                        // post-move cleanup prompt (Surface 9), not this confirmation step.
                        deleteSourceAfterVerify = false,
                    )
                } else {
                    StorageMoveOperation.Link(
                        graphId = confirming.graph.id.value,
                        source = confirming.source,
                        destination = confirming.destination,
                    )
                }
                onStorageLocationChosen(operation)
                confirmingMove = null
            },
            onDismissRequest = { confirmingMove = null },
        )
    }
}

/** Epic 3.4: carries a graph + resolved source/destination through the
 * `StorageMoveChoiceDialog` → `StorageMoveConfirmDialog` hand-off inside [GraphSwitcher]. */
private data class PendingStorageMove(
    val graph: GraphInfo,
    val source: StorageLocation,
    val destination: StorageLocation,
    val isRelocate: Boolean = true,
    val isLinkAvailable: Boolean = true,
)

/**
 * Individual graph item in the dropdown.
 */
@Composable
fun GraphItem(
    graph: GraphInfo,
    isActive: Boolean,
    isSynced: Boolean = false,
    /** Epic 2.3: true when this graph is the active graph and it currently has a granted
     * host-directory connection — shown as a distinct badge from [graph.path]'s OPFS path,
     * which alone gives no indication the graph is backed by a live local folder. */
    isHostConnected: Boolean = false,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onEditPath: (() -> Unit)? = null,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = graph.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // Prefer the real linked host folder's name over the internal OPFS path —
                    // the path (e.g. "/stelekit/notes") tells the user nothing about which real
                    // folder on disk the graph is backed by once host-directory-livesync is wired up.
                    text = graph.hostDirName?.let { "linked to: $it" } ?: graph.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = (if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        .copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onEditPath != null) {
                IconButton(
                    onClick = onEditPath,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit graph path",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isHostConnected) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = "Connected to local folder",
                    modifier = Modifier.size(14.dp).padding(end = 2.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
            }
            if (isSynced) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Git sync active",
                    modifier = Modifier.size(14.dp).padding(end = 2.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
            }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    .clickable { onNavigateToPage(block.pageUuid.value) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    text = block.content.take(80),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = block.pageUuid.value.take(8),
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SidebarItem(
    title: String,
    isSelected: Boolean,
    icon: ImageVector,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit,
    hasPendingConflict: Boolean = false,
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
            if (hasPendingConflict) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Unresolved disk conflict") } },
                    state = rememberTooltipState(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Unresolved disk conflict",
                        tint = DiskConflictWarningColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
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
