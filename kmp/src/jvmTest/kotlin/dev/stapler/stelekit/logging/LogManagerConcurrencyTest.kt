// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.logging

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals

class LogManagerConcurrencyTest {

    @Test
    fun addLog_should_KeepEveryEntry_When_ManyThreadsLogConcurrently() {
        val threads = 8
        val perThread = 100
        val tagPrefix = "LogManagerConcurrencyTest-${System.nanoTime()}-"
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                repeat(perThread) { i -> LogManager.addLog(LogEntry(LogLevel.INFO, "$tagPrefix$t", "m$i")) }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join() }

        val mine = LogManager.logs.value.count { it.tag.startsWith(tagPrefix) }
        assertEquals(threads * perThread, mine, "entries lost to a read-modify-write race on the log buffer")
    }

    @Test
    fun addLog_should_NotThrow_When_SinkRegisteredWhileOthersLog() {
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val logger = Thread {
            start.await()
            try { repeat(300) { LogManager.addLog(LogEntry(LogLevel.INFO, "SinkRace", "x")) } } catch (e: Throwable) { errors.add(e) }
        }.also { it.start() }
        start.countDown()
        repeat(300) { LogManager.addSink(object : LogSink { override fun write(entry: LogEntry, formatted: String) {} }).close() }
        logger.join()
        assertEquals(emptyList<Throwable>(), errors.toList())
    }
}
