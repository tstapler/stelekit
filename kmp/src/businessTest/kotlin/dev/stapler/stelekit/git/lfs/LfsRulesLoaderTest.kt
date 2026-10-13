// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LfsRulesLoaderTest {
    private val root = "/repo"

    private fun fs(vararg files: Pair<String, String>) =
        MemoryFileSystem(files.associate { (path, text) -> "$root/$path" to text.encodeToByteArray() }.toMutableMap())

    private fun policy(fs: MemoryFileSystem, vararg changed: String, ignoreCase: Boolean = false) =
        LfsPathPolicy(LfsRulesLoader(fs, root, ignoreCase).load(changed.toList()))

    @Test
    fun load_should_ReadTheRootAndEveryAncestorDirectoryFile() {
        val fs = fs(".gitattributes" to "*.png filter=lfs\n", "a/.gitattributes" to "*.bin filter=lfs\n", "a/b/.gitattributes" to "*.pdf filter=lfs\n")

        val p = policy(fs, "a/b/x.png")

        assertTrue(p.isTracked("a/b/x.png"))
        assertTrue(p.isTracked("a/y.bin"))
        assertTrue(p.isTracked("a/b/z.pdf"))
        assertFalse(p.isTracked("other/z.pdf"), "a/b's file does not apply outside a/b")
    }

    @Test
    fun load_should_NotReadDirectoriesUnrelatedToTheChangedPaths() {
        val fs = fs("elsewhere/.gitattributes" to "* filter=lfs\n")

        assertFalse(policy(fs, "a/x.png").isTracked("elsewhere/x.png"))
    }

    @Test
    fun load_should_LetInfoAttributesOverrideTheTree() {
        val fs = fs(".gitattributes" to "*.png filter=lfs\n", ".git/info/attributes" to "*.png -filter\n")

        assertFalse(policy(fs, "x.png").isTracked("x.png"))
    }

    @Test
    fun load_should_ReturnNoRules_When_NoAttributeFileExists() {
        assertFalse(policy(fs(), "x.png").hasAnyRules)
    }

    @Test
    fun load_should_PassIgnoreCaseThrough() {
        val fs = fs(".gitattributes" to "*.PNG filter=lfs\n")

        assertTrue(policy(fs, "x.png", ignoreCase = true).isTracked("x.png"))
        assertFalse(policy(fs, "x.png", ignoreCase = false).isTracked("x.png"))
    }

    @Test
    fun load_should_SeeAWorkingTreeEditOnTheNextCall_When_NothingIsCached() {
        val fs = fs(".gitattributes" to "# nothing\n")
        val loader = LfsRulesLoader(fs, root)
        assertFalse(LfsPathPolicy(loader.load(listOf("x.png"))).isTracked("x.png"))

        fs.files["$root/.gitattributes"] = "*.png filter=lfs\n".encodeToByteArray()

        assertTrue(LfsPathPolicy(loader.load(listOf("x.png"))).isTracked("x.png"), "a pull or edit must take effect immediately")
    }
}
