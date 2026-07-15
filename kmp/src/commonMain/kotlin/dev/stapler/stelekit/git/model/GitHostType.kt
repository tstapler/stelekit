// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

/**
 * The remote git hosting provider a [dev.stapler.stelekit.git.model.GitConfig]'s remote URL
 * resolves to, as detected by `GitHostAdapter.detect`.
 */
enum class GitHostType { GITHUB, GITLAB, UNSUPPORTED }
