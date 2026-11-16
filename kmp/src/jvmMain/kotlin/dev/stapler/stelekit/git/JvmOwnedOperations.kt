// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.net.InetAddress
import java.util.UUID

/** The desktop/CLI process's claim identity and support; one instance id per JVM, shared by every repository. */
object JvmOwnedOperations {
    private val processInstance = InstanceId(UUID.randomUUID().toString())

    private val host: String by lazy {
        try {
            InetAddress.getLocalHost().hostName
        } catch (_: java.io.IOException) {
            "unknown-host"
        }
    }

    /** Random id persisted under the user's home: unlike a hostname it is not shared by cloned images or default names. */
    private val installId: String? by lazy {
        try {
            val file = java.io.File(System.getProperty("user.home"), ".stelekit/install-id")
            file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().also { file.parentFile.mkdirs(); file.writeText(it) }
        } catch (_: java.io.IOException) {
            null
        }
    }

    fun identity(): ProcessIdentity = ProcessIdentity(
        instanceId = processInstance,
        pid = ProcessHandle.current().pid(),
        startTicks = JvmProcessLiveness.selfStartStamp(),
        pidNs = JvmProcessLiveness.selfPidNamespace(),
        host = host,
        bootId = JvmProcessLiveness.bootId(),
        machineId = installId,
    )

    fun support(): OwnedOperationSupport = OwnedOperationSupport(JvmProcessLiveness(), identity())
}
