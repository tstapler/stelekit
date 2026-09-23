// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DagValidatorTest {

    @Test
    fun linear_chain_passes_validation() {
        val v001 = migration("V001") {}
        val v002 = migration("V002") { requires("V001") }
        val v003 = migration("V003") { requires("V002") }

        val errors = DagValidator.validate(listOf(v001, v002, v003))

        assertEquals(emptyList(), errors)
    }

    @Test
    fun out_of_order_produces_error() {
        // V002 (which requires V001) is declared BEFORE V001
        val v002 = migration("V002") { requires("V001") }
        val v001 = migration("V001") {}

        val errors = DagValidator.validate(listOf(v002, v001))

        assertTrue(errors.any { it is DagValidator.DagError.OutOfOrder && (it as DagValidator.DagError.OutOfOrder).migrationId == "V002" && it.requiredId == "V001" },
            "Expected OutOfOrder error for V002 requiring V001, got: $errors")
    }

    @Test
    fun direct_cycle_produces_cycle_detected() {
        val v001 = migration("V001") { requires("V002") }
        val v002 = migration("V002") { requires("V001") }

        val errors = DagValidator.validate(listOf(v001, v002))

        val cycleErrors = errors.filterIsInstance<DagValidator.DagError.CycleDetected>()
        assertTrue(cycleErrors.isNotEmpty(), "Expected CycleDetected error, got: $errors")
        val cyclePath = cycleErrors.first().cyclePath
        assertTrue("V001" in cyclePath, "Expected V001 in cyclePath, got: $cyclePath")
        assertTrue("V002" in cyclePath, "Expected V002 in cyclePath, got: $cyclePath")
    }

    @Test
    fun missing_dependency_produces_unresolved_error() {
        val v001 = migration("V001") { requires("ghost-migration") }

        val errors = DagValidator.validate(listOf(v001))

        val unresolvedErrors = errors.filterIsInstance<DagValidator.DagError.UnresolvedDependency>()
        assertTrue(unresolvedErrors.isNotEmpty(), "Expected UnresolvedDependency error, got: $errors")
        assertEquals("V001", unresolvedErrors.first().migrationId)
        assertEquals("ghost-migration", unresolvedErrors.first().missingDep)
    }

    @Test
    fun conflict_violation_when_applied() {
        val v002 = migration("V002") { conflicts("V001") }

        val errors = DagValidator.validate(listOf(v002), appliedIds = setOf("V001"))

        val conflictErrors = errors.filterIsInstance<DagValidator.DagError.ConflictViolation>()
        assertTrue(conflictErrors.isNotEmpty(), "Expected ConflictViolation error, got: $errors")
        assertEquals("V002", conflictErrors.first().migrationId)
        assertEquals("V001", conflictErrors.first().conflictId)
    }

    @Test
    fun empty_list_passes_validation() {
        val errors = DagValidator.validate(emptyList())

        assertEquals(emptyList(), errors)
    }
}
