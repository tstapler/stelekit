// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveFailure
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 1.4.4 (engineering rule "uncaught coroutine Throwables kill the process on Android"): an `OutOfMemoryError` in the
 * saver, whether inside the guarded save or anywhere else in its scope, must end as a failed save and never reach
 * `Thread.getDefaultUncaughtExceptionHandler`, which on Android kills the process. The `SolveRunner` scope joins this test
 * when that class exists (Story 4.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCrashGuardTest {
    private val strings = object : BannerStringSource {
        override fun summary(row: BannerRowId) = "Couldn't save."
        override fun shortTitle(row: BannerRowId) = "Not saved"
        override fun primaryLabel(row: BannerRowId) = "Retry"
        override fun moreItems(row: BannerRowId) = emptyList<String>()
    }

    /** A store whose every call fails the test: the failures below must happen before any write is planned. */
    private val untouchedStore: ProjectSidecarStore = Proxy.newProxyInstance(
        ProjectSidecarStore::class.java.classLoader,
        arrayOf(ProjectSidecarStore::class.java),
    ) { _, method, _ -> error("the store must not be called: ${method.name}") } as ProjectSidecarStore

    private fun saverFor(
        scope: TestScope,
        diagnostics: InMemoryDiagnosticsPort,
        externalChanges: Flow<Unit> = emptyFlow(),
        encoder: () -> Nothing = { throw OutOfMemoryError("test") },
    ): SheetSaver {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return SheetSaver(
            SheetTarget("proj-0a1b2c", ProjectUuid("p1"), SheetUuid("s2")),
            SheetSaverPorts(
                inMemorySketchRepositories(), untouchedStore, SidecarSlugLock(), diagnostics,
                externalChanges = externalChanges, encoder = { _, _ -> encoder() },
            ),
            context = dispatcher, encodeContext = dispatcher,
        )
    }

    private inline fun withRecordingDefaultHandler(block: (MutableList<Throwable>) -> Unit) {
        val seen = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> seen += t }
        try {
            block(seen)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun heapExhaustionWhileEncoding_should_FailTheSaveAndKeepTheProcessAlive() = runTest {
        withRecordingDefaultHandler { seen ->
            val diagnostics = InMemoryDiagnosticsPort(strings)
            val saver = saverFor(this, diagnostics)

            saver.submit(SketchDocumentV2(sheetUuid = "s2"))
            advanceUntilIdle()

            assertEquals(SaveState.Failed(SaveFailure.OutOfMemory), saver.state.value)
            assertEquals(listOf(BannerRowId.SaveFailed), diagnostics.diagnostics.value.entries.map { it.row })
            assertTrue(seen.isEmpty(), "the default uncaught-exception handler saw $seen")
            saver.close()
        }
    }

    @Test
    fun heapExhaustionOutsideTheSave_should_BeSwallowedByTheScopeHandler() = runTest {
        withRecordingDefaultHandler { seen ->
            val diagnostics = InMemoryDiagnosticsPort(strings)
            val saver = saverFor(this, diagnostics, externalChanges = flow { throw OutOfMemoryError("test") })

            advanceUntilIdle()

            assertEquals(SaveState.Failed(SaveFailure.OutOfMemory), saver.state.value)
            assertEquals(listOf(BannerRowId.SaveFailed), diagnostics.diagnostics.value.entries.map { it.row })
            assertTrue(seen.isEmpty(), "the default uncaught-exception handler saw $seen")
            saver.close()
        }
    }
}
