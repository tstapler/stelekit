package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * UX Acceptance Test criterion 12 (validation.md) — AUTOMATED SUBSET ONLY. This test covers the
 * `ComposeTestRule`-checkable half: every clickable control on every stable transfer
 * screen/dialog/entry-point state also carries a keyboard `RequestFocus` action, so it is reachable
 * via standard Tab/focus-traversal order and activatable without a pointer, rather than bypassing
 * accessibility semantics via a bare `Modifier.clickable`.
 *
 * OUT OF SCOPE (explicitly, per validation.md): the manual physical Tab/D-pad/switch-scan pass on
 * a real device is NOT automatable and is not attempted here — this only proves the semantics tree
 * *supports* that traversal, not that a human confirmed it end-to-end.
 */
class QrTransferAccessibilityUxTest {

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

    /** Every `hasClickAction()` node must also satisfy `hasRequestFocusAction()` — no bare clickables. */
    private fun assertAllClickablesAreFocusable(label: String) {
        composeTestRule.waitForIdle()
        val clickable = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val clickableAndFocusable = composeTestRule
            .onAllNodes(hasClickAction().and(hasRequestFocusAction()))
            .fetchSemanticsNodes()
        assertTrue(
            clickable.isNotEmpty(),
            "$label: expected at least one clickable control to exist on screen",
        )
        assertTrue(
            clickableAndFocusable.size == clickable.size,
            "$label: ${clickable.size - clickableAndFocusable.size} of ${clickable.size} clickable " +
                "control(s) lack a RequestFocus action — reachable by pointer only, not Tab/D-pad " +
                "traversal (bypasses proper Button/Role semantics)",
        )
    }

    private fun seedPage(pageUuid: PageUuid, contentLength: Int): Pair<InMemoryPageRepository, InMemoryBlockRepository> {
        val now = Clock.System.now()
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        runBlocking {
            pageRepo.savePage(Page(uuid = pageUuid, name = "Meeting Notes", createdAt = now, updatedAt = now))
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
        return pageRepo to blockRepo
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

    /**
     * Mirrors [QrDecodeScreenTest.buildScanningViewModel] / [QrCancelUxTest.buildDecodeViewModel]
     * — `Scanning` is only reached via a genuine `FragmentAdmitted`/`ScanHintUpdated` coordinator
     * event, so a raw undecoded [CameraFrame] (as used for the PreflightFailed fixtures below)
     * never gets there; this feeds real fountain-encoded chunks through a fake [QrScanner].
     */
    private fun buildScanningDecodeViewModel(): QrDecodeViewModel {
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

    @Test
    fun allInteractiveControls_should_BeReachableViaTabOrder_And_ActivatableWithoutPointer_When_NavigatingS1ThroughS11() {
        // --- S1/S7: entry-point menu items ---
        composeTestRule.setContent {
            SendViaQrMenuItem(settings = QrTransferSettings(MapSettings()).apply { enabled = true }, onClick = {})
        }
        assertAllClickablesAreFocusable("S1 SendViaQrMenuItem")

        composeTestRule.setContent {
            ImportViaCameraMenuItem(settings = QrTransferSettings(MapSettings()).apply { enabled = true }, onClick = {})
        }
        assertAllClickablesAreFocusable("S7 ImportViaCameraMenuItem")

        // --- S3: QrEncodeScreen Displaying ---
        run {
            val pageUuid = PageUuid("00000000-0000-0000-0000-000000000001")
            val (pageRepo, blockRepo) = seedPage(pageUuid, contentLength = 2045)
            val settings = QrTransferSettings(MapSettings()).apply { maxFragmentBytes = 171 }
            val vm = QrEncodeViewModel(
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                settings = settings,
                tick = { awaitCancellation() }, // freeze pacing — a stable tree to inspect
            )
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
            assertAllClickablesAreFocusable("S3 QrEncodeScreen.Displaying")
            vm.close()
        }

        // --- S4: QrEncodeScreen Paused ---
        run {
            val pageUuid = PageUuid("00000000-0000-0000-0000-000000000002")
            val (pageRepo, blockRepo) = seedPage(pageUuid, contentLength = 2045)
            val settings = QrTransferSettings(MapSettings()).apply { maxFragmentBytes = 171 }
            val vm = QrEncodeViewModel(
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                settings = settings,
                tick = { awaitCancellation() },
            )
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
            assertAllClickablesAreFocusable("S4 QrEncodeScreen.Paused")
            vm.close()
        }

        // --- QrEncodeScreen Failed (PayloadTooLarge) ---
        run {
            val pageUuid = PageUuid("00000000-0000-0000-0000-000000000003")
            val (pageRepo, blockRepo) = seedPage(pageUuid, contentLength = 70_000)
            val vm = QrEncodeViewModel(pageRepository = pageRepo, blockRepository = blockRepo, settings = QrTransferSettings(MapSettings()))
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
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Failed }
            assertAllClickablesAreFocusable("QrEncodeScreen.Failed")
            vm.close()
        }

        // --- QrEncodeScreen Complete ---
        run {
            val pageUuid = PageUuid("00000000-0000-0000-0000-000000000004")
            val (pageRepo, blockRepo) = seedPage(pageUuid, contentLength = 2045)
            val settings = QrTransferSettings(MapSettings()).apply { maxFragmentBytes = 171 }
            val vm = QrEncodeViewModel(
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                settings = settings,
                tick = { awaitCancellation() },
            )
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
            vm.complete()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Complete }
            assertAllClickablesAreFocusable("QrEncodeScreen.Complete")
            vm.close()
        }

        // --- QrEncodeScreen Cancelled ---
        run {
            val pageUuid = PageUuid("00000000-0000-0000-0000-000000000005")
            val (pageRepo, blockRepo) = seedPage(pageUuid, contentLength = 2045)
            val settings = QrTransferSettings(MapSettings()).apply { maxFragmentBytes = 171 }
            val vm = QrEncodeViewModel(
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                settings = settings,
                tick = { awaitCancellation() },
            )
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
            vm.cancel()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrEncodeUiState.Cancelled }
            assertAllClickablesAreFocusable("QrEncodeScreen.Cancelled")
            vm.close()
        }

        // --- S9: QrDecodeScreen Scanning ---
        run {
            val vm = buildScanningDecodeViewModel()
            composeTestRule.setContent {
                QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = {})
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.Scanning }
            assertAllClickablesAreFocusable("S9 QrDecodeScreen.Scanning")
            vm.close()
        }

