// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

/**
 * Text that passed [OutboundLlmGate]. A plain class, not a data class: a generated `copy()` would
 * sidestep the [GateIssued] opt-in on the constructor and let a caller forge one.
 */
class GatedPrompt @GateIssued internal constructor(
    val systemPrompt: String,
    val payload: String,
    val question: String,
)
