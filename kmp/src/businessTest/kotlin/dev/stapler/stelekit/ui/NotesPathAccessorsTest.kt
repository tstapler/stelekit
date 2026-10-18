// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.mutableStateOf
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The typed notes-folder accessors callers use instead of reaching into the view model's state or the graph info. */
class NotesPathAccessorsTest {
    private fun vaultSetup(info: GraphInfo?) = GraphContentVaultSetup(
        activeGraphInfo = info,
        activeGraphPath = "",
        effectiveFileSystem = FakeRelocationFileSystem(),
        isParanoidModeState = mutableStateOf(false),
        vaultStateState = mutableStateOf<VaultState>(VaultState.Locked),
        vaultManagerState = mutableStateOf(null),
        vaultCredentialStore = null,
    )

    @Test
    fun activeNotesPath_should_BeTheNotesFolder_NotTheRepoRoot() {
        val info = GraphInfo(
            id = GraphId("g"), path = "/graphs/g", displayName = "g", addedAt = 0L,
            detectedRepoRoot = "/graphs/g", detectedWikiSubdir = "logseq", effectivePath = "/graphs/g/logseq",
        )

        assertEquals(EffectiveNotesPath("/graphs/g/logseq"), vaultSetup(info).activeNotesPath)
    }

    @Test
    fun activeNotesPath_should_BeNull_When_NoGraphIsActive() {
        assertNull(vaultSetup(null).activeNotesPath)
    }

    @Test
    fun currentNotesPath_should_BeNull_BeforeAGraphIsLoaded() {
        val harness = StelekitViewModelTestHarness()

        assertNull(harness.viewModel.currentNotesPath)
        harness.scope.cancel()
    }

    @Test
    fun child_should_JoinWithExactlyOneSeparator_And_directory_should_DropTheTrailingOne() {
        assertEquals("/graphs/g/logseq/journals", EffectiveNotesPath("/graphs/g/logseq").child("journals"))
        assertEquals("/graphs/g/logseq/journals", EffectiveNotesPath("/graphs/g/logseq/").child("journals"))
        assertEquals("/graphs/g/logseq", EffectiveNotesPath("/graphs/g/logseq/").directory())
    }
}
