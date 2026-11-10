// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

/** Opt-in marker for minting a [GatedPrompt]; only [OutboundLlmGate] may opt in (GateIssuedAuditTest). */
@RequiresOptIn(
    message = "A GatedPrompt is minted only by OutboundLlmGate.check, never constructed directly.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.CLASS)
annotation class GateIssued
