package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.db.sidecar.ImageSidecarManager
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImageImportServicePathTest {

    private fun buildService(fs: FakeFileSystem = FakeFileSystem()) = ImageImportService(
        fileSystem = fs,
        imageAnnotationRepository = InMemoryImageAnnotationRepository(),
        blockRepository = InMemoryBlockRepository(),
        sidecarManager = ImageSidecarManager(fs),
    )

    @Test
    fun reservePath_should_createDirectory_when_assetsImagesAbsent() {
        val fs = FakeFileSystem()
        val service = buildService(fs)
        val graphPath = "/graph"
        val uuid = "a3f8b2c1-d4e5-6789-abcd-ef0123456789"

        val result = service.reservePath(graphPath, uuid)
        assertIs<Either.Right<String>>(result)

        val resolvedPath = result.value
        assertTrue(resolvedPath.contains("/assets/images/"), "Path should contain /assets/images/")
        assertTrue(resolvedPath.endsWith(".jpg"), "Path should end with .jpg")

        // The directory should now exist in the fake FS
        assertTrue(
            fs.directoryExists("$graphPath/assets") || fs.directoryExists("$graphPath/assets/images"),
            "assets or assets/images directory should have been created"
        )
    }

    @Test
    fun reservePath_should_returnCorrectPath_when_calledWithKnownUuid() {
        val fs = FakeFileSystem()
        val service = buildService(fs)
        val uuid = "abc12345-0000-0000-0000-000000000000"
        val result = service.reservePath("/graph", uuid)

        assertIs<Either.Right<String>>(result)
        // UUID prefix is first 8 non-dash chars: abc12345
        assertTrue(result.value.contains("abc12345"), "Path should contain the uuid prefix abc12345")
    }
}
