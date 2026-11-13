// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.git.testsupport.sampleConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * "Cannot read" is never "no remote" (A-C1): an unopenable or unreadable DB is `Unavailable`. The DB read itself is
 * injected because Robolectric's bundled SQLite lacks the `fts5` module the app schema needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidGitLegacyConfigSourceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun source(exists: Boolean = true, load: (String) -> dev.stapler.stelekit.git.model.GitConfig?) =
        AndroidGitLegacyConfigSource(context, databaseExists = { exists }, load = load)

    @Test
    fun read_should_beUnavailable_When_DatabaseFileDoesNotExist() = runBlocking {
        var loaded = false
        val result = source(exists = false) { loaded = true; null }.read("g1")
        assertEquals(LegacyGitConfigResult.Unavailable, result)
        assertEquals(false, loaded, "a missing database must not be opened (opening would create it)")
    }

    @Test
    fun read_should_beUnavailable_When_TheReadThrows() = runBlocking {
        assertEquals(LegacyGitConfigResult.Unavailable, source { throw IllegalStateException("cannot open") }.read("g1"))
    }

    @Test
    fun read_should_beUnavailable_When_TheRealDatabasePathIsUnopenable() = runBlocking {
        // No database exists for this id in the test files dir, so the real existence check reports unavailable.
        assertEquals(LegacyGitConfigResult.Unavailable, AndroidGitLegacyConfigSource(context).read("no-such-graph"))
    }

    @Test
    fun read_should_findNoRemote_When_TheDatabaseHasNoGitConfigRow() = runBlocking {
        assertEquals(LegacyGitConfigResult.Found(hasRemote = false, pollIntervalMinutes = 0), source { null }.read("g1"))
    }

    @Test
    fun read_should_findRemoteAndInterval_When_TheDatabaseHasAGitConfigRow() = runBlocking {
        val result = source { sampleConfig.copy(graphId = it, pollIntervalMinutes = 30) }.read("g1")
        assertEquals(LegacyGitConfigResult.Found(hasRemote = true, pollIntervalMinutes = 30), result)
    }
}
