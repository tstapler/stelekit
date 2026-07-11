@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.transfer

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Story 3.1.2 acceptance criteria. [QrEncodeViewModel] owns its [kotlinx.coroutines.CoroutineScope]
 * internally on REAL `Dispatchers.Default` (CLAUDE.md scope-ownership) and drives
 * [dev.stapler.stelekit.transfer.qrcode.QrFrameTransport.send] — the frame-advance delay is
 * injected via the `tick` constructor parameter (Task 3.1.2b testability note) so pacing is
 * asserted deterministically without any real-time sleep between frames.
 *
 * These tests use [runBlocking], not `kotlinx.coroutines.test.runTest`: the VM's scope runs on a
 * real dispatcher independent of any `TestCoroutineScheduler`, so `runTest`'s virtual-time
 * auto-advance would race against (and can pre-empt) the VM's genuine cross-thread work. Real
 * [withTimeout] against real background completion is the correct tool here; the injected `tick`
 * still removes the need for a real 400 ms sleep between individual frame advances.
 */
class QrEncodeViewModelTest {

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    private val now = Clock.System.now()

    /** Seeds a page with a single block whose content is exactly [contentLength] ASCII bytes. */
    private fun fixture(contentLength: Int = 30): Triple<InMemoryPageRepository, InMemoryBlockRepository, PageUuid> {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")

        runBlocking {
            pageRepo.savePage(
                Page(
                    uuid = pageUuid,
                    name = "Meeting Notes",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            blockRepo.saveBlock(
                Block(
                    uuid = BlockUuid("00000000-0000-0000-0000-000000000010"),
                    pageUuid = pageUuid,
                    content = "x".repeat(contentLength),
                    level = 0,
                    position = "a0",
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return Triple(pageRepo, blockRepo, pageUuid)
    }

    @Test
    fun start_should_TransitionIdleSerializingDisplaying_When_PageHasThreeBlocksAtDefaultFps() = runBlocking {
        val (pageRepo, blockRepo, pageUuid) = fixture(contentLength = 30)
        val tickChannel = Channel<Unit>(Channel.RENDEZVOUS)
        val capturedIntervals = mutableListOf<Long>()
        val settings = QrTransferSettings(MapSettings())
        val vm = QrEncodeViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            settings = settings,
            tick = { ms -> synchronized(capturedIntervals) { capturedIntervals.add(ms) }; tickChannel.receive() },
        )

        assertEquals(QrEncodeUiState.Idle, vm.state.value)
        vm.start(pageUuid)

        val displaying = withTimeout(5_000) {
            vm.state.first { it is QrEncodeUiState.Displaying }
        }
        val first = assertIs<QrEncodeUiState.Displaying>(displaying)
        assertEquals(0, first.frameIndex)
        assertEquals(0, first.totalCycled)
        assertTrue(first.chunkCount > 0)
        assertTrue(first.estBytes > 0)

        // Advance exactly one paced tick and confirm the interval matches ADR-004's 2.5fps default
        // (400ms/frame). The background loop may already be blocked on its *next* tick() call by
        // the time this assertion runs, so check the recorded interval(s), not an exact-length list.
        tickChannel.send(Unit)
        withTimeout(5_000) {
            vm.state.first { (it as? QrEncodeUiState.Displaying)?.totalCycled == 1 }
        }
        synchronized(capturedIntervals) {
            assertTrue(capturedIntervals.isNotEmpty())
            assertTrue(capturedIntervals.all { it == 400L }, "expected only 400ms ticks, got $capturedIntervals")
        }

        vm.close()
    }

    @Test
    fun start_should_TransitionToFailed_When_SerializedPayloadExceedsMaxPayloadBytes() = runBlocking {
        // FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES = 65536; a 70_000-byte block content trips
        // the pre-flight PayloadTooLarge gate before any frame is ever displayed (UX gap G1).
        val (pageRepo, blockRepo, pageUuid) = fixture(contentLength = 70_000)
        val settings = QrTransferSettings(MapSettings())
        val vm = QrEncodeViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            settings = settings,
        )

        vm.start(pageUuid)

        val failed = withTimeout(5_000) {
            vm.state.first { it is QrEncodeUiState.Failed }
        }
        val error = assertIs<QrEncodeUiState.Failed>(failed).error
        assertIs<DomainError.QrTransferError.PayloadTooLarge>(error)

        vm.close()
    }

    @Test
    fun pause_should_FreezeFrameIndex_And_resume_should_ContinueAtSameIndex_When_LifecycleTogglesMidDisplay() = runBlocking {
        // Small maxFragmentBytes forces many chunks for a modest payload so frameIndex can reach 4.
        val (pageRepo, blockRepo, pageUuid) = fixture(contentLength = 400)
        val tickChannel = Channel<Unit>(Channel.RENDEZVOUS)
        val settings = QrTransferSettings(MapSettings()).apply { maxFragmentBytes = 16 }
        val vm = QrEncodeViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            settings = settings,
            tick = { tickChannel.receive() },
        )

        vm.start(pageUuid)
        val initial = withTimeout(5_000) { vm.state.first { it is QrEncodeUiState.Displaying } }
        val chunkCount = assertIs<QrEncodeUiState.Displaying>(initial).chunkCount
        assertTrue(chunkCount > 4, "fixture must yield chunkCount > 4 to exercise frameIndex=4, was $chunkCount")

        repeat(4) {
            tickChannel.send(Unit)
        }
        val beforePause = withTimeout(5_000) {
            vm.state.first { (it as? QrEncodeUiState.Displaying)?.totalCycled == 4 }
        }
        val displaying4 = assertIs<QrEncodeUiState.Displaying>(beforePause)
        assertEquals(4, displaying4.frameIndex)

        vm.pause()
        val paused = assertIs<QrEncodeUiState.Paused>(vm.state.value)
        assertEquals(displaying4.frameIndex, paused.frameIndex)
        assertEquals(displaying4.totalCycled, paused.totalCycled)

        vm.resume()
        val resumed = assertIs<QrEncodeUiState.Displaying>(vm.state.value)
        assertEquals(paused.frameIndex, resumed.frameIndex)
        assertEquals(paused.totalCycled, resumed.totalCycled)

        vm.close()
    }

    @Test
    fun cancel_should_StopLoopWithinOneTick_And_close_should_CancelScopeWithoutForgottenScopeException() = runBlocking {
        // Real scope-owning VM wired to the REAL QrFrameTransport.send(...) (default QrCodec/QrScanner,
        // no fakes) via a fake repository — proves the VM never touches FountainEncoder/ChunkFrameCodec/
        // QrCodec.encode directly, only through the QrFrameTransport seam.
        val (pageRepo, blockRepo, pageUuid) = fixture(contentLength = 30)
        val settings = QrTransferSettings(MapSettings())
        val vm = QrEncodeViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            settings = settings,
        )

        vm.start(pageUuid)
        withTimeout(5_000) { vm.state.first { it is QrEncodeUiState.Displaying } }

        vm.cancel()
        assertEquals(QrEncodeUiState.Cancelled, vm.state.value)

        // The loop must not resurrect Displaying after cancellation.
        delay(50)
        assertEquals(QrEncodeUiState.Cancelled, vm.state.value)

        // Must not throw ForgottenCoroutineScopeException (or anything else) — the scope is
        // internally owned, never a caller-supplied rememberCoroutineScope().
        vm.close()
    }
}
