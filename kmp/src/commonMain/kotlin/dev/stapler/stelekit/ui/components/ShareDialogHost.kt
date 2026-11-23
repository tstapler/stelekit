// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import dev.stapler.stelekit.ui.ShareScope
import kotlinx.datetime.LocalDate

/**
 * What the share dialog needs from the app's view model. Narrow on purpose: it keeps `ui_main` from importing the
 * `StelekitViewModel` hub (which sits in the app module above it); the view model implements it.
 */
interface ShareDialogHost {
    fun setShareFormat(format: String)

    fun setShareScope(scope: ShareScope)

    fun setStripTrackingParams(enabled: Boolean)

    suspend fun resolveExportContent(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        formatId: String,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
    ): Either<DomainError, String>

    fun exportScopeToClipboard(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        formatId: String,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
        onDone: () -> Unit = {},
    )

    fun launchGoogleAuth(manager: GoogleAuthManager)

    fun refreshShareGoogleAuthState(manager: GoogleAuthManager)

    fun shareToGoogleDocs(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        driveClient: DriveUploader,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
    )
}
