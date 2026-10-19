// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.stats.BenchmarkTargets
import dev.stapler.stelekit.stats.GraphStatsReport
import dev.stapler.stelekit.platform.LibraryStatsProvider
import dev.stapler.stelekit.stats.NamespaceStat
import dev.stapler.stelekit.stats.PageConnectivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [LibraryStatsScreen] across its Idle/Loading/Loaded/Error states. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class LibraryStatsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun report(pageCount: Int = 42) = GraphStatsReport(
        graphPath = "/graphs/test",
        pageCount = pageCount,
        journalCount = 7,
        totalBlocks = 1234,
        pagesWithNoContent = 3,
        totalOutgoingLinks = 500,
        totalHashtags = 11,
        pagesWithOutgoingLinks = 20,
        pagesWithIncomingLinks = 10,
        avgOutgoingLinksPerPage = 2.5f,
        avgIncomingLinksPerPage = 1.5f,
        maxIncomingLinks = 9,
        maxOutgoingLinks = 8,
        blockLinkDensity = 0.25f,
        incomingLinkHistogram = mapOf(0 to 5, 1 to 3, 2 to 1),
        outgoingLinkHistogram = mapOf(0 to 5),
        topByIncomingLinks = listOf(PageConnectivity("Hub Page", 9, 2)),
        topByOutgoingLinks = emptyList(),
        firstJournalDate = "2024-01-01",
        lastJournalDate = "2024-12-31",
        journalDays = 7,
        journalSpanDays = 366,
        journalFillRate = 0.02f,
        avgBlocksPerPage = 29.4f,
        journalsByYear = mapOf("2024" to 7),
        journalsByMonth = mapOf("2024-01" to 7),
        topNamespaces = listOf(NamespaceStat("projects", 4)),
        benchmarkTargets = BenchmarkTargets(84, 14, 0.25f, 5, 40),
    )

    private class CountingProvider(val result: GraphStatsReport?) : LibraryStatsProvider {
        var calls = 0
        override suspend fun collect(notesPath: EffectiveNotesPath): GraphStatsReport? {
            calls++
            return result
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun vm(provider: LibraryStatsProvider) =
        LibraryStatsViewModel(provider, EffectiveNotesPath("/graphs/test"), scope)

    private fun show(vm: LibraryStatsViewModel) {
        composeTestRule.setContent { MaterialTheme { LibraryStatsScreen(vm) } }
    }

    @Test
    fun libraryStats_should_showScanningLabel_when_stateIsLoading() {
        val gate = CompletableDeferred<GraphStatsReport?>()
        val vm = vm(object : LibraryStatsProvider {
            override suspend fun collect(notesPath: EffectiveNotesPath) = gate.await()
        })
        try {
            show(vm)
            composeTestRule.waitUntil(5_000) { vm.state.value == LibraryStatsState.Loading }
            composeTestRule.onNodeWithText("Scanning library…").assertExists()
        } finally {
            gate.complete(null)
        }
    }

    @Test
    fun libraryStats_should_showStats_when_providerReturnsReport() {
        val vm = vm(CountingProvider(report()))
        show(vm)
        composeTestRule.waitUntil(5_000) { vm.state.value is LibraryStatsState.Loaded }
        composeTestRule.onNodeWithText("Library Stats").assertExists()
        composeTestRule.onNodeWithText("Volume").assertExists()
        composeTestRule.onNodeWithText("Link Topology").assertExists()
        composeTestRule.onNodeWithText("Pages").assertExists()
        composeTestRule.onNodeWithText("42 pages  ·  7 journals  ·  2024-01-01 → 2024-12-31").assertExists()
        composeTestRule.onNodeWithText("1,234").assertExists()
    }

    @Test
    fun libraryStats_should_showErrorMessage_when_providerReturnsNull() {
        val vm = vm(CountingProvider(null))
        show(vm)
        composeTestRule.waitUntil(5_000) { vm.state.value is LibraryStatsState.Error }
        composeTestRule.onNodeWithText("Graph path not found: /graphs/test").assertExists()
    }

    @Test
    fun libraryStats_should_showErrorMessage_when_providerThrows() {
        val vm = vm(object : LibraryStatsProvider {
            override suspend fun collect(notesPath: EffectiveNotesPath): GraphStatsReport? = error("disk on fire")
        })
        show(vm)
        composeTestRule.waitUntil(5_000) { vm.state.value is LibraryStatsState.Error }
        composeTestRule.onNodeWithText("disk on fire").assertExists()
    }

    @Test
    fun libraryStats_should_rescan_when_refreshIsClicked() {
        val provider = CountingProvider(report())
        val vm = vm(provider)
        show(vm)
        composeTestRule.waitUntil(5_000) { vm.state.value is LibraryStatsState.Loaded }
        assertEquals(1, provider.calls)
        composeTestRule.onNodeWithContentDescription("Refresh").performClick()
        composeTestRule.waitUntil(5_000) { provider.calls == 2 }
        assertTrue(vm.state.value is LibraryStatsState.Loaded)
    }

    @Test
    fun libraryStats_should_showNoDatedJournals_when_journalDatesMissing() {
        val vm = vm(CountingProvider(report().copy(firstJournalDate = null, lastJournalDate = null)))
        show(vm)
        composeTestRule.waitUntil(5_000) { vm.state.value is LibraryStatsState.Loaded }
        composeTestRule.onNodeWithText("(no dated journals)", substring = true).assertExists()
    }
}
