package dev.stapler.stelekit.ui.nav

import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.library.LibraryFilter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit of persisted/serialised screen identifiers (plan D.8c). `rg` over the app found no store that
 * serialises a [Screen] (history lives in memory, `AppState.currentScreen` is not persisted); the only
 * stringly-typed identifiers are the tracing keys, mapped here, and in-memory `Screen` values from callers
 * that still use the aliases.
 */
class PersistedScreenIdTest {
    @Test
    fun oldTracingKeysResolveToLibrary() {
        assertEquals("Library", LegacyScreenAlias.resolveTracingKey("Gallery"))
        assertEquals("Library", LegacyScreenAlias.resolveTracingKey("AssetBrowser"))
        assertEquals("Library", LegacyScreenAlias.resolveTracingKey("Library"))
        assertEquals("Journals", LegacyScreenAlias.resolveTracingKey("Journals"))
    }

    @Test
    fun oldScreenValuesStillResolve() {
        assertEquals(Screen.Library(initialFilter = LibraryFilter.Annotated), LegacyScreenAlias.resolve(Screen.Gallery))
        assertEquals(Screen.Library(initialSearch = "x"), LegacyScreenAlias.resolve(Screen.AssetBrowser("x")))
    }
}
