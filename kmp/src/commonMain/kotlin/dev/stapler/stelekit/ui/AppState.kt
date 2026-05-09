package dev.stapler.stelekit.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.stapler.stelekit.docs.AllPagesDocs
import dev.stapler.stelekit.docs.FlashcardsDocs
import dev.stapler.stelekit.docs.HelpPage
import dev.stapler.stelekit.docs.JournalsDocs
import dev.stapler.stelekit.docs.PageViewDocs
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.vault.VaultError
import dev.stapler.stelekit.vault.VaultNamespace
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.ui.i18n.Language

/** Vault unlock state for paranoid-mode graphs. */
sealed interface VaultState {
    data object Locked : VaultState
    data object Unlocking : VaultState
    data class Unlocked(val namespace: VaultNamespace) : VaultState
    data class Error(val error: VaultError) : VaultState
}

sealed class Screen {
    @HelpPage(docs = JournalsDocs::class)
    data object Journals : Screen()

    @HelpPage(docs = FlashcardsDocs::class)
    data object Flashcards : Screen()

    @HelpPage(docs = AllPagesDocs::class)
    data object AllPages : Screen()

    data object LibraryStats : Screen()
    data object Notifications : Screen()
    data object Logs : Screen()
    data object Performance : Screen()
    data object GlobalUnlinkedReferences : Screen()
    data object Import : Screen()

    data object VaultUnlock : Screen()

    @HelpPage(docs = PageViewDocs::class)
    data class PageView(val page: Page) : Screen()
}

data class AppState(
    val sidebarExpanded: Boolean = false,
    val rightSidebarExpanded: Boolean = false,
    val settingsVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isFullyLoaded: Boolean = false,  // True when all background loading is complete
    val themeMode: StelekitThemeMode = StelekitThemeMode.SYSTEM,
    val language: Language = Language.ENGLISH,
    val onboardingCompleted: Boolean = false,
    val currentScreen: Screen = Screen.Journals,
    val currentPage: Page? = null,
    val currentGraphPath: String = "",
    val commandPaletteVisible: Boolean = false,
    val searchDialogVisible: Boolean = false,
    val searchDialogInitialQuery: String = "",
    val commands: List<Command> = emptyList(),
    val statusMessage: String = "Ready",
    // Navigation history for forward/back navigation
    val navigationHistory: List<Screen> = listOf(Screen.Journals),
    val historyIndex: Int = 0,
    // Derived/Cached page lists for UI
    val regularPages: List<Page> = emptyList(),
    val regularPagesOffset: Int = 0,
    val hasMoreRegularPages: Boolean = true,
    val isLoadingMorePages: Boolean = false,
    val journalPages: List<Page> = emptyList(),
    val favoritePages: List<Page> = emptyList(),
    val recentPages: List<Page> = emptyList(),
    val editingBlockId: String? = null,
    val editingCursorIndex: Int? = null,
    // Debug settings
    val isDebugMode: Boolean = false,
    val isDebugMenuVisible: Boolean = false,
    // Accessibility
    val isLeftHanded: Boolean = false,
    // Multi-graph support
    val currentGraphId: String? = null,
    val currentGraphName: String = "",
    val availableGraphs: List<GraphInfo> = emptyList(),
    val isGraphSwitching: Boolean = false,
    // Disk conflict — non-null when a file-watcher change was detected while editing
    val diskConflict: DiskConflict? = null,
    // Write error — non-null when a background DB write failed persistently
    val indexingError: String? = null,
    // Rename dialog — non-null when the rename dialog is open for a specific page
    val renameDialogPage: Page? = null,
    val renameDialogBusy: Boolean = false,
    val renameDialogError: String? = null,
    // Git sync state
    val syncState: SyncState = SyncState.Idle,
    val gitConfig: GitConfig? = null,
    val gitSetupVisible: Boolean = false,
    val conflictResolutionVisible: Boolean = false,
    val gitLogVisible: Boolean = false,
) {
    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
}

/** Opens the global search dialog pre-filled with the given text. */
val LocalOpenSearchWithText = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

data class Command(
    val id: String,
    val label: String,
    val shortcut: String? = null,
    val action: () -> Unit
)

/**
 * Represents a conflict between the user's in-progress edits and an external
 * change detected on disk by the file watcher.
 */
data class DiskConflict(
    val pageUuid: String,
    val pageName: String,
    val filePath: String,
    val editingBlockUuid: String,
    val localContent: String,
    val diskContent: String
)
