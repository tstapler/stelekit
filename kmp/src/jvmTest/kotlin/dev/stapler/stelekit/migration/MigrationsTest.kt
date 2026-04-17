// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationsTest {
    @BeforeTest
    fun setup() { MigrationRegistry.clear() }

    @AfterTest
    fun teardown() { MigrationRegistry.clear() }

    @Test
    fun registerAllMigrations_populates_registry() {
        registerAllMigrations()
        val migrations = MigrationRegistry.all()
        assertTrue(migrations.isNotEmpty(), "Registry should have at least one migration after registration")
        assertEquals("V20260414001__baseline", migrations.first().id)
    }

    @Test
    fun all_migrations_have_valid_checksums() {
        registerAllMigrations()
        for (migration in MigrationRegistry.all()) {
            assertTrue(migration.checksumBody.isNotBlank(), "Migration ${migration.id} must have checksumBody")
            assertTrue(migration.description.isNotBlank(), "Migration ${migration.id} must have description")
        }
    }

    @Test
    fun dag_is_valid_after_registration() {
        registerAllMigrations()
        val errors = DagValidator.validate(MigrationRegistry.all())
        assertTrue(errors.isEmpty(), "Migration DAG should be valid: $errors")
    }
}
