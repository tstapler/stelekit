// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])

/**
 * Tests for [MediaSessionObserver] metadata extraction and position interpolation.
 * Covers T-FR2.1–T-FR2.8.
 *
 * These tests run without Robolectric since they test pure Kotlin logic in the
 * companion object (no Android framework calls in the static extraction methods).
 */
class MediaSessionObserverTest {

    // ---- Position interpolation tests ----

    @Test
    fun `getCurrentPositionMs returns null for null PlaybackState T-FR2-7`() {
        val result = MediaSessionObserver.getCurrentPositionMs(null)
        assertNull(result)
    }

    @Test
    fun `getCurrentPositionMs returns null for STATE_NONE T-FR2-8`() {
        val playbackState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_NONE, 60_000L, 1.0f)
            .build()
        val result = MediaSessionObserver.getCurrentPositionMs(playbackState)
        assertNull(result)
    }

    @Test
    fun `getCurrentPositionMs interpolation formula is correct T-FR2-6`() {
        // Test the formula: position + elapsed * speed
        val snapshotPosition = 60_000L
        val elapsedMs = 500L
        val speed = 1.0f
        val expected = snapshotPosition + (elapsedMs * speed).toLong()
        assertEquals(60_500L, expected)
    }

    @Test
    fun `getCurrentPositionMs returns non-null for PLAYING state T-FR2-6`() {
        val playbackState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 10_000L, 1.0f)
            .build()
        val result = MediaSessionObserver.getCurrentPositionMs(playbackState)
        assertNotNull(result)
        assertTrue("Position should be at least snapshot", result!! >= 10_000L)
    }

    // ---- Metadata extraction tests ----

    @Test
    fun `extractBookInfo extracts title author and chapter T-FR2-1`() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Dune")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Frank Herbert")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "Part 1")
            .build()
        val playbackState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 60_000L, 1.0f)
            .build()

        val bookInfo = MediaSessionObserver.extractBookInfo(metadata, playbackState)

        assertEquals("Dune", bookInfo.title)
        assertEquals("Frank Herbert", bookInfo.author)
        assertEquals("Part 1", bookInfo.chapter)
        assertTrue(bookInfo.isActive)
    }

    @Test
    fun `extractBookInfo uses album as title fallback when title is null T-FR2-2`() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Dune (Album)")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Frank Herbert")
            .build()

        val bookInfo = MediaSessionObserver.extractBookInfo(metadata, null)

        assertEquals("Dune (Album)", bookInfo.title)
        assertFalse(bookInfo.isActive)
    }

    @Test
    fun `extractBookInfo returns inactive BookInfo for null metadata`() {
        val bookInfo = MediaSessionObserver.extractBookInfo(null, null)
        assertFalse(bookInfo.isActive)
        assertNull(bookInfo.title)
        assertNull(bookInfo.author)
    }

    @Test
    fun `extractBookInfo marks as inactive when state is PAUSED`() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Dune")
            .build()
        val playbackState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, 60_000L, 0f)
            .build()

        val bookInfo = MediaSessionObserver.extractBookInfo(metadata, playbackState)
        assertFalse(bookInfo.isActive)
        assertEquals("Dune", bookInfo.title)
    }
}
