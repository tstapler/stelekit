// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** Parallel test forks share the data dir; the loser of the create race must not fail the graph open. */
class EnsureParentDirectoryTest {

    @Test
    fun ensureParentDirectory_should_NotThrow_When_ManyThreadsRaceToCreateTheSameDirectory() {
        repeat(RACES) {
            val root = Files.createTempDirectory("ensure-parent").toFile()
            val dbPath = File(root, "a/b/c/graph.db").path
            val barrier = CyclicBarrier(THREADS)
            val pool = Executors.newFixedThreadPool(THREADS)
            try {
                val results = (1..THREADS).map {
                    pool.submit<Unit> {
                        barrier.await()
                        ensureParentDirectory(dbPath)
                    }
                }
                results.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                assertTrue(File(dbPath).parentFile.isDirectory)
            } finally {
                pool.shutdownNow()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun ensureParentDirectory_should_BeANoOp_When_PathHasNoParent() {
        ensureParentDirectory("graph.db")
    }

    private companion object {
        const val RACES = 50
        const val THREADS = 16
        const val TIMEOUT_SECONDS = 30L
    }
}
