// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.SteleKitContext
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the idiom-review MUST FIX: [AndroidGraphZipExporter.export] used to read
 * every file through [dev.stapler.stelekit.platform.FileSystem.readFile] (`String?`) and
 * re-encode it as UTF-8, silently mangling any file whose bytes aren't valid UTF-8 (images,
 * attachments, paranoid-mode STEK-encrypted files) since the graph walk in
 * [dev.stapler.stelekit.platform.FileSystem.listFilesRecursiveWithModTimes] covers every file
 * under the graph root, not just `.md` files. It now reads via
 * [dev.stapler.stelekit.platform.FileSystem.readFileBytes] instead.
 *
 * `export()` also hands the finished zip to the OS share sheet via `FileProvider` + `Intent`,
 * which needs a `fileprovider` authority declared in the app module's manifest — not present in
 * `kmp`'s own test manifest, so `export()` legitimately returns `Left(ShareFailed)` under
 * Robolectric here. That failure occurs strictly after the zip file is fully written and closed,
 * so the test reads the zip back off disk directly rather than asserting on the `Either`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidGraphZipExporterBinaryRoundTripTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun readZipEntries(zipFile: File): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun export_should_RoundTripBinaryContentByteIdentical_When_GraphContainsNonUtf8Files() = runTest {
        SteleKitContext.init(context)
        val fileSystem = PlatformFileSystem().apply { init(context) }
        val graphPath = File(context.filesDir, "graphs/binary-roundtrip-test").absolutePath
        File(graphPath).deleteRecursively()

        // Invalid UTF-8 byte sequences (lone continuation/start bytes) that a String round-trip
        // (decode + re-encode) would lossily replace with U+FFFD, corrupting the content.
        val binaryContent = byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x01, 0x80.toByte(), 0xC0.toByte(), 0x02)
        val textContent = "# Alpha\n- a plain text page"

        check(fileSystem.writeFileBytes("$graphPath/assets/photo.png", binaryContent)) {
            "test setup: writing the binary fixture file failed"
        }
        check(fileSystem.writeFile("$graphPath/pages/Alpha.md", textContent)) {
            "test setup: writing the text fixture file failed"
        }

        AndroidGraphZipExporter().export(fileSystem, graphPath, "roundtrip-graph")

        val zipFile = File(context.cacheDir, "share_export/roundtrip-graph.zip")
        assertTrue(zipFile.exists(), "export must write the zip file to cacheDir before the share-sheet step")
        val entries = readZipEntries(zipFile)

        assertContentEquals(
            binaryContent,
            entries["assets/photo.png"],
            "binary file must round-trip byte-identical through export, not be mangled by a String re-encode",
        )
        assertContentEquals(
            textContent.encodeToByteArray(),
            entries["pages/Alpha.md"],
            "text file content must still round-trip correctly",
        )
    }
}
