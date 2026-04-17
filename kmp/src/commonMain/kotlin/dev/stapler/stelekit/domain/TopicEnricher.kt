// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

fun interface TopicEnricher {
    suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>): List<TopicSuggestion>
}

class NoOpTopicEnricher : TopicEnricher {
    override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>) = localSuggestions
}
