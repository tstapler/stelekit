// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import android.content.Intent
import android.net.Uri
import dev.stapler.stelekit.platform.SteleKitContext

/**
 * Android binding of the Google Photos Picker flow: the picker URI opens in the system browser /
 * Custom Tab and the session logic itself lives in [GooglePhotosSessionPicker].
 *
 * UI copy requirement: callers show [GooglePhotosSessionPicker.PICKER_UI_COPY] before launching.
 * Prerequisite: the owner is signed in, otherwise [launchPicker] returns `PermissionDenied`.
 */
class GooglePhotosPickerLauncher(
    apiClient: PhotosPickerPort,
    tokenStore: GoogleTokenStore,
    openPickerUri: (String) -> Unit = ::openInSystemBrowser,
) : PhotosPicker by GooglePhotosSessionPicker(apiClient, tokenStore, openPickerUri)

private fun openInSystemBrowser(uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    SteleKitContext.context.startActivity(intent)
}
