@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.library

import arrow.core.left
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parity rows "Missing-file handling" and "Per-asset actions menu": the state behind the card. */
class LibraryViewModelActionsTest {
    private val fx = LibraryFixture()
    private val toClose = mutableListOf<LibraryViewModel>()

    @AfterTest
    fun tearDown() {
        toClose.forEach { it.close() }
        fx.close()
    }

    private fun vm(fileExists: ((String) -> Boolean)? = null, delete: (suspend (String) -> arrow.core.Either<DomainError, Unit>)? = null) =
        LibraryViewModel(fx.library, null, fileExists = fileExists, deleteAsset = delete, searchDebounceMs = 20).also { toClose += it }

    private suspend fun await(v: LibraryViewModel, cond: (LibraryState) -> Boolean) = withTimeout(10_000) { v.state.first(cond) }

    private suspend fun seed() {
        fx.add(1, "here.png", AssetMediaType.IMAGE, 1_000L)
        fx.add(2, "gone.png", AssetMediaType.IMAGE, 1_001L)
    }

    @Test
    fun itemsWhoseFileIsGone_areFlaggedMissing_andOthersAreNot() = runBlocking {
        seed()
        val v = vm(fileExists = { !it.endsWith("gone.png") })
        val s = await(v) { !it.isLoading && it.items.size == 2 && it.missingIds.isNotEmpty() }
        assertEquals(setOf("asset-000002"), s.missingIds)
    }

    @Test
    fun withoutAFileSystem_nothingIsFlaggedMissing() = runBlocking {
        seed()
        val v = vm()
        val s = await(v) { !it.isLoading && it.items.size == 2 }
        assertTrue(s.missingIds.isEmpty())
    }

    @Test
    fun deleteItem_callsTheDeleter_thenReloadsWithoutTheItem() = runBlocking {
        seed()
        val deleted = mutableListOf<String>()
        val v = vm(delete = { id -> deleted += id; fx.assets.deleteAsset(dev.stapler.stelekit.asset.AssetUuid(id)) })
        await(v) { !it.isLoading && it.items.size == 2 }
        v.deleteItem("asset-000002")
        val s = await(v) { !it.isLoading && it.items.size == 1 && it.totalCount == 1L }
        assertEquals(listOf("asset-000002"), deleted)
        assertEquals("asset-000001", s.items.single().id)
    }

    @Test
    fun aFailedDelete_surfacesTheErrorAndKeepsTheItem() = runBlocking {
        seed()
        val v = vm(delete = { DomainError.DatabaseError.WriteFailed("disk full").left() })
        await(v) { !it.isLoading && it.items.size == 2 }
        v.deleteItem("asset-000001")
        val s = await(v) { it.actionError != null }
        assertEquals("disk full", s.actionError)
        assertEquals(2, s.items.size)
        v.dismissActionError()
        assertEquals(null, v.state.value.actionError)
    }

    @Test
    fun deleteIsANoOpWhenNoDeleterIsWired() = runBlocking {
        seed()
        val v = vm()
        await(v) { !it.isLoading && it.items.size == 2 }
        v.deleteItem("asset-000001")
        assertEquals(2, v.state.value.items.size)
    }
}
