// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import dev.stapler.stelekit.domain.ArchiveResult
import dev.stapler.stelekit.domain.WaybackMachineService
import kotlinx.coroutines.launch

/**
 * Returns a callback that archives the given URL to the Wayback Machine and shows a
 * snackbar with the result. Pass the returned lambda to [BlockItem] as [onArchiveUrl].
 *
 * [onBlockContentUpdate] is called with (blockUuid, appendedText) when archiving succeeds,
 * so the caller can append the archive link to the block content.
 */
@Composable
fun rememberArchiveUrlAction(
    waybackService: WaybackMachineService?,
    snackbarHostState: SnackbarHostState,
    onBlockContentUpdate: (blockUuid: String, appendText: String) -> Unit
): ((url: String, blockUuid: String) -> Unit)? {
    if (waybackService == null) return null
    val scope = rememberCoroutineScope()
    return { url, blockUuid ->
        scope.launch {
            snackbarHostState.showSnackbar("Archiving $url…")
            val result = waybackService.archiveUrl(url)
            result.fold(
                ifLeft = { error ->
                    snackbarHostState.showSnackbar("Archive failed: ${error.message}")
                },
                ifRight = { archive ->
                    val archiveUrl = when (archive) {
                        is ArchiveResult.Success -> archive.archiveUrl
                        is ArchiveResult.AlreadyArchived -> archive.archiveUrl
                    }
                    onBlockContentUpdate(blockUuid, " [archived]($archiveUrl)")
                    snackbarHostState.showSnackbar("Archived: $archiveUrl")
                }
            )
        }
    }
}
