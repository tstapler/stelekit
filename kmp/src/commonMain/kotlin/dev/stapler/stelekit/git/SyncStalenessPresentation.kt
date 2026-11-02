// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

enum class StalenessSeverity { Healthy, Neutral, Amber }

/** The always-visible "last merged · behind n" chip. */
data class StalenessChipState(val text: String, val severity: StalenessSeverity)

/** Pure function of the persisted [SyncStaleness]; a fetch-only graph is never [StalenessSeverity.Healthy]. */
fun stalenessChipState(staleness: SyncStaleness, nowMs: Long, intervalMinutes: Int): StalenessChipState {
    val behind = staleness.behindCount
    val behindText = if (behind == null) "checking…" else "behind $behind"
    val intervalMs = intervalMinutes.coerceAtLeast(1) * MILLIS_PER_MINUTE
    val stale = behind != null && behind > 0 && sinceLastProgress(staleness, nowMs) > STALE_FACTOR * intervalMs
    val failing = staleness.history.takeLast(FAILING_RUN).let { recent ->
        recent.size == FAILING_RUN && recent.all { it.kind == BackgroundOutcomeKind.Error }
    }
    val reason = staleness.fetchOnlyReason
    if (reason != null) {
        val label = if (reason == FetchOnlyReason.Saf) "SAF" else "app closed"
        val severity = if (stale || failing || (behind ?: 0) > 0) StalenessSeverity.Amber else StalenessSeverity.Neutral
        return StalenessChipState("fetch-only ($label) · $behindText", severity)
    }
    val merged = staleness.lastMergedAtMs?.let { "last merged ${ageText(nowMs - it)}" } ?: "no merge yet"
    val severity = when {
        stale || failing -> StalenessSeverity.Amber
        behind == null -> StalenessSeverity.Neutral
        else -> StalenessSeverity.Healthy
    }
    return StalenessChipState("$merged · $behindText", severity)
}

/** Time since the last merge, or since the graph was first seen when it never merged; unknown counts as not stale. */
private fun sinceLastProgress(s: SyncStaleness, nowMs: Long): Long {
    val anchor = s.lastMergedAtMs ?: s.firstSeenAtMs ?: return 0L
    return (nowMs - anchor).coerceAtLeast(0L)
}

/** "just now", "N min ago", "N h ago", "N d ago"; never "0 min ago". */
fun ageText(elapsedMs: Long): String {
    val minutes = elapsedMs.coerceAtLeast(0L) / MILLIS_PER_MINUTE
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
        else -> "${minutes / MINUTES_PER_DAY} d ago"
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 1_440L
private const val STALE_FACTOR = 2
private const val FAILING_RUN = 3

enum class Banner { Mismatch, FirstSyncReview, UpdatesWaiting }

/** At most one [banner]; the `Review first sync` [reviewBadge] is never a banner and stays visible while a review is owed. */
data class BannerChoice(val banner: Banner?, val reviewBadge: Boolean)

/** UX-86: highest of mismatch, first-sync review, updates waiting; dismissing the top one reveals the next. */
object BannerPrecedence {
    fun choose(mismatch: Boolean, firstSyncReview: Boolean, updatesWaiting: Boolean, reviewBadge: Boolean): BannerChoice =
        BannerChoice(
            banner = when {
                mismatch -> Banner.Mismatch
                firstSyncReview -> Banner.FirstSyncReview
                updatesWaiting -> Banner.UpdatesWaiting
                else -> null
            },
            reviewBadge = reviewBadge,
        )
}

/** Where a tap on the updates-waiting notification lands; no sync ever starts from a tap. */
sealed interface NotificationDestination {
    data class FirstSyncReview(val graphId: String) : NotificationDestination
    data class SyncStatusSheet(val graphId: String) : NotificationDestination
    data class UpToDate(val graphId: String) : NotificationDestination

    /** The named graph is gone: open the current graph's sheet with [notice]. */
    data class CurrentGraphSheet(val notice: String) : NotificationDestination
}

/** Pure routing for the notification's pending intent (UX-101). */
object NotificationTapRouter {
    fun route(
        intentGraphId: String?,
        intentGraphName: String,
        knownGraphIds: Set<String>,
        firstSyncUnconfirmed: (String) -> Boolean,
        behindCount: (String) -> Int,
    ): NotificationDestination = when {
        intentGraphId == null || intentGraphId !in knownGraphIds ->
            NotificationDestination.CurrentGraphSheet("Updates are waiting for $intentGraphName")
        firstSyncUnconfirmed(intentGraphId) -> NotificationDestination.FirstSyncReview(intentGraphId)
        behindCount(intentGraphId) <= 0 -> NotificationDestination.UpToDate(intentGraphId)
        else -> NotificationDestination.SyncStatusSheet(intentGraphId)
    }
}

/** Runtime state of the Android 13+ `POST_NOTIFICATIONS` permission (UX-89); [NotRequired] below API 33. */
enum class NotifyPermissionState { NotRequired, Granted, NotAsked, DeniedOnce, PermanentlyDenied }

enum class NotifyToggleAction { Enable, ShowRationaleThenRequest, OpenSystemSettings }

object NotifyPermissionFlow {
    /** The permission is requested only from the Settings toggle, after a rationale; permanent denial disables the toggle. */
    fun onToggleOn(state: NotifyPermissionState): NotifyToggleAction = when (state) {
        NotifyPermissionState.NotRequired, NotifyPermissionState.Granted -> NotifyToggleAction.Enable
        NotifyPermissionState.NotAsked, NotifyPermissionState.DeniedOnce -> NotifyToggleAction.ShowRationaleThenRequest
        NotifyPermissionState.PermanentlyDenied -> NotifyToggleAction.OpenSystemSettings
    }

    fun canPost(state: NotifyPermissionState): Boolean =
        state == NotifyPermissionState.NotRequired || state == NotifyPermissionState.Granted
}

/** What the notifier does after a background tick. */
sealed interface NotificationPlan {
    data object None : NotificationPlan
    data object Clear : NotificationPlan
    data class Post(val behindCount: Int, val text: String) : NotificationPlan
}

/** Decides post, clear or nothing: posted once per behind-count change, cleared at 0, silent when disabled or denied. */
object UpdatesNotificationPolicy {
    fun plan(
        staleness: SyncStaleness,
        lastNotifiedBehind: Int?,
        nowMs: Long,
        intervalMinutes: Int,
        enabled: Boolean,
        permission: NotifyPermissionState,
        firstTypedErrorThisRun: Boolean = false,
    ): NotificationPlan {
        val behind = staleness.behindCount ?: 0
        if (behind == 0 && !firstTypedErrorThisRun) return if (lastNotifiedBehind != null) NotificationPlan.Clear else NotificationPlan.None
        if (!enabled || !NotifyPermissionFlow.canPost(permission)) return NotificationPlan.None
        val old = nowMs - (staleness.lastMergedAtMs ?: staleness.firstSeenAtMs ?: nowMs) > 2L * intervalMinutes * 60_000L
        val worthPosting = (behind > 0 && old) || firstTypedErrorThisRun
        return if (worthPosting && behind != lastNotifiedBehind) NotificationPlan.Post(behind, notificationText(behind)) else NotificationPlan.None
    }

    fun notificationText(behind: Int): String =
        if (behind > 0) "$behind remote ${if (behind == 1) "commit is" else "commits are"} waiting — open SteleKit to merge"
        else "Sync could not reach the remote — open SteleKit to check"
}
