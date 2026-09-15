// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

private object JvmPlatformFileOpener : PlatformFileOpener {
    override suspend fun openFile(absolutePath: String, mimeType: String) =
        withContext(Dispatchers.IO) {
            if (Desktop.isDesktopSupported()) {
                runCatching {
                    val desktop = Desktop.getDesktop()
                    if (desktop.isSupported(Desktop.Action.OPEN)) {
                        desktop.open(File(absolutePath))
                    }
                }
            }
        }
}

@Composable
actual fun rememberPlatformFileOpener(): PlatformFileOpener = JvmPlatformFileOpener
