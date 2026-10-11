package dev.stapler.stelekit.merge

/**
 * Markdown fixtures embedded as Kotlin strings rather than `.md` files under commonTest/resources/merge-fixtures:
 * commonTest has no resource loader that works on wasmJs (same reason as the bcur vectors), and
 * `\r`/`\t`/trailing spaces are invisible and editor-fragile in a `.md` file. Content is synthetic.
 */
object MergeFixtures {
    /** The string `GraphLoader` would pass; any fixed value works as long as before/after use the same one. */
    const val PATH = "/graphs/work/pages/Notes.md"

    /** CRLF, tabs, fenced code with `- ` lines, collapsed, #+BEGIN_QUOTE, trailing whitespace, no final newline. */
    val REAL_CRLF_TAB: String = listOf(
        "alias:: notes, jottings",
        "- Project notes   ",
        "\t- Snippet",
        "\t  ```kotlin",
        "\t  - not a bullet",
        "\t  val x = 1",
        "\t  ```",
        "\t- Folded section",
        "\t  collapsed:: true",
        "\t\t- Hidden grandchild",
        "- Quote follows",
        "  #+BEGIN_QUOTE",
        "  - dash line inside the quote",
        "  quoted text",
        "  #+END_QUOTE",
        "- Last block, trailing spaces   ",
    ).joinToString("\r\n")

    /**
     * REAL_CRLF_TAB without dash lines inside the fence/quote (the parser mis-nests them) and with a final
     * CRLF: the parser keeps `\r` in block content, so appending an eol after an unterminated last line changes it.
     */
    val REAL_CRLF_CLEAN: String = REAL_CRLF_TAB.replace("\t  - not a bullet\r\n", "").replace("  - dash line inside the quote\r\n", "")
        .replace("trailing spaces   ", "no trailing spaces") + "\r\n"

    val UNLABELED_FLAT: String = "- alpha\n- beta\n- gamma\n"

    val UNLABELED_NESTED: String = listOf(
        "- alpha",
        "\t- alpha one",
        "\t- alpha two",
        "\t\t- deep",
        "- beta",
        "\t- beta one",
        "",
    ).joinToString("\n")

    val MIXED_LABELED: String = listOf(
        "- alpha",
        "  id:: 11111111-1111-1111-1111-111111111111",
        "- unlabeled middle",
        "\t- unlabeled child",
        "- omega",
        "  id:: 22222222-2222-2222-2222-222222222222",
        "\t- omega child",
        "",
    ).joinToString("\n")

    /** Two-space indented outline: the splicer must not introduce tabs. */
    val SPACE_INDENTED: String = "- one\n  - one-a\n  - one-b\n- two\n"

    /**
     * Stand-in for the Spike 0.1.4 failure class (all 49 production-guard failures on the author's
     * graph end in a fence whose body has `- ` lines): the parser splits the fence on those lines
     * and the last "block" it leaves open swallows the appended bullet.
     */
    val FENCE_WITH_DASH_LINES: String = "- Example\n  ```\n  - item inside the fence\n  - another\n  ```\n"
}
