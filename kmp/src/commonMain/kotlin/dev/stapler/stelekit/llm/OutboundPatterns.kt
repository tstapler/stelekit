// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

enum class LeakCategory { PATH, URI, EMAIL, PHONE, STREET_ADDRESS, FILENAME, WIKI_LINK }

/**
 * Structural leak patterns for outbound LLM text. The address, home-path and email regexes are the
 * same strings as `private-string-patterns.txt` (a test asserts the street-address ones are a
 * subset). Portable across JVM and JS: no inline flags and no lookbehind; case-insensitivity is a
 * [RegexOption].
 */
object OutboundPatterns {
    class Pattern(val name: String, val category: LeakCategory, val source: String, ignoreCase: Boolean = false) {
        val regex: Regex = if (ignoreCase) Regex(source, RegexOption.IGNORE_CASE) else Regex(source)
    }

    val all: List<Pattern> = listOf(
        Pattern("street-address-numbered", LeakCategory.STREET_ADDRESS, """\b\d{2,5}\s+(?:N|S|E|W|North|South|East|West)\.?\s+\d{1,3}(?:st|nd|rd|th)\b"""),
        Pattern("street-address-named", LeakCategory.STREET_ADDRESS, """\b\d{2,5}\s+(?:[A-Z][a-z]+\s+){1,2}(?:St|Ave|Rd|Blvd|Dr|Ln|Ct)\b"""),
        Pattern(
            "street-address-numbered-any-case", LeakCategory.STREET_ADDRESS,
            """\b\d{1,5}\s+(?:n|s|e|w|north|south|east|west)\.?\s+\d{1,3}(?:st|nd|rd|th)\b""",
            ignoreCase = true,
        ),
        Pattern(
            "street-address-any-case", LeakCategory.STREET_ADDRESS,
            """\b\d{1,5}\s+(?:[a-z]+\s+){1,3}(?:street|st|avenue|ave|road|rd|boulevard|blvd|drive|dr|lane|ln|court|ct)\b""",
            ignoreCase = true,
        ),
        Pattern("home-path", LeakCategory.PATH, """/(?:home|Users)/[A-Za-z0-9._-]+"""),
        Pattern("unix-path", LeakCategory.PATH, """(?:^|[\s"'(=])(?:~|\.{1,2})?/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)+"""),
        Pattern("windows-path", LeakCategory.PATH, """\b[A-Za-z]:\\[^\s"']+"""),
        Pattern("uri", LeakCategory.URI, """\b[A-Za-z][A-Za-z0-9+.-]*://\S+"""),
        Pattern("www-host", LeakCategory.URI, """\bwww\.[A-Za-z0-9.-]+""", ignoreCase = true),
        Pattern("email", LeakCategory.EMAIL, """\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""),
        Pattern("phone", LeakCategory.PHONE, """(?:\+\d{1,2}[ .-])?\(?\b\d{3}\)?[ .-]\d{3}[ .-]\d{4}\b"""),
        Pattern(
            "filename", LeakCategory.FILENAME,
            """\b[A-Za-z0-9][A-Za-z0-9 _-]*\.(?:md|markdown|svg|png|jpe?g|gif|webp|pdf|ya?ml|json|txt|csv|docx?|xlsx?)\b""",
            ignoreCase = true,
        ),
        Pattern("wiki-link", LeakCategory.WIKI_LINK, """\[\[[^\]]+\]\]"""),
    )

    /** `<kind>:<id>` source refs; a token that starts like one but is not in this shape is a violation. */
    val ref = Regex("^(table|svg|yaml|prose|user|derived):[a-z0-9]{6,16}$")
    val refCandidate = Regex("""\b(?:table|svg|yaml|prose|user|derived):[^\s",}\]]+""")

    /** Generated `proj-<6 hex>` and the fixture form `proj-closet-1`. */
    val slug = Regex("^proj-(?:[0-9a-f]{6}|closet-1)$")
    val slugCandidate = Regex("""\bproj-[A-Za-z0-9-]+""")
}
