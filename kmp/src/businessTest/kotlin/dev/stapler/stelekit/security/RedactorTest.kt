// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.security

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactorTest {
    @Test
    fun redact_should_MaskUserinfoButKeepHost_When_UrlCarriesAToken() {
        val out = Redactor.redact("origin https://u:ghp_x1y2z3@github.com/o/r.git")
        assertEquals("origin https://[redacted]@github.com/o/r.git", out)
    }

    @Test
    fun redact_should_LeaveOrdinaryTextAlone() {
        val text = "page 'Notes' has 12 blocks; see https://example.com/a?b=c and user@example.com"
        assertEquals(text, Redactor.redact(text))
    }

    @Test
    fun redact_should_MaskExactSecrets_And_IgnoreTooShortOnes() {
        assertEquals("key=[redacted] ok", Redactor.redact("key=hunter2-long ok", listOf("hunter2-long")))
        assertEquals("a b", Redactor.redact("a b", listOf("a")))
    }

    @Test
    fun redactGitMessage_should_DropUserinfoAndMaskQueryValues_AsBefore() {
        assertEquals(
            "cannot open https://host/x?token=<redacted>&a=<redacted> timed out",
            Redactor.redactGitMessage("cannot open https://user:tok@host/x?token=abc&a=b timed out"),
        )
    }

    @Test
    fun redact_should_RemoveEveryGeneratedSecretShapedToken_When_EmbeddedInArbitraryText() = runTest {
        val prefixes = Arb.element("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_", "glpat-")
        val body = Arb.stringPattern("[A-Za-z0-9_]{8,40}")
        val awsKey = Arb.stringPattern("AKIA[0-9A-Z]{16}")
        val noise = Arb.stringPattern("[ a-z.,:/=-]{0,20}")
        checkAll(prefixes, body, awsKey, noise, noise) { prefix, tail, aws, before, after ->
            val token = prefix + tail
            val out = Redactor.redact("$before $token $aws $after")
            assertFalse(token in out, "token survived: $out")
            assertFalse(aws in out, "aws key survived: $out")
            assertEquals(out, Redactor.redact(out), "redaction must be idempotent")
        }
    }
}
