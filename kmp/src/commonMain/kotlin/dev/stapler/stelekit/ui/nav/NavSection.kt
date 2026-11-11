package dev.stapler.stelekit.ui.nav

import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.library.LibraryFilter

/** Platform-free icon identity; renderers map it to an `ImageVector` so the model stays in commonMain. */
enum class NavIconKey { JOURNALS, ALL_PAGES, FLASHCARDS, UNLINKED_REFS, NOTIFICATIONS, LIBRARY, GRAPH_STATS, GIT_SETUP, LOGS, PERFORMANCE, SKETCH }

/** What activating a row does. Git setup is a dialog, not a [Screen]. */
sealed interface NavAction {
    data class Open(val screen: Screen) : NavAction
    data object GitSetup : NavAction
}

/**
 * One row in the nav. Labels come from I18n keys (never literals) so a rename is one edit.
 * [shortLabelKey] is the bottom-bar label when the row label is too long for the bar.
 */
data class NavDestinationSpec(
    val id: String,
    val action: NavAction,
    val labelKey: String,
    val descriptionKey: String,
    val iconKey: NavIconKey,
    val shortLabelKey: String? = null,
) {
    val screen: Screen? get() = (action as? NavAction.Open)?.screen

    /** True when [current] is the screen this row opens. */
    fun isSelected(current: Screen): Boolean = when (val s = screen) {
        null -> false
        is Screen.AllPages -> current is Screen.AllPages
        is Screen.Library -> current is Screen.Library
        is Screen.Sketch -> current is Screen.Sketch
        else -> s == current
    }
}

data class NavSection(val id: String, val headerKey: String, val items: List<NavDestinationSpec>)

/** How a [Screen] relates to the nav: a row, deliberately not a row, or an alias rewritten on navigation. */
sealed interface NavEntry {
    data class Destination(val spec: NavDestinationSpec) : NavEntry
    data object NonNav : NavEntry

    /** Old destination kept one release; never in a section. */
    data class NonNavRedirect(val target: Screen) : NavEntry
}

object NavModel {
    val journals = NavDestinationSpec("journals", NavAction.Open(Screen.Journals), I18n.NAV_JOURNALS, I18n.NAV_JOURNALS_DESC, NavIconKey.JOURNALS)
    val allPages = NavDestinationSpec(
        "all-pages", NavAction.Open(Screen.AllPages()), I18n.NAV_ALL_PAGES, I18n.NAV_ALL_PAGES_DESC,
        NavIconKey.ALL_PAGES, shortLabelKey = I18n.NAV_PAGES_SHORT,
    )
    val flashcards = NavDestinationSpec("flashcards", NavAction.Open(Screen.Flashcards), I18n.NAV_FLASHCARDS, I18n.NAV_FLASHCARDS_DESC, NavIconKey.FLASHCARDS)
    val unlinkedRefs = NavDestinationSpec(
        "unlinked-refs", NavAction.Open(Screen.GlobalUnlinkedReferences), I18n.NAV_UNLINKED_REFS, I18n.NAV_UNLINKED_REFS_DESC, NavIconKey.UNLINKED_REFS,
    )
    val notifications = NavDestinationSpec(
        "notifications", NavAction.Open(Screen.Notifications), I18n.NAV_NOTIFICATIONS, I18n.NAV_NOTIFICATIONS_DESC, NavIconKey.NOTIFICATIONS,
    )
    val library = NavDestinationSpec("library", NavAction.Open(Screen.Library()), I18n.NAV_LIBRARY, I18n.NAV_LIBRARY_DESC, NavIconKey.LIBRARY)
    val graphStats = NavDestinationSpec("graph-stats", NavAction.Open(Screen.LibraryStats), I18n.NAV_GRAPH_STATS, I18n.NAV_GRAPH_STATS_DESC, NavIconKey.GRAPH_STATS)
    val gitSetup = NavDestinationSpec("git-setup", NavAction.GitSetup, I18n.NAV_GIT_SETUP, I18n.NAV_GIT_SETUP_DESC, NavIconKey.GIT_SETUP)
    /** Not in [allSpecs]: it exists only while the `homeSketch` flag is on, so the palette never lists it. */
    val sketch = NavDestinationSpec("sketch", NavAction.Open(Screen.Sketch()), I18n.NAV_SKETCH, I18n.NAV_SKETCH_DESC, NavIconKey.SKETCH)
    val logs = NavDestinationSpec("logs", NavAction.Open(Screen.Logs()), I18n.NAV_LOGS, I18n.NAV_LOGS_DESC, NavIconKey.LOGS)
    val performance = NavDestinationSpec("performance", NavAction.Open(Screen.Performance), I18n.NAV_PERFORMANCE, I18n.NAV_PERFORMANCE_DESC, NavIconKey.PERFORMANCE)