        // --- S8: QrDecodeScreen PreflightFailed (HardwareUnavailable) ---
        run {
            val cameraSource = object : CameraFrameSource {
                override val isAvailable = false
                override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
            }
            val vm = QrDecodeViewModel(
                cameraFrameSource = cameraSource,
                qrImportService = buildImportService(),
                settings = QrTransferSettings(MapSettings()),
            )
            composeTestRule.setContent {
                QrDecodeScreen(
                    viewModel = vm,
                    settings = QrTransferSettings(MapSettings()),
                    onDismiss = {},
                    onImportFromFile = {},
                )
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.PreflightFailed }
            assertAllClickablesAreFocusable("S8 QrDecodeScreen.PreflightFailed(HardwareUnavailable)")
            vm.close()
        }

        // --- S6: QrDecodeScreen PreflightFailed (PermissionDenied, rationale dialog) ---
        run {
            val cameraSource = object : CameraFrameSource {
                override val isAvailable = true
                override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
                    emit(DomainError.SensorError.PermissionDenied("camera").left())
                }
            }
            val vm = QrDecodeViewModel(
                cameraFrameSource = cameraSource,
                qrImportService = buildImportService(),
                settings = QrTransferSettings(MapSettings()),
            )
            composeTestRule.setContent {
                QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = {})
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.PreflightFailed }
            assertAllClickablesAreFocusable("S6 QrDecodeScreen.PreflightFailed(PermissionDenied)")
            vm.close()
        }

        // --- QrDecodeScreen Cancelled ---
        run {
            val vm = buildScanningDecodeViewModel()
            composeTestRule.setContent {
                QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = {})
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.Scanning }
            vm.cancel()
            composeTestRule.waitUntil(timeoutMillis = 5_000) { vm.state.value is QrDecodeUiState.Cancelled }
            assertAllClickablesAreFocusable("QrDecodeScreen.Cancelled")
            vm.close()
        }

        // --- S11: QrImportConfirmDialog ---
        composeTestRule.setContent {
            QrImportConfirmDialog(
                existingName = "Meeting Notes",
                pendingChoice = null,
                onKeepBoth = {},
                onOverwrite = {},
                onCancel = {},
            )
        }
        assertAllClickablesAreFocusable("S11 QrImportConfirmDialog")
    }
}
