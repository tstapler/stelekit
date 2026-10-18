// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import dev.stapler.stelekit.model.EffectiveNotesPath
import kotlinx.coroutines.test.runTest
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

class JvmMediaAttachmentServiceTest {
    @Test fun `attachBytes should return null on JVM because the platform does not override the default`() = runTest {
        val service = JvmMediaAttachmentService()

        val result = service.attachBytes(byteArrayOf(1, 2, 3), "x.png", EffectiveNotesPath("/tmp/g"))

        assertNull(result)
    }

    @Test fun `attachFilePath should copy the file into the notes folder assets, not the repo root`() = runTest {
        val repoRoot = Files.createTempDirectory("repo").toFile()
        val notes = File(repoRoot, "logseq")
        val source = File(Files.createTempDirectory("src").toFile(), "photo.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }

        val result = JvmMediaAttachmentService().attachFilePath(source.path, EffectiveNotesPath(notes.path))

        val attachment = assertNotNull(result).getOrNull()
        assertEquals("../assets/photo.png", attachment?.relativePath)
        assertEquals(listOf<Byte>(9, 8, 7), File(notes, "assets/photo.png").readBytes().toList())
        assertTrue(!File(repoRoot, "assets").exists(), "assets must not be created beside the notes folder")
    }

    @Test fun `pasteFromClipboard should attach a copied image file into the notes folder assets`() = runTest {
        assumeFalse("needs a display for the system clipboard", GraphicsEnvironment.isHeadless())
        val notes = Files.createTempDirectory("notes").toFile()
        val source = File(Files.createTempDirectory("src").toFile(), "clip.png").apply { writeBytes(byteArrayOf(1)) }
        val clipboard = try {
            Toolkit.getDefaultToolkit().systemClipboard.also { cb ->
                cb.setContents(
                    object : Transferable {
                        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
                        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
                        override fun getTransferData(flavor: DataFlavor): Any = listOf(source)
                    },
                    null,
                )
            }
        } catch (e: Exception) {
            assumeFalse("system clipboard unavailable: ${e.message}", true)
            return@runTest
        }

        val result = try {
            JvmMediaAttachmentService().pasteFromClipboard(EffectiveNotesPath(notes.path))
        } finally {
            clipboard.setContents(StringSelection(""), null)
        }

        assertEquals("../assets/clip.png", assertNotNull(result).getOrNull()?.relativePath)
        assertTrue(File(notes, "assets/clip.png").exists())
    }
}
