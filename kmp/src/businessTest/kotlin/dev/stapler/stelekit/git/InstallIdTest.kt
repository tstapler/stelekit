// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InstallIdTest {
    private val dir: File = Files.createTempDirectory("install-id").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun loadOrCreate_should_ReturnTheSameIdOnEveryCall() {
        val file = File(dir, "nested/id")
        val first = assertNotNull(InstallId.loadOrCreate(file))
        assertEquals(first, InstallId.loadOrCreate(file))
    }

    @Test
    fun loadOrCreate_should_ConvergeOnOneId_When_ManyStartersRace() {
        val file = File(dir, "id")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val ids = (1..16).map { pool.submit<String?> { InstallId.loadOrCreate(file) } }.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, ids.toSet().size, "racing creators must agree: $ids")
            assertNotNull(ids.first())
        } finally {
            pool.shutdownNow()
        }
    }
}
