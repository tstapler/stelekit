package dev.stapler.stelekit.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.capture.InboxItem
import dev.stapler.stelekit.capture.InboxItemStatus
import dev.stapler.stelekit.capture.InboxSlot
import dev.stapler.stelekit.capture.RetryResult
import dev.stapler.stelekit.capture.ShareInboxState
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.Notification
import dev.stapler.stelekit.model.NotificationDuration
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.performance.TraceEvent
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.ui.ShareInboxActions
import dev.stapler.stelekit.ui.components.BrowserOnlySyncBanner
import dev.stapler.stelekit.ui.components.FolderSyncStatusBadge
import dev.stapler.stelekit.ui.components.GraphItem
import dev.stapler.stelekit.ui.components.HostReconnectBanner
import dev.stapler.stelekit.ui.components.LogItem
import dev.stapler.stelekit.ui.components.NavigationItem
import dev.stapler.stelekit.ui.components.NotificationToast
import dev.stapler.stelekit.ui.components.PendingConflictsBanner
import dev.stapler.stelekit.ui.components.PerformanceEventItem
import dev.stapler.stelekit.ui.components.QueuedSharesBadge
import dev.stapler.stelekit.ui.components.SearchResultRow
import dev.stapler.stelekit.ui.components.SectionContextIndicator
import dev.stapler.stelekit.ui.components.ShareInboxPanelContent
import dev.stapler.stelekit.ui.components.git.GitDetectionBanner
import dev.stapler.stelekit.ui.screens.ParsedQuery
import dev.stapler.stelekit.ui.components.ActivePrefixChipRow
import kotlin.time.Instant

private val FIXED_INSTANT = Instant.fromEpochMilliseconds(1_700_000_000_000L)
private const val FIXED_NOW_MS = 1_700_000_000_000L

private fun toast(type: NotificationType, text: String) =
    Notification("n-${type.name}", text, type, FIXED_INSTANT, NotificationDuration.Permanent)

private val GRAPH_A = GraphId("aaaa")
private val GRAPH_B = GraphId("bbbb")

private fun graphName(slot: InboxSlot): String = when (slot) {
    is InboxSlot.Graph -> if (slot.id == GRAPH_A) "Personal notes" else "Work wiki"
    InboxSlot.Unassigned -> "No graph yet"
}

private fun graphInfo(name: String, path: String) = GraphInfo(
    id = GraphId(name.lowercase().take(8)),
    path = path,
    displayName = name,
    addedAt = 0L,
)

private val sampleShares = ShareInboxState(
    items = listOf(
        InboxItem(InboxSlot.Graph(GRAPH_A), "c1", FIXED_NOW_MS - 300_000, "Great article on KMP resource handling https://example.com/kmp", InboxItemStatus.Ready, false),
        InboxItem(InboxSlot.Graph(GRAPH_B), "c2", FIXED_NOW_MS - 7_200_000, "Meeting notes: ship the release on Friday", InboxItemStatus.Ready, true, "Graph is read-only"),
        InboxItem(InboxSlot.Unassigned, "c3", FIXED_NOW_MS - 86_400_000, "Saved before any graph existed", InboxItemStatus.Ready, false),
        InboxItem(InboxSlot.Graph(GRAPH_A), "c4", FIXED_NOW_MS - 172_800_000, "Written by a newer version", InboxItemStatus.NeedsNewerApp(9), false),
    ),
)

private val noopShareActions = object : ShareInboxActions {
    override suspend fun copyText(item: InboxItem): String? = item.text
    override suspend fun discard(item: InboxItem): Boolean = true
    override suspend fun retryNow(item: InboxItem): RetryResult = RetryResult.Kept
}

