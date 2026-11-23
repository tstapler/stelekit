// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.AppState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the page view needs from the app's view model. Narrow on purpose: it keeps `ui_main` from importing the
 * `StelekitViewModel` hub (which sits in the app module above it); the view model implements it.
 */
interface PageViewHost {
    val suggestionMatcher: StateFlow<AhoCorasickMatcher?>
    val localPageNames: StateFlow<Set<String>>
    val uiState: StateFlow<AppState>

    fun proposeLlmSuggestion(suggestion: PendingLlmSuggestion)

    fun exportSelectedBlocks(formatId: String)

    fun exportPage(formatId: String)

    fun showRenameDialog(page: Page)

    fun sendSnackbar(message: String)

    suspend fun getBlockContent(blockUuid: String): String?

    fun searchPages(query: String): Flow<List<SearchResultItem>>

    fun navigateToAnnotationEditor(imageAnnotationUuid: String, pageUuid: String? = null)
}
