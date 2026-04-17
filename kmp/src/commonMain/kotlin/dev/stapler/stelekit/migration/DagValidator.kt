// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

object DagValidator {

    sealed class DagError {
        data class CycleDetected(val cyclePath: List<String>) : DagError()
        data class UnresolvedDependency(val migrationId: String, val missingDep: String) : DagError()
        data class OutOfOrder(val migrationId: String, val requiredId: String) : DagError()
        data class ConflictViolation(val migrationId: String, val conflictId: String) : DagError()
    }

    /**
     * Validates the migration list for DAG correctness.
     *
     * @param migrations All registered migrations in declared order.
     * @param appliedIds IDs of migrations already applied to this graph (for conflict checks).
     * @return All errors found (collect-all, not fail-fast).
     */
    fun validate(migrations: List<Migration>, appliedIds: Set<String> = emptySet()): List<DagError> {
        val errors = mutableListOf<DagError>()

        val knownIds: Set<String> = migrations.map { it.id }.toSet()
        val indexById: Map<String, Int> = migrations.mapIndexed { index, m -> m.id to index }.toMap()

        // 1. Unresolved dependency check + Out-of-order check
        migrations.forEachIndexed { i, migration ->
            for (dep in migration.requires) {
                if (dep !in knownIds) {
                    errors += DagError.UnresolvedDependency(migration.id, dep)
                } else {
                    val depIndex = indexById[dep]!!
                    if (depIndex >= i) {
                        errors += DagError.OutOfOrder(migration.id, dep)
                    }
                }
            }
        }

        // 2. Cycle detection via Kahn's BFS
        // Build adjacency: edge from dep -> migration (dep must come before migration)
        val adjacency = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()

        for (m in migrations) {
            if (m.id !in adjacency) adjacency[m.id] = mutableListOf()
            if (m.id !in inDegree) inDegree[m.id] = 0
        }

        for (m in migrations) {
            for (dep in m.requires) {
                if (dep in knownIds) {
                    adjacency.getOrPut(dep) { mutableListOf() }.add(m.id)
                    inDegree[m.id] = (inDegree[m.id] ?: 0) + 1
                }
            }
        }

        val queue = ArrayDeque<String>()
        for ((id, degree) in inDegree) {
            if (degree == 0) queue.add(id)
        }

        val processed = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            processed.add(current)
            for (neighbor in adjacency[current] ?: emptyList()) {
                val newDegree = (inDegree[neighbor] ?: 0) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) queue.add(neighbor)
            }
        }

        val cyclingIds = migrations.map { it.id }.filter { it !in processed }
        if (cyclingIds.isNotEmpty()) {
            errors += DagError.CycleDetected(cyclingIds)
        }

        // 3. Conflict violation check
        for (migration in migrations) {
            for (conflictId in migration.conflicts) {
                if (conflictId in appliedIds) {
                    errors += DagError.ConflictViolation(migration.id, conflictId)
                }
            }
        }

        return errors
    }
}
