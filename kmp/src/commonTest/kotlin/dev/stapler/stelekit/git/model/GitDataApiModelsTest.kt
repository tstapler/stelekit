// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GitDataApiModelsTest {

    @Test
    fun `TC-1_2_1-A GitBlobRequest encodes content and encoding fields exactly`() {
        val request = GitBlobRequest(content = "IyBGb28=", encoding = "base64")

        val encoded = Json.encodeToString(request)

        assertEquals("""{"content":"IyBGb28=","encoding":"base64"}""", encoded)
    }

    @Test
    fun `TC-1_2_1-B GitCommitResponse and GitCompareResponse decode with ignoreUnknownKeys when GitHub adds an unmodeled field`() {
        val commitJson = """
            {"sha":"a1b2c3","url":"https://api.github.com/repos/tstapler/steno-wiki/git/commits/a1b2c3","extra_field":"unmodeled"}
        """.trimIndent()
        val commitResult = gitApiJson.decodeFromString<GitCommitResponse>(commitJson)
        assertEquals("a1b2c3", commitResult.sha)

        val compareJson = """
            {"ahead_by":2,"files":[{"filename":"pages/Foo.md"},{"filename":"journals/2026_07_14.md"}],"behind_by":0}
        """.trimIndent()
        val compareResult = gitApiJson.decodeFromString<GitCompareResponse>(compareJson)
        assertEquals(2, compareResult.aheadBy)
        assertEquals(listOf("pages/Foo.md", "journals/2026_07_14.md"), compareResult.files.map { it.filename })
    }
}
