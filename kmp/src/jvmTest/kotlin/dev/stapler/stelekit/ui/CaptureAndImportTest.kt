package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.ImageImportService
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.toUiMessage
import dev.stapler.stelekit.platform.sensor.CameraProvider
import dev.stapler.stelekit.platform.sensor.PlatformImageFile
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureAndImportTest {

    private class FakeCameraProvider(
        private val result: Either<DomainError.SensorError, PlatformImageFile>,
    ) : CameraProvider {
        override val isAvailable = true
        override suspend fun capturePhoto() = result
    }

    private fun wiredService(fs: FakeFileSystem = FakeFileSystem()): ImageImportService =
        ImageImportService(
            fileSystem = fs,
            imageAnnotationRepository = InMemoryImageAnnotationRepository(),
            blockRepository = InMemoryBlockRepository(),
            sidecarManager = ImageSidecarManager(fs),
        )

    // ── Guard branches ──────────────────────────────────────────────────────

    @Test
    fun `null service - early return, no snackbar`() = runBlocking {
        val snackbars = mutableListOf<String>()
        val warns = mutableListOf<String>()
        executeCaptureAndImport(
            imageImportService = null,
            getActiveGraphPath = { "/graph" },
            pageUuid = "page-1",
            navigateAfterImport = false,
            cameraProvider = FakeCameraProvider(PlatformImageFile("/tmp/x.jpg").right()),
            onSnackbar = { snackbars += it },
            onNavigate = { _, _ -> },
            onWarn = { warns += it },
        )
        assertTrue(snackbars.isEmpty(), "no snackbar when service is null")
        assertEquals(1, warns.size)
    }

    @Test
    fun `empty graphPath - early return, no snackbar`() = runBlocking {
        val snackbars = mutableListOf<String>()
        val warns = mutableListOf<String>()
        executeCaptureAndImport(
            imageImportService = wiredService(),
            getActiveGraphPath = { "" },
            pageUuid = "page-1",
            navigateAfterImport = false,
            cameraProvider = FakeCameraProvider(PlatformImageFile("/tmp/x.jpg").right()),
            onSnackbar = { snackbars += it },
            onNavigate = { _, _ -> },
            onWarn = { warns += it },
        )
        assertTrue(snackbars.isEmpty(), "no snackbar when path is empty")
        assertEquals(1, warns.size)
    }

    @Test
    fun `null graphPath - early return, no snackbar`() = runBlocking {
        val snackbars = mutableListOf<String>()
        val warns = mutableListOf<String>()
        executeCaptureAndImport(
            imageImportService = wiredService(),
            getActiveGraphPath = { null },
            pageUuid = "page-1",
            navigateAfterImport = false,
            cameraProvider = FakeCameraProvider(PlatformImageFile("/tmp/x.jpg").right()),
            onSnackbar = { snackbars += it },
            onNavigate = { _, _ -> },
            onWarn = { warns += it },
        )
        assertTrue(snackbars.isEmpty(), "no snackbar when path is null")
        assertEquals(1, warns.size)
    }

    // ── Capture failure ─────────────────────────────────────────────────────

    @Test
    fun `permission denied capture failure - posts sanitized error to snackbar`() = runBlocking {
        val snackbars = mutableListOf<String>()
        // Use a realistic Android exception string to verify internal details are stripped
        val captureError = DomainError.SensorError.PermissionDenied(
            "android.hardware.camera2.CameraAccessException: CAMERA_ERROR (3): Camera service is currently unavailable"
        )
        executeCaptureAndImport(
            imageImportService = wiredService(),
            getActiveGraphPath = { "/graph" },
            pageUuid = "page-1",
            navigateAfterImport = false,
            cameraProvider = FakeCameraProvider(captureError.left()),
            onSnackbar = { snackbars += it },
            onNavigate = { _, _ -> },
            onWarn = {},
        )
        assertEquals(1, snackbars.size)
        assertEquals("Camera permission denied", snackbars[0])
        assertFalse(snackbars[0].contains("android.hardware"), "raw exception text must not reach UI")
    }

    @Test
    fun `capture failed - posts sanitized error to snackbar`() = runBlocking {
        val snackbars = mutableListOf<String>()
        val captureError = DomainError.SensorError.CaptureFailed(
            "java.lang.RuntimeException: android.hardware.camera2.CameraAccessException: CAMERA_DISABLED (1)"
        )
        executeCaptureAndImport(
            imageImportService = wiredService(),
            getActiveGraphPath = { "/graph" },
            pageUuid = "page-1",
            navigateAfterImport = false,
            cameraProvider = FakeCameraProvider(captureError.left()),
            onSnackbar = { snackbars += it },
            onNavigate = { _, _ -> },
            onWarn = {},
        )
        assertEquals(1, snackbars.size)
        assertEquals("Capture failed", snackbars[0])
        assertFalse(snackbars[0].contains("RuntimeException"), "raw exception text must not reach UI")
    }

    // ── Import failure ──────────────────────────────────────────────────────

    @Test
    fun `import failure - posts error to snackbar, no navigation`() = runBlocking {
        val snackbars = mutableListOf<String>()
        val navigations = mutableListOf<Pair<String, String>>()
        val fs = FakeFileSystem()
        // No bytes at the temp path → copyImageBytes fails with FileSystemError
        executeCaptureAndImport(
            imageImportService = wiredService(fs),
            getActiveGraphPath = { "/graph" },
            pageUuid = "page-1",
            navigateAfterImport = true,
            cameraProvider = FakeCameraProvider(PlatformImageFile("/tmp/missing.jpg").right()),
            onSnackbar = { snackbars += it },
            onNavigate = { ann, pg -> navigations += ann to pg },
            onWarn = {},
        )
        assertEquals(1, snackbars.size, "snackbar posted on import failure")
        assertEquals("File not found", snackbars[0], "snackbar must not embed raw filesystem path")
        assertTrue(navigations.isEmpty(), "no navigation on failure")
    }

    // ── Success paths ───────────────────────────────────────────────────────

    @Test
    fun `import success with navigateAfterImport true - calls onNavigate with correct pageUuid`() =
        runBlocking {
            val navigations = mutableListOf<Pair<String, String>>()
            val snackbars = mutableListOf<String>()
            val fs = FakeFileSystem()
            fs.writeFileBytes("/tmp/photo.jpg", "IMAGE".encodeToByteArray())
            executeCaptureAndImport(
                imageImportService = wiredService(fs),
                getActiveGraphPath = { "/graph" },
                pageUuid = "page-1",
                navigateAfterImport = true,
                cameraProvider = FakeCameraProvider(PlatformImageFile("/tmp/photo.jpg").right()),
                onSnackbar = { snackbars += it },
                onNavigate = { ann, pg -> navigations += ann to pg },
                onWarn = {},
            )
            assertTrue(snackbars.isEmpty(), "no snackbar on success")
            assertEquals(1, navigations.size, "navigation called on success")
            assertEquals("page-1", navigations[0].second)
        }

    @Test
    fun `import success with navigateAfterImport false - does not call onNavigate`() = runBlocking {
        val navigations = mutableListOf<Pair<String, String>>()
        val fs = FakeFileSystem()
        fs.writeFileBytes("/tmp/photo.jpg", "IMAGE".encodeToByteArray())
        executeCaptureAndImport(
            imageImportService = wiredService(fs),
            getActiveGraphPath = { "/graph" },
            pageUuid = "page-1",
            navigateAfterImport = false,
            cameraProvider = FakeCameraProvider(PlatformImageFile("/tmp/photo.jpg").right()),
            onSnackbar = {},
            onNavigate = { ann, pg -> navigations += ann to pg },
            onWarn = {},
        )
        assertTrue(navigations.isEmpty(), "navigation not called when navigateAfterImport=false")
    }

    // ── withImportTimeout ───────────────────────────────────────────────────

    @Test
    fun `withImportTimeout returns null instead of hanging when the import operation never completes`() =
        runBlocking {
            val result = withImportTimeout<Either<DomainError, String>>(timeoutMs = 200L) {
                suspendCancellableCoroutine { /* never resumed — simulates a wedged save */ }
            }
            assertNull(result, "a stalled import must time out instead of hanging forever")
        }

    @Test
    fun `withImportTimeout returns the operation result when it completes in time`() = runBlocking {
        val expected: Either<DomainError, String> = "ok".right()
        val result = withImportTimeout(timeoutMs = 200L) { expected }
        assertEquals(expected, result)
    }
}
