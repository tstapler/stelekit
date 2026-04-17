// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

data class MigrationPlan(
    val pendingMigrations: List<MigrationPlanEntry>,
    val alreadyApplied: Int,
    val wouldApply: Int,
    val wouldSkip: Int,
)

data class MigrationPlanEntry(
    val migrationId: String,
    val description: String,
    val plannedChanges: List<BlockChange>,
    val isDestructive: Boolean,
)
