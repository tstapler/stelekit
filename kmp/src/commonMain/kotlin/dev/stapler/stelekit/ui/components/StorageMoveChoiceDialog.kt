// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.StorageLocation

/** design/ux.md Surface 5, AC-X6: "App storage" never renders a raw path — this is the one place
 * every surface in this feature (Surfaces 5, 6, 8b) should route human-readable location text
 * through, so a `content://` URI or OPFS path can never leak into UI copy by accident. */
fun StorageLocation.describeForHumans(): String = when (this) {
    is StorageLocation.AppOwned -> "App storage"
    is StorageLocation.SafFolder -> "a folder on your device"
    is StorageLocation.DirectAccessFolder -> "a folder on your device"
    is StorageLocation.HostFolder -> "a folder on your device (\"$displayName\")"
}

/**
 * Story 3.4.1: Relocate-vs-Link choice, shaped like [DiskConflictDialog]'s stacked-full-width-
 * button pattern (design/ux.md Surface 5) — unlike that dialog's Button/OutlinedButton pairing
 * (which ranks one option over the other), both options here use the identical [OutlinedButton]
 * style so neither reads as pre-selected/primary (AC18).
 *
 * @param isLinkAvailable false hides the "Link" card entirely (never shown disabled — a broken,
 * no-op affordance is worse than an absent one) for a plain (non-git) Android graph, per Story
 * 4.2.1's acceptance criterion; [linkUnavailableNote] renders in its place.
 * @param isUnlinking true adapts the "Link" card's subtitle to the reverse-of-link framing used
 * when [destination] is [StorageLocation.AppOwned] and [source] is already a link (Surface 5's
 * "un-linking" edge case) — callers compute this from their own source/destination, since this
 * dialog has no independent way to know a link's current sync direction.
 */
@Composable
fun StorageMoveChoiceDialog(
    graphName: String,
    source: StorageLocation,
    destination: StorageLocation,
    isLinkAvailable: Boolean = true,
    isUnlinking: Boolean = false,
    linkUnavailableNote: String = "Continuous sync isn't available yet for graphs without git.",
    onRelocateChosen: () -> Unit,
    onLinkChosen: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("How should \"$graphName\" move?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("From: ${source.describeForHumans()}", style = MaterialTheme.typography.bodySmall)
                Text("To: ${destination.describeForHumans()}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRelocateChosen,
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                ) {
                    Column {
                        Text("Relocate", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Move and stop using the old location. The old copy stays until " +
                                "you confirm it's safe to remove.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (isLinkAvailable) {
                    OutlinedButton(
                        onClick = onLinkChosen,
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    ) {
                        Column {
                            Text("Link", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (isUnlinking) {
                                    "Stop keeping both in sync — this graph becomes App-storage-only."
                                } else {
                                    "Keep both copies in sync. Nothing is ever removed."
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                } else {
                    Text(
                        linkUnavailableNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        },
    )
}
