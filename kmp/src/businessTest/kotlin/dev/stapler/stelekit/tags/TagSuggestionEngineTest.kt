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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class TagSuggestionEngineTest {

    private fun now() = Clock.System.now()

    private fun makePage(uuid: String, name: String, isJournal: Boolean = false) = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now(),
        updatedAt = now(),
        isJournal = isJournal,
    )

    private suspend fun PageNameIndex.awaitMatcher(timeoutMs: Long = 2000): AhoCorasickMatcher {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            val m = matcher.value
            if (m != null) return m
            delay(20)
        }
        error("Matcher was still null after ${timeoutMs}ms")
    }

    /** Builds a [LlmTagProvider] whose [LlmFormatterProvider] returns the given text verbatim. */
    private fun stubLlmProvider(responseText: String): LlmTagProvider {
        val formatter = LlmFormatterProvider { _, _ -> LlmResult.Success(responseText, false) }
        return LlmTagProvider(formatter, timeoutSeconds = 5)
    }

    // ─── directMatch ─────────────────────────────────────────────────────────

    @Test
    fun `directMatch returns empty when no matcher is built`() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            val engine = TagSuggestionEngine(index)
            // No pages added — matcher is null
            val result = engine.directMatch("Kotlin is great")
            assertTrue(result.isEmpty(), "Expected empty but got $result")
        } finally {
            indexScope.cancel()
        }
    }

    @Test
    fun `directMatch finds exact page name in block content`() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        repo.savePage(makePage("1", "Kotlin"))
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            index.awaitMatcher()
            val engine = TagSuggestionEngine(index)
            val result = engine.directMatch("I love Kotlin programming")
            assertEquals(1, result.size)
            assertEquals("Kotlin", result[0].term)
            assertEquals(1.0f, result[0].confidence)
            assertTrue(result[0].autoApplied, "Local hits should be auto-applied")
            assertEquals(TagSuggestion.Source.LOCAL, result[0].source)
        } finally {
            indexScope.cancel()
        }
    }

    @Test
    fun `directMatch deduplicates repeated matches by term`() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        repo.savePage(makePage("1", "Kotlin"))
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            index.awaitMatcher()
            val engine = TagSuggestionEngine(index)
            val result = engine.directMatch("Kotlin and more Kotlin")
            assertEquals(1, result.size, "Should deduplicate repeated term")
        } finally {
            indexScope.cancel()
        }
    }

    @Test
    fun `directMatch returns empty when no page name appears in content`() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        repo.savePage(makePage("1", "Kotlin"))
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            index.awaitMatcher()
            val engine = TagSuggestionEngine(index)
            val result = engine.directMatch("Python is also a great language")
            assertTrue(result.isEmpty())
        } finally {
            indexScope.cancel()
        }
    }

    // ─── llmSuggest — no provider ─────────────────────────────────────────────

    @Test
    fun `llmSuggest returns empty right when no LLM provider`() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            val engine = TagSuggestionEngine(index, llmTagProvider = null)
            val result = engine.llmSuggest("Kotlin is great")
            assertTrue(result.isRight())
            assertTrue(result.getOrNull()!!.isEmpty())
        } finally {
            indexScope.cancel()
        }
    }

    // ─── llmSuggest — with stub provider ─────────────────────────────────────

    @Test
    fun `llmSuggest filters out already-linked terms`() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        repo.savePage(makePage("1", "Kotlin"))
        repo.savePage(makePage("2", "Python"))
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            index.awaitMatcher()
            // LLM responds with both names; "Kotlin" is already linked
            val provider = stubLlmProvider("Kotlin\nPython")
            val engine = TagSuggestionEngine(
                pageNameIndex = index,
                llmTagProvider = provider,
                vocabularyProvider = { listOf("Kotlin", "Python") },
            )
            val result = engine.llmSuggest(
                blockContent = "Kotlin and Python",
                alreadyLinkedTerms = setOf("Kotlin"),
            )
            assertTrue(result.isRight())
            val suggestions = result.getOrNull()!!
            assertFalse(suggestions.any { it.term.lowercase() == "kotlin" }, "Linked term should be filtered")
            assertTrue(suggestions.any { it.term.lowercase() == "python" })
        } finally {
            indexScope.cancel()
        }
    }

    @Test
    fun `llmSuggest deduplicates by term case-insensitive`() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        repo.savePage(makePage("1", "Kotlin"))
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            index.awaitMatcher()
            // LLM returns Kotlin twice (different cases)
            val provider = stubLlmProvider("Kotlin\nkotlin")
            val engine = TagSuggestionEngine(
                pageNameIndex = index,
                llmTagProvider = provider,
                vocabularyProvider = { listOf("Kotlin") },
            )
            val result = engine.llmSuggest("Kotlin")
            assertTrue(result.isRight())
            assertEquals(1, result.getOrNull()!!.size, "Duplicates should be removed")
        } finally {
            indexScope.cancel()
        }
    }

    // ─── vocabularyNames ──────────────────────────────────────────────────────

    @Test
    fun `vocabularyNames returns canonical page names from index`() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        repo.savePage(makePage("1", "Kotlin"))
        repo.savePage(makePage("2", "Python"))
        val indexScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, indexScope, rebuildDebounceMs = 0L)
            index.awaitMatcher()
            val vocab = index.vocabularyNames()
            assertTrue(vocab.contains("Kotlin"), "Vocab should contain Kotlin")
            assertTrue(vocab.contains("Python"), "Vocab should contain Python")
        } finally {
            indexScope.cancel()
        }
    }
}
