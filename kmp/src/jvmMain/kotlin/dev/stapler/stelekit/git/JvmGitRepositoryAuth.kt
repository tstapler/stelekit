// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.security.CredentialAccess
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File

/**
 * SSH/HTTPS transport auth configuration for [JvmGitRepository], split out to keep that class
 * under the file-size guideline. [credentialAccess] is a function, not a captured value, because
 * [JvmGitRepository.credentialAccess] is swapped at runtime (paranoid-mode vault lock/unlock) and
 * this class must always see the current one.
 */
internal class JvmGitRepositoryAuth(
    private val credentialAccess: () -> CredentialAccess,
    private val logger: Logger,
) {
    private fun buildSshdSessionFactory(keyPath: String) =
        SshdSessionFactoryBuilder()
            .setPreferredAuthentications("publickey")
            .setHomeDirectory(File(System.getProperty("user.home")))
            .setSshDirectory(File(keyPath).parentFile ?: File(System.getProperty("user.home"), ".ssh"))
            .build(null)

    /** Used by [JvmGitRepository.fetch]/[JvmGitRepository.push] — auth sourced from [GitConfig]. */
    fun configureTransport(cmd: TransportCommand<*, *>, config: GitConfig) {
        configureTransportAuth(cmd, config, credentialAccess()) {
            val keyPath = config.sshKeyPath ?: return@configureTransportAuth
            // TODO(vault-cred): Wire the stored passphrase into MINA sshd's KeyPasswordProvider
            // for encrypted key support — retrieval below is correct, the passphrase just isn't
            // consumed yet.
            val sshFactory = buildSshdSessionFactory(keyPath)
            cmd.setTransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = sshFactory
                }
            }
        }
    }

    /** Used by [JvmGitRepository.clone]/[JvmGitRepository.testRemote] — auth sourced from [GitAuth]. */
    fun configureAuth(cmd: TransportCommand<*, *>, auth: GitAuth, preResolvedToken: String?) {
        val handled = configureHttpsOrNoAuth(cmd, auth, preResolvedToken) {
            logger.warn("HTTPS token unavailable for clone — proceeding unauthenticated")
        }
        if (handled || auth !is GitAuth.SshKey) return
        val sshFactory = buildSshdSessionFactory(auth.keyPath)
        cmd.setTransportConfigCallback { transport ->
            if (transport is SshTransport) {
                transport.sshSessionFactory = sshFactory
            }
        }
    }
}
