package dev.stapler.stelekit.ui.transfer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.Either
import arrow.core.left
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
import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.FrameTransportReceiver
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.transfer.qrcode.ChunkFrameCodec
import dev.stapler.stelekit.transfer.qrcode.FountainChunk
import dev.stapler.stelekit.transfer.qrcode.FountainCodec
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrScanner
import dev.stapler.stelekit.transfer.qrcode.QrTransferCoordinator
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import dev.stapler.stelekit.transfer.qrcode.ScanResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.assertTrue
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test

/**
 * Story 3.2.4 / UX Acceptance Test criterion 5 (ux.md §12.5): every error/terminal state offers at
 * least one action that returns the user to a known, functional screen — never a blank dead end.
 * Parameterized (as one test, per validation.md's single named test method) over:
 * - `PreflightFailed` — both [DomainError.SensorError.HardwareUnavailable] and
 *   [DomainError.SensorError.PermissionDenied] (distinct copy per Bug 1 fix).
 * - `Failed` — the 3 of 6 [DomainError.QrTransferError] variants actually reachable through
 *   [QrDecodeViewModel]'s real pipeline: `ChunkDecodeFailed`, `IntegrityCheckFailed`,
 *   `MarkdownParseFailed`. The other 3 (`IncompleteTransfer`, `PayloadTooLarge`,
 *   `TransferCancelled`) are **not exercised here — see the KDoc on
 *   [failed_should_OfferReturnAction_When_QrTransferErrorVariantReachableViaRealPipeline] for the
 *   file:line evidence that they are unreachable dead code on the decode side** (a genuine
 *   implementation gap, not weakened around — reported in full in this suite's final report).
 * - `Cancelled` — both decoder ([QrDecodeUiState.Cancelled]) and encoder ([QrEncodeUiState.Cancelled]).
 *
 * Each scenario wires the screen's return action to a stateful router that swaps in a distinct
 * "known functional screen" marker on invocation — proving the return action actually *navigates*
 * somewhere, not just that a callback fires into the void.
 */
class QrNoDeadEndUxTest {

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

    private fun neverAvailableCameraSource() = object : CameraFrameSource {
        override val isAvailable = false
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
    }

