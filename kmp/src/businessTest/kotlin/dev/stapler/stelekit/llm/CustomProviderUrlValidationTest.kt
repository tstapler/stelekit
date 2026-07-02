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
        Case("http 127.x.x.x is valid", "http://127.5.5.5:11434/v1", true),
        Case("http IPv6 loopback is valid", "http://[::1]:11434/v1", true),
        Case("http non-loopback host is invalid", "http://example.com/v1", false),
        Case("http non-loopback IP is invalid", "http://192.168.1.5:11434/v1", false),
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
