// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.Git_config
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * SQLDelight-backed implementation of [GitConfigRepository].
 *
 * All writes go through [DatabaseWriteActor] via the `execute` lambda, following the same
 * pattern as other SqlDelight repositories in this codebase.
 */
class SqlDelightGitConfigRepository(
    private val database: SteleDatabase,
    private val writeActor: DatabaseWriteActor,
) : GitConfigRepository {

    private val queries get() = database.steleDatabaseQueries

    override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> =
        withContext(PlatformDispatcher.DB) {
            try {
                val row = queries.selectGitConfig(graphId).executeAsOneOrNull()
                row?.toModel().right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> {
        return writeActor.execute(DatabaseWriteActor.Priority.HIGH) {
            try {
                @OptIn(DirectSqlWrite::class)
                database.steleDatabaseQueries.insertOrReplaceGitConfig(
                    graph_id = config.graphId,
                    repo_root = config.repoRoot,
                    wiki_subdir = config.wikiSubdir,
                    remote_name = config.remoteName,
                    remote_branch = config.remoteBranch,
                    auth_type = config.authType.name,
                    ssh_key_path = config.sshKeyPath,
                    ssh_key_passphrase_key = config.sshKeyPassphraseKey,
                    https_token_key = config.httpsTokenKey,
                    poll_interval_minutes = config.pollIntervalMinutes.toLong(),
                    auto_commit = if (config.autoCommit) 1L else 0L,
                    commit_message_template = config.commitMessageTemplate,
                )
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> {
        return writeActor.execute(DatabaseWriteActor.Priority.HIGH) {
            try {
                @OptIn(DirectSqlWrite::class)
                database.steleDatabaseQueries.deleteGitConfig(graphId)
                Unit.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }
    }

    override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> =
        queries.selectGitConfig(graphId)
            .asFlow()
            .mapToOneOrNull(PlatformDispatcher.DB)
            .map<Git_config?, Either<DomainError, GitConfig?>> { row -> row?.toModel().right() }
            .catch { e -> emit(DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()) }

    private fun Git_config.toModel(): GitConfig = GitConfig(
        graphId = graph_id,
        repoRoot = repo_root,
        wikiSubdir = wiki_subdir,
        remoteName = remote_name,
        remoteBranch = remote_branch,
        authType = runCatching { GitAuthType.valueOf(auth_type) }.getOrDefault(GitAuthType.NONE),
        sshKeyPath = ssh_key_path,
        sshKeyPassphraseKey = ssh_key_passphrase_key,
        httpsTokenKey = https_token_key,
        pollIntervalMinutes = poll_interval_minutes.toInt(),
        autoCommit = auto_commit != 0L,
        commitMessageTemplate = commit_message_template,
    )
}
