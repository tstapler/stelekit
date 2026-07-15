// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DirtySetMarkerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TC-2_2_1-A DirtySetMarker with pendingCommit Staged round-trips through kotlinx serialization's sealed-interface polymorphism`() {
        val marker = DirtySetMarker(
            graphId = "default",
            baseSha = "8f3c1a9d2e7b4c1a0f9e8d7c6b5a4938271605f4",
            pendingCommit = PendingCommit.Staged(commitSha = "c0ffee1", treeSha = "7ea5e11"),
            checkpointedAtMillis = 1752500000000,
            dirtyFiles = mapOf(
                "pages/Foo.md" to DirtyEntry(DirtyOp.WRITE, 1752499990000),
                "pages/Bar.md" to DirtyEntry(DirtyOp.DELETE, 1752499995000),
            ),
        )

        val encoded = json.encodeToString(marker)
        assertEquals(true, encoded.contains(""""type":"staged""""), "encoded=$encoded")
        assertEquals(true, encoded.contains(""""commitSha":"c0ffee1""""), "encoded=$encoded")
        assertEquals(true, encoded.contains(""""treeSha":"7ea5e11""""), "encoded=$encoded")
        assertEquals(true, encoded.contains(""""op":"write""""), "encoded=$encoded")
        assertEquals(true, encoded.contains(""""op":"delete""""), "encoded=$encoded")

        val decoded = json.decodeFromString<DirtySetMarker>(encoded)
        assertEquals(marker, decoded)
        assertIs<PendingCommit.Staged>(decoded.pendingCommit)
    }

    @Test
    fun `TC-2_2_1-A PendingCommit None round-trips to type none`() {
        val marker = DirtySetMarker(
            graphId = "default",
            baseSha = "8f3c1a9",
            checkpointedAtMillis = 1752500000000,
        )

        val encoded = json.encodeToString(marker)
        assertEquals(true, encoded.contains(""""pendingCommit":{"type":"none"}"""), "encoded=$encoded")

        val decoded = json.decodeFromString<DirtySetMarker>(encoded)
        assertEquals(PendingCommit.None, decoded.pendingCommit)
        assertEquals(emptyMap(), decoded.dirtyFiles)
    }

    @Test
    fun `TC-2_2_1-B PendingCommit has no constructor path producing commitSha-set-treeSha-null`() {
        // Type-level guarantee, not a runtime guard: PendingCommit has exactly two constructors —
        // None (zero-arg data object) and Staged(commitSha: String, treeSha: String), where both
        // fields are non-nullable. There is no third shape and no way to construct a Staged value
        // with only one SHA present — the illegal "one set, one missing" state is unrepresentable
        // by the type itself. If a future change introduced nullable fields on Staged, or a third
        // subtype, this file would need new code to exercise it — the exhaustive `when` below
        // fails to compile if a new subtype is added without being handled here.
        val none: PendingCommit = PendingCommit.None
        val staged: PendingCommit = PendingCommit.Staged(commitSha = "c1", treeSha = "t1")

        fun describe(pc: PendingCommit): String = when (pc) {
            is PendingCommit.None -> "none"
            is PendingCommit.Staged -> "staged(${pc.commitSha},${pc.treeSha})"
        }

        assertEquals("none", describe(none))
        assertEquals("staged(c1,t1)", describe(staged))
    }
}
