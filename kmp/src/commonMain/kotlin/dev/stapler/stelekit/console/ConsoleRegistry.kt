// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

/** Verbs are matched case-insensitively; a duplicate name is a programming error and fails at construction. */
class ConsoleRegistry(commands: List<ConsoleCommand>) {
    val all: List<ConsoleCommand> = commands.sortedBy { it.name }
    private val byName: Map<String, ConsoleCommand> = all.associateBy { it.name.lowercase() }

    init {
        require(byName.size == all.size) {
            "duplicate console command names: " +
                all.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys.joinToString()
        }
    }

    fun find(name: String): ConsoleCommand? = byName[name.lowercase()]
}
