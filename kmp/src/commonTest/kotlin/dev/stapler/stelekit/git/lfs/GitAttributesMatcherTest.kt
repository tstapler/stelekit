package dev.stapler.stelekit.git.lfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitAttributesMatcherTest {
    private fun policy(
        rootRules: String,
        nested: Map<String, String> = emptyMap(),
        info: String? = null,
        ignoreCase: Boolean = false,
    ): LfsPathPolicy {
        val files = buildList {
            add(GitAttributesParser.parse(rootRules))
            nested.forEach { (dir, text) -> add(GitAttributesParser.parse(text, dirPrefix = dir)) }
        }
        val infoFile = info?.let { GitAttributesParser.parse(it, allowMacros = true, sourceName = ".git/info/attributes") }
        return LfsPathPolicy(AttributeRuleSet(files, infoFile, ignoreCase))
    }

    private fun assertTracked(rules: String, path: String, expected: Boolean) =
        assertEquals(expected, policy(rules).isTracked(path), "rules=[$rules] path=$path")

    @Test
    fun isTracked_should_MatchBasenameAtAnyDepth_When_PatternHasNoSlash() {
        assertTracked("*.png filter=lfs", "a.png", true)
        assertTracked("*.png filter=lfs", "dir/sub/a.png", true)
        assertTracked("*.png filter=lfs", "a.PNG", false)
        assertTracked("*.png filter=lfs", "a.png.txt", false)
    }

    @Test
    fun isTracked_should_AnchorToTheFilesDirectory_When_PatternContainsASlash() {
        val rules = "*.pdf filter=lfs\n/docs/*.pdf filter=lfs"
        assertTracked(rules, "a/b/c.pdf", true)
        assertTracked(rules, "docs/x.pdf", true)
        // only the first rule can match here, and it does
        assertTracked(rules, "a/docs/x.pdf", true)
        assertTracked("/docs/*.pdf filter=lfs", "a/docs/x.pdf", false)
        assertTracked("/docs/*.pdf filter=lfs", "docs/sub/x.pdf", false)
    }

    @Test
    fun isTracked_should_HandleDoubleStarForms() {
        assertTracked("assets/** filter=lfs", "assets/a.png", true)
        assertTracked("assets/** filter=lfs", "assets/x/y.png", true)
        assertTracked("assets/** filter=lfs", "assets", false)
        assertTracked("assets/** filter=lfs", "other/assets/a.png", false)
        assertTracked("**/assets/** filter=lfs", "x/y/assets/a.png", true)
        assertTracked("**/assets/** filter=lfs", "assets/a.png", true)
        assertTracked("docs/**/*.pdf filter=lfs", "docs/a.pdf", true)
        assertTracked("docs/**/*.pdf filter=lfs", "docs/x/y/a.pdf", true)
        assertTracked("docs/**/*.pdf filter=lfs", "docs/x/y/a.txt", false)
    }

    @Test
    fun isTracked_should_HandleQuestionMarkAndBracketClasses() {
        assertTracked("a?.png filter=lfs", "ab.png", true)
        assertTracked("a?.png filter=lfs", "abc.png", false)
        assertTracked("[ab].png filter=lfs", "b.png", true)
        assertTracked("[ab].png filter=lfs", "c.png", false)
        assertTracked("[!a].png filter=lfs", "c.png", true)
        assertTracked("[!a].png filter=lfs", "a.png", false)
        assertTracked("[a-c].png filter=lfs", "b.png", true)
        assertTracked("[[:digit:]].png filter=lfs", "7.png", true)
        assertTracked("[[:digit:]].png filter=lfs", "x.png", false)
    }

    @Test
    fun isTracked_should_LetTheLastMatchingLineWin() {
        assertTracked("*.png filter=lfs\n*.png -filter", "a.png", false)
        assertTracked("*.png -filter\n*.png filter=lfs", "a.png", true)
        assertTracked("*.png filter=lfs\n*.png !filter", "a.png", false)
        assertTracked("*.png filter=lfs\na.png filter=other", "a.png", false)
        assertTracked("*.png filter=lfs\na.png filter=other", "b.png", true)
    }

    @Test
    fun isTracked_should_RejectOtherFilterValues() {
        assertTracked("*.png filter=lfs-extra", "a.png", false)
        assertTracked("*.png filter", "a.png", false)
        assertTracked("*.png diff=lfs", "a.png", false)
    }

    @Test
    fun isTracked_should_LetADeeperFileOverrideAShallowerOne() {
        val p = policy("*.png filter=lfs", nested = mapOf("assets/raw" to "*.png -filter"))
        assertFalse(p.isTracked("assets/raw/a.png"))
        assertTrue(p.isTracked("assets/b.png"))
        assertFalse(p.isTracked("assets/raw/deep/c.png"), "a directory's file applies to everything below it")
    }

    @Test
    fun isTracked_should_NotApplyANestedFileToSiblingDirectories() {
        val p = policy("", nested = mapOf("assets" to "*.png filter=lfs"))
        assertTrue(p.isTracked("assets/a.png"))
        assertFalse(p.isTracked("other/a.png"))
        assertFalse(p.isTracked("a.png"))
    }

    @Test
    fun isTracked_should_LetInfoAttributesOverrideEveryFile() {
        val p = policy("*.png filter=lfs", nested = mapOf("a" to "*.png filter=lfs"), info = "*.png -filter")
        assertFalse(p.isTracked("a/x.png"))
        assertFalse(p.isTracked("x.png"))
    }

    @Test
    fun isTracked_should_ExpandMacros_When_RootFileDefinesThem() {
        val rules = "[attr]lfs filter=lfs diff=lfs merge=lfs -text\n*.bin lfs"
        assertTracked(rules, "x.bin", true)
        assertTracked(rules, "x.txt", false)
    }

    @Test
    fun isTracked_should_IgnoreMacroDefinitions_When_InASubdirectoryFile() {
        val p = policy("*.bin lfs", nested = mapOf("sub" to "[attr]lfs filter=lfs"))
        assertFalse(p.isTracked("x.bin"))
        assertFalse(p.isTracked("sub/x.bin"))
    }

    @Test
    fun isTracked_should_IgnoreComments_BlankLinesAndNegativePatterns() {
        assertTracked("# *.png filter=lfs\n\n   \n!*.png filter=lfs", "a.png", false)
        assertTracked("  *.png   filter=lfs   # trailing words are just attributes", "a.png", true)
    }

    @Test
    fun isTracked_should_SupportQuotedPatterns() {
        assertTracked("\"my file.png\" filter=lfs", "my file.png", true)
        assertTracked("\"my file.png\" filter=lfs", "my.png", false)
    }

    @Test
    fun isTracked_should_NeverMatchADirectoryOnlyPattern() {
        assertTracked("assets/ filter=lfs", "assets/a.png", false)
    }

    @Test
    fun isTracked_should_FoldCase_When_IgnoreCaseIsSet() {
        assertTrue(policy("*.PNG filter=lfs", ignoreCase = true).isTracked("a.png"))
        assertFalse(policy("*.PNG filter=lfs", ignoreCase = false).isTracked("a.png"))
    }

    @Test
    fun ruleSource_should_NameTheFileAndLine_When_PathIsTracked() {
        val p = policy("# header\n*.png filter=lfs", nested = mapOf("a" to "*.bin filter=lfs"))

        assertEquals(".gitattributes:2", p.ruleSource("x.png"))
        assertEquals("a/.gitattributes:1", p.ruleSource("a/x.bin"))
        assertEquals(null, p.ruleSource("x.txt"))
    }
}
