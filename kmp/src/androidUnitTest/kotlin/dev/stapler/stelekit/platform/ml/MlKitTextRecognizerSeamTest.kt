// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Branches of [MlKitTextRecognizer] that do not need the ML Kit model, driven through its seams. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MlKitTextRecognizerSeamTest {
    private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test
    fun undecodableBytes_returnEmptyText_andNeverCallMlKit() {
        var called = false
        val recognizer = MlKitTextRecognizer(decode = { null }, readText = { called = true; "x" })

        assertEquals("", runBlocking { recognizer.recognize(byteArrayOf(1)) }.getOrNull())
        assertFalse(called)
    }

    @Test
    fun recognizedText_isReturned_andTheBitmapIsRecycled() {
        val bmp = bitmap()
        val recognizer = MlKitTextRecognizer(decode = { bmp }, readText = { "Invoice 4821" })

        assertEquals("Invoice 4821", runBlocking { recognizer.recognize(byteArrayOf(1)) }.getOrNull())
        assertTrue(bmp.isRecycled)
    }

    @Test
    fun mlKitFailure_becomesLeft_andTheBitmapIsStillRecycled() {
        val bmp = bitmap()
        val recognizer = MlKitTextRecognizer(decode = { bmp }, readText = { error("model missing") })

        val result = runBlocking { recognizer.recognize(byteArrayOf(1)) }

        assertTrue(assertNotNull(result.leftOrNull()).message.contains("model missing"))
        assertTrue(bmp.isRecycled)
    }

    @Test
    fun decoderThrowing_becomesLeft() {
        val recognizer = MlKitTextRecognizer(decode = { error("bad header") }, readText = { "x" })

        assertNotNull(runBlocking { recognizer.recognize(byteArrayOf(1)) }.leftOrNull())
    }

    @Test
    fun cancellation_isRethrown_notMappedToLeft() {
        val recognizer = MlKitTextRecognizer(decode = { bitmap() }, readText = { throw CancellationException("cancelled") })

        assertFailsWith<CancellationException> { runBlocking { recognizer.recognize(byteArrayOf(1)) } }
    }
}
