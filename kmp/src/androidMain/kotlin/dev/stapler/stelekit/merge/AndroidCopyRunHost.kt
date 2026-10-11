// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Copy host owned by `SteleKitApplication`: runs outlive any Activity. The scope's handler catches
 * every Throwable (including OutOfMemoryError), which would otherwise kill the Android process.
 */
class AndroidCopyRunHost : ScopedCopyRunHost(applicationScope()) {
    private companion object {
        private val logger = Logger("AndroidCopyRunHost")

        fun applicationScope() = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e ->
                    if (e !is CancellationException) logger.error("copy scope: ${e::class.simpleName}: ${e.message}", e)
                },
        )
    }
}
