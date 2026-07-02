// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class LlmProviderAvailabilityTest {

    @Test
    fun `unavailable_and_preparing_dataclass_equality_and_copy_preserve_retryable_default`() {
        // retryable defaults to false — this default is load-bearing for downstream
        // fallback-selection logic (a caller that doesn't specify retryable must get the
        // conservative "permanent" interpretation).
        val unavailable = LlmProviderAvailability.Unavailable(reason = "no key")
        assertFalse(unavailable.retryable)

        val copy = unavailable.copy(reason = "still initializing")
        assertFalse(copy.retryable, "copy() must preserve the retryable default unless explicitly overridden")
        assertEquals("still initializing", copy.reason)

        // Equality
        assertEquals(
            LlmProviderAvailability.Unavailable("no key", retryable = false),
            LlmProviderAvailability.Unavailable("no key"),
        )
        assertNotEquals(
            LlmProviderAvailability.Unavailable("no key", retryable = true),
            LlmProviderAvailability.Unavailable("no key", retryable = false),
        )

        // Preparing equality/copy, including the nullable detail default.
        val preparing = LlmProviderAvailability.Preparing()
        assertEquals(LlmProviderAvailability.Preparing(detail = null), preparing)
        val preparingWithDetail = preparing.copy(detail = "downloading model")
        assertEquals("downloading model", preparingWithDetail.detail)
        assertNotEquals(preparing, preparingWithDetail)

        // Available is a data object — always equal to itself.
        assertEquals(LlmProviderAvailability.Available, LlmProviderAvailability.Available)
    }
}
