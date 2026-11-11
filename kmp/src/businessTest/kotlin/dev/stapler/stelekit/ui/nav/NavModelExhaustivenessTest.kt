package dev.stapler.stelekit.ui.nav

import dev.stapler.stelekit.ui.Screen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `NavModel.forScreen` is a compiler-exhaustive `when`; this adds the runtime check that every sealed subtype
 * is classified once and that the classification is the intended one (JVM reflection, so businessTest).
 */
class NavModelExhaustivenessTest {
    private val expectedKinds = mapOf(
        "Journals" to "row", "Flashcards" to "row", "AllPages" to "row", "LibraryStats" to "row",
        "Notifications" to "row", "GlobalUnlinkedReferences" to "row", "Library" to "row", "Sketch" to "row", "Logs" to "row", "Performance" to "row",
        "Gallery" to "redirect", "AssetBrowser" to "redirect",
        "BackgroundJobs" to "nonnav", "Console" to "nonnav", "Import" to "nonnav", "VaultUnlock" to "nonnav",
        "PageView" to "nonnav", "DriveBrowser" to "nonnav", "AssetDetail" to "nonnav", "AnnotationEditor" to "nonnav",
    )

    private fun kind(entry: NavEntry) = when (entry) {
        is NavEntry.Destination -> "row"
        NavEntry.NonNav -> "nonnav"
        is NavEntry.NonNavRedirect -> "redirect"
    }

    @Test
    fun everySealedSubtypeOfScreenIsClassifiedExactlyOnce() {
        val declared = Screen::class.java.declaredClasses.filter { Screen::class.java.isAssignableFrom(it) }.map { it.simpleName }.toSet()
        assertEquals(expectedKinds.keys, declared, "a Screen subtype was added or removed: classify it in NavModel.forScreen and here")
    }

    @Test
    fun sampledScreensClassifyAsIntended() {
        val samples = listOf<Screen>(
            Screen.Journals, Screen.Flashcards, Screen.AllPages(), Screen.LibraryStats, Screen.Notifications,
            Screen.GlobalUnlinkedReferences, Screen.Library(), Screen.Logs(), Screen.Performance, Screen.Gallery,
            Screen.AssetBrowser(), Screen.BackgroundJobs, Screen.Console, Screen.Import, Screen.VaultUnlock, Screen.DriveBrowser,
        )
        for (s in samples) {
            assertEquals(expectedKinds[s::class.java.simpleName], kind(NavModel.forScreen(s)), "classification of $s")
        }
    }

    @Test
    fun everyRowSpecIsReachableFromSomeScreenOrIsAnAction() {
        for (spec in NavModel.allSpecs) {
            val screen = spec.screen ?: continue
            assertEquals(spec, (NavModel.forScreen(screen) as NavEntry.Destination).spec)
        }
    }
}
