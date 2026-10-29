// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DbRestore
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphManagerRestoreHost
import dev.stapler.stelekit.db.GraphWriteLockOrderGuard
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.db.RestoreOutcome
import dev.stapler.stelekit.db.RestoreSource
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.merge.RemoveReport
import dev.stapler.stelekit.merge.TargetWriter
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A restore against concurrent `TargetWriterRouter` writes to the same graph (plan Task 3.4c, modeled on
 * `TargetWriterRouterInFlightSwitchTest`): the real [GraphManager] and the real [GraphManagerRestoreHost] and
 * lock; only the files are fake. Every write must land entirely before or entirely after the swap.
 */
class DbRestoreLockStressTest {
    private val a = GraphId("aaaaaaaaaaaaaaaa")
    private val db = "/data/graph.db"
    private val dir = "/data/console-backups"
    private val backup = "graph-20261027-101500.db"
    private val before = "pages=9,blocks=90;live"
    private val after = "pages=4,blocks=40;snapshot"
    private lateinit var manager: GraphManager

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class NoopWriter : TargetWriter {
        override suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?> = null.right()
        override suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome> = WriteOutcome.Unchanged.right()
        override suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun fileHash(page: PageKey): Either<DomainError, String?> = null.right()
        override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>): Either<DomainError, RemoveReport> =
            RemoveReport(emptySet(), emptySet(), emptySet()).right()
    }

    @BeforeTest
    fun start() {
        GraphWriteLockOrderGuard.forceEnabled = true
        val settings = MapSettings()
        settings.putString(
            "graph_registry",
            Json.encodeToString(GraphRegistry(activeGraphId = a, graphs = listOf(GraphInfo(id = a, path = "/data/${a.value}", displayName = "a", addedAt = 0L)))),
        )
        manager = GraphManager(settings, DriverFactory(), StubFileSystem(), defaultBackend = GraphBackend.IN_MEMORY)
    }

    @AfterTest
    fun stop() {
        GraphWriteLockOrderGuard.forceEnabled = false
        manager.shutdown()
    }

    @Test
    fun restore_should_NeverLetAWriteSeeAHalfSwappedDatabase_When_RouterWritesRunConcurrently() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(60.seconds) {
                manager.awaitPendingMigration()
                val files = FakeRestoreFiles()
                files.put(db, before)
                files.put("$dir/$backup", after)
                val midSwap = AtomicBoolean(false)
                files.onRename = { from, to ->
                    if (from == db && to == "$db.pre-restore") midSwap.set(true)
                    if (to == db && from == "$db.restoring") midSwap.set(false)
                }
                val observed = ConcurrentLinkedQueue<String?>()
                val sawMidSwap = ConcurrentLinkedQueue<Boolean>()
                val router = TargetWriterRouter(
                    graphManager = manager,
                    locator = RegistryGraphLocator(manager.graphRegistry),
                    capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
                    activeWriterFor = { NoopWriter() },
                    offGraphWriterFor = { NoopWriter() },
                    retryBackoff = {},
                )
                val restore = DbRestore(db, files, FakeRestoreInspector(files), GraphManagerRestoreHost(manager, a), dir)

                val writers = (1..4).map {
                    async {
                        repeat(WRITES_PER_WRITER) {
                            router.withWriter(a) {
                                sawMidSwap += midSwap.get()
                                observed += files.store[db]
                                "ok".right()
                            }
                            delay(1)
                        }
                    }
                }
                delay(5)
                val outcome = restore.restore(RestoreSource.Backup(backup))
                writers.awaitAll()

                assertEquals(RestoreOutcome.Restored(dev.stapler.stelekit.db.RowCounts(4, 40), undoKept = true), outcome)
                assertFalse(sawMidSwap.any { it }, "a router write ran while the swap was half done")
                assertTrue(observed.all { it == before || it == after }, "a write saw a database that is neither the old nor the new one: ${observed.toSet()}")
                assertEquals(after, files.store[db])
                assertEquals(before, files.store["$db.pre-restore"])
                assertEquals(a, manager.readyGraphId, "the graph was reopened")
            }
        }
    }

    @Test
    fun restore_should_ReleaseTheLockAndReopen_When_TheSwapFails() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(60.seconds) {
                manager.awaitPendingMigration()
                val files = FakeRestoreFiles()
                files.put(db, before)
                files.put("$dir/$backup", after)
                files.failRename = { from, _ -> from == "$db.restoring" }
                val restore = DbRestore(db, files, FakeRestoreInspector(files), GraphManagerRestoreHost(manager, a), dir)

                assertIs<RestoreOutcome.Failed>(restore.restore(RestoreSource.Backup(backup)))

                assertFalse(manager.graphWriteLock.isLocked(a), "the swap lock must be released after a failed swap")
                assertEquals(before, files.store[db])
                assertEquals(a, manager.readyGraphId)
                val router = TargetWriterRouter(
                    graphManager = manager,
                    locator = RegistryGraphLocator(manager.graphRegistry),
                    capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
                    activeWriterFor = { NoopWriter() },
                    offGraphWriterFor = { NoopWriter() },
                )
                assertTrue(router.withWriter(a) { "ok".right() }.isRight(), "writes work again after the failed restore")
            }
        }
    }

    private companion object {
        const val WRITES_PER_WRITER = 40
    }
}
