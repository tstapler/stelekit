// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class PageNameIndexVocabularyCacheTest {

    private fun page(uuid: String, name: String) =
        Page(uuid = PageUuid(uuid), name = name, createdAt = Clock.System.now(), updatedAt = Clock.System.now())

    private suspend fun PageNameIndex.awaitVocabulary(expected: Set<String>) {
        val deadline = Clock.System.now().toEpochMilliseconds() + 3000
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            if (vocabularySet() == expected) return
            delay(20)
        }
        error("vocabulary never became $expected, was ${vocabularySet()}")
    }

    @Test
    fun vocabularySet_isCachedBetweenCalls_andRebuiltWhenPagesChange() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryPageRepository()
        repo.savePage(page("p1", "Kotlin"))
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val index = PageNameIndex(repo, scope, rebuildDebounceMs = 0L)
            index.awaitVocabulary(setOf("Kotlin"))

            val first = index.vocabularySet()
            assertSame(first, index.vocabularySet(), "same entry snapshot must reuse the cached set")
            assertEquals(index.vocabularyNames().toSet(), first)

            repo.savePage(page("p2", "Treacle"))
            index.awaitVocabulary(setOf("Kotlin", "Treacle"))
            assertTrue(index.vocabularySet() !== first, "cache must be invalidated when the vocabulary changes")
        } finally {
            scope.cancel()
        }
    }
}
