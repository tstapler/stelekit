// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

data class Migration(
    val id: String,
    val description: String,
    val checksumBody: String,
    val requires: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val allowDestructive: Boolean = false,
    val apply: MigrationScope.() -> Unit,
    val revert: (MigrationScope.() -> Unit)? = null,
)
