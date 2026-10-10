// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import dev.stapler.stelekit.merge.CopyRunHost
import dev.stapler.stelekit.merge.SourcePlatform
import java.io.File

/** Copy-feature host config for JVM and Android: real disk for staging/manifests under [appDataDir], symlinks resolved. */
fun copyHostConfigFor(appDataDir: String, runHost: CopyRunHost, sourcePlatform: SourcePlatform): CopyHostConfig =
    CopyHostConfig(
        appDataDir = appDataDir,
        fileSystem = okio.FileSystem.SYSTEM,
        runHost = runHost,
        sourcePlatform = sourcePlatform,
        canonicalize = { File(it).canonicalPath },
    )
