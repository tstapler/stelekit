// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncNotificationLogicTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L

    private fun route(graphId: String?, known: Set<String> = setOf("g"), unconfirmed: Boolean = false, behind: Int = 3) =
        NotificationTapRouter.route(graphId, "Notes", known, { unconfirmed }, { behind })

    @Test
    fun `tap router table`() {
        assertEquals(NotificationDestination.FirstSyncReview("g"), route("g", unconfirmed = true))
        assertEquals(NotificationDestination.SyncStatusSheet("g"), route("g"))
        assertEquals(NotificationDestination.UpToDate("g"), route("g", behind = 0))
        assertEquals(NotificationDestination.CurrentGraphSheet("Updates are waiting for Notes"), route("gone"))
        assertEquals(NotificationDestination.CurrentGraphSheet("Updates are waiting for Notes"), route(null))
    }

    @Test
    fun `an unconfirmed first sync wins over an already-empty behind count`() {
        assertEquals(NotificationDestination.FirstSyncReview("g"), route("g", unconfirmed = true, behind = 0))
    }

    @Test
    fun `permission table`() {
        val actions = NotifyPermissionState.entries.associateWith(NotifyPermissionFlow::onToggleOn)
        assertEquals(NotifyToggleAction.Enable, actions.getValue(NotifyPermissionState.NotRequired))
        assertEquals(NotifyToggleAction.Enable, actions.getValue(NotifyPermissionState.Granted))
        assertEquals(NotifyToggleAction.ShowRationaleThenRequest, actions.getValue(NotifyPermissionState.NotAsked))
        assertEquals(NotifyToggleAction.ShowRationaleThenRequest, actions.getValue(NotifyPermissionState.DeniedOnce))
        assertEquals(NotifyToggleAction.OpenSystemSettings, actions.getValue(NotifyPermissionState.PermanentlyDenied))
        assertEquals(
            setOf(NotifyPermissionState.NotRequired, NotifyPermissionState.Granted),
            NotifyPermissionState.entries.filter(NotifyPermissionFlow::canPost).toSet(),
        )
    }

    private fun plan(
        behind: Int?,
        lastNotified: Int? = null,
        mergedAgoMin: Long = 60,
        enabled: Boolean = true,
        permission: NotifyPermissionState = NotifyPermissionState.Granted,
        typedError: Boolean = false,
    ) = UpdatesNotificationPolicy.plan(
        staleness = SyncStaleness(lastMergedAtMs = now - mergedAgoMin * minute, behindCount = behind),
        lastNotifiedBehind = lastNotified,
        nowMs = now,
        intervalMinutes = 15,
        enabled = enabled,
        permission = permission,
        firstTypedErrorThisRun = typedError,
    )

    @Test
    fun `posts once per behind-count change and not before twice the interval`() {
        assertEquals(NotificationPlan.Post(3, "3 remote commits are waiting — open SteleKit to merge"), plan(3))
        assertEquals(NotificationPlan.None, plan(3, lastNotified = 3))
        assertEquals(NotificationPlan.Post(4, "4 remote commits are waiting — open SteleKit to merge"), plan(4, lastNotified = 3))
        assertEquals(NotificationPlan.None, plan(3, mergedAgoMin = 20))
        assertEquals("1 remote commit is waiting — open SteleKit to merge", UpdatesNotificationPolicy.notificationText(1))
    }

    @Test
    fun `clears at zero only when something was posted`() {
        assertEquals(NotificationPlan.Clear, plan(0, lastNotified = 3))
        assertEquals(NotificationPlan.None, plan(0))
    }

    @Test
    fun `disabled or denied permission never posts`() {
        assertEquals(NotificationPlan.None, plan(3, enabled = false))
        for (state in listOf(NotifyPermissionState.NotAsked, NotifyPermissionState.DeniedOnce, NotifyPermissionState.PermanentlyDenied)) {
            assertEquals(NotificationPlan.None, plan(3, permission = state), "state $state")
        }
        assertEquals(NotificationPlan.Post(3, "3 remote commits are waiting — open SteleKit to merge"), plan(3, permission = NotifyPermissionState.NotRequired))
    }

    @Test
    fun `a first typed error posts even with nothing behind`() {
        val p = plan(0, typedError = true)
        assertEquals(NotificationPlan.Post(0, "Sync could not reach the remote — open SteleKit to check"), p)
    }
}
