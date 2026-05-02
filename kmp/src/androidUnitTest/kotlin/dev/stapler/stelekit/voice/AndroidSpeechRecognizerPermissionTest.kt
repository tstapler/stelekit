// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `listen with permission granted calls requestMicPermission and does not short-circuit`() =
        runTest(UnconfinedTestDispatcher()) {
            // Verifies the permission check is not over-eager: when permission is granted,
            // requestMicPermission is invoked and we proceed into listenInternal().
            // UnconfinedTestDispatcher runs the async body eagerly on the calling thread until
            // the first real suspension point (suspendCancellableCoroutine inside listenInternal),
            // so permissionWasChecked is true before we cancel — no main-looper involvement needed.
            var permissionWasChecked = false
            val provider = AndroidSpeechRecognizerProvider(
                context = context,
                requestMicPermission = {
                    permissionWasChecked = true
                    true
                },
            )
            val deferred = async { provider.listen() }
            // Coroutine has already passed the permission check and suspended in listenInternal().
            deferred.cancel()
            assertTrue(permissionWasChecked, "requestMicPermission must be invoked when present")
        }
}
