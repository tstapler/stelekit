package dev.stapler.stelekit.google

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.google.FALLBACK_ACCOUNT_LABEL
import dev.stapler.stelekit.platform.google.GoogleBuildFlags
import dev.stapler.stelekit.platform.google.emailFromIdToken
import dev.stapler.stelekit.platform.google.parseAuthCodeResponse
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The token-endpoint reply handling behind Google sign-in, and the release gate for the Google surfaces. */
@OptIn(ExperimentalEncodingApi::class)
class GoogleAuthCodeExchangeTest {
    @AfterTest
    fun resetFlag() {
        GoogleBuildFlags.internalBuild = false
    }

    private fun idToken(claims: String): String =
        "header." + Base64.UrlSafe.encode(claims.encodeToByteArray()).trimEnd('=') + ".signature"

    private val noRefreshBody = """{"access_token":"at","expires_in":3600}"""
    private val refreshOnlyBody = """{"access_token":"at","expires_in":3600,"refresh_token":"rt"}"""
    private fun fullBody(): String {
        val token = idToken("""{"email":"me@example.com"}""")
        return """{"access_token":"at","expires_in":3600,"refresh_token":"rt","id_token":"$token"}"""
    }

    @Test
    fun parseAuthCodeResponse_should_StoreTokensAndReturnEmail_When_ExchangeSucceeds() = runTest {
        val store = FakeGoogleTokenStore(authed = false)

        val result = parseAuthCodeResponse(200, fullBody(), store)

        assertEquals("me@example.com", assertIs<Either.Right<String>>(result).value)
        assertEquals("at", store.accessToken)
        assertEquals("rt", store.refreshToken)
        assertEquals("me@example.com", store.email)
        assertTrue(store.isAuthenticated())
    }

    @Test
    fun parseAuthCodeResponse_should_UseFallbackLabel_When_NoIdToken() = runTest {
        val store = FakeGoogleTokenStore(authed = false)

        val result = parseAuthCodeResponse(200, refreshOnlyBody, store)

        assertEquals(FALLBACK_ACCOUNT_LABEL, assertIs<Either.Right<String>>(result).value)
        assertEquals(FALLBACK_ACCOUNT_LABEL, store.getEmail(), "the Settings card needs a non-null email to show Connected")
    }

    @Test
    fun parseAuthCodeResponse_should_StoreNothingAndOmitTheDescription_When_GoogleRejectsTheCode() = runTest {
        val store = FakeGoogleTokenStore(authed = false)
        val body = """{"error":"invalid_grant","error_description":"Bad code SECRET-CODE-123"}"""

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(parseAuthCodeResponse(400, body, store)).value

        assertEquals(400, error.statusCode)
        assertTrue("invalid_grant" in error.message)
        assertFalse("SECRET-CODE-123" in error.message, "the error description may echo the code")
        assertNull(store.accessToken)
        assertFalse(store.isAuthenticated())
    }

    @Test
    fun parseAuthCodeResponse_should_Fail_When_NoRefreshTokenIsIssued() = runTest {
        val store = FakeGoogleTokenStore(authed = false)

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(parseAuthCodeResponse(200, noRefreshBody, store)).value

        assertTrue("refresh token" in error.message)
        assertNull(store.accessToken, "tokens are not half-saved")
    }

    @Test
    fun parseAuthCodeResponse_should_Fail_When_ReplyIsNotJson() = runTest {
        val store = FakeGoogleTokenStore(authed = false)

        val error = assertIs<Either.Left<DomainError.NetworkError.HttpError>>(parseAuthCodeResponse(502, "<html>bad gateway</html>", store)).value

        assertEquals(502, error.statusCode)
        assertFalse(store.isAuthenticated())
    }

    @Test
    fun emailFromIdToken_should_ReadTheEmailClaim_And_ReturnNullForMalformedTokens() {
        assertEquals("a@b.co", emailFromIdToken(idToken("""{"email":"a@b.co","sub":"1"}""")))
        assertNull(emailFromIdToken(idToken("""{"sub":"1"}""")))
        assertNull(emailFromIdToken("not-a-jwt"))
        assertNull(emailFromIdToken("a.!!!.c"))
        assertNull(emailFromIdToken(null))
    }

    @Test
    fun googleBuildFlags_should_HideSurfaces_When_ReleaseBuild_And_ShowThemWhenInternal() {
        assertFalse(GoogleBuildFlags.RELEASE_VISIBLE, "release exposure stays off until the OAuth device pass (plan item 12.5)")
        assertFalse(GoogleBuildFlags.visible)
        GoogleBuildFlags.internalBuild = true
        assertTrue(GoogleBuildFlags.visible)
    }
}
