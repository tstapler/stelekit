// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Serves one PNG-typed file through a content:// uri, the way a gallery picker or the clipboard does. */
class ImageProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("shot.png")) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        lateinit var file: File
    }
}

/** In-memory SAF stand-in; the Bazel android unit-test target has no access to commonTest fakes. */
private class FakeFileSystem : FileSystem {
    private val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf<String>()

    override fun getDefaultGraphPath() = "/graph"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = files[path]?.decodeToString()
    override fun writeFile(path: String, content: String): Boolean = writeFileBytes(path, content.encodeToByteArray())
    override fun listFiles(path: String) = emptyList<String>()
    override fun listDirectories(path: String) = emptyList<String>()
    override fun fileExists(path: String) = path in files
    override fun directoryExists(path: String) = path in dirs
    override fun createDirectory(path: String): Boolean = dirs.add(path).let { true }
    override fun deleteFile(path: String) = files.remove(path) != null
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
    override fun readFileBytes(path: String): ByteArray? = files[path]
    override fun writeFileBytes(path: String, data: ByteArray): Boolean { files[path] = data; return true }
}

/** Asserts attachments land in `<notes folder>/assets`, the folder git sync stages, for both local and SAF graphs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidMediaAttachmentServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://stelekit.test.images/shot")
    private val png = byteArrayOf(1, 2, 3)

    @Before fun registerProvider() {
        ImageProvider.file = File.createTempFile("shot", ".png").apply { writeBytes(png) }
        Robolectric.buildContentProvider(ImageProvider::class.java).create("stelekit.test.images")
    }

    private fun service(fileSystem: FakeFileSystem = FakeFileSystem()) =
        AndroidMediaAttachmentService(context, fileSystem) { uri }

    private fun copyImageToClipboard() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "img", uri))
    }

    @Test fun pickAndAttach_should_CopyIntoNotesAssets_When_GraphIsLocal() = runTest {
        val repoRoot = Files.createTempDirectory("repo").toFile()
        val notes = File(repoRoot, "logseq")

        val result = service().pickAndAttach(EffectiveNotesPath(notes.path), pageRelativePath = "")

        assertEquals("../assets/shot.png", assertNotNull(result).getOrNull()?.relativePath)
        assertEquals(png.toList(), File(notes, "assets/shot.png").readBytes().toList())
        assertTrue(!File(repoRoot, "assets").exists(), "assets must not appear beside the notes folder")
    }

    @Test fun pickAndAttach_should_WriteUnderNotesAssets_When_GraphIsSaf() = runTest {
        val fs = FakeFileSystem()

        val result = service(fs).pickAndAttach(EffectiveNotesPath("saf://tree/graph/logseq"), pageRelativePath = "")

        assertEquals("../assets/shot.png", assertNotNull(result).getOrNull()?.relativePath)
        assertEquals(png.toList(), fs.readFileBytes("saf://tree/graph/logseq/assets/shot.png")?.toList())
    }

    @Test fun pasteFromClipboard_should_CopyIntoNotesAssets_When_GraphIsLocal() = runTest {
        copyImageToClipboard()
        val notes = Files.createTempDirectory("notes").toFile()

        val result = service().pasteFromClipboard(EffectiveNotesPath(notes.path))

        assertEquals("../assets/clipboard.png", assertNotNull(result).getOrNull()?.relativePath)
        assertTrue(File(notes, "assets/clipboard.png").exists())
    }

    @Test fun pasteFromClipboard_should_WriteUnderNotesAssets_When_GraphIsSaf() = runTest {
        copyImageToClipboard()
        val fs = FakeFileSystem()

        val result = service(fs).pasteFromClipboard(EffectiveNotesPath("saf://tree/graph/logseq"))

        assertEquals("../assets/clipboard.png", assertNotNull(result).getOrNull()?.relativePath)
        assertEquals(png.toList(), fs.readFileBytes("saf://tree/graph/logseq/assets/clipboard.png")?.toList())
    }
}
