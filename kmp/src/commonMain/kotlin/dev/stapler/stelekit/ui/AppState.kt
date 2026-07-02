package dev.stapler.stelekit.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.datetime.LocalDate
import dev.stapler.stelekit.docs.AllPagesDocs
import dev.stapler.stelekit.docs.FlashcardsDocs
import dev.stapler.stelekit.docs.GlobalUnlinkedReferencesDocs
import dev.stapler.stelekit.docs.HelpExempt
import dev.stapler.stelekit.docs.HelpPage
import dev.stapler.stelekit.docs.JournalsDocs
import dev.stapler.stelekit.docs.PageViewDocs
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.vault.VaultError
import dev.stapler.stelekit.vault.VaultNamespace
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
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

    @HelpExempt(reason = "Internal diagnostics screen; developer tooling only, not reachable from user nav")
    data object LibraryStats : Screen()

    @HelpExempt(reason = "System surface shown automatically; users do not navigate to it deliberately")
    data object Notifications : Screen()

    @HelpExempt(reason = "Developer log viewer; reachable only from debug menu")
    data object Logs : Screen()

    @HelpExempt(reason = "Developer profiling screen; reachable only from debug menu")
    data object Performance : Screen()

    @HelpPage(docs = GlobalUnlinkedReferencesDocs::class)
    data object GlobalUnlinkedReferences : Screen()

    @HelpExempt(reason = "Transient wizard step shown during onboarding; not a standing navigation destination")
    data object Import : Screen()

    @HelpExempt(reason = "Shown programmatically when opening a paranoid-mode graph; not a user-initiated nav destination")
    data object VaultUnlock : Screen()

    @HelpPage(docs = PageViewDocs::class)
    data class PageView(val page: Page) : Screen()

    /** Full-screen gallery of annotated images. */
    @HelpExempt(reason = "Full-screen gallery shown from image blocks; not a primary navigation destination")
    data object Gallery : Screen()

    /** Asset browser for viewing and managing all graph assets. */
    @HelpExempt(reason = "Asset management surface; power-user feature not in primary nav")
    data object AssetBrowser : Screen()

    /** Detail view for a single asset. */
    @HelpExempt(reason = "Opened by tapping an asset in the browser; not a primary nav destination")
    data class AssetDetail(val assetUuid: AssetUuid) : Screen()

    /**
     * Annotation editor for a single image annotation.
     *
     * [imageAnnotationUuid] is the UUID of the [ImageAnnotation] to open.
     * [pageUuid] is the UUID of the page that owns the block — used for "Go to page" navigation.
     */
    @HelpExempt(reason = "Entered via image tap, not from sidebar nav; advanced feature for image annotation")
    data class AnnotationEditor(
        val imageAnnotationUuid: String,
        val pageUuid: String? = null,
    ) : Screen()
}

data class AppState(
    val sidebarExpanded: Boolean = false,
    val rightSidebarExpanded: Boolean = false,
    val settingsVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isContentFetching: Boolean = false,
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
    // Developer flags
    val isLibsqlDriverEnabled: Boolean = false,
    // Multi-graph support
    val currentGraphId: String? = null,
    val currentGraphName: String = "",
    val availableGraphs: List<GraphInfo> = emptyList(),
    val isGraphSwitching: Boolean = false,
    // Disk conflict — non-null when a file-watcher change was detected while editing
    val diskConflict: DiskConflict? = null,
    // Pending conflicts — files changed externally while the user was on a different page.
    // Keyed by filePath; shown as a dialog when the user next navigates to that page.
    val pendingConflicts: Map<String, PendingConflict> = emptyMap(),
    // Write error — non-null when a background DB write failed persistently
    val indexingError: String? = null,
    // Fatal error — non-null when a Throwable-level crash was caught and converted to a
    // recoverable state. Shown on the error report screen so the user can copy the message.
    val fatalError: String? = null,
    // Rename dialog — non-null when the rename dialog is open for a specific page
    val renameDialogPage: Page? = null,
    val renameDialogBusy: Boolean = false,
    val renameDialogError: String? = null,
    // Git sync state
    val syncState: SyncState = SyncState.Idle,
    val gitConfig: GitConfig? = null,
    val gitSetupVisible: Boolean = false,
    val gitSetupInitialStep: Int = 1,
    val gitSetupOpenForClone: Boolean = false,
    // LLM provider settings — mirrors gitSetupVisible/openGitSetup()/dismissGitSetup() (Epic 6
    // Story 6.1). Drives direct-open + auto-selection of the "AI Providers" settings category.
    val llmProviderSettingsVisible: Boolean = false,
    val conflictResolutionVisible: Boolean = false,
    val journalMergeReviewVisible: Boolean = false,
    // Export in-flight: true while an exportPage/exportSelectedBlocks coroutine is running
    val isExporting: Boolean = false,
    // Share dialog state
    val shareDialogVisible: Boolean = false,
    val shareFormat: String = "markdown",
    val shareScope: ShareScope = ShareScope.CurrentPage,
    val shareJournalFromDate: LocalDate? = null,
    val shareJournalToDate: LocalDate? = null,
    val shareIsGoogleAuthenticated: Boolean = false,
    val shareGoogleEmail: String? = null,
    val isExportingToDrive: Boolean = false,
    // Section support
    val currentManifest: SectionManifest? = null,
    val currentSectionStates: Map<String, SectionState> = emptyMap(),
    val defaultSection: String = "",
    val deviceSetupComplete: Boolean = false,
    val sectionPickerVisible: Boolean = false,
    val sectionPickerPage: Page? = null,
    val deviceSetupWizardVisible: Boolean = false,
    val sectionQuickToggleVisible: Boolean = false,
) {
    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
    val hasSectionFilter: Boolean
        get() = currentManifest?.sections?.isNotEmpty() == true &&
            currentSectionStates.values.any { it != SectionState.ACTIVE }
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

/**
 * A disk conflict detected while the user was NOT viewing the affected page.
 * Stored until the user navigates to that page, at which point [DiskConflict] is
 * built from current DB blocks and the captured disk content.
 */
data class PendingConflict(
    val filePath: String,
    val pageName: String,
    val diskContent: String,
)
