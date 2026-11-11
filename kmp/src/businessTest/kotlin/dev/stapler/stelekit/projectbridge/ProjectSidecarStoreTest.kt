// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.platform.AtomicFileWriter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 2.5 store: ignore rule, ordering, stale-sheet cleanup, no churn, snapshots, stale temp sweep. */
class ProjectSidecarStoreTest {
    private val slug = "proj-0a1b2c"
    private val files = mapOf(
        "project-model.json" to "{\"schema\":1}".encodeToByteArray(),
        "sheets/s1.json" to "{\"schema\":1}".encodeToByteArray(),
    )

    @Test
    fun write_should_AddTheTmpIgnoreRuleOnce() = runBlocking<Unit> {
        val fs = SidecarFs()
        fs.seed("$GRAPH/.gitignore", "*.db".encodeToByteArray())
        val store = newStore(fs)
        store.write(slug, files)
        store.write(slug, files + ("sheets/s2.json" to "{\"schema\":1}".encodeToByteArray()))
        val ignore = fs.readFile("$GRAPH/.gitignore")!!
        assertEquals("*.db\nassets/stelekit-sketch/**/*.tmp\n", ignore)
    }

    @Test
    fun write_should_DeleteSheetsThatLeftTheBundle_AndSkipIdenticalBytes() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.write(slug, files + ("sheets/s2.json" to "{\"schema\":1}".encodeToByteArray()))
        assertEquals(SidecarWriteOutcome.Written, store.write(slug, files))
        assertFalse(fs.fileExists(sidecarPath(slug, "sheets/s2.json")))
        assertTrue(fs.fileExists(sidecarPath(slug, "sheets/s1.json")))
        assertEquals(emptyList(), fs.tmps())
    }

    @Test
    fun read_should_ReportAbsent_ThenPresent_AndIgnoreConflictCopies() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        assertEquals(SidecarRead.Absent, store.read(slug))
        store.write(slug, files)
        store.writeConflict(slug, files)
        assertEquals(files.keys, assertIs<SidecarRead.Present>(store.read(slug)).files.keys)
    }

    @Test
    fun writeSourceSnapshot_should_StoreContentUnderAHashName_AndDeferWhileBusy() = runBlocking<Unit> {
        val fs = SidecarFs()
        var busy = true
        val store = newStore(fs) { busy }
        assertEquals(SidecarWriteOutcome.Deferred, store.writeSourceSnapshot(slug, "abcd1234", "a: 1\n".encodeToByteArray()))
        busy = false
        assertEquals(SidecarWriteOutcome.Written, store.writeSourceSnapshot(slug, "abcd1234", "a: 1\n".encodeToByteArray()))
        assertContentEquals("a: 1\n".encodeToByteArray(), fs.readFileBytes(sidecarPath(slug, "sources/yaml-abcd1234.yaml")))
    }

    @Test
    fun mode_should_BeUnsupported_WhereTempRenameIsNot() {
        val fs = SidecarFs().also { it.tempRenameSupported = false }
        assertEquals(dev.stapler.stelekit.platform.AtomicWriteMode.UNSUPPORTED, AtomicFileWriter.mode(fs, GRAPH))
    }
}
