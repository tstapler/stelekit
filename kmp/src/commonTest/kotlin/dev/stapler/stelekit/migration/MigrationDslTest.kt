// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationDslTest {

    @Test
    fun migration_builder_produces_correct_id() {
        val m = migration("V001") {}
        assertEquals("V001", m.id)
    }

    @Test
    fun migration_builder_collects_requires() {
        val m = migration("V002") {
            requires("V001", "V000")
        }
        assertEquals(listOf("V001", "V000"), m.requires)
    }

    @Test
    fun revert_lambda_is_optional() {
        val m = migration("V003") {
            apply { /* no-op */ }
        }
        assertNull(m.revert)
    }

    @Test
    fun revert_lambda_is_captured_when_declared() {
        val m = migration("V004") {
            apply { /* no-op */ }
            revert { /* no-op */ }
        }
        assertNotNull(m.revert)
    }

    @Test
    fun checksum_normalization_strips_bom() {
        assertEquals(
            MigrationChecksumComputer.compute("\uFEFFbody"),
            MigrationChecksumComputer.compute("body"),
        )
    }

    @Test
    fun checksum_normalization_crlf_equals_lf() {
        assertEquals(
            MigrationChecksumComputer.compute("hello\r\n"),
            MigrationChecksumComputer.compute("hello\n"),
        )
    }

    @Test
    fun checksum_normalization_trailing_whitespace() {
        assertEquals(
            MigrationChecksumComputer.compute("line  \n"),
            MigrationChecksumComputer.compute("line\n"),
        )
    }

    @Test
    fun checksum_has_sha256_v1_prefix() {
        val result = MigrationChecksumComputer.compute("some body text")
        assertTrue(result.startsWith("sha256-v1:"), "Expected sha256-v1: prefix, got: $result")
    }

    @Test
    fun registry_preserves_registration_order() {
        MigrationRegistry.clear()
        val m1 = migration("V001") {}
        val m2 = migration("V002") {}
        val m3 = migration("V003") {}
        MigrationRegistry.registerAll(m1, m2, m3)
        val ids = MigrationRegistry.all().map { it.id }
        assertEquals(listOf("V001", "V002", "V003"), ids)
        MigrationRegistry.clear()
    }
}
