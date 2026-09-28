// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

// ponytail: no-op — MANAGE_EXTERNAL_STORAGE is an Android-only permission; Desktop already has
// direct java.io.File access, so GitSetupScreen's existingRepoNeedsAllFilesAccess never fires here.
actual fun openAllFilesAccessSettings() {}
