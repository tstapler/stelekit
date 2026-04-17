// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

/**
 * Thrown by [DslEvaluator] when a migration that has [Migration.allowDestructive] = false
 * accumulates a destructive [BlockChange] (i.e. [BlockChange.DeleteBlock] or [BlockChange.DeletePage]).
 */
class DestructiveOperationException(message: String) : Exception(message)
