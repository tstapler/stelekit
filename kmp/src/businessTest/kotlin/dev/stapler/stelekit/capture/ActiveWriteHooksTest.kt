// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.ReadyGraph
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ActiveWriteHooksTest {
    private class MapSettings : Settings {
        private val values = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }

    private val managers = mutableListOf<GraphManager>()

    @AfterTest
    fun tearDown() = managers.forEach { it.shutdown() }

    private fun binding(id: String): ActiveWriteBinding {
        val fs = FakeFileSystem()
        return ActiveWriteBinding(GraphId(id), GraphWriter(fs), fs, EffectiveNotesPath("/g/$id"), isPageDirty = { false })
    }

    @Test
    fun noBindingUntilRegistered() {
        assertNull(ActiveWriteHooks().binding.value)
    }

    @Test
    fun registerReplacesTheCurrentBinding() {
        val hooks = ActiveWriteHooks()
        val first = binding("a")
        val second = binding("b")

        hooks.register(first)
        hooks.register(second)

        assertSame(second, hooks.binding.value)
    }

    @Test
    fun unregisterClearsOnlyTheBindingItWasGiven() {
        val hooks = ActiveWriteHooks()
        val old = binding("a")
        val current = binding("b")
        hooks.register(old)
        hooks.register(current)

        hooks.unregister(old)
        assertSame(current, hooks.binding.value)

        hooks.unregister(current)
        assertNull(hooks.binding.value)
    }

    @Test
    fun aGraphWithoutAWriteActorGetsAWriterThatRefusesEveryOperationAsRetryable() = runBlocking {
        val manager = GraphManager(MapSettings(), DriverFactory(), StubFileSystem(), defaultBackend = GraphBackend.IN_MEMORY).also { managers += it }
        val pages = InMemoryPageRepository()
        val blocks = InMemoryBlockRepository()
        val noActor = RepositorySet(
            blockRepository = blocks,
            pageRepository = pages,
            propertyRepository = InMemoryPropertyRepository(),
            referenceRepository = InMemoryReferenceRepository(),
            searchRepository = InMemorySearchRepository(pages, blocks),
            journalService = JournalService(pages, blocks),
        )

        val writer = activeTargetWriterFor(ReadyGraph(GraphId("g"), noActor), ActiveWriteHooks(), FakeFileSystem(), manager)

        val key = PageKey("p")
        val refusals = listOf(
            writer.readExisting(key).leftOrNull(),
            writer.deletePageFile(key, "hash").leftOrNull(),
            writer.fileHash(key).leftOrNull(),
            writer.removeBlocks(key, setOf("u"), emptyMap()).leftOrNull(),
        )
        refusals.forEach { assertIs<DomainError.MergeError.Retryable>(it) }
        assertEquals(true, (refusals.first() as DomainError.MergeError.Retryable).message.contains("g is open but not writable yet"))
    }
}
