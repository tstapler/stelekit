// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidSpeechRecognizerPermissionTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `listen returns PermissionDenied immediately when requestMicPermission returns false`() = runTest {
        // Regression: pre-fix, no permission check existed — the provider called
        // SpeechRecognizer.startListening() directly, which on a device without RECORD_AUDIO
        // granted would fail with ERROR_INSUFFICIENT_PERMISSIONS and show an error rather than
        // prompting the user.
        val provider = AndroidSpeechRecognizerProvider(
            context = context,
            requestMicPermission = { false },
        )
        val result = provider.listen()
        assertEquals(TranscriptResult.Failure.PermissionDenied, result)
    }

    @Test
    fun `listen with permission granted proceeds past the permission check`() = runTest {
        // Verifies the permission check is not over-eager: when permission is granted,
        // we proceed to listenInternal() rather than short-circuiting.
        // listenInternal() will fail in the test environment (no real speech service),
        // but the important assertion is that PermissionDenied is NOT returned.
        val provider = AndroidSpeechRecognizerProvider(
            context = context,
            requestMicPermission = { true },
        )
        // listenInternal() posts to mainHandler and suspends — in Robolectric it will
        // typically error out via onError(ERROR_CLIENT), which maps to ApiError, not PermissionDenied.
        val result = provider.listen()
        assert(result !is TranscriptResult.Failure.PermissionDenied) {
            "Expected any result other than PermissionDenied when permission is granted, got $result"
        }
    }
}
