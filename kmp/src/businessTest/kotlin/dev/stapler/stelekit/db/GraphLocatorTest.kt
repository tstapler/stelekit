package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class GraphLocatorTest {
    private val a = GraphId("aaaaaaaaaaaaaaaa")
    private val b = GraphId("bbbbbbbbbbbbbbbb")

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    @Test
    fun `locate returns the registered graph without activating it`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(30.seconds) {
                val settings = MapSettings()
                val graphs = listOf(a, b).map { GraphInfo(id = it, path = "/data/${it.value}", displayName = it.value, addedAt = 0L) }
                settings.putString("graph_registry", Json.encodeToString(GraphRegistry(activeGraphId = a, graphs = graphs)))
                val m = GraphManager(settings, DriverFactory(), StubFileSystem(), GraphBackend.IN_MEMORY)
                try {
                    assertNotNull(m.awaitPendingMigration())
                    val activeBefore = m.activeRepositorySet.value
                    val info = (RegistryGraphLocator(m.graphRegistry).locate(b) as Either.Right).value
                    assertEquals("/data/${b.value}", info.path)
                    assertSame(activeBefore, m.activeRepositorySet.value)
                    assertEquals(a, m.readyGraphId)
                } finally {
                    m.shutdown()
                }
            }
        }
    }

    @Test
    fun `unknown id is NotFound`() {
        val locator = RegistryGraphLocator(MutableStateFlow(GraphRegistry()))
        val left = locator.locate(GraphId("gone")) as Either.Left
        assertIs<DomainError.DatabaseError.NotFound>(left.value)
    }
}
