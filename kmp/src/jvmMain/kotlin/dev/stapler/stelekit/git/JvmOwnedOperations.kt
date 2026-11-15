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

    fun identity(): ProcessIdentity = ProcessIdentity(
        instanceId = processInstance,
        pid = ProcessHandle.current().pid(),
        startTicks = JvmProcessLiveness.selfStartStamp(),
        pidNs = JvmProcessLiveness.selfPidNamespace(),
        host = host,
        bootId = JvmProcessLiveness.bootId(),
    )

    fun support(): OwnedOperationSupport = OwnedOperationSupport(JvmProcessLiveness(), identity())
}
