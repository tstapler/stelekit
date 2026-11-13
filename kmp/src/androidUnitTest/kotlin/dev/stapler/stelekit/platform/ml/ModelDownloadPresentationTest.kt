// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDownloadPresentationTest {
    private val online = NetworkSnapshot(online = true, isMetered = false)
    private val metered = NetworkSnapshot(online = true, isMetered = true)
    private val offline = NetworkSnapshot(online = false, isMetered = null)
    private val anyway = ModelDownloadAction.DownloadNowAnyway
    private val cancel = ModelDownloadAction.Cancel

    private fun row(input: ModelDownloadInput) = ModelDownloadPresentation.present(input)!!

    @Test
    fun queued_should_offerDownloadAnywayAndCancel_When_meteredOrUnknownAndUnmeteredRequired() {
        for (net in listOf(metered, NetworkSnapshot(online = true, isMetered = null), null)) {
            val r = row(ModelDownloadInput(ModelWorkState.Queued, network = net))
            assertEquals("Waiting for an unmetered network", r.text)
            assertEquals(listOf(anyway, cancel), r.actions)
            assertTrue(r.isWaiting)
        }
    }

    @Test
    fun queued_should_offerOnlyCancel_When_offline() {
        val r = row(ModelDownloadInput(ModelWorkState.Queued, network = offline))
        assertEquals("Offline - will download when you are online", r.text)
        assertEquals(listOf(cancel), r.actions)
    }

    @Test
    fun queued_should_sayItRetriesAutomatically_When_aRealAttemptAlreadyFailed() {
        val r = row(ModelDownloadInput(ModelWorkState.Queued, network = online, attempt = 2))
        assertEquals("Download failed. It will retry automatically.", r.text)
        assertEquals(listOf(cancel), r.actions)
    }

    @Test
    fun queued_should_notOfferAnyway_When_requestAlreadyAcceptsMetered() {
        val r = row(ModelDownloadInput(ModelWorkState.Queued, network = metered, requiresUnmetered = false))
        assertEquals(listOf(cancel), r.actions)
    }

    @Test
    fun running_should_showProgressOrIndeterminateWithCancel() {
        assertEquals("Downloading the depth model... 40%", row(ModelDownloadInput(ModelWorkState.Running, progress = 40)).text)
        assertEquals("Downloading the depth model...", row(ModelDownloadInput(ModelWorkState.Running, progress = -1)).text)
        assertEquals(listOf(cancel), row(ModelDownloadInput(ModelWorkState.Running)).actions)
    }

    @Test
    fun failed_should_offerTryAgainAfterFiveTries() {
        val r = row(ModelDownloadInput(ModelWorkState.Failed, error = "skipped: metered"))
        assertEquals("Download failed after 5 tries", r.text)
        assertEquals(listOf(ModelDownloadAction.TryAgain), r.actions)
    }

    @Test
    fun cancelled_should_offerDownloadAgain() {
        val r = row(ModelDownloadInput(ModelWorkState.Cancelled))
        assertEquals("Download cancelled.", r.text)
        assertEquals(listOf(ModelDownloadAction.DownloadAgain), r.actions)
    }

    @Test
    fun notificationsOff_should_addNoticeWithoutChangingTheActions() {
        val r = row(ModelDownloadInput(ModelWorkState.Running, notificationsOff = true))
        assertTrue(r.notice!!.startsWith("Notifications are off, so you won't see download progress"))
        assertEquals(listOf(cancel), r.actions)
    }

    @Test
    fun everyNonFinishedState_should_haveAnExit() {
        for (state in ModelWorkState.entries) {
            for (net in listOf(online, metered, offline, null)) {
                val r = ModelDownloadPresentation.present(ModelDownloadInput(state, network = net)) ?: continue
                assertTrue(r.actions.isNotEmpty(), "$state / $net has no exit")
            }
        }
        assertNull(ModelDownloadPresentation.present(ModelDownloadInput(ModelWorkState.None)))
        assertNull(ModelDownloadPresentation.present(ModelDownloadInput(ModelWorkState.Succeeded)))
    }

    @Test
    fun failureReason_should_rewriteSkipReasonsOnly() {
        assertEquals("Download failed after 5 tries.", ModelDownloadPresentation.failureReason("skipped: metered"))
        assertEquals("The download failed unexpectedly.", ModelDownloadPresentation.failureReason("The download failed unexpectedly."))
        assertNull(ModelDownloadPresentation.failureReason(null))
    }
}
