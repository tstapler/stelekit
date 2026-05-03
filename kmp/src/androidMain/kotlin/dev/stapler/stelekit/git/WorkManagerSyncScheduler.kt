// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [BackgroundSyncScheduler] using WorkManager.
 *
 * Schedules [GitSyncWorker] with a [NetworkType.CONNECTED] constraint.
 * Android enforces a minimum 15-minute repeat interval for periodic work.
 *
 * For sub-15-minute polling when the app is foregrounded, [GitSyncService] manages
 * its own coroutine timer via [GitSyncService.startPeriodicSync].
 */
class WorkManagerSyncScheduler(
    private val context: Context,
    private val graphId: String,
) : BackgroundSyncScheduler {

    companion object {
        private const val WORK_NAME = "stelekit_git_sync"
    }

    override fun schedule(intervalMinutes: Int) {
        val repeatInterval = maxOf(intervalMinutes.toLong(), 15L)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<GitSyncWorker>(repeatInterval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(GitSyncWorker.KEY_GRAPH_ID, graphId)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

/**
 * WorkManager worker that calls [GitSyncService.fetchOnly] to check for remote changes.
 *
 * Only fetches — does not merge — to minimise battery impact and avoid data loss
 * from automatic merges when the user may be editing.
 *
 * The [GitSyncService] instance is obtained from [GitSyncServiceRegistry], which must be
 * populated from the Application class before WorkManager starts any work.
 */
class GitSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_GRAPH_ID = "graph_id"
    }

    override suspend fun doWork(): Result {
        val graphId = inputData.getString(KEY_GRAPH_ID) ?: return Result.failure()

        // Fast path: app is running and service is registered
        val service = GitSyncServiceRegistry.getService(graphId)
        if (service != null) {
            return try {
                service.fetchOnly(graphId)
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.retry()
            }
        }

        // Slow path: process was killed and WorkManager restarted it.
        // Application.onCreate() has run, so DriverFactory is initialized.
        // Perform a standalone fetch directly without a full GitSyncService.
        return try {
            CredentialStore.init(applicationContext)
            DriverFactory.setContext(applicationContext)

            val factory = DriverFactory()
            val dbUrl = factory.getDatabaseUrl(graphId)
            val driver = factory.createDriver(dbUrl)
            val db = dev.stapler.stelekit.db.SteleDatabase(driver)

            val row = db.steleDatabaseQueries.selectGitConfig(graphId).executeAsOneOrNull()
                ?: run { driver.close(); return Result.success() }

            val config = row.toGitConfig()
            val gitRepository = AndroidGitRepository()
            gitRepository.fetch(config)

            driver.close()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private fun dev.stapler.stelekit.db.Git_config.toGitConfig() =
    dev.stapler.stelekit.git.model.GitConfig(
        graphId = graph_id,
        repoRoot = repo_root,
        wikiSubdir = wiki_subdir,
        remoteName = remote_name,
        remoteBranch = remote_branch,
        authType = runCatching {
            dev.stapler.stelekit.git.model.GitAuthType.valueOf(auth_type)
        }.getOrDefault(dev.stapler.stelekit.git.model.GitAuthType.NONE),
        sshKeyPath = ssh_key_path,
        sshKeyPassphraseKey = ssh_key_passphrase_key,
        httpsTokenKey = https_token_key,
        pollIntervalMinutes = poll_interval_minutes.toInt(),
        autoCommit = auto_commit != 0L,
        commitMessageTemplate = commit_message_template,
    )

/**
 * Simple static registry that maps graphId → [GitSyncService].
 *
 * Populated from the Application class (or equivalent host) so [GitSyncWorker] can
 * retrieve the service without a DI container.
 *
 * In a future iteration this should be replaced with Hilt injection.
 */
object GitSyncServiceRegistry {
    private val services = mutableMapOf<String, GitSyncService>()

    fun register(graphId: String, service: GitSyncService) {
        services[graphId] = service
    }

    fun unregister(graphId: String) {
        services.remove(graphId)
    }

    fun getService(graphId: String): GitSyncService? = services[graphId]
}
