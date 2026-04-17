package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.repository.SearchScope

private data class ScopeOption(val scope: SearchScope, val label: String)

private val SCOPE_OPTIONS = listOf(
    ScopeOption(SearchScope.ALL, "All"),
    ScopeOption(SearchScope.PAGES_ONLY, "Pages"),
    ScopeOption(SearchScope.BLOCKS_ONLY, "Blocks"),
    ScopeOption(SearchScope.JOURNAL, "Journal"),
    ScopeOption(SearchScope.FAVORITES, "Favorites"),
    ScopeOption(SearchScope.CURRENT_PAGE, "This page"),
)

/**
 * Horizontal scrolling row of [FilterChip]s for selecting a [SearchScope].
 *
 * [currentScope] controls which chip appears selected.
 * [onScopeChange] is called when the user taps a chip.
 * [showCurrentPage] controls whether the "This page" chip is visible (hide when not in a page context).
 */
@Composable
fun FilterBar(
    currentScope: SearchScope,
    onScopeChange: (SearchScope) -> Unit,
    modifier: Modifier = Modifier,
    showCurrentPage: Boolean = false
) {
    val options = if (showCurrentPage) SCOPE_OPTIONS else SCOPE_OPTIONS.filter {
        it.scope != SearchScope.CURRENT_PAGE
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        items(options) { option ->
            FilterChip(
                selected = currentScope == option.scope,
                onClick = { onScopeChange(option.scope) },
                label = { Text(option.label) }
            )
        }
    }
}
