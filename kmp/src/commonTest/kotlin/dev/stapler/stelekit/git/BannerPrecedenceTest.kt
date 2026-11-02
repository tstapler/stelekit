// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.ui.SyncBannerDismissals
import dev.stapler.stelekit.ui.reviewDestinationFor
import dev.stapler.stelekit.ui.syncBannerChoice
import kotlin.test.Test
import kotlin.test.assertEquals

class BannerPrecedenceTest {

    private fun expected(mismatch: Boolean, review: Boolean, waiting: Boolean): Banner? = when {
        mismatch -> Banner.Mismatch
        review -> Banner.FirstSyncReview
        waiting -> Banner.UpdatesWaiting
        else -> null
    }

    @Test
    fun `all 16 subsets show at most the highest banner and the badge never counts as one`() {
        var checked = 0
        for (bits in 0 until 16) {
            val mismatch = bits and 1 != 0
            val review = bits and 2 != 0
            val waiting = bits and 4 != 0
            val badge = bits and 8 != 0
            val choice = BannerPrecedence.choose(mismatch, review, waiting, badge)
            assertEquals(expected(mismatch, review, waiting), choice.banner, "subset $bits banner")
            assertEquals(badge, choice.reviewBadge, "subset $bits: the badge is independent of every banner")
            checked++
        }
        assertEquals(16, checked)
    }

    @Test
    fun `dismissing the first-sync banner reveals updates waiting and keeps the review badge`() {
        val dismissals = SyncBannerDismissals()
        val before = syncBannerChoice(mismatch = false, reviewPending = true, behindCount = 3, dismissals = dismissals)
        assertEquals(Banner.FirstSyncReview, before.banner)

        dismissals.dismiss(Banner.FirstSyncReview)

        val after = syncBannerChoice(mismatch = false, reviewPending = true, behindCount = 3, dismissals = dismissals)
        assertEquals(Banner.UpdatesWaiting, after.banner)
        assertEquals(true, after.reviewBadge, "a dismissed first-sync review is replaced by the badge, not lost")
    }

    @Test
    fun `dismissing updates waiting hides only that banner and a mismatch is unaffected by dismissals`() {
        val dismissals = SyncBannerDismissals()
        dismissals.dismiss(Banner.UpdatesWaiting)
        dismissals.dismiss(Banner.Mismatch)

        assertEquals(null, syncBannerChoice(false, false, 5, dismissals).banner)
        assertEquals(Banner.Mismatch, syncBannerChoice(true, false, 5, dismissals).banner)
    }

    @Test
    fun `review leads to the first-sync review when one is owed, otherwise to the status surface`() {
        assertEquals(NotificationDestination.FirstSyncReview("g"), reviewDestinationFor(true, "g"))
        assertEquals(NotificationDestination.SyncStatusSheet("g"), reviewDestinationFor(false, "g"))
    }
}
