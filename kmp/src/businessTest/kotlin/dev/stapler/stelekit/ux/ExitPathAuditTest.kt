// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ux

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.LockVerdict
import dev.stapler.stelekit.git.PausedRetryInfo
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.SyncRunInfo
import dev.stapler.stelekit.git.SyncSheetState
import dev.stapler.stelekit.git.SyncStaleness
import dev.stapler.stelekit.git.SyncStatusSheetModel
import dev.stapler.stelekit.git.UnknownReason
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.syncStatusSheetModel
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * X-47 / UX-47: every error or waiting state offers a way out or recovers by itself. Each presentation model's state
 * values are enumerated from source, so a new state with no entry in its exits table fails here. Only the sync sheet
 * exists today; the camera, value-editor, known-distances and tool-strip models add their table when they land.
 */
class ExitPathAuditTest {
    private val now = 1_000_000L
    private val lockError = DomainError.GitError.StaleLockFile("/r/.git/index.lock", LockVerdict.OwnerUnknown(UnknownReason.TornRecord))

    private fun model(syncState: SyncState = SyncState.Idle, inFlight: SyncRunInfo? = null, paused: PausedRetryInfo? = null, noRecord: Boolean = false) =
        syncStatusSheetModel(
            SyncStaleness(behindCount = 0), syncState, now, 15, firstSyncReviewPending = false,
            inFlight = inFlight, lastVerdict = if (noRecord) LockVerdict.NoRecord else null, pausedRetry = paused, retryNow = { },
        )

    /** The ways out a sheet state offers, read off the real model; empty means a dead end. */
    private fun exits(m: SyncStatusSheetModel): List<String> = buildList {
        when (m.state) {
            is SyncSheetState.Idle -> if (m.canSyncNow) add("Sync now")
            is SyncSheetState.Running -> add("auto: the run finishes and releases the gate")
            is SyncSheetState.Paused -> {
                if (m.retryNow != null) add("Retry now")
                add("auto: PausedRetryPolicy retries")
            }
            is SyncSheetState.Failed -> if (m.canSyncNow) add("Sync now")
            is SyncSheetState.Attention -> m.problem?.let { add("Details: ${it.target}") }
        }
    }

    private val samples: Map<String, SyncStatusSheetModel> by lazy {
        mapOf(
            "Idle" to model(),
            "Running" to model(inFlight = SyncRunInfo(SyncOrigin.UiPress, now, "g", false)),
            "Paused" to model(SyncState.Error(lockError), paused = PausedRetryInfo(now + 30_000, 1)),
            "Failed" to model(SyncState.Error(lockError), paused = null),
            "Attention" to model(SyncState.Error(DomainError.GitError.FetchFailed("offline"))),
        )
    }

    @Test
    fun everyStateInUxTables_should_HaveExitOrAutoRecovery() {
        val declared = sealedStateNames(WiringInputs.productionSources.getValue(SHEET_MODEL_SOURCE), "SyncSheetState")
        assertEquals(declared.toSet(), samples.keys, "a SyncSheetState has no sample here (or a sample is stale): add its exits row")
        samples.forEach { (name, m) ->
            assertEquals(name, m.state::class.simpleName, "sample for $name built the wrong state")
            assertTrue(exits(m).isNotEmpty(), "state $name is a dead end: no exit and no automatic recovery")
        }
    }

    @Test
    fun sealedStateNames_should_ListNestedStatesOfASealedInterface() {
        val src = "sealed interface S {\n    data object A : S\n    data class B(val x: Int) : S\n    /** doc */\n    object C : S\n}\nclass Other\n"
        assertEquals(listOf("A", "B", "C"), sealedStateNames(src, "S"))
    }

    companion object {
        private const val SHEET_MODEL_SOURCE = "commonMain/dev/stapler/stelekit/git/SyncStatusSheetModel.kt"

        /** Names of the `data object` / `data class` / `object` members declared in `sealed interface [sealedName] { ... }`. */
        fun sealedStateNames(source: String, sealedName: String): List<String> {
            val open = source.indexOf("sealed interface $sealedName")
            check(open >= 0) { "sealed interface $sealedName not found" }
            var depth = 0
            var end = source.length
            for (i in source.indexOf('{', open) until source.length) {
                if (source[i] == '{') depth++
                if (source[i] == '}' && --depth == 0) { end = i; break }
            }
            val member = Regex("""^\s{4}(?:data\s+)?(?:object|class)\s+(\w+)""", RegexOption.MULTILINE)
            return member.findAll(source.substring(open, end)).map { it.groupValues[1] }.toList()
        }
    }
}
