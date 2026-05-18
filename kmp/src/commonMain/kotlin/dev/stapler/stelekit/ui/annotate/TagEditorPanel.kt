// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Compact tag editor panel for the annotation editor.
 *
 * Displays current tags as dismissible chips in a horizontal scroll row.
 * A [TextField] at the end lets the user type a new tag and confirm with Enter / IME action.
 *
 * Tag format is Logseq-compatible: lower-case alphanumeric with hyphens.
 * The caller is responsible for deduplication (via [AnnotationEditorViewModel.addTag]).
 *
 * Note on Logseq compatibility: the `::tags::` property in Logseq uses `[[tag1]], [[tag2]]`
 * format. Tags stored in [ImageAnnotation.tags] are the raw strings; the `::tags::` property
 * rendering with brackets is the responsibility of the export/sync layer.
 */
@Composable
fun TagEditorPanel(
    tags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier,
    existingTagSuggestions: List<String> = emptyList(),
) {
    var tagInput by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    val filteredSuggestions = remember(tagInput, existingTagSuggestions) {
        if (tagInput.isBlank()) emptyList()
        else existingTagSuggestions
            .filter { it.contains(tagInput.trim(), ignoreCase = true) && it !in tags }
            .take(5)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // Current tags as dismissible chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(tag) },
                    trailingIcon = {
                        IconButton(
                            onClick = { onRemoveTag(tag) },
                            modifier = Modifier.size(16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove tag $tag",
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Tag input field + autocomplete suggestions
        Box {
            OutlinedTextField(
                value = tagInput,
                onValueChange = { newValue ->
                    tagInput = newValue
                    showSuggestions = newValue.isNotBlank()
                },
                placeholder = { Text("Add tag…") },
                singleLine = true,
                trailingIcon = {
                    if (tagInput.isNotBlank()) {
                        IconButton(onClick = {
                            onAddTag(tagInput.trim())
                            tagInput = ""
                            showSuggestions = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add tag",
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (tagInput.isNotBlank()) {
                        onAddTag(tagInput.trim())
                        tagInput = ""
                        showSuggestions = false
                    }
                }),
                modifier = Modifier.fillMaxWidth(),
            )

            // Autocomplete dropdown
            DropdownMenu(
                expanded = showSuggestions && filteredSuggestions.isNotEmpty(),
                onDismissRequest = { showSuggestions = false },
            ) {
                filteredSuggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(suggestion) },
                        onClick = {
                            onAddTag(suggestion)
                            tagInput = ""
                            showSuggestions = false
                        },
                    )
                }
            }
        }
    }
}
