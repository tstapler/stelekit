// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import dev.stapler.stelekit.platform.FileSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * Android composition root for the Google surfaces: sign-in, Photos import, Drive export and the
 * Drive browser.
 *
 * Returns null unless [GoogleBuildFlags.visible], so a release build constructs nothing and every
 * Google entry point stays hidden (see [GoogleBuildFlags] for when that flips).
 */
object AndroidGoogleIntegration {
    private const val TIMEOUT_MS = 30_000L

    fun create(
        fileSystem: FileSystem,
        oauthClientId: String,
        oauthClientSecret: String = "",
        tokenStore: GoogleTokenStore = AndroidGoogleTokenStore(),
        httpClient: HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = TIMEOUT_MS
                requestTimeoutMillis = TIMEOUT_MS
            }
        },
    ): GoogleIntegration? {
        if (!GoogleBuildFlags.visible) return null
        val api = GoogleApiClient(httpClient, tokenStore, oauthClientId, oauthClientSecret)
        return GoogleIntegration(
            authManager = AndroidGoogleAuthManager(tokenStore, httpClient, oauthClientId, oauthClientSecret),
            driveUploader = api.drive,
            driveBrowser = api.drive,
            photosPicker = GooglePhotosPickerLauncher(api.photos, tokenStore),
            imageRenderer = AndroidAnnotatedImageRenderer(fileSystem),
        )
    }
}
