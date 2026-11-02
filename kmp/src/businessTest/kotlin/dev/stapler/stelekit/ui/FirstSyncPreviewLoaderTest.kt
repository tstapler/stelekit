// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.git.FirstSyncPreview
import dev.stapler.stelekit.ui.screens.git.FirstSyncPreviewLoader
import dev.stapler.stelekit.ui.screens.git.FirstSyncPreviewState
import dev.stapler.stelekit.ui.screens.git.allowsSyncNow
import dev.stapler.stelekit.ui.screens.git.allowsSyncWithoutPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstSyncPreviewLoaderTest {
    private val ready = FirstSyncPreviewState.Ready(
        FirstSyncPreview("origin/main", 0, 0, emptyList(), remoteBranchesLive = true, localOnlyCommits = emptyList(), filesToDelete = 0),
    )

    private suspend fun FirstSyncPreviewLoader.awaitNot(vararg unwanted: FirstSyncPreviewState) =
        withTimeout(AWAIT_MILLIS) { state.first { it !in unwanted } }

    @Test
    fun `loader_should_ShowLoadingThenReady_AndOnlyReadyAllowsSyncNow`() = runBlocking {
        val gate = CompletableDeferred<FirstSyncPreviewState>()
        val loader = FirstSyncPreviewLoader({ gate.await() })

        loader.start()
        assertEquals(FirstSyncPreviewState.Loading, loader.state.value)
        assertFalse(loader.state.value.allowsSyncNow)
        assertFalse(loader.state.value.allowsSyncWithoutPreview, "never offered while the check is running")

        gate.complete(ready)
        assertEquals(ready, loader.awaitNot(FirstSyncPreviewState.Loading))
        assertTrue(loader.state.value.allowsSyncNow)
        assertFalse(loader.state.value.allowsSyncWithoutPreview, "never offered once the numbers loaded")
        loader.close()
    }

    @Test
    fun `cancel_should_StopTheCheckAndOfferSyncWithoutPreview_WhenLoading`() = runBlocking {
        var finished = false
        val gate = CompletableDeferred<FirstSyncPreviewState>()
        val loader = FirstSyncPreviewLoader({ gate.await().also { finished = true } })
        loader.start()

        loader.cancel()
        gate.complete(ready)

        val state = loader.state.value
        assertEquals(FirstSyncPreviewState.Failed(FirstSyncPreviewLoader.CANCELLED_MESSAGE), state)
        assertTrue(state.allowsSyncWithoutPreview)
        assertFalse(state.allowsSyncNow)
        assertFalse(finished, "the cancelled load must not complete and overwrite the state")
        loader.close()
    }

    @Test
    fun `cancel_should_LeaveALoadedPreviewAlone`() = runBlocking {
        val loader = FirstSyncPreviewLoader({ ready })
        loader.start()
        loader.awaitNot(FirstSyncPreviewState.Loading)

        loader.cancel()

        assertEquals(ready, loader.state.value)
        loader.close()
    }

    @Test
    fun `start_should_RetryAfterAFailure`() = runBlocking {
        var attempts = 0
        val loader = FirstSyncPreviewLoader({ if (++attempts == 1) FirstSyncPreviewState.Failed("timed out") else ready })
        loader.start()
        assertEquals(FirstSyncPreviewState.Failed("timed out"), loader.awaitNot(FirstSyncPreviewState.Loading))

        loader.start()

        assertEquals(ready, loader.awaitNot(FirstSyncPreviewState.Loading, FirstSyncPreviewState.Failed("timed out")))
        assertEquals(2, attempts)
        loader.close()
    }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
    }
}
