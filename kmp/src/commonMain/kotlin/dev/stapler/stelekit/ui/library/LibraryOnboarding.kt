package dev.stapler.stelekit.ui.library

import dev.stapler.stelekit.platform.Settings

/** FRESH: new install, shows the tip. UPGRADE: had a graph before the merge, shows the notice and a "New" badge. */
enum class LibraryOnboardingMode { FRESH, UPGRADE }

/**
 * Pure state of the one-time Library explanations (plan D.9, ux.md LD-20). The notice persists across opens
 * until dismissed; opening Library only clears the badge. A fresh install never gets the badge or the notice.
 */
data class LibraryOnboardingState(
    val mode: LibraryOnboardingMode = LibraryOnboardingMode.FRESH,
    val tipDismissed: Boolean = false,
    val noticeDismissed: Boolean = false,
    val badgeCleared: Boolean = false,
) {
    val showTip: Boolean get() = mode == LibraryOnboardingMode.FRESH && !tipDismissed
    val showNotice: Boolean get() = mode == LibraryOnboardingMode.UPGRADE && !noticeDismissed
    val showBadge: Boolean get() = mode == LibraryOnboardingMode.UPGRADE && !badgeCleared

    fun onLibraryOpened(): LibraryOnboardingState = copy(badgeCleared = true)
    fun dismissTip(): LibraryOnboardingState = copy(tipDismissed = true)
    fun dismissNotice(): LibraryOnboardingState = copy(noticeDismissed = true)
}

/**
 * Persists [LibraryOnboardingState]. A missing or failing store never blocks the screen: reads fall back to
 * the in-session state and writes are best effort (a failed write shows the badge/notice again next launch).
 */
class LibraryOnboardingStore(private val settings: Settings?) {

    /**
     * First call after the update decides the mode: no `navIaVersion` and a graph already registered means an
     * existing install (UPGRADE); no graph means a fresh install. The decision is stored so it never flips.
     */
    fun load(graphRegistered: Boolean): LibraryOnboardingState {
        val s = settings ?: return LibraryOnboardingState(mode = if (graphRegistered) LibraryOnboardingMode.UPGRADE else LibraryOnboardingMode.FRESH)
        return try {
            if (!s.containsKey(NAV_IA_VERSION)) {
                val mode = if (graphRegistered) LibraryOnboardingMode.UPGRADE else LibraryOnboardingMode.FRESH
                s.putString(MODE, mode.name)
                s.putString(NAV_IA_VERSION, CURRENT_VERSION)
            }
            LibraryOnboardingState(
                mode = LibraryOnboardingMode.entries.firstOrNull { it.name == s.getString(MODE, "") } ?: LibraryOnboardingMode.FRESH,
                tipDismissed = s.getBoolean(TIP_DISMISSED, false),
                noticeDismissed = s.getBoolean(NOTICE_DISMISSED, false),
                badgeCleared = s.getBoolean(BADGE_CLEARED, false),
            )
        } catch (_: Exception) {
            LibraryOnboardingState(mode = if (graphRegistered) LibraryOnboardingMode.UPGRADE else LibraryOnboardingMode.FRESH)
        }
    }

    fun save(state: LibraryOnboardingState) {
        val s = settings ?: return
        try {
            s.putBoolean(TIP_DISMISSED, state.tipDismissed)
            s.putBoolean(NOTICE_DISMISSED, state.noticeDismissed)
            s.putBoolean(BADGE_CLEARED, state.badgeCleared)
        } catch (_: Exception) {
            // Best effort: the screen already shows the in-session state.
        }
    }

    companion object {
        const val NAV_IA_VERSION = "navIaVersion"
        const val CURRENT_VERSION = "1"
        const val MODE = "libraryOnboardingMode"
        const val TIP_DISMISSED = "libraryTipDismissed"
        const val NOTICE_DISMISSED = "upgradeNoticeDismissed"
        const val BADGE_CLEARED = "libraryBadgeCleared"
    }
}
