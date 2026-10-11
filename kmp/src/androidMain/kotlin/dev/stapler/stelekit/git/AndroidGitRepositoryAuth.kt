// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.security.CredentialAccess
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.util.FS

/**
 * SSH/HTTPS transport auth configuration for [AndroidGitRepository], split out to keep that
 * class under the file-size guideline. [credentialAccess] is a function, not a captured value,
 * because [AndroidGitRepository.credentialAccess] is swapped at runtime (paranoid-mode vault
 * lock/unlock) and this class must always see the current one.
 */
internal class AndroidGitAuthConfigurer(
    private val sshKeyProvider: (() -> ByteArray)?,
    private val credentialAccess: () -> CredentialAccess,
    private val logger: Logger,
) {
    fun buildJschSessionFactory(keyPath: String, passphrase: String? = null): JschConfigSessionFactory {
        return object : JschConfigSessionFactory() {
            override fun configure(host: OpenSshConfig.Host, session: Session) {
                session.setConfig("StrictHostKeyChecking", "accept-new")
            }

            override fun createDefaultJSch(fs: FS): JSch {
                val jsch = super.createDefaultJSch(fs)
                val keyBytes = sshKeyProvider?.invoke()
                val passphraseBytes = passphrase?.toByteArray(Charsets.UTF_8)
                if (keyBytes != null) {
                    jsch.addIdentity("stelekit-key", keyBytes, null, passphraseBytes)
                } else if (keyPath.isNotEmpty()) {
                    if (passphraseBytes != null) {
                        jsch.addIdentity(keyPath, passphraseBytes)
                    } else {
                        jsch.addIdentity(keyPath)
                    }
                }
                return jsch
            }
        }
    }

    /** Used by [AndroidGitRepository.fetch]/[AndroidGitRepository.push] — auth sourced from [GitConfig]. */
    fun configureTransport(cmd: TransportCommand<*, *>, config: GitConfig) {
        configureTransportAuth(cmd, config, credentialAccess()) { passphrase ->
            cmd.setTransportConfigCallback { transport ->
                if (transport is SshTransport && config.sshKeyPath != null) {
                    transport.sshSessionFactory = buildJschSessionFactory(config.sshKeyPath, passphrase)
                }
            }
        }
    }

    /** Used by [AndroidGitRepository.clone]/[AndroidGitRepository.testRemote] — auth sourced from [GitAuth]. */
    fun configureAuth(cmd: TransportCommand<*, *>, auth: GitAuth, preResolvedToken: String?) {
        val handled = configureHttpsOrNoAuth(cmd, auth, preResolvedToken) {
            logger.warn("HTTPS token unavailable for clone — proceeding unauthenticated")
        }
        if (handled || auth !is GitAuth.SshKey) return
        cmd.setTransportConfigCallback { transport ->
            if (transport is SshTransport) {
                transport.sshSessionFactory = buildJschSessionFactory(auth.keyPath)
            }
        }
    }
}
