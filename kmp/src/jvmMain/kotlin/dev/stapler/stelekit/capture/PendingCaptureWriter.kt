// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.util.UuidGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes a [PendingCaptureFile] atomically: the JSON body lands in a `.tmp` file first,
 * then an [Files.move] with [StandardCopyOption.ATOMIC_MOVE] publishes it under the final
 * `.json` name, so readers never observe a partially written capture file.
 */
object PendingCaptureWriter {

    fun write(
        text: String,
        captureId: String = UuidGenerator.generateV7(),
        directory: String = PendingCapturesDirectory.path()
    ): Path {
        val dir = Path.of(directory)
        Files.createDirectories(dir)

        val file = PendingCaptureFile(
            captureId = captureId,
            text = text,
            capturedAt = Clock.System.now().toString()
        )
        val json = Json.encodeToString(file)

        val target = dir.resolve("$captureId.json")
        val tmp = dir.resolve("$captureId.json.tmp")
        Files.writeString(tmp, json)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        return target
    }
}
