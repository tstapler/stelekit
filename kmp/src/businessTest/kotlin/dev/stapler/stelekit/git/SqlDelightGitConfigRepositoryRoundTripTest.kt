// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Field-level persistence of [SqlDelightGitConfigRepository] (clone-depth mapping is in `GitConfigCloneDepthMappingTest`). */
class SqlDelightGitConfigRepositoryRoundTripTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    private val repository = SqlDelightGitConfigRepository(
        SteleDatabase(driver),
        DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository(), scope = scope),
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        driver.close()
    }

    private fun full(graphId: String = "g1") = GitConfig(
        graphId = graphId,
        repoRoot = RepoRoot("/repo"),
        wikiSubdir = "wiki",
        remoteName = "upstream",
        remoteBranch = "trunk",
        authType = GitAuthType.SSH_KEY,
        sshKeyPath = "/home/u/.ssh/id",
        sshKeyPassphraseKey = "pp-key",
        httpsTokenKey = "https-key",
        oauthTokenKey = "oauth-key",
        pollIntervalMinutes = 17,
        autoCommit = false,
        commitMessageTemplate = "sync {date}",
    )

    @Test
    fun saveThenGet_returnsEveryField() = runBlocking {
        val config = full()

        repository.saveConfig(config)

        assertEquals(config, repository.getConfig("g1").getOrNull())
    }

    @Test
    fun nullWikiSubdirRoundTripsAsNull() = runBlocking {
        val config = full().copy(wikiSubdir = null)

        repository.saveConfig(config)

        assertNull(repository.getConfig("g1").getOrNull()?.wikiSubdir)
    }

    @Test
    fun getConfig_forUnknownGraphIsNullNotAnError() = runBlocking {
        val result = repository.getConfig("nope")

        assertEquals(true, result.isRight())
        assertNull(result.getOrNull())
    }

    @Test
    fun saveIsAnUpsertKeyedByGraphId() = runBlocking {
        repository.saveConfig(full("g1"))
        repository.saveConfig(full("g2").copy(remoteBranch = "other"))

        repository.saveConfig(full("g1").copy(remoteBranch = "changed", autoCommit = true))

        assertEquals("changed", repository.getConfig("g1").getOrNull()?.remoteBranch)
        assertEquals(true, repository.getConfig("g1").getOrNull()?.autoCommit)
        assertEquals("other", repository.getConfig("g2").getOrNull()?.remoteBranch)
    }

    @Test
    fun deleteConfig_removesOnlyThatGraph() = runBlocking {
        repository.saveConfig(full("g1"))
        repository.saveConfig(full("g2"))

        val deleted = repository.deleteConfig("g1")

        assertEquals(true, deleted.isRight())
        assertNull(repository.getConfig("g1").getOrNull())
        assertEquals("g2", repository.getConfig("g2").getOrNull()?.graphId)
    }

    @Test
    fun observeConfig_emitsTheStoredValueAndNullWhenAbsent() = runBlocking {
        withTimeout(10_000) {
            assertNull(repository.observeConfig("g1").first().getOrNull())

            repository.saveConfig(full("g1"))

            assertEquals(full("g1"), repository.observeConfig("g1").first().getOrNull())
        }
    }
}
