// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingCaptureWriterTest {

    @Test
    fun write_should_CreateAtomicJsonFileWithGeneratedCaptureId_When_CaptureIdNotSupplied() {
        val dir = Files.createTempDirectory("pending-captures")

        val target = PendingCaptureWriter.write("Call dentist", directory = dir.toString())

        assertTrue(target.isRegularFile())
        assertEquals(dir.toString(), target.parent.toString())
        val captureId = target.fileName.toString().removeSuffix(".json")
        assertTrue(captureId.isNotBlank())

        val decoded = Json.decodeFromString<PendingCaptureFile>(target.readText())
        assertEquals(captureId, decoded.captureId)
        assertEquals("Call dentist", decoded.text)
    }

    @Test
    fun write_should_UseSuppliedCaptureId_NotGenerateNew_When_CaptureIdProvided() {
        val dir = Files.createTempDirectory("pending-captures")
        val suppliedId = "0193abc-fixed-id"

        val target = PendingCaptureWriter.write("Buy milk", captureId = suppliedId, directory = dir.toString())

        assertEquals("$suppliedId.json", target.fileName.toString())
        val decoded = Json.decodeFromString<PendingCaptureFile>(target.readText())
        assertEquals(suppliedId, decoded.captureId)
    }

    @Test
    fun write_should_LeaveNoPartialJsonVisible_When_AtomicMoveThrows() {
        val dir = Files.createTempDirectory("pending-captures")
        val captureId = "will-fail-to-move"
        // Pre-create a directory at the target's ".json" path so the atomic
        // move (which does not pass REPLACE_EXISTING) throws instead of publishing.
        val blockingDir = dir.resolve("$captureId.json")
        Files.createDirectory(blockingDir)

        assertFailsWith<IOException> {
            PendingCaptureWriter.write("Should not appear", captureId = captureId, directory = dir.toString())
        }

        assertTrue(blockingDir.isDirectory(), "pre-existing directory must be untouched, not replaced by a file")
        val jsonFiles = dir.listDirectoryEntries("*.json").filter { it.isRegularFile() }
        assertTrue(jsonFiles.isEmpty(), "no regular .json file should be visible after a failed move")

        val tmp = dir.resolve("$captureId.json.tmp")
        assertTrue(tmp.exists(), "the orphaned .tmp file is the only artifact a failed move may leave behind")
    }

    @Test
    fun write_should_RoundTripToIdenticalFields_When_ReadBackFromRealTempDirectory() {
        val dir = Files.createTempDirectory("pending-captures")

        val generatedTarget = PendingCaptureWriter.write("Generated id capture", directory = dir.toString())
        val generatedDecoded = Json.decodeFromString<PendingCaptureFile>(generatedTarget.readText())
        assertEquals(generatedTarget.fileName.toString().removeSuffix(".json"), generatedDecoded.captureId)
        assertEquals("Generated id capture", generatedDecoded.text)
        assertFalse(generatedDecoded.capturedAt.isBlank())

        val explicitId = "explicit-capture-id"
        val explicitTarget = PendingCaptureWriter.write("Explicit id capture", captureId = explicitId, directory = dir.toString())
        val explicitDecoded = Json.decodeFromString<PendingCaptureFile>(explicitTarget.readText())
        assertEquals(explicitId, explicitDecoded.captureId)
        assertEquals("Explicit id capture", explicitDecoded.text)
        assertFalse(explicitDecoded.capturedAt.isBlank())
    }
}
