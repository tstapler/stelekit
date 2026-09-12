// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

/**
 * Whether this platform supports the "open temporarily" flow — checking out files, working with
 * them, and syncing via git for the rest of this session without anything ever touching local
 * disk. Currently web-only: JVM/Android/iOS always persist to a real filesystem/SQLite file by
 * design (that's the "traditional" method those platforms already use), so there is no equivalent
 * non-persistent mode to offer there.
 */
expect fun isEphemeralWebModeAvailable(): Boolean

/**
 * Starts a fresh, non-persistent session: a full page reload into `browser/Main.kt`'s ephemeral
 * boot branch (an in-memory-only SQLite driver plus a `PlatformFileSystem` that never writes to
 * OPFS — see [dev.stapler.stelekit.platform.PlatformFileSystem.markEphemeral]). A reload is
 * required rather than an in-place switch because the wasmJs `DriverFactory` caches exactly one
 * driver for the lifetime of the page — see its `createDriverAsync`/`createEphemeralDriverAsync`
 * KDoc. No-op where [isEphemeralWebModeAvailable] is false.
 */
expect fun startEphemeralSession()

/**
 * Whether the currently-running session is itself an ephemeral one (i.e. `browser/Main.kt` booted
 * via [startEphemeralSession]'s reload) — distinct from [isEphemeralWebModeAvailable], which asks
 * whether the *platform* supports switching into one, not whether this session already has. Used
 * to hide graph-management affordances that make no sense inside an already-ephemeral session
 * (e.g. "Open local folder...", which would introduce the exact persistent OPFS side channel the
 * mode exists to avoid).
 */
expect fun isCurrentSessionEphemeral(): Boolean
