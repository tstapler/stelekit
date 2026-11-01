package dev.stapler.stelekit.git.lfs

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LfsEndpointTest {
    @Test
    fun endpointFromRemoteUrl_should_AppendInfoLfs_ForHttpsAndHttpRemotes() {
        assertEquals("https://github.com/o/r.git/info/lfs", LfsRepositorySupport.endpointFromRemoteUrl("https://github.com/o/r"))
        assertEquals("https://github.com/o/r.git/info/lfs", LfsRepositorySupport.endpointFromRemoteUrl("https://github.com/o/r.git/"))
        assertEquals("http://host:8080/r.git/info/lfs", LfsRepositorySupport.endpointFromRemoteUrl("http://host:8080/r"))
    }

    @Test
    fun endpointFromRemoteUrl_should_StripCredentials_FromHttpsRemotes() {
        val endpoint = LfsRepositorySupport.endpointFromRemoteUrl("https://user:secret@github.com/o/r.git")

        assertEquals("https://github.com/o/r.git/info/lfs", endpoint)
    }

    @Test
    fun endpointFromRemoteUrl_should_MapSshRemotesToTheirHttpsTwin() {
        assertEquals("https://github.com/o/r.git/info/lfs", LfsRepositorySupport.endpointFromRemoteUrl("git@github.com:o/r.git"))
        assertEquals("https://example.com/o/r.git/info/lfs", LfsRepositorySupport.endpointFromRemoteUrl("ssh://git@example.com/o/r"))
    }

    @Test
    fun endpointFromRemoteUrl_should_ReturnNull_WhenNothingResolves() {
        assertNull(LfsRepositorySupport.endpointFromRemoteUrl("/local/path/repo"))
        assertNull(LfsRepositorySupport.endpointFromRemoteUrl("ssh://"))
    }

    @Test
    fun endpointFor_should_PreferLfsUrlConfig_ThenFallBackToTheRemote() {
        val repo = InMemoryRepository(DfsRepositoryDescription("t"))
        repo.config.setString("remote", "origin", "url", "https://github.com/o/r.git")
        assertEquals("https://github.com/o/r.git/info/lfs", LfsRepositorySupport.endpointFor(repo, "origin"))
        assertNull(LfsRepositorySupport.endpointFor(repo, "missing"))

        repo.config.setString("lfs", null, "url", "https://lfs.example.com/store/")
        assertEquals("https://lfs.example.com/store", LfsRepositorySupport.endpointFor(repo, "origin"))
    }

    @Test
    fun basicAuth_should_EncodeUsernameAndToken() {
        assertEquals("Basic eC1vYXV0aC1iYXNpYzp0b2s=", LfsRepositorySupport.basicAuth("x-oauth-basic", "tok"))
    }
}
