// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import arrow.optics.Lens
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.theme.StelekitThemeMode

object AppStateOptics {
    val sidebarExpanded: Lens<AppState, Boolean> = Lens(get = { it.sidebarExpanded }, set = { s, v -> s.copy(sidebarExpanded = v) })
    val rightSidebarExpanded: Lens<AppState, Boolean> = Lens(get = { it.rightSidebarExpanded }, set = { s, v -> s.copy(rightSidebarExpanded = v) })
    val settingsVisible: Lens<AppState, Boolean> = Lens(get = { it.settingsVisible }, set = { s, v -> s.copy(settingsVisible = v) })
    val isLoading: Lens<AppState, Boolean> = Lens(get = { it.isLoading }, set = { s, v -> s.copy(isLoading = v) })
    val isFullyLoaded: Lens<AppState, Boolean> = Lens(get = { it.isFullyLoaded }, set = { s, v -> s.copy(isFullyLoaded = v) })
    val themeMode: Lens<AppState, StelekitThemeMode> = Lens(get = { it.themeMode }, set = { s, v -> s.copy(themeMode = v) })
    val language: Lens<AppState, Language> = Lens(get = { it.language }, set = { s, v -> s.copy(language = v) })
    val onboardingCompleted: Lens<AppState, Boolean> = Lens(get = { it.onboardingCompleted }, set = { s, v -> s.copy(onboardingCompleted = v) })
    val currentScreen: Lens<AppState, Screen> = Lens(get = { it.currentScreen }, set = { s, v -> s.copy(currentScreen = v) })
    val currentPage: Lens<AppState, Page?> = Lens(get = { it.currentPage }, set = { s, v -> s.copy(currentPage = v) })
    val currentGraphPath: Lens<AppState, String> = Lens(get = { it.currentGraphPath }, set = { s, v -> s.copy(currentGraphPath = v) })
    val commandPaletteVisible: Lens<AppState, Boolean> = Lens(get = { it.commandPaletteVisible }, set = { s, v -> s.copy(commandPaletteVisible = v) })
    val searchDialogVisible: Lens<AppState, Boolean> = Lens(get = { it.searchDialogVisible }, set = { s, v -> s.copy(searchDialogVisible = v) })
    val commands: Lens<AppState, List<Command>> = Lens(get = { it.commands }, set = { s, v -> s.copy(commands = v) })
    val statusMessage: Lens<AppState, String> = Lens(get = { it.statusMessage }, set = { s, v -> s.copy(statusMessage = v) })
    val navigationHistory: Lens<AppState, List<Screen>> = Lens(get = { it.navigationHistory }, set = { s, v -> s.copy(navigationHistory = v) })
    val historyIndex: Lens<AppState, Int> = Lens(get = { it.historyIndex }, set = { s, v -> s.copy(historyIndex = v) })
    val regularPages: Lens<AppState, List<Page>> = Lens(get = { it.regularPages }, set = { s, v -> s.copy(regularPages = v) })
    val regularPagesOffset: Lens<AppState, Int> = Lens(get = { it.regularPagesOffset }, set = { s, v -> s.copy(regularPagesOffset = v) })
    val hasMoreRegularPages: Lens<AppState, Boolean> = Lens(get = { it.hasMoreRegularPages }, set = { s, v -> s.copy(hasMoreRegularPages = v) })
    val isLoadingMorePages: Lens<AppState, Boolean> = Lens(get = { it.isLoadingMorePages }, set = { s, v -> s.copy(isLoadingMorePages = v) })
    val journalPages: Lens<AppState, List<Page>> = Lens(get = { it.journalPages }, set = { s, v -> s.copy(journalPages = v) })
    val favoritePages: Lens<AppState, List<Page>> = Lens(get = { it.favoritePages }, set = { s, v -> s.copy(favoritePages = v) })
    val recentPages: Lens<AppState, List<Page>> = Lens(get = { it.recentPages }, set = { s, v -> s.copy(recentPages = v) })
    val editingBlockId: Lens<AppState, String?> = Lens(get = { it.editingBlockId }, set = { s, v -> s.copy(editingBlockId = v) })
    val editingCursorIndex: Lens<AppState, Int?> = Lens(get = { it.editingCursorIndex }, set = { s, v -> s.copy(editingCursorIndex = v) })
    val isDebugMode: Lens<AppState, Boolean> = Lens(get = { it.isDebugMode }, set = { s, v -> s.copy(isDebugMode = v) })
    val isDebugMenuVisible: Lens<AppState, Boolean> = Lens(get = { it.isDebugMenuVisible }, set = { s, v -> s.copy(isDebugMenuVisible = v) })
    val isLeftHanded: Lens<AppState, Boolean> = Lens(get = { it.isLeftHanded }, set = { s, v -> s.copy(isLeftHanded = v) })
    val currentGraphId: Lens<AppState, String?> = Lens(get = { it.currentGraphId }, set = { s, v -> s.copy(currentGraphId = v) })
    val currentGraphName: Lens<AppState, String> = Lens(get = { it.currentGraphName }, set = { s, v -> s.copy(currentGraphName = v) })
    val availableGraphs: Lens<AppState, List<GraphInfo>> = Lens(get = { it.availableGraphs }, set = { s, v -> s.copy(availableGraphs = v) })
    val isGraphSwitching: Lens<AppState, Boolean> = Lens(get = { it.isGraphSwitching }, set = { s, v -> s.copy(isGraphSwitching = v) })
    val diskConflict: Lens<AppState, DiskConflict?> = Lens(get = { it.diskConflict }, set = { s, v -> s.copy(diskConflict = v) })
    val indexingError: Lens<AppState, String?> = Lens(get = { it.indexingError }, set = { s, v -> s.copy(indexingError = v) })
    val renameDialogPage: Lens<AppState, Page?> = Lens(get = { it.renameDialogPage }, set = { s, v -> s.copy(renameDialogPage = v) })
    val renameDialogBusy: Lens<AppState, Boolean> = Lens(get = { it.renameDialogBusy }, set = { s, v -> s.copy(renameDialogBusy = v) })
    val renameDialogError: Lens<AppState, String?> = Lens(get = { it.renameDialogError }, set = { s, v -> s.copy(renameDialogError = v) })
}
