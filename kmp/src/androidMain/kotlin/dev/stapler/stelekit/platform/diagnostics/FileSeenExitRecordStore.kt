// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.diagnostics

import java.io.File

/** Remembers the last announced exit record in one small file next to the persisted logs. */
class FileSeenExitRecordStore(private val file: File) : SeenExitRecordStore {
    override fun lastSeenId(): String? = if (file.exists()) file.readText().trim().ifEmpty { null } else null

    override fun markSeen(id: String) {
        file.parentFile?.mkdirs()
        file.writeText(id)
    }
}
