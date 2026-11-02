// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.git.Banner
import dev.stapler.stelekit.git.BannerChoice
import dev.stapler.stelekit.git.BannerPrecedence
import dev.stapler.stelekit.git.NotificationDestination
import dev.stapler.stelekit.ui.components.SyncLaunchBanner

/** Per-launch dismissals: each banner keeps its own rule, and dismissing one never hides the badge. */
class SyncBannerDismissals {
    var firstSyncReview by mutableStateOf(false)
    var updatesWaiting by mutableStateOf(false)

    fun dismiss(banner: Banner) {
        when (banner) {
            Banner.FirstSyncReview -> firstSyncReview = true
            Banner.UpdatesWaiting -> updatesWaiting = true
            Banner.Mismatch -> Unit
        }
    }
}

/** Which single banner to show now, given what is pending and what the user already dismissed. */
fun syncBannerChoice(mismatch: Boolean, reviewPending: Boolean, behindCount: Int, dismissals: SyncBannerDismissals): BannerChoice =
    BannerPrecedence.choose(
        mismatch = mismatch,
        firstSyncReview = reviewPending && !dismissals.firstSyncReview,
        updatesWaiting = behindCount > 0 && !dismissals.updatesWaiting,
        reviewBadge = reviewPending,
    )

/** Where the banner's Review button leads: the first-sync review when one is owed, otherwise Settings' sync section. */
fun reviewDestinationFor(reviewPending: Boolean, graphId: String): NotificationDestination =
    if (reviewPending) NotificationDestination.FirstSyncReview(graphId) else NotificationDestination.SyncStatusSheet(graphId)

/** Renders the sync launch banner for [choice]; the mismatch banner stays with its own host. */
@Composable
internal fun SyncLaunchBannerHost(
    choice: BannerChoice,
    behindCount: Int,
    dismissals: SyncBannerDismissals,
    onReview: () -> Unit,
) {
    val banner = choice.banner ?: return
    if (banner == Banner.Mismatch) return
    SyncLaunchBanner(
        banner = banner,
        behindCount = behindCount,
        onReview = onReview,
        onDismiss = { dismissals.dismiss(banner) },
    )
}

@Composable
internal fun rememberSyncBannerDismissals(graphKey: String?): SyncBannerDismissals = remember(graphKey) { SyncBannerDismissals() }
