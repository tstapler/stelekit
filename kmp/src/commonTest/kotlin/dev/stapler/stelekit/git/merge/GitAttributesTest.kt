package dev.stapler.stelekit.git.merge

import dev.stapler.stelekit.model.ParsedBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitAttributesTest {
    private val wiki = GitAttributes.parse(
        """
        *.md merge=logseq-union
        *.png filter=lfs diff=lfs merge=lfs -text
        # comment
        journals/*.txt -merge
        """.trimIndent()
    )

    @Test
    fun `logseq-union driver maps to the ported driver policy at any depth`() {
        assertEquals("logseq-union", wiki.mergeAttribute("journals/2026_10_10.md"))
        assertEquals(MergePolicy.LogseqUnion, wiki.policyFor("pages/a/b.md", MergePolicy.Strict))
    }

    @Test
    fun `unknown custom driver is Strict because real git falls back to a text merge`() {
        val attrs = GitAttributes.parse("*.md merge=some-other-driver")
        assertEquals(MergePolicy.Strict, attrs.policyFor("a.md", MergePolicy.Union))
    }

    @Test
    fun `bang merge means unspecified and resets earlier rules to the default`() {
        val attrs = GitAttributes.parse("*.md merge=union\n*.md !merge")
        assertNull(attrs.mergeAttribute("a.md"))
        assertEquals(MergePolicy.Insertions, attrs.policyFor("a.md", MergePolicy.Insertions))
    }

    @Test
    fun `pathological double-star pattern against a long path finishes quickly`() {
        val attrs = GitAttributes.parse("${"**a".repeat(12)} merge=union")
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        assertEquals(MergePolicy.Strict, attrs.policyFor("a".repeat(11) + "b".repeat(2000), MergePolicy.Strict))
        assertTrue(start.elapsedNow().inWholeMilliseconds < 1000)
    }

    @Test
    fun `over-long patterns are ignored`() {
        val attrs = GitAttributes.parse("${"a".repeat(GitAttributes.MAX_PATTERN_LENGTH + 1)} merge=union")
        assertEquals(MergePolicy.Strict, attrs.policyFor("a".repeat(GitAttributes.MAX_PATTERN_LENGTH + 1), MergePolicy.Strict))
    }

    @Test
    fun `lfs and -merge opt out to Strict`() {
        assertEquals(MergePolicy.Strict, wiki.policyFor("assets/x.png", MergePolicy.Insertions))
        assertEquals(MergePolicy.Strict, wiki.policyFor("journals/n.txt", MergePolicy.Insertions))
    }

    @Test
    fun `unmatched path uses default`() {
        assertNull(wiki.mergeAttribute("README"))
        assertEquals(MergePolicy.Insertions, wiki.policyFor("README", MergePolicy.Insertions))
    }

    @Test
    fun `nested attributes override and are directory-scoped`() {
        val combined = GitAttributes.combine(wiki, GitAttributes.parse("*.md merge=text", "pages"))
        assertEquals(MergePolicy.Strict, combined.policyFor("pages/a.md", MergePolicy.Union))
        assertEquals(MergePolicy.LogseqUnion, combined.policyFor("journals/a.md", MergePolicy.Strict))
    }

    private fun blk(c: String) = ParsedBlock(content = c, properties = emptyMap(), level = 0)

    @Test
    fun `Insertions keeps both sides when both insert at the same spot`() {
        val base = listOf(blk("A"))
        val local = listOf(blk("A"), blk("mine"))
        val remote = listOf(blk("A"), blk("theirs"))
        assertTrue(BlockDiff3.merge(base, local, remote).hasConflicts())
        val merged = BlockDiff3.merge(base, local, remote, MergePolicy.Insertions)
        assertFalse(merged.hasConflicts())
        assertEquals(listOf("A", "mine", "theirs"), merged.filterIsInstance<BlockDiff3Chunk.Stable>().flatMap { it.blocks }.map { it.content })
    }

    @Test
    fun `Insertions still conflicts on edit of the same block but Union keeps both`() {
        val base = listOf(blk("A"), blk("B"))
        val local = listOf(blk("A"), blk("B-mine"))
        val remote = listOf(blk("A"), blk("B-theirs"))
        assertTrue(BlockDiff3.merge(base, local, remote, MergePolicy.Insertions).hasConflicts())
        val union = BlockDiff3.merge(base, local, remote, MergePolicy.Union)
        assertFalse(union.hasConflicts())
        assertEquals(listOf("A", "B-mine", "B-theirs"), union.filterIsInstance<BlockDiff3Chunk.Stable>().flatMap { it.blocks }.map { it.content })
    }

    @Test
    fun `Union does not duplicate identical inserted blocks`() {
        val base = listOf(blk("A"))
        val both = listOf(blk("A"), blk("same"))
        val merged = BlockDiff3.merge(base, both, both, MergePolicy.Union)
        assertEquals(listOf("A", "same"), merged.filterIsInstance<BlockDiff3Chunk.Stable>().flatMap { it.blocks }.map { it.content })
    }

    @Test
    fun `slash patterns are anchored to their directory while bare patterns match any depth`() {
        val attrs = GitAttributes.parse("journals/*.md merge=union\n*.org merge=union\n/top.md merge=union")
        assertEquals(MergePolicy.Union, attrs.policyFor("journals/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("x/journals/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("journals/sub/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Union, attrs.policyFor("deep/er/n.org", MergePolicy.Strict))
        assertEquals(MergePolicy.Union, attrs.policyFor("top.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("sub/top.md", MergePolicy.Strict))
    }

    @Test
    fun `double star spans directories and question mark matches one character`() {
        val attrs = GitAttributes.parse("docs/**/*.md merge=union\na?.txt merge=union\nlogs/** merge=union")
        assertEquals(MergePolicy.Union, attrs.policyFor("docs/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Union, attrs.policyFor("docs/x/y/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("other/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Union, attrs.policyFor("ab.txt", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("abc.txt", MergePolicy.Strict))
        assertEquals(MergePolicy.Union, attrs.policyFor("logs/2026/10.log", MergePolicy.Strict))
    }

    @Test
    fun `regex metacharacters in a pattern are literal`() {
        val attrs = GitAttributes.parse("a+b.md merge=union")
        assertEquals(MergePolicy.Union, attrs.policyFor("a+b.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("aab.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("a+bxmd", MergePolicy.Strict))
    }

    @Test
    fun `every built-in opt-out value yields Strict and bare merge means text`() {
        listOf("merge=binary", "merge=text", "merge=lfs", "-merge", "merge").forEach { attr ->
            val policy = GitAttributes.parse("*.md $attr").policyFor("a.md", MergePolicy.Union)
            assertEquals(MergePolicy.Strict, policy, "attr `$attr`")
        }
    }

    @Test
    fun `last matching rule wins and unrelated attributes are ignored`() {
        val attrs = GitAttributes.parse("*.md merge=union\n*.md -merge\n*.md diff=markdown")
        assertEquals(MergePolicy.Strict, attrs.policyFor("a.md", MergePolicy.Union))
        assertNull(GitAttributes.parse("*.md diff=markdown").mergeAttribute("a.md"))
        assertNull(GitAttributes.parse("*.md").mergeAttribute("a.md"))
        assertEquals(MergePolicy.Insertions, GitAttributes.EMPTY.policyFor("a.md", MergePolicy.Insertions))
    }

    @Test
    fun `subdirectory attributes only apply beneath their directory`() {
        val attrs = GitAttributes.parse("*.md merge=union", "wiki/pages")
        assertEquals(MergePolicy.Union, attrs.policyFor("wiki/pages/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Union, attrs.policyFor("wiki/pages/deep/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("wiki/other/a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("a.md", MergePolicy.Strict))
    }

    @Test
    fun `probe dirs are root then wiki subdir only when the path is beneath it`() {
        assertEquals(listOf("", "wiki"), gitAttributeProbeDirs("wiki/pages/a.md", "wiki"))
        assertEquals(listOf("", "wiki"), gitAttributeProbeDirs("wiki/a.md", "/wiki/"))
        assertEquals(listOf(""), gitAttributeProbeDirs("other/a.md", "wiki"))
        assertEquals(listOf(""), gitAttributeProbeDirs("a.md", null))
        assertEquals(listOf(""), gitAttributeProbeDirs("a.md", ""))
    }

    @Test
    fun `combine order lets the subdir file override the root file`() {
        val dirs = gitAttributeProbeDirs("wiki/a.md", "wiki")
        val files = mapOf("" to "*.md merge=union", "wiki" to "*.md merge=text")
        val combined = GitAttributes.combine(*dirs.map { GitAttributes.parse(files.getValue(it), it) }.toTypedArray())
        assertEquals(MergePolicy.Strict, combined.policyFor("wiki/a.md", MergePolicy.Union))
    }
}
