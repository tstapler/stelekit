// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

object MigrationRegistry {
    private val _migrations = mutableListOf<Migration>()
    fun register(migration: Migration) { _migrations.add(migration) }
    fun registerAll(vararg migrations: Migration) { _migrations.addAll(migrations) }
    fun all(): List<Migration> = _migrations.toList()
    fun clear() { _migrations.clear() }  // for test isolation
}
