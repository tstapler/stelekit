// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

/**
 * Derived per statement, never a user setting: plain `sql` is always [ReadOnly]; `sql!` is
 * [GuardedWrite] from its dry run until the write ends (success, cancel or failure), then back.
 */
enum class ConsoleMode { ReadOnly, GuardedWrite }
