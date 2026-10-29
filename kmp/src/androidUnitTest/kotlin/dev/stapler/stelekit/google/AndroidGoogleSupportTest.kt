// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.google

import android.graphics.Bitmap
import arrow.core.Either
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.platform.google.AndroidAnnotatedImageRenderer
import dev.stapler.stelekit.platform.google.AndroidGoogleAuthManager
import dev.stapler.stelekit.platform.google.GooglePhotosPickerLauncher
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Android-only Google pieces that need a real `android.graphics` / `android.util.Base64`. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidGoogleSupportTest {
    @Test
    fun pkceChallenge_matchesTheRfc7636AppendixBVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AndroidGoogleAuthManager.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun authenticate_failsFastWithoutAClientId_andOpensNoBrowser() {
        val manager = AndroidGoogleAuthManager(FakeStore(), HttpClient(), clientId = "")

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(runBlocking { manager.authenticate() }).value

        assertEquals(400, error.statusCode)
    }

    @Test
    fun signOut_clearsTheStoredTokens() = runBlocking {
        val store = FakeStore()
        val manager = AndroidGoogleAuthManager(store, HttpClient(), clientId = "id")
        assertTrue(manager.isAuthenticated())

        manager.signOut()

        assertEquals(false, manager.isAuthenticated())
    }

    @Test
    fun launcher_refusesWhenSignedOut_withoutOpeningTheBrowser() {
        var opened = 0
        val launcher = GooglePhotosPickerLauncher(
            apiClient = dev.stapler.stelekit.platform.google.PhotosPickerApiClient(
                dev.stapler.stelekit.platform.google.GoogleAuthClient(FakeStore(authed = false), HttpClient(), "", ""),
                HttpClient(),
            ),
            tokenStore = FakeStore(authed = false),
            openPickerUri = { opened++ },
        )

        val result = runBlocking { launcher.launchPicker() }

        assertIs<DomainError.SensorError.PermissionDenied>(assertIs<Either.Left<DomainError>>(result).value)
        assertEquals(0, opened)
    }

    // ── renderer ───────────────────────────────────────────────────────────────

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun annotation(path: String) = ImageAnnotation(
        uuid = "img-render-001",
        blockUuid = "blk-render-001",
        pageUuid = "page-render-001",
        graphPath = "/graph",
        filePath = path,
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
    )

    @Test
    fun renderer_encodesAJpeg_forADecodableSource() {
        val fs = FakeFileSystem().apply { writeFileBytes("/graph/assets/p.png", pngBytes(64, 48)) }

        val result = runBlocking { AndroidAnnotatedImageRenderer(fs).renderJpeg(annotation("/graph/assets/p.png"), emptyList()) }

        val jpeg = assertIs<Either.Right<ByteArray>>(result).value
        assertTrue(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte(), "output is a JPEG (SOI marker)")
    }

    @Test
    fun renderer_failsWithTheNothingUploadedCopy_forAMissingOrUndecodableSource() {
        val fs = FakeFileSystem().apply { writeFileBytes("/graph/assets/garbage.png", byteArrayOf(1, 2, 3)) }
        val renderer = AndroidAnnotatedImageRenderer(fs)

        val missing = runBlocking { renderer.renderJpeg(annotation("/graph/assets/none.png"), emptyList()) }
        val garbage = runBlocking { renderer.renderJpeg(annotation("/graph/assets/garbage.png"), emptyList()) }

        for (result in listOf(missing, garbage)) {
            val error = assertIs<Either.Left<DomainError>>(result).value
            assertTrue(error.message.startsWith("Couldn't encode the image - nothing was uploaded"), error.message)
        }
    }

    private class FakeStore(private var authed: Boolean = true) : dev.stapler.stelekit.platform.google.GoogleTokenStore {
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {}
        override suspend fun getAccessToken(): String? = null
        override suspend fun getRefreshToken(): String? = null
        override suspend fun getExpiresAt(): Long? = null
        override suspend fun clearTokens() { authed = false }
        override suspend fun isAuthenticated() = authed
        override suspend fun saveEmail(email: String) {}
        override suspend fun getEmail(): String? = null
    }
}
