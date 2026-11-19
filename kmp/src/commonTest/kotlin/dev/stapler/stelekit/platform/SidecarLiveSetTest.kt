// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidecarLiveSetTest {
    private val graph = "/stelekit/g1"
    private val root = "$graph/assets/stelekit-sketch"

    @Test
    fun rootDir_should_EqualTheStoreRoot() {
        assertEquals(FileProjectSidecarStore.ROOT_DIR, SidecarLiveSet.ROOT_DIR)
    }

    @Test
    fun modelAndSheets_should_BeLive() {
        assertTrue(SidecarLiveSet.isLive(graph, "$root/kitchen/project-model.json"))
        assertTrue(SidecarLiveSet.isLive(graph, "$root/kitchen/sheets/abc.v2.json"))
        assertTrue(SidecarLiveSet.isLive(graph, "$root/kitchen/sheets/abc.json"))
    }

    @Test
    fun everythingElseUnderTheRoot_should_NotBeLive() {
        listOf(
            "$root/kitchen/backup/abc.v1.json",
            "$root/kitchen/conflicts/abc.json",
            "$root/kitchen/sources/yaml-1.yaml",
            "$root/kitchen/exports/x.svg",
            "$root/kitchen/preview.svg",
            "$root/kitchen/sheets/abc.json.tmp",
            "$root/kitchen/sheets/nested/abc.json",
            "$root/project-model.json",
            "$graph/pages/project-model.json",
        ).forEach { assertFalse(SidecarLiveSet.isLive(graph, it), it) }
    }

    @Test
    fun isUnderRoot_should_MatchTheRootAndItsDescendantsOnly() {
        assertTrue(SidecarLiveSet.isUnderRoot(graph, root))
        assertTrue(SidecarLiveSet.isUnderRoot(graph, "$root/kitchen/preview.svg"))
        assertFalse(SidecarLiveSet.isUnderRoot(graph, "$root-other/x"))
        assertFalse(SidecarLiveSet.isUnderRoot(graph, "$graph/pages/a.md"))
    }
}
