// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

/**
 * Wraps the OPFS folder-name path segment a graph is stored under —
 * `graphPath.removePrefix("$homeDir/").substringBefore("/")` (see [PlatformFileSystem.graphRootPath]/
 * `switchActiveGraph`'s identical derivation). Cross-tab-stable, so two independently-constructed
 * [HostDirectorySync] instances (one per browser tab) for the same graph always derive the same
 * [FolderSyncLockNaming] `WebLock` name from this value.
 *
 * Deliberately **not** the canonical, sha256-hash-based `GraphId`
 * (`dev.stapler.stelekit.model.GraphInfo.id`) — `HostDirectorySync`/`PlatformFileSystem` only ever
 * have the OPFS folder-name segment in scope, never the canonical (un-hashed) path needed to
 * re-derive `GraphId`'s hash. The two types are not mutually derivable and must never be
 * conflated — see ADR-019's Alternatives Considered section and the implementation plan's Domain
 * Glossary (`OpfsGraphSlug` row) for the full rationale.
 */
value class OpfsGraphSlug(val value: String)

/**
 * Story 1.1.3: `OpfsGraphSlug`-accepting overloads of [FolderSyncLockNaming]'s `String`-keyed
 * functions. `FolderSyncLockNaming` itself stays in `commonMain` (pure string logic, unit-tested
 * from `commonTest` — see its own class doc comment) and cannot reference this wasmJs-only type
 * directly; these thin wasmJs-side extensions delegate to the existing `String` overloads so every
 * call site in this module can pass an [OpfsGraphSlug] directly while the underlying lock-name
 * string derivation (and its existing `commonTest` coverage) stays completely unchanged. Two
 * independently-constructed [HostDirectorySync] instances (e.g. in different browser tabs) for the
 * same graph therefore still produce the identical lock-name string, since both ultimately resolve
 * to the same `String`-keyed function for the same underlying value.
 */
internal fun FolderSyncLockNaming.pollLockNameFor(graphId: OpfsGraphSlug): String = pollLockNameFor(graphId.value)

internal fun FolderSyncLockNaming.writeLockNameFor(graphId: OpfsGraphSlug, repoRelativePath: String): String =
    writeLockNameFor(graphId.value, repoRelativePath)
