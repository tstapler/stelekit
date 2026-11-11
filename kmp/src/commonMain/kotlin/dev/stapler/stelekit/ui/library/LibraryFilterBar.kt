package dev.stapler.stelekit.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.t

internal fun sortLabelKey(sort: LibrarySort) = when (sort) {
    LibrarySort.RECENTLY_ADDED -> I18n.LIBRARY_SORT_RECENTLY_ADDED
    LibrarySort.RECENTLY_ANNOTATED -> I18n.LIBRARY_SORT_RECENTLY_ANNOTATED
    LibrarySort.MOST_MEASUREMENTS -> I18n.LIBRARY_SORT_MOST_MEASUREMENTS
    LibrarySort.NAME -> I18n.LIBRARY_SORT_NAME
}

internal fun mediaClassLabelKey(c: LibraryMediaClass) = when (c) {
    LibraryMediaClass.IMAGES -> I18n.LIBRARY_FILTER_IMAGES
    LibraryMediaClass.PDFS -> I18n.LIBRARY_FILTER_PDFS
    LibraryMediaClass.AUDIO -> I18n.LIBRARY_FILTER_AUDIO
    LibraryMediaClass.VIDEO -> I18n.LIBRARY_FILTER_VIDEO
    LibraryMediaClass.DOCS -> I18n.LIBRARY_FILTER_DOCUMENTS
    LibraryMediaClass.OTHER -> I18n.LIBRARY_FILTER_OTHER
}

internal fun reasonKey(r: LibraryDisabledReason) = when (r) {
    LibraryDisabledReason.ANNOTATE_NEEDS_PHOTOS -> I18n.LIBRARY_REASON_ANNOTATE_NEEDS_PHOTOS
    LibraryDisabledReason.TYPE_NOT_PHOTO -> I18n.LIBRARY_REASON_TYPE_NOT_PHOTO
}

/**
 * Search, sort and the chip groups. Chips that cannot be combined are dimmed, stay focusable and carry the
 * reason as their state description; tapping one reports the reason through [onReason] instead of changing
 * the filters (LD-1). [compact] collapses the groups behind a single "Filters (n)" button (LD-6).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryFilterBar(
    filters: LibraryFilterState,
    sort: LibrarySort,
    availableTags: List<String>,
    searchHint: Boolean,
    compact: Boolean,
    onChip: (LibraryChip) -> Unit,
    onSearch: (String) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onClear: () -> Unit,
    onReason: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var groupsOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = filters.search,
                onValueChange = onSearch,
                singleLine = true,
                placeholder = { Text(t(I18n.LIBRARY_SEARCH_PLACEHOLDER)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (filters.search.isNotEmpty()) {
                    { IconButton(onClick = { onSearch("") }) { Icon(Icons.Default.Close, contentDescription = t(I18n.LIBRARY_FILTER_CLEAR)) } }
                } else null,
                modifier = Modifier.weight(1f).testTag("library-search"),
            )
            if (compact) {
                OutlinedButton(onClick = { groupsOpen = !groupsOpen }, modifier = Modifier.heightIn(min = 48.dp).testTag("library-filters-button")) {
                    Text("${t(I18n.LIBRARY_GROUP_SHOW)} (${filters.activeChipCount})")
                }
            }
            Column {
                IconButton(onClick = { sortOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("library-sort")) {
                    Icon(Icons.Default.Sort, contentDescription = "${t(I18n.LIBRARY_SORT)}: ${t(sortLabelKey(sort))}")
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(t(sortLabelKey(option))) },
                            leadingIcon = if (option == sort) { { Icon(Icons.Default.Check, contentDescription = null) } } else null,
                            onClick = { sortOpen = false; onSort(option) },
                            modifier = Modifier.testTag("library-sort-${option.name}"),
                        )
                    }
                }
            }
        }
        if (searchHint) {
            Text(t(I18n.LIBRARY_SEARCH_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!compact || groupsOpen) {
            ChipGroups(filters, availableTags, onChip, onReason)
        }
        if (!filters.isDefault) {
            TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp).testTag("library-clear")) {
                Text(t(I18n.LIBRARY_FILTER_CLEAR))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroups(
    filters: LibraryFilterState,
    availableTags: List<String>,
    onChip: (LibraryChip) -> Unit,
    onReason: (String) -> Unit,
) {
    val typeGroup = t(I18n.LIBRARY_GROUP_TYPE)
    val showGroup = t(I18n.LIBRARY_GROUP_SHOW)
    val tagGroup = t(I18n.LIBRARY_GROUP_TAGS)
    Column {
    GroupLabel(typeGroup)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = typeGroup }) {
        LibraryMediaClass.entries.forEach { c ->
            val chip = LibraryChip.Type(c)
            RuleChip(t(mediaClassLabelKey(c)), c in filters.types, chip, filters, onChip, onReason, "library-chip-type-${c.name}")
        }
    }
    GroupLabel(showGroup)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = showGroup }) {
        RuleChip(t(I18n.LIBRARY_FILTER_ANNOTATED), filters.annotated, LibraryChip.Annotated, filters, onChip, onReason, "library-chip-annotated")
        RuleChip(t(I18n.LIBRARY_FILTER_ORPHANED), filters.orphaned, LibraryChip.Orphaned, filters, onChip, onReason, "library-chip-orphaned")
    }
    if (availableTags.isNotEmpty() || filters.tags.isNotEmpty()) {
        GroupLabel(tagGroup)
        val shown = (filters.tags + availableTags.filter { it !in filters.tags }).take(TAG_CHIP_LIMIT)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = tagGroup }) {
            shown.forEach { tag ->
                RuleChip(tag, tag in filters.tags, LibraryChip.Tag(tag), filters, onChip, onReason, "library-chip-tag-$tag")
            }
        }
    }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleChip(
    label: String,
    selected: Boolean,
    chip: LibraryChip,
    filters: LibraryFilterState,
    onChip: (LibraryChip) -> Unit,
    onReason: (String) -> Unit,
    tag: String,
) {
    val reason = LibraryFilterRules.disabledReason(chip, filters)?.let { t(reasonKey(it)) }
    FilterChip(
        selected = selected,
        onClick = { if (reason != null) onReason(reason) else onChip(chip) },
        label = { Text(label) },
        leadingIcon = if (selected) { { Icon(Icons.Default.Check, contentDescription = null) } } else null,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .alpha(if (reason != null) DIMMED_ALPHA else 1f)
            .testTag(tag)
            .semantics { if (reason != null) stateDescription = reason },
    )
}

private const val DIMMED_ALPHA = 0.5f
private const val TAG_CHIP_LIMIT = 8
