// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitRefDiagnosticsRedactionTest {

    @Test
    fun `remote url and ls-remote failure never print userinfo or query tokens`() {
        val dir: File = createTempDirectory("stelekit_refs_").toFile()
        try {
            Git.init().setDirectory(dir).call().use { git ->
                val url = "http://user:tok@127.0.0.1:1/x?token=abc"
                git.repository.config.setString("remote", "origin", "url", url)
                git.repository.config.save()
                val config = GitConfig(
                    graphId = "g", repoRoot = dir.absolutePath, wikiSubdir = null,
                    remoteBranch = "main", authType = GitAuthType.NONE,
                )

                val out = describeGitRefs(git, config) { }

                assertTrue("origin.url=" in out, out)
                assertFalse("tok@" in out || "user:" in out, out)
                assertFalse("abc" in out, out)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