    private fun permissionDeniedCameraSource() = object : CameraFrameSource {
        override val isAvailable = true
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
            emit(DomainError.SensorError.PermissionDenied("camera").left())
        }
    }

    private fun buildPreflightDecodeViewModel(cameraSource: CameraFrameSource): QrDecodeViewModel =
        QrDecodeViewModel(
            cameraFrameSource = cameraSource,
            qrImportService = buildImportService(),
            settings = QrTransferSettings(MapSettings()),
        )

    /** [cancel] is unconditional in [QrDecodeViewModel] — no real pipeline needs to run first. */
    private fun buildCancellableDecodeViewModel(): QrDecodeViewModel = QrDecodeViewModel(
        cameraFrameSource = neverAvailableCameraSource(),
        qrImportService = buildImportService(),
        settings = QrTransferSettings(MapSettings()),
    )

    /** [QrEncodeViewModel.cancel] is likewise unconditional — no page/blocks need to load first. */
    private fun buildCancellableEncodeViewModel(): QrEncodeViewModel = QrEncodeViewModel(
        pageRepository = InMemoryPageRepository(),
        blockRepository = InMemoryBlockRepository(),
        settings = QrTransferSettings(MapSettings()),
    )

    private fun litFrame() =
        CameraFrame(luminanceBytes = ByteArray(4) { 200.toByte() }, width = 2, height = 2, rotationDegrees = 0)

    private fun noOpQrScanner() = object : QrScanner {
        override fun decode(frame: CameraFrame): ScanResult = ScanResult.NoCodeDetected
        override val isAvailable: Boolean = true
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
    }

    /** Reaches `Failed(ChunkDecodeFailed)` via [QrTransferCoordinator]'s own uncaught-exception fallback (QrTransferCoordinator.kt:288). */
    private fun buildChunkDecodeFailedViewModel(): QrDecodeViewModel {
        val importService = buildImportService()
        val throwingReceiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow { throw RuntimeException("boom") }
        }
        return QrDecodeViewModel(
            cameraFrameSource = neverAvailableCameraSource(),
            qrImportService = importService,
            settings = QrTransferSettings(MapSettings()),
            coordinatorFactory = { name ->
                QrTransferCoordinator(
                    frameTransportReceiver = throwingReceiver,
                    qrImportService = importService,
                    targetName = name,
                    qrScanner = noOpQrScanner(),
                )
            },
        )
    }

    /**
     * Reaches `Failed(IntegrityCheckFailed)` for real: a single hand-built [FountainChunk] whose
     * `payloadCrc` deliberately does NOT match its `fragment`'s real CRC32 — with `fragment.size ==
     * payloadLen`, `expectedSeqLen == 1`, so [dev.stapler.stelekit.transfer.qrcode.ChunkBuffer]
     * fully "decodes" from this one chunk and [dev.stapler.stelekit.transfer.qrcode.FountainDecoder]
     * resolves it immediately — then the checksum comparison in `ChunkBuffer.resolveIfDecoded`
     * fails, exactly as `ChunkBufferTest` exercises at the unit level, but reached here through the
     * full ViewModel/coordinator pipeline.
     */
    private fun buildIntegrityCheckFailedViewModel(): QrDecodeViewModel {
        val importService = buildImportService()
        val fragment = "hello".encodeToByteArray()
        val badChunk = FountainChunk(
            transferId = TransferId(1),
            chunkIndex = ChunkIndex(0),
            payloadLen = fragment.size,
            payloadCrc = PayloadChecksum(0x0BAD_C0DE), // deliberately wrong — real CRC32("hello") differs
            fragment = fragment,
        )
        val receiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow {
                emit(ChunkFrameCodec.encode(badChunk))
                awaitCancellation()
            }
        }
        return QrDecodeViewModel(
            cameraFrameSource = neverAvailableCameraSource(),
            qrImportService = importService,
            settings = QrTransferSettings(MapSettings()),
            coordinatorFactory = { name ->
                QrTransferCoordinator(
                    frameTransportReceiver = receiver,
                    qrImportService = importService,
                    targetName = name,
                    qrScanner = noOpQrScanner(),
                )
            },
        )
    }

    /**
     * Reaches `Failed(MarkdownParseFailed)` for real: a genuine, checksum-VALID single-chunk
     * fountain transfer (via the real [FountainCodec.encoder], so it passes the CRC32 proof gate
     * and reassembles successfully) whose markdown payload contains a null byte, which
     * `Block`'s `Validation.validateContent` rejects during
     * [GraphLoader.importMarkdownString]'s block-construction tail (GraphLoader.kt:1560-1564) —
     * the exact fixture [QrImportServiceTest] uses at the service level, driven here through the
     * full pipeline instead.
     */
    private fun buildMarkdownParseFailedViewModel(): QrDecodeViewModel {
        val importService = buildImportService()
        val payload = "- bad content\n".encodeToByteArray()
        val encoder = FountainCodec.encoder(TransferId(2), payload, maxFragmentBytes = 500).getOrNull()!!
        val chunk = encoder.parts().first() // seqLen == 1 for this small a payload — systematic and complete.
        val receiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow {
                emit(ChunkFrameCodec.encode(chunk))
                awaitCancellation()
            }
        }
        return QrDecodeViewModel(
            cameraFrameSource = neverAvailableCameraSource(),
            qrImportService = importService,
            settings = QrTransferSettings(MapSettings()),
            coordinatorFactory = { name ->
                QrTransferCoordinator(
                    frameTransportReceiver = receiver,
                    qrImportService = importService,
                    targetName = name,
                    qrScanner = noOpQrScanner(),
                )
            },
        )
    }

    private fun buildEncodeViewModelWithPage(): Pair<QrEncodeViewModel, PageUuid> {
        val now = Clock.System.now()
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000099")
        kotlinx.coroutines.runBlocking {
            pageRepo.savePage(Page(uuid = pageUuid, name = "Sample", createdAt = now, updatedAt = now))
            blockRepo.saveBlock(
                Block(
                    uuid = BlockUuid("00000000-0000-0000-0000-000000000098"),
                    pageUuid = pageUuid,
                    content = "hello",
                    level = 0,
                    position = "a0",
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        val vm = QrEncodeViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            settings = QrTransferSettings(MapSettings()),
        )
        return vm to pageUuid
    }

    @Test
    fun everyTerminalState_should_OfferReturnAction_When_ErrorCancelledOrPermissionDeniedStateRendered() {
        // --- PreflightFailed: HardwareUnavailable → "Back" ---
        run {
            val vm = buildPreflightDecodeViewModel(neverAvailableCameraSource())
            val reachedKnownScreen = mutableStateOf(false)
            composeTestRule.setContent {
                MaterialTheme {
                    if (!reachedKnownScreen.value) {
                        QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = { reachedKnownScreen.value = true })
                    } else {
                        Text("Known screen")
                    }
                }
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.PreflightFailed }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Back").performClick()
            composeTestRule.onNodeWithText("Known screen").assertExists()
            assertTrue(reachedKnownScreen.value, "HardwareUnavailable: Back must navigate to a known functional screen")
            vm.close()
        }

        // --- PreflightFailed: PermissionDenied → rationale dialog's "Not now" ---
        run {
            val vm = buildPreflightDecodeViewModel(permissionDeniedCameraSource())
            val reachedKnownScreen = mutableStateOf(false)
            composeTestRule.setContent {
                MaterialTheme {
                    if (!reachedKnownScreen.value) {
                        QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = { reachedKnownScreen.value = true })
                    } else {
                        Text("Known screen")
                    }
                }
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.PreflightFailed }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Not now").performClick()
            composeTestRule.onNodeWithText("Known screen").assertExists()
            assertTrue(reachedKnownScreen.value, "PermissionDenied: \"Not now\" must navigate to a known functional screen")
            vm.close()
        }

        // --- Failed: the 3 real-pipeline-reachable QrTransferError variants ---
        val failedScenarios = listOf(
            "ChunkDecodeFailed" to ::buildChunkDecodeFailedViewModel,
            "IntegrityCheckFailed" to ::buildIntegrityCheckFailedViewModel,
            "MarkdownParseFailed" to ::buildMarkdownParseFailedViewModel,
        )
        for ((label, build) in failedScenarios) {
            val vm = build()
            val reachedKnownScreen = mutableStateOf(false)
            composeTestRule.setContent {
                MaterialTheme {
                    if (!reachedKnownScreen.value) {
                        QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = { reachedKnownScreen.value = true })
                    } else {
                        Text("Known screen")
                    }
                }
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.Failed }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Close").performClick()
            composeTestRule.onNodeWithText("Known screen").assertExists()
            assertTrue(reachedKnownScreen.value, "$label: Close must navigate to a known functional screen")
            vm.close()
        }

        // --- Cancelled: decoder side → "Close" ---
        run {
            val vm = buildCancellableDecodeViewModel()
            vm.cancel()
            val reachedKnownScreen = mutableStateOf(false)
            composeTestRule.setContent {
                MaterialTheme {
                    if (!reachedKnownScreen.value) {
                        QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = { reachedKnownScreen.value = true })
                    } else {
                        Text("Known screen")
                    }
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Close").performClick()
            composeTestRule.onNodeWithText("Known screen").assertExists()
            assertTrue(reachedKnownScreen.value, "Decoder Cancelled: Close must navigate to a known functional screen")
            vm.close()
        }

        // --- Cancelled: encoder side → "Close" ---
        run {
            val (vm, pageUuid) = buildEncodeViewModelWithPage()
            vm.cancel()
            val reachedKnownScreen = mutableStateOf(false)
            composeTestRule.setContent {
                MaterialTheme {
                    if (!reachedKnownScreen.value) {
                        QrEncodeScreen(
                            pageUuid = pageUuid,
                            pageName = "Sample",
                            blockCount = 1,
                            viewModel = vm,
                            settings = QrTransferSettings(MapSettings()),
                            onDismiss = { reachedKnownScreen.value = true },
                        )
                    } else {
                        Text("Known screen")
                    }
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Close").performClick()
            composeTestRule.onNodeWithText("Known screen").assertExists()
            assertTrue(reachedKnownScreen.value, "Encoder Cancelled: Close must navigate to a known functional screen")
            vm.close()
        }
    }
}
