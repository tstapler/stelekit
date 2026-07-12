package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.FrameTransportReceiver
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.transfer.qrcode.ChunkFrameCodec
import dev.stapler.stelekit.transfer.qrcode.FountainCodec
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrScanner
import dev.stapler.stelekit.transfer.qrcode.QrTransferCoordinator
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import dev.stapler.stelekit.transfer.qrcode.ScanResult
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * UX Acceptance Test criterion 3 (validation.md): Cancel must resolve in exactly one tap, with no
 * confirmation dialog interposed, from every non-terminal state that offers a Cancel button —
 * [QrEncodeUiState.Displaying], [QrEncodeUiState.Paused] (sender), and [QrDecodeUiState.Scanning]
 * (receiver).
 */
class QrCancelUxTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    private class NoOpFileSystem : FileSystem {
        override fun getDefaultGraphPath() = ""
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    // ── Encoder (sender) side: Displaying / Paused ──────────────────────────────────────────

    private fun buildEncodeViewModel(): Pair<QrEncodeViewModel, PageUuid> {
        val now = Clock.System.now()
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
        runBlocking {
            pageRepo.savePage(Page(uuid = pageUuid, name = "Meeting Notes", createdAt = now, updatedAt = now))
            blockRepo.saveBlock(
                Block(
                    uuid = BlockUuid("00000000-0000-0000-0000-000000000010"),
                    pageUuid = pageUuid,
                    content = "x".repeat(2045),
                    level = 0,
                    position = "a0",
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        val settings = QrTransferSettings(MapSettings()).apply { maxFragmentBytes = 171 }
        // Freeze the auto-advance pacing loop (never-resolving tick) so Cancel/Pause assertions
        // race against nothing — same tick-injection seam QrEncodeViewModelTest uses to avoid real
        // sleeps; here it also rules out a last-writer-wins race between a genuine pacing tick's
        // Displaying write and cancel()'s Cancelled write landing on different threads at once.
        val vm = QrEncodeViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            settings = settings,
            tick = { awaitCancellation() },
        )
        return vm to pageUuid
    }

    // ── Decoder (receiver) side: Scanning ───────────────────────────────────────────────────

    private fun buildImportService(): QrImportService {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val actor = DatabaseWriteActor(blockRepo, pageRepo)
        val graphLoader = GraphLoader(
            fileSystem = NoOpFileSystem(),
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            externalWriteActor = actor,
        )
        return QrImportService(graphLoader, pageRepo, actor)
    }

    /** Mirrors [QrDecodeScreenTest.buildScanningViewModel] — idles on `Scanning` indefinitely. */
    private fun buildDecodeViewModel(): QrDecodeViewModel {
        val cameraSource = object : CameraFrameSource {
            override val isAvailable = true
            override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
                emit(CameraFrame(luminanceBytes = ByteArray(4) { 200.toByte() }, width = 2, height = 2, rotationDegrees = 0).right())
                awaitCancellation()
            }
        }
        val encoder = FountainCodec.encoder(TransferId(1), "x".repeat(500).encodeToByteArray(), maxFragmentBytes = 12).getOrNull()!!
        val receiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow {
                var count = 0
                for (chunk in encoder.parts()) {
                    if (count >= 3) awaitCancellation()
                    emit(ChunkFrameCodec.encode(chunk))
                    count++
                    kotlinx.coroutines.delay(5)
                }
            }
        }
        val importService = buildImportService()
        val fakeQrScanner = object : QrScanner {
            override fun decode(frame: CameraFrame): ScanResult = ScanResult.NoCodeDetected
            override val isAvailable: Boolean get() = cameraSource.isAvailable
            override fun frameStream() = cameraSource.frameStream()
        }
        return QrDecodeViewModel(
            cameraFrameSource = cameraSource,
            qrImportService = importService,
            settings = QrTransferSettings(MapSettings()),
            coordinatorFactory = {
                QrTransferCoordinator(
                    frameTransportReceiver = receiver,
                    qrImportService = importService,
                    qrScanner = fakeQrScanner,
                )
            },
        )
    }

    /** No node whose text mentions a confirmation prompt is on screen — i.e. no interposed dialog. */
    private fun assertNoConfirmationDialog() {
        val sureNodes = composeTestRule.onAllNodesWithText("sure", substring = true, ignoreCase = true).fetchSemanticsNodes()
        assertTrue(sureNodes.isEmpty(), "expected no confirmation prompt, found: $sureNodes")
        val confirmNodes = composeTestRule.onAllNodesWithText("confirm", substring = true, ignoreCase = true).fetchSemanticsNodes()
        assertTrue(confirmNodes.isEmpty(), "expected no confirmation prompt, found: $confirmNodes")
    }

    @Test
    fun cancel_should_TransitionToCancelled_When_SingleTapOnCancelButton_ParameterizedOverDisplayingPausedAndScanning() {
        // --- S3: Displaying (encoder) ---
        run {
            val (vm, pageUuid) = buildEncodeViewModel()
            composeTestRule.setContent {
                QrEncodeScreen(
                    pageUuid = pageUuid,
                    pageName = "Meeting Notes",
                    blockCount = 5,
                    viewModel = vm,
                    settings = QrTransferSettings(MapSettings()),
                    onDismiss = {},
                )
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Displaying }
            composeTestRule.waitForIdle()

            // performScrollTo() brings Cancel into view first — the first-use explainer banner
            // plus the large inset QR card can push it below the fold on a real viewport; scrolling
            // to it doesn't count as a tap, so this still honors the "single tap" criterion.
            composeTestRule.onNodeWithText("Cancel").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            assertTrue(vm.state.value is QrEncodeUiState.Cancelled, "single Cancel tap must reach Cancelled directly from Displaying, actual=${vm.state.value}")
            composeTestRule.onNodeWithText("Transfer cancelled").assertIsDisplayed()
            assertNoConfirmationDialog()
            vm.close()
        }

        // --- S4 Paused (encoder) ---
        run {
            val (vm, pageUuid) = buildEncodeViewModel()
            composeTestRule.setContent {
                QrEncodeScreen(
                    pageUuid = pageUuid,
                    pageName = "Meeting Notes",
                    blockCount = 5,
                    viewModel = vm,
                    settings = QrTransferSettings(MapSettings()),
                    onDismiss = {},
                )
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Displaying }
            vm.pause()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Paused }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Cancel").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            assertTrue(vm.state.value is QrEncodeUiState.Cancelled, "single Cancel tap must reach Cancelled directly from Paused")
            composeTestRule.onNodeWithText("Transfer cancelled").assertIsDisplayed()
            assertNoConfirmationDialog()
            vm.close()
        }

        // --- S9: Scanning (decoder) ---
        run {
            val vm = buildDecodeViewModel()
            composeTestRule.setContent {
                QrDecodeScreen(
                    viewModel = vm,
                    settings = QrTransferSettings(MapSettings()),
                    onDismiss = {},
                )
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.Scanning }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Cancel").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            assertTrue(vm.state.value is QrDecodeUiState.Cancelled, "single Cancel tap must reach Cancelled directly from Scanning")
            composeTestRule.onNodeWithText("Import cancelled").assertIsDisplayed()
            assertNoConfirmationDialog()
            vm.close()
        }
    }
}
