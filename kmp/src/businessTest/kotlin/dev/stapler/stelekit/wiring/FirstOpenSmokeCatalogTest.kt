package dev.stapler.stelekit.wiring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.3c catalog check: every screen in `first-open-screens.txt` must be named in the
 * `FirstOpenSmokeTest` screen list (androidApp/src/test), so a story that adds a screen cannot skip
 * its first-open smoke test.
 */
class FirstOpenSmokeCatalogTest {
    companion object {
        const val SMOKE_TEST = "FirstOpenSmokeTest.kt"

        fun screens(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        fun missing(required: List<String>, smokeSource: String?): List<String> {
            val code = smokeSource ?: return required
            return required.filter { "\"$it\"" !in code }
        }
    }

    @Test
    fun smokeCatalog_should_FailNamingScreen_When_StoryScreenMissingFromFirstOpenSmokeTestList() {
        val required = listOf("Console", "Settings > Plugins")
        assertEquals(listOf("Console"), missing(required, """val FIRST_OPEN_SCREENS = listOf("Settings > Plugins")"""))
        assertEquals(required, missing(required, null))
        assertEquals(emptyList(), missing(required, """listOf("Console", "Settings > Plugins")"""))
    }

    @Test
    fun smokeCatalog_should_PassAtHead_When_EveryListedScreenIsInFirstOpenSmokeTest() {
        val required = screens(WiringInputs.dataFile("first-open-screens.txt"))
        assertTrue(required.isNotEmpty(), "first-open-screens.txt must list the U1 screens")
        val source = WiringInputs.testSources.entries.firstOrNull { it.key.endsWith("/$SMOKE_TEST") }?.value
        val missing = missing(required, source)
        assertTrue(missing.isEmpty(), "FirstOpenSmokeTest (androidApp/src/test) is missing screens: $missing")
    }
}
