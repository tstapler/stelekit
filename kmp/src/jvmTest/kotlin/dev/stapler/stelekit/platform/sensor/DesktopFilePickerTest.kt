// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.sensor

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Entry point for the forced-headless child JVM; prints the result type and message. */
object HeadlessPickerProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = runBlocking { DesktopFilePicker.pickImageFile() }
        when (result) {
            is Either.Left -> println("LEFT:${result.value::class.simpleName}:${result.value.message}")
            is Either.Right -> println("RIGHT")
        }
    }
}

class DesktopFilePickerTest {

    /**
     * jvmTest runs with `-Djava.awt.headless=false`, so the headless branch can't be reached
     * in-process; run it in a child JVM with headless forced on, which is deterministic anywhere.
     */
    @Test
    fun `pickImageFile returns HardwareUnavailable when headless`() {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            java, "-Djava.awt.headless=true",
            "-cp", System.getProperty("java.class.path"),
            HeadlessPickerProbe::class.java.name,
        ).redirectErrorStream(true).start()

        val finished = process.waitFor(60, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) process.destroyForcibly()

        assertTrue(finished, "child JVM timed out: $output")
        val line = output.lines().firstOrNull { it.startsWith("LEFT:") || it == "RIGHT" }
        assertTrue(line != null && line.startsWith("LEFT:"), "unexpected output: $output")
        assertEquals("HardwareUnavailable", line.split(":")[1])
        assertTrue("headless" in line)
    }
}
