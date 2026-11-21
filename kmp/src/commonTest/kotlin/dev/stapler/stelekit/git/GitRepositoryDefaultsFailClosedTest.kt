// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitRepositoryDefaultsFailClosedTest {
    // StubGitRepository answers NoLock for beginOwnedOperation (service fixtures need it); this implementer routes
    // that one method back to the interface default so the fail-closed default is still what is asserted.
    private val bare = object : StubGitRepository(), GitRepository {
        override suspend fun beginOwnedOperation(config: dev.stapler.stelekit.git.model.GitConfig, info: OwnedOperationInfo) =
            super<GitRepository>.beginOwnedOperation(config, info)
    }

    @Test
    fun defaultBeginOwnedOperation_should_ReturnOwnerUnknownNeverNoLock_AndForceRemoveLockShould_Refuse_ForAnImplementerWithoutOverrides() = runTest {
        val begin = bare.beginOwnedOperation(sampleConfig, OwnedOperationInfo("sync", SyncOrigin.UiPress))
        assertEquals(Either.Right(LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)), begin)

        assertTrue(bare.forceRemoveLock(sampleConfig).isLeft(), "default forceRemoveLock must refuse")

        // They hold no state, so these must simply not throw.
        bare.touchOwnedOperation(sampleConfig)
        bare.endOwnedOperation(sampleConfig)
    }
}