/** Stories for the chrome category -- see [ComponentGallery]. */
internal val chromeStories: List<GalleryStory> = listOf(
    GalleryStory("chrome-browser-only-sync-banner") {
        BrowserOnlySyncBanner(onEnableSync = {}, onDismiss = {})
    },
    GalleryStory("chrome-host-reconnect-banner") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HostReconnectBanner(HostAccessState.PromptNeeded, onReconnect = {}, onDismiss = {})
            HostReconnectBanner(HostAccessState.Denied, onReconnect = {}, onDismiss = {})
            HostReconnectBanner(HostAccessState.Granted, onReconnect = {}, onDismiss = {}, degraded = true)
        }
    },
    GalleryStory("chrome-folder-sync-status-badge") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FolderSyncStatusBadge(HostAccessState.Granted, "notes", 0, onReconnect = {})
            FolderSyncStatusBadge(HostAccessState.Granted, "notes", 3, onReconnect = {})
            FolderSyncStatusBadge(HostAccessState.Granted, "notes", 3, onReconnect = {}, hostWriteStuck = true)
            FolderSyncStatusBadge(HostAccessState.PromptNeeded, "notes", 0, onReconnect = {})
            FolderSyncStatusBadge(HostAccessState.Denied, "notes", 0, onReconnect = {})
            FolderSyncStatusBadge(HostAccessState.Disconnected("folder moved"), null, 0, onReconnect = {})
            FolderSyncStatusBadge(HostAccessState.Unlinked, null, 0, onReconnect = {})
            FolderSyncStatusBadge(HostAccessState.NotApplicable, null, 0, onReconnect = {})
        }
    },
    GalleryStory("chrome-git-detection-banner") {
        GitDetectionBanner(repoRoot = "/home/user/notes", onSetupSync = {}, onDismiss = {})
    },
    GalleryStory("chrome-pending-conflicts-banner") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PendingConflictsBanner(count = 1, onClick = {})
            PendingConflictsBanner(count = 5, onClick = {})
        }
    },
    GalleryStory("chrome-graph-item") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GraphItem(graphInfo("Personal notes", "/home/user/notes"), isActive = true, isSynced = true, onSelect = {}, onRemove = {})
            GraphItem(graphInfo("Work wiki", "/home/user/work"), isActive = false, onSelect = {}, onRemove = {}, onEditPath = {})
            GraphItem(graphInfo("Browser graph", "opfs://browser"), isActive = true, isHostConnected = true, onSelect = {})
        }
    },
    GalleryStory("chrome-navigation-item") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NavigationItem("Journals", Icons.Default.Home, isSelected = true, onClick = {})
            NavigationItem("Settings", Icons.Default.Settings, isSelected = false, onClick = {})
        }
    },
    GalleryStory("chrome-notification-toast") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotificationToast(toast(NotificationType.INFO, "Graph loaded: 8,030 pages"), onDismiss = {})
            NotificationToast(toast(NotificationType.SUCCESS, "Page saved"), onDismiss = {})
            NotificationToast(toast(NotificationType.WARNING, "File changed on disk"), onDismiss = {})
            NotificationToast(toast(NotificationType.ERROR, "Failed to write page: permission denied"), onDismiss = {})
        }
    },
    GalleryStory("chrome-queued-shares-badge") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QueuedSharesBadge(sampleShares, ::graphName, onClick = {})
            QueuedSharesBadge(
                ShareInboxState(items = sampleShares.items.take(1)),
                ::graphName,
                onClick = {},
            )
        }
    },
    GalleryStory("chrome-share-inbox-panel") {
        ShareInboxPanelContent(
            state = sampleShares,
            graphNameOf = ::graphName,
            actions = noopShareActions,
            onCopyToClipboard = {},
            nowEpochMs = { FIXED_NOW_MS },
        )
    },
    GalleryStory("chrome-section-context-indicator") {
        val manifest = SectionManifest(
            sections = listOf(
                SectionDefinition("work", "Work", "#3B82F6", "pages/work/", "journals/work/"),
                SectionDefinition("home", "Home", null, "pages/home/", "journals/home/"),
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionContextIndicator("", manifest, onClick = {})
            SectionContextIndicator("work", manifest, onClick = {})
            SectionContextIndicator("home", manifest, onClick = {})
        }
    },
    GalleryStory("chrome-search-result-row") {
        Column {
            SearchResultRow(
                title = "Kotlin Multiplatform",
                isSelected = true,
                onClick = {},
                subtitle = "Programming > Kotlin",
                relativeDate = "Mar 4",
                inlineTags = listOf("kotlin", "kmp"),
            )
            SearchResultRow(
                title = "Release checklist",
                isSelected = false,
                onClick = {},
                subtitle = "Projects",
                snippet = "Verify the <em>release</em> build before publishing",
            )
            SearchResultRow(title = "Plain title only", subtitle = "Journal", isSelected = false, onClick = {})
        }
    },
    GalleryStory("chrome-active-prefix-chips") {
        ActivePrefixChipRow(
            parsedQuery = ParsedQuery(
                ftsTerm = "release",
                tagFilter = "kotlin",
                propertyFilters = mapOf("status" to "done"),
            ),
            onRemoveTag = {},
            onRemoveScope = {},
            onRemoveDate = {},
            onRemoveProperty = {},
        )
    },
    GalleryStory("chrome-log-item") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(0.dp)) {
            LogLevel.entries.forEach { level ->
                LogItem(LogEntry(level, "GraphLoader", "Loaded 8030 pages in 1.2s ($level)", FIXED_INSTANT))
            }
        }
    },
    GalleryStory("chrome-performance-event-item") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PerformanceEventItem(TraceEvent("GraphLoader.loadDirectory", 0L, 12L, "trace", "DefaultDispatcher-worker-1"))
            PerformanceEventItem(TraceEvent("BlockRepository.save", 0L, 240L, "trace", "DefaultDispatcher-worker-2"))
            PerformanceEventItem(TraceEvent("Sidebar.recompose", 0L, 1800L, "trace", "main"))
        }
    },
)
