// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.domain.PageNameIndex
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class TagSuggestionViewModelTest {

    private fun now() = Clock.System.now()

    private fun makePage(uuid: String, name: String) = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now(),
        updatedAt = now(),
    )

    private suspend fun PageNameIndex.awaitMatcher(timeoutMs: Long = 2000): AhoCorasickMatcher {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            val m = matcher.value
            if (m != null) return m
            delay(20)
        }
        error("Matcher still null after ${timeoutMs}ms")
    }

    /**
     * Polls [TagSuggestionViewModel.state] until it satisfies [predicate] or times out.
     * Necessary because [TagSuggestionViewModel] owns its own [CoroutineScope] with
     * [kotlinx.coroutines.Dispatchers.Default], which [kotlinx.coroutines.test.advanceUntilIdle]
     * cannot control.
     */
    private suspend fun TagSuggestionViewModel.awaitState(
        timeoutMs: Long = 5000,
        predicate: (TagSuggestionState) -> Boolean,
    ): TagSuggestionState {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            val s = state.value
            if (predicate(s)) return s
            delay(20)
        }
        error("State ${state.value} never satisfied predicate within ${timeoutMs}ms")
    }

    private fun makeIdleEngine(indexScope: CoroutineScope): TagSuggestionEngine {
        val repo = InMemoryPageRepository()
        val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
        return TagSuggestionEngine(index, llmTagProvider = null)
    }

    // ─── initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() = runTest(UnconfinedTestDispatcher()) {
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val vm = TagSuggestionViewModel(makeIdleEngine(indexScope))
            assertIs<TagSuggestionState.Idle>(vm.state.value)
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    // ─── requestSuggestions ───────────────────────────────────────────────────

    @Test
    fun `requestSuggestions transitions to Ready`() = runTest(UnconfinedTestDispatcher()) {
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val vm = TagSuggestionViewModel(makeIdleEngine(indexScope))
            vm.requestSuggestions("block-1", "Some content")
            val ready = vm.awaitState { it is TagSuggestionState.Ready }
            assertIs<TagSuggestionState.Ready>(ready)
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    @Test
    fun `Ready state contains correct blockUuid`() = runTest(UnconfinedTestDispatcher()) {
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val vm = TagSuggestionViewModel(makeIdleEngine(indexScope))
            vm.requestSuggestions("block-42", "Some content")
            val state = vm.awaitState { it is TagSuggestionState.Ready }
            assertIs<TagSuggestionState.Ready>(state)
            assertTrue(state.blockUuid == "block-42")
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    // ─── dismiss ─────────────────────────────────────────────────────────────

    @Test
    fun `dismiss resets state to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val vm = TagSuggestionViewModel(makeIdleEngine(indexScope))
            vm.requestSuggestions("block-1", "Some content")
            vm.awaitState { it is TagSuggestionState.Ready }

            vm.dismiss()
            assertIs<TagSuggestionState.Idle>(vm.state.value)
            vm.close()
        } finally {
            indexScope.cancel()
        }
    }

    // ─── LLM tier ─────────────────────────────────────────────────────────────

    @Test
    fun `LLM suggestions appear in Ready state when provider returns results`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = InMemoryPageRepository()
            repo.savePage(makePage("1", "Kotlin"))
            val indexScope = CoroutineScope(UnconfinedTestDispatcher())
            try {
                val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
                index.awaitMatcher()
                val formatter = LlmFormatterProvider { _, _ -> LlmResult.Success("Kotlin", false) }
                val llmProvider = LlmTagProvider(formatter, timeoutSeconds = 5)
                val engine = TagSuggestionEngine(
                    pageNameIndex = index,
                    llmTagProvider = llmProvider,
                    vocabularyProvider = { listOf("Kotlin") },
                )
                val vm = TagSuggestionViewModel(engine)
                vm.requestSuggestions("block-1", "I love Kotlin")
                // Wait until LLM suggestions are populated
                val state = vm.awaitState {
                    it is TagSuggestionState.Ready && it.llmSuggestions.isNotEmpty()
                }
                assertIs<TagSuggestionState.Ready>(state)
                assertTrue(state.llmSuggestions.isNotEmpty(), "Expected LLM suggestions but got none")
                vm.close()
            } finally {
                indexScope.cancel()
            }
        }
}
