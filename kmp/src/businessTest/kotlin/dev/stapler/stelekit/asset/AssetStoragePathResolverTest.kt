package dev.stapler.stelekit.asset

import kotlin.test.Test
import kotlin.test.assertEquals

class AssetStoragePathResolverTest {

    @Test fun `image mime resolves to images subfolder`() {
        assertEquals("images", AssetStoragePathResolver.resolveSubfolder("image/jpeg"))
        assertEquals("images", AssetStoragePathResolver.resolveSubfolder("image/png"))
        assertEquals("images", AssetStoragePathResolver.resolveSubfolder("image/gif"))
    }

    @Test fun `application pdf resolves to pdfs subfolder`() {
        assertEquals("pdfs", AssetStoragePathResolver.resolveSubfolder("application/pdf"))
    }

    @Test fun `audio mime resolves to audio subfolder`() {
        assertEquals("audio", AssetStoragePathResolver.resolveSubfolder("audio/mpeg"))
        assertEquals("audio", AssetStoragePathResolver.resolveSubfolder("audio/wav"))
    }

    @Test fun `video mime resolves to video subfolder`() {
        assertEquals("video", AssetStoragePathResolver.resolveSubfolder("video/mp4"))
        assertEquals("video", AssetStoragePathResolver.resolveSubfolder("video/quicktime"))
    }

    @Test fun `msword resolves to documents subfolder`() {
        assertEquals("documents", AssetStoragePathResolver.resolveSubfolder("application/msword"))
        assertEquals("documents", AssetStoragePathResolver.resolveSubfolder("text/plain"))
    }

    @Test fun `unknown mime resolves to files subfolder`() {
        // application/* maps to documents, not files
        assertEquals("documents", AssetStoragePathResolver.resolveSubfolder("application/octet-stream"))
        // truly unknown schemes map to files
        assertEquals("files", AssetStoragePathResolver.resolveSubfolder("unknown/type"))
        assertEquals("files", AssetStoragePathResolver.resolveSubfolder(""))
    }

    @Test fun `resolvePath constructs absolute path`() {
        val path = AssetStoragePathResolver.resolvePath("/home/user/graph", "images", "photo.jpg")
        assertEquals("/home/user/graph/assets/images/photo.jpg", path)
    }

    @Test fun `relativeMarkdownPath constructs relative path`() {
        val path = AssetStoragePathResolver.relativeMarkdownPath("pdfs", "doc.pdf")
        assertEquals("../assets/pdfs/doc.pdf", path)
    }
}
