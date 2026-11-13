// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.registerAndroidTasks
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `git-sync` is registered once by the Application root; a second process start re-registers the identical spec as a no-op. */
class GitSyncTaskRegistrationTest {
    @Test
    fun register_should_exposeGitSyncSpecOnceAndBeIdempotent_When_calledTwice() {
        val registry = TaskRegistry()
        registerAndroidTasks(registry)
        registerGitSyncTask(registry)
        registerGitSyncTask(registry) // new GitSyncTask instance, equal spec

        assertEquals(setOf(GitSyncTask.KIND), registry.kinds())
        assertEquals(GitSyncTask.spec, registry.specFor(GitSyncTask.KIND))
        assertNotNull(registry.taskFor(GitSyncTask.KIND))
        assertEquals(6, registry.specFor(GitSyncTask.KIND)!!.defaultConfig.schedule.jitterMinutes)
        assertTrue(!registry.specFor(GitSyncTask.KIND)!!.defaultConfig.enabled)
    }
}
