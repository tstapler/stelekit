// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

data class TopicSuggestion(
    val term: String,
    val confidence: Float,        // 0.0–1.0
    val source: Source,           // LOCAL | AI_ENHANCED
    val accepted: Boolean = false,
    val dismissed: Boolean = false,
) {
    enum class Source { LOCAL, AI_ENHANCED }
}
