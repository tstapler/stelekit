// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.projectbridge.isAvailable
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings

/** Why the overflow "Import .excalidraw..." entry is disabled on this platform, or null when it can run (UX-135). */
object ImportAvailability {
    fun reason(files: SketchFilePort?, storage: V2Storage): String? = when {
        !storage.isAvailable -> SketchReasonText.v2StorageUnavailable(storage)
        files == null || !files.canPick -> SketchImportExportStrings.Import.NO_PICKER
        else -> null
    }
}
