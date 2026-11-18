// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Raw `/proc` reads shared by the JVM and Android liveness adapters. java.io/NIO only, and no
 * `Files.readString` (Java 11, Android API 33), so it loads on Android minSdk 26.
 */
object ProcfsProbe {
    private val PROC = File("/proc")

    /** Start ticks of [pid] read from `/proc/<pid>/stat`: Absent on ENOENT, Unreadable on EACCES or a parse failure. */
    fun probe(pid: Long, procRoot: File = PROC): ProbeResult = probeFile(File(procRoot, "$pid/stat"))

    fun isAvailable(procRoot: File = PROC): Boolean = File(procRoot, "self/stat").canRead()

    /** This process's own start ticks via `/proc/self`, null where unreadable. */
    fun selfStartTicks(procRoot: File = PROC): Long? = (probeFile(File(procRoot, "self/stat")) as? ProbeResult.Present)?.startStamp

    private fun probeFile(stat: File): ProbeResult = try {
        val ticks = ProcStatParser.startTicks(String(Files.readAllBytes(stat.toPath()), Charsets.UTF_8))
        if (ticks == null) ProbeResult.Unreadable else ProbeResult.Present(ticks)
    } catch (_: NoSuchFileException) {
        ProbeResult.Absent
    } catch (_: AccessDeniedException) {
        ProbeResult.Unreadable
    } catch (_: IOException) {
        ProbeResult.Unreadable
    }

    /** `readlink /proc/self/ns/pid`, null where unreadable. */
    fun selfPidNamespace(procRoot: File = PROC): String? = try {
        Files.readSymbolicLink(File(procRoot, "self/ns/pid").toPath()).toString()
    } catch (_: IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    /** `/proc/sys/kernel/random/boot_id`, null where unreadable. */
    fun bootId(procRoot: File = PROC): String? = try {
        String(Files.readAllBytes(File(procRoot, "sys/kernel/random/boot_id").toPath()), Charsets.UTF_8).trim().ifEmpty { null }
    } catch (_: IOException) {
        null
    }
}
