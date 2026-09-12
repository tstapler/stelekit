// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

/**
 * Opens the OS settings screen where the user can grant "All files access"
 * (MANAGE_EXTERNAL_STORAGE) to SteleKit — the permission that lets git sync open an
 * existing SAF-picked repo's real .git via java.io.File instead of the shadow-worktree
 * mirror (see AndroidGitRepository.resolveForJGit).
 * Platform actuals:
 * - androidMain: Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION (API 30+), falling
 *   back to the general ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION list, no-op below API 30
 *   or if neither resolves.
 * - jvmMain / iosMain / wasmJsMain: no-op — the permission only exists on Android, and the
 *   caller (GitSetupScreen's existingRepoNeedsAllFilesAccess check) never surfaces the
 *   button that calls this on those platforms.
 */
expect fun openAllFilesAccessSettings()
