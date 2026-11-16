// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import android.os.Process
import android.provider.Settings
import java.util.UUID

/** The Android process's claim identity and support; one instance id per process, shared by every repository. */
object AndroidOwnedOperations {
    private val processInstance = InstanceId(UUID.randomUUID().toString())

    fun identity(context: Context): ProcessIdentity = ProcessIdentity(
        instanceId = processInstance,
        pid = Process.myPid().toLong(),
        startTicks = AndroidProcessLiveness.selfStartTicks(),
        pidNs = AndroidProcessLiveness.selfPidNamespace(),
        host = AndroidProcessLiveness.HOST,
        bootId = AndroidProcessLiveness.bootId { bootCount(context) },
        machineId = InstallId.loadOrCreate(java.io.File(context.noBackupFilesDir, "stelekit-install-id")),
    )

    fun support(context: Context): OwnedOperationSupport = OwnedOperationSupport(
        AndroidProcessLiveness(),
        identity(context),
        // Only a repository under filesDir (the SAF shadow) is out of reach of Termux/MGit; real paths keep the long rule.
        lockFilesFor = { JavaIoLockFiles.forPrivateRoot(context.filesDir, it) },
    )

    private fun bootCount(context: Context): String? = try {
        Settings.Global.getInt(context.contentResolver, "boot_count").toString()
    } catch (_: Settings.SettingNotFoundException) {
        null
    }
}
