// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitLabCommitModelsTest {

    @Test
    fun `TC-1_2_2-A GitLabCommitAction serializes an update action with last_commit_id under snake_case keys`() {
        val action = GitLabCommitAction(
            action = "update",
            filePath = "pages/Foo.md",
            content = "IyBGb28=",
            encoding = "base64",
            lastCommitId = "9f8e7d6c",
        )

        val encoded = Json.encodeToString(action)

        assertTrue(encoded.contains(""""action":"update""""))
        assertTrue(encoded.contains(""""file_path":"pages/Foo.md""""))
        assertTrue(encoded.contains(""""last_commit_id":"9f8e7d6c""""))
    }

    @Test
    fun `TC-1_2_2-B GitLabCommitRequest omits start_sha when null without breaking round-trip`() {
        val request = GitLabCommitRequest(
            branch = "main",
            commitMessage = "SteleKit: 2026-07-14",
            startSha = null,
            actions = listOf(
                GitLabCommitAction(action = "update", filePath = "pages/Foo.md", content = "IyBGb28="),
            ),
        )

        val encoded = Json.encodeToString(request)
        assertFalse(encoded.contains("start_sha"))

        val decodedBack = Json.decodeFromString<GitLabCommitRequest>(encoded)
        assertEquals(request, decodedBack)

        val responseJson = """{"id":"a1b2c3d4","title":"SteleKit: 2026-07-14"}"""
        val response = gitApiJson.decodeFromString<GitLabCommitResponse>(responseJson)
        assertEquals("a1b2c3d4", response.id)
        assertEquals(null, response.shortId)
    }
}
