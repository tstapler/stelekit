// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.SketchProject

/**
 * Whether the sidecar on disk moved past this device's last reconcile (a pull brought changes nobody reconciled yet).
 * A write that builds files from the local database would silently revert them, so every writer except the reconciler
 * checks this under the slug lock before its first write and reports Deferred.
 */
object SidecarBehindGuard {
    fun isBehind(project: SketchProject, files: Map<String, ByteArray>): Boolean {
        val base = project.lastSyncedSidecarHash ?: return false
        return files.isNotEmpty() && SidecarCodec.bundleHash(files) != base
    }
}
