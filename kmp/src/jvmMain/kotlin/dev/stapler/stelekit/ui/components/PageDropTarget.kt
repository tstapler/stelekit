// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp")

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
actual fun Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier {
    val target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val dropEvent = event.nativeEvent as? DropTargetDropEvent ?: return false
            dropEvent.acceptDrop(dropEvent.dropAction)
            val transferable = try {
                dropEvent.transferable
            } catch (e: Exception) {
                dropEvent.dropComplete(false)
                return false
            }
            if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dropEvent.dropComplete(false)
                return false
            }
            @Suppress("UNCHECKED_CAST")
            val files = try {
                transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
            } catch (e: Exception) {
                null
            }
            if (files == null) {
                dropEvent.dropComplete(false)
                return false
            }
            val imageFiles = files.filter { it.extension.lowercase() in imageExtensions }
            dropEvent.dropComplete(imageFiles.isNotEmpty())
            if (imageFiles.isEmpty()) return false
            onFilesDropped(imageFiles)
            return true
        }
    }
    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            val nativeEvent = event.nativeEvent
            when (nativeEvent) {
                is DropTargetDragEvent ->
                    nativeEvent.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                is DropTargetDropEvent ->
                    nativeEvent.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                else -> false
            }
        },
        target = target
    )
}
