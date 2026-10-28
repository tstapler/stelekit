// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import dev.stapler.stelekit.domain.ArchiveResult
import dev.stapler.stelekit.domain.WaybackMachineService
import kotlinx.coroutines.launch

/** Archive action for URLs in rendered blocks; null (default) disables right-click archiving. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell; threading it through BlockList/BlockRenderer would touch every page screen
val LocalArchiveUrlAction = staticCompositionLocalOf<((url: String, blockUuid: String) -> Unit)?> { null }

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
    lateinit var archive: (url: String, blockUuid: String) -> Unit
    archive = { url, blockUuid ->
        scope.launch {
            // Not awaited: the in-flight notice must not block the request behind its own timeout.
            val inFlight = launch { snackbarHostState.showSnackbar("Archiving $url…") }
            val result = waybackService.archiveUrl(url)
            inFlight.cancel()
            result.fold(
                ifLeft = {
                    val action = snackbarHostState.showSnackbar("Couldn't archive - Retry", actionLabel = "Retry")
                    if (action == SnackbarResult.ActionPerformed) archive(url, blockUuid)
                },
                ifRight = { archived ->
                    val archiveUrl = when (archived) {
                        is ArchiveResult.Success -> archived.archiveUrl
                        is ArchiveResult.AlreadyArchived -> archived.archiveUrl
                    }
                    onBlockContentUpdate(blockUuid, " [archived]($archiveUrl)")
                    snackbarHostState.showSnackbar("Archived: $archiveUrl")
                }
            )
        }
    }
    return archive
}
