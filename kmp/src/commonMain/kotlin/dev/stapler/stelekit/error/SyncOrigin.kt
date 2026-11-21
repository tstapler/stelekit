// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.error

/**
 * Who started a repository operation. Used for logging, the run-in-flight info and rerun bookkeeping only;
 * consent and guard semantics stay on [SyncTrigger] (ADR-001 decision 1).
 */
enum class SyncOrigin {
    UiPress,
    FollowUp,
    Scheduled,
    Worker,
    Cold,
    Resume,
    RateLimitRetry,
    Resolve,
    Repair,
    Deepen,
    Setup,
    Clone,
    Cli,
    Quiesce,
}
