// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/** Whether an [LlmProvider] runs remotely (network round-trip) or on-device. */
enum class LlmProviderKind { REMOTE, ON_DEVICE }
