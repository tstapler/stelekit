// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import dev.stapler.stelekit.platform.DroppedCanvasFile
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.io.RandomAccessFile

/** A file read by range, so a 20 MB drop is never one array; the file is opened per read and closed again. */
internal class FileImportSource(private val file: File) : ImportSource {
    override val size: Long = file.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= size) return ByteArray(0)
        val count = minOf(length.toLong(), size - offset).toInt()
        val buffer = ByteArray(count)
        RandomAccessFile(file, "r").use {
            it.seek(offset)
            it.readFully(buffer)
        }
        return buffer
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
actual fun Modifier.canvasFileDrop(onFile: (DroppedCanvasFile) -> Unit): Modifier {
    val target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val dropEvent = event.nativeEvent as? DropTargetDropEvent ?: return false
            dropEvent.acceptDrop(dropEvent.dropAction)
            val files = try {
                (dropEvent.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()
            } catch (e: java.io.IOException) {
                null
            } catch (e: java.awt.datatransfer.UnsupportedFlavorException) {
                null
            }
            val file = files?.firstOrNull { it.isFile }
            dropEvent.dropComplete(file != null)
            if (file == null) return false
            onFile(DroppedCanvasFile(file.name, FileImportSource(file)))
            return true
        }
    }
    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            when (val native = event.nativeEvent) {
                is DropTargetDragEvent -> native.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                is DropTargetDropEvent -> native.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                else -> false
            }
        },
        target = target,
    )
}
