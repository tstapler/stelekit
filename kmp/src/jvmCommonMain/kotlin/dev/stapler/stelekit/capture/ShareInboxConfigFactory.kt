// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.platform.AppPrivateFileSystem
import java.io.File

/**
 * Share-inbox config for JVM and Android: the inbox lives in `<appDataDir>/share-inbox` (Desktop:
 * the database directory; Android: `filesDir`). SAF graphs stay refused until a verified grant check
 * is wired into [TargetWriterCapabilities], so they queue.
 */
fun shareInboxConfigFor(appDataDir: String): ShareInboxConfig = ShareInboxConfig(
    inboxFileSystem = AppPrivateFileSystem(),
    inboxRoot = "${appDataDir.trimEnd('/')}/share-inbox",
    capabilities = TargetWriterCapabilities(platformSupportsOffGraphWrite = true),
    canonicalize = { File(it).canonicalPath },
)
