// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class CustomProviderUrlValidationTest {

    private data class Case(val name: String, val url: String, val expectValid: Boolean)

    private val cases = listOf(
        Case("blank url is invalid", "", false),
        Case("missing scheme is invalid", "localhost:11434/v1", false),
        Case("unsupported scheme is invalid", "ftp://localhost:11434/v1", false),
        Case("https anywhere is valid", "https://api.openai.com/v1", true),
        Case("http localhost is valid (Ollama default)", "http://localhost:11434/v1", true),
        Case("http 127.0.0.1 is valid (LM Studio default)", "http://127.0.0.1:1234/v1", true),
        // isLoopbackHost recognizes the full 127.0.0.0/8 range via real dotted-quad IP
        // parsing (not a "127." string-prefix match) — any genuine 127.x.x.x address is a
        // valid loopback address per RFC 5735.
        Case("http 127.x.x.x other than 127.0.0.1 is valid (real loopback IP)", "http://127.5.5.5:11434/v1", true),
        Case("http IPv6 loopback is valid", "http://[::1]:11434/v1", true),
        Case("http non-loopback host is invalid", "http://example.com/v1", false),
        Case("http non-loopback IP is invalid", "http://192.168.1.5:11434/v1", false),
        // MA6 regression: a crafted hostname that merely starts with "127." must not be
        // treated as loopback — DNS would resolve it to an attacker-controlled server. This is
        // rejected by real IP parsing (5 dot-separated segments, not 4), not a string-prefix
        // match, so it stays rejected even though 127.5.5.5-style genuine IPs are now accepted.
        Case(
            "http hostname crafted to look like a loopback IP is invalid (MA6 bypass)",
            "http://127.0.0.1.attacker.example/v1",
            false,
        ),
        Case("http non-numeric octet is invalid", "http://127.0.0.abc/v1", false),
        Case("http out-of-range octet is invalid", "http://127.0.0.999/v1", false),
    )

    @Test
    fun `validate should MatchExpected ForEachTableDrivenCase`() {
        for (case in cases) {
            val result = CustomProviderUrlValidation.validate(case.url)
            if (case.expectValid) {
                assertNull(result, "case '${case.name}' expected valid but got error: $result")
            } else {
                assertNotNull(result, "case '${case.name}' expected an error message")
            }
            assertEquals(case.expectValid, CustomProviderUrlValidation.isValid(case.url), "case: ${case.name}")
        }
    }

    @Test
    fun `validate should TrimWhitespace before checking`() {
        assertNull(CustomProviderUrlValidation.validate("  https://api.openai.com/v1  "))
    }
}
