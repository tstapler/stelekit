package dev.stapler.stelekit.git.lfs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LfsPathPolicyTest {
    private fun policy(root: String, nested: Map<String, String> = emptyMap()) = LfsPathPolicy(
        AttributeRuleSet(
            listOf(GitAttributesParser.parse(root)) + nested.map { (d, t) -> GitAttributesParser.parse(t, dirPrefix = d) },
        ),
    )

    @Test
    fun gitattributesFileNeverTracked() {
        val p = policy("* filter=lfs")

        assertFalse(p.isTracked(".gitattributes"))
        assertFalse(p.isTracked("sub/dir/.gitattributes"))
        assertTrue(p.isTracked("sub/dir/a.png"))
    }

    @Test
    fun nestedOverrideUnsets() {
        val p = policy("*.png filter=lfs", nested = mapOf("assets/raw" to "*.png -filter"))

        assertFalse(p.isTracked("assets/raw/a.png"))
        assertTrue(p.isTracked("assets/b.png"))
    }

    @Test
    fun hasAnyRules_should_BeFalse_When_NoLayerHasRules() {
        assertFalse(policy("# only a comment\n").hasAnyRules)
        assertTrue(policy("*.png filter=lfs").hasAnyRules)
    }
}