    /** Every spec, in nav order; used by the palette and the uniqueness tests. */
    val allSpecs: List<NavDestinationSpec> = listOf(
        journals, allPages, flashcards, unlinkedRefs, notifications, library, graphStats, gitSetup, logs, performance,
    )

    /**
     * Compiler-exhaustive classification (no `else`): adding a [Screen] subtype fails the build here
     * until it is a row, [NavEntry.NonNav] or an alias.
     */
    fun forScreen(screen: Screen): NavEntry = when (screen) {
        is Screen.Journals -> NavEntry.Destination(journals)
        is Screen.Flashcards -> NavEntry.Destination(flashcards)
        is Screen.AllPages -> NavEntry.Destination(allPages)
        is Screen.LibraryStats -> NavEntry.Destination(graphStats)
        is Screen.Notifications -> NavEntry.Destination(notifications)
        is Screen.GlobalUnlinkedReferences -> NavEntry.Destination(unlinkedRefs)
        is Screen.Library -> NavEntry.Destination(library)
        is Screen.Sketch -> NavEntry.Destination(sketch)
        is Screen.Logs -> NavEntry.Destination(logs)
        is Screen.Performance -> NavEntry.Destination(performance)
        is Screen.Gallery -> NavEntry.NonNavRedirect(Screen.Library(initialFilter = LibraryFilter.Annotated))
        is Screen.AssetBrowser -> NavEntry.NonNavRedirect(Screen.Library(initialSearch = screen.initialSearch))
        is Screen.BackgroundJobs,
        is Screen.Console,
        is Screen.Import,
        is Screen.VaultUnlock,
        is Screen.PageView,
        is Screen.DriveBrowser,
        is Screen.AssetDetail,
        is Screen.AnnotationEditor -> NavEntry.NonNav
    }

    /** Sections for the sidebar and phone sheet. Developer is appended last and only while active. */
    fun sections(current: Screen, homeSketchEnabled: Boolean = false): List<NavSection> {
        val base = listOf(
            NavSection("daily", I18n.NAV_SECTION_DAILY, listOf(journals, allPages, flashcards)),
            NavSection("connections", I18n.NAV_SECTION_CONNECTIONS, listOf(unlinkedRefs, notifications)),
            NavSection("graph", I18n.NAV_SECTION_GRAPH, listOfNotNull(library, sketch.takeIf { homeSketchEnabled }, graphStats, gitSetup)),
        )
        val developerActive = current is Screen.Logs || current is Screen.Performance
        return if (developerActive) base + NavSection("developer", I18n.NAV_SECTION_DEVELOPER, listOf(logs, performance)) else base
    }
}

/** Legacy `Gallery`/`AssetBrowser` values (saved state, deep links, tracing keys) resolve through here. */
object LegacyScreenAlias {
    /** Persisted or traced identifier -> current tracing key. */
    private val tracingKeys = mapOf("Gallery" to "Library", "AssetBrowser" to "Library", "Library" to "Library")

    fun resolveTracingKey(key: String): String = tracingKeys[key] ?: key

    /** Rewrites an alias to its [Screen.Library] target; returns [screen] unchanged otherwise. */
    fun resolve(screen: Screen): Screen =
        (NavModel.forScreen(screen) as? NavEntry.NonNavRedirect)?.target ?: screen
}
