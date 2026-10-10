// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultBranchDetectionTest {

    @Test
    fun `symref wins`() {
        val heads = mapOf("main" to "a", "master" to "a")
        assertEquals(
            DefaultBranchDetection.Detected("master"),
            classifyDefaultBranch("refs/heads/master", "a", heads),
        )
    }

    @Test
    fun `same tip on two branches and no symref is ambiguous`() {
        val heads = mapOf("main" to "a", "master" to "a")
        assertEquals(
            DefaultBranchDetection.Ambiguous(listOf("main", "master")),
            classifyDefaultBranch(null, "a", heads),
        )
    }

    @Test
    fun `head tip matching exactly one branch picks it`() {
        val heads = mapOf("main" to "a", "dev" to "b")
        assertEquals(DefaultBranchDetection.Detected("dev"), classifyDefaultBranch(null, "b", heads))
    }

    @Test
    fun `single branch is detected and no branches is empty`() {
        assertEquals(DefaultBranchDetection.Detected("master"), classifyDefaultBranch(null, null, mapOf("master" to "a")))
        assertEquals(DefaultBranchDetection.EmptyRemote, classifyDefaultBranch("refs/heads/main", "a", emptyMap()))
    }

    @Test
    fun `result never names a branch that is not on the remote`() = runTest {
        val names = listOf("main", "master", "dev", "trunk", "release/1")
        val oids = listOf("a", "b", "c")
        checkAll(
            Arb.list(Arb.int(0..names.lastIndex), 0..5),
            Arb.element(names + "ghost").orNull(),
            Arb.element(oids).orNull(),
        ) { picked, symref, headOid ->
            val heads = picked.distinct().associate { names[it] to oids[(it + picked.size) % oids.size] }
            val result = classifyDefaultBranch(symref?.let { "refs/heads/$it" }, headOid, heads)
            when (result) {
                is DefaultBranchDetection.Detected -> assertTrue(result.name in heads, "$result not in $heads")
                is DefaultBranchDetection.Ambiguous -> assertTrue(heads.keys.containsAll(result.candidates))
                DefaultBranchDetection.EmptyRemote -> assertTrue(heads.isEmpty())
                is DefaultBranchDetection.Unreachable -> error("classifier never returns Unreachable")
            }
        }
    }
}
