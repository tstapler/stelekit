package dev.stapler.stelekit.capture

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.Settings
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureTargetResolverTest {

    private val work = GraphId("work")
    private val personal = GraphId("personal")
    private val both = setOf(work, personal)

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    @Test
    fun resolve_should_PreferLastUsed_When_RememberEnabledAndGraphExists() {
        assertEquals(
            CaptureTarget.NamedGraph(personal),
            CaptureTargetResolver.resolve(work, personal, rememberLast = true, availableGraphIds = both),
        )
    }

    @Test
    fun resolve_should_UseDefault_When_RememberDisabled() {
        assertEquals(
            CaptureTarget.NamedGraph(work),
            CaptureTargetResolver.resolve(work, personal, rememberLast = false, availableGraphIds = both),
        )
    }

    @Test
    fun resolve_should_FallBackToDefault_When_LastGraphDeleted() {
        assertEquals(
            CaptureTarget.NamedGraph(work),
            CaptureTargetResolver.resolve(work, personal, rememberLast = true, availableGraphIds = setOf(work)),
        )
    }

    @Test
    fun resolve_should_FallBackToActive_When_BothGraphsDeleted() {
        assertEquals(
            CaptureTarget.ActiveGraph,
            CaptureTargetResolver.resolve(work, personal, rememberLast = true, availableGraphIds = emptySet()),
        )
    }

    @Test
    fun resolve_should_ReturnActive_When_NothingConfigured() {
        assertEquals(
            CaptureTarget.ActiveGraph,
            CaptureTargetResolver.resolve(null, null, rememberLast = true, availableGraphIds = both),
        )
    }

    @Test
    fun recordLastUsed_should_NotChangeDefault_When_OverridingPerCapture() {
        val settings = CaptureTargetSettings(MapSettings())
        settings.defaultGraphId = work
        settings.rememberLast = true

        settings.recordLastUsed(personal)

        assertEquals(work, settings.defaultGraphId)
        assertEquals(personal, settings.lastGraphId)
        assertEquals(CaptureTarget.NamedGraph(personal), CaptureTargetResolver.resolve(settings, both))
    }

    @Test
    fun settings_should_UseDocumentedKeys_And_ClearDefaultWithNull() {
        val backing = MapSettings()
        val settings = CaptureTargetSettings(backing)
        settings.defaultGraphId = work
        settings.recordLastUsed(personal)
        settings.rememberLast = true

        assertEquals("work", backing.getString("capture_default_graph_id", ""))
        assertEquals("personal", backing.getString("capture_last_graph_id", ""))
        assertEquals("true", backing.getString("capture_remember_last", ""))

        settings.defaultGraphId = null
        assertNull(settings.defaultGraphId)
    }

    @Test
    fun resolve_should_NeverReturnUnavailableGraph_ForAnyInputs() = runTest {
        val ids = listOf("a", "b", "c", "d").map(::GraphId)
        checkAll(
            Arb.element(ids).orNull(),
            Arb.element(ids).orNull(),
            Arb.boolean(),
            Arb.set(Arb.element(ids), 0..4),
        ) { default, last, remember, available ->
            val target = CaptureTargetResolver.resolve(default, last, remember, available)
            if (target is CaptureTarget.NamedGraph) {
                assertTrue(target.graphId in available)
                val expected = (if (remember) last else null)?.takeIf { it in available }
                    ?: default?.takeIf { it in available }
                assertEquals(expected, target.graphId)
            } else {
                assertEquals(CaptureTarget.ActiveGraph, target)
                assertTrue(default == null || default !in available)
                assertTrue(!remember || last == null || last !in available)
            }
        }
    }
}
