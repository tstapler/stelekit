@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.assets

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.asset.testAsset
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [AssetDetailViewModel.moveTo] outcome handling: mover failures surface as state, never crash. */
class AssetDetailMoveTest {

    private suspend fun viewModel(mover: suspend (AssetEntry, String) -> Either<DomainError, Unit>): AssetDetailViewModel {
        val repo = InMemoryAssetRepository()
        repo.saveAsset(testAsset("a1"))
        val vm = AssetDetailViewModel(repo, AssetUuid("a1"), mover = mover)
        withTimeout(10_000) { vm.uiState.first { it.asset != null } }
        return vm
    }

    private suspend fun settled(vm: AssetDetailViewModel, predicate: (AssetDetailUiState) -> Boolean) =
        withTimeout(10_000) { vm.uiState.first { !it.isMoving && predicate(it) } }

    @Test
    fun moverLeft_surfacesTheDomainErrorMessage() = runBlocking {
        val vm = viewModel { _, _ -> DomainError.DatabaseError.WriteFailed("disk full").left() }
        val target = vm.moveTargets.first()

        vm.moveTo(target)

        val state = settled(vm) { it.moveError != null }
        assertEquals(true, state.moveError?.contains("disk full"))
    }

    @Test
    fun moverThrowing_surfacesTheMessage_and_releasesTheClaim() = runBlocking {
        var calls = 0
        val vm = viewModel { _, _ -> calls++; throw IllegalStateException("boom") }
        val target = vm.moveTargets.first()

        vm.moveTo(target)
        settled(vm) { it.moveError == "boom" }
        vm.moveTo(target)
        withTimeout(10_000) { while (calls < 2) kotlinx.coroutines.yield() }

        assertEquals(2, calls, "claim released after a failure so Move can be retried")
    }

    @Test
    fun moverThrowingWithoutMessage_usesGenericText() = runBlocking {
        val vm = viewModel { _, _ -> throw IllegalStateException() }

        vm.moveTo(vm.moveTargets.first())

        assertEquals("Move failed", settled(vm) { it.moveError != null }.moveError)
    }

    @Test
    fun moverCancelled_doesNotReportAnError_and_clearsMovingFlag() = runBlocking {
        val vm = viewModel { _, _ -> throw CancellationException("cancelled") }

        vm.moveTo(vm.moveTargets.first())

        assertNull(settled(vm) { true }.moveError)
    }

    @Test
    fun moverRight_leavesNoError() = runBlocking {
        val vm = viewModel { _, _ -> Unit.right() }

        vm.moveTo(vm.moveTargets.first())

        assertNull(settled(vm) { true }.moveError)
    }
}
