package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.util.ContentHasher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssetCopierTest {
    private class Fs : FakeRelocationFileSystem() {
        var failWrites = false
        var writes = 0
        override fun writeFileBytes(path: String, data: ByteArray): Boolean {
            if (failWrites) return false
            writes++
            return super.writeFileBytes(path, data)
        }
        fun seed(path: String, bytes: ByteArray) { super.writeFileBytes(path, bytes) }
    }

    private val fs = Fs()
    private val copier = AssetCopier(fs)
    private val src = "/graphs/src"
    private val dst = "/graphs/dst"
    private val old = byteArrayOf(1, 2, 3)
    private val new = byteArrayOf(9, 8, 7)
    private val hash8 = ContentHasher.sha256(new).take(8)

    private fun page(vararg contents: String) =
        MergePage("P", blocks = contents.mapIndexed { i, c -> MergeBlock("u$i", c) })

    private fun Either<DomainError, AssetCopyResult>.ok(): AssetCopyResult =
        if (this is Either.Right) value else error("expected Right but was $this")

    @Test
    fun `same name different bytes is copied as renamed file and link rewritten`() {
        fs.seed("$src/assets/a.png", new)
        fs.seed("$dst/assets/a.png", old)
        val r = copier.copy(page("![x](../assets/a.png)"), src, dst).ok()
        assertEquals("![x](../assets/a-$hash8.png)", r.page.blocks.single().content)
        assertEquals(1, r.assetsRenamed)
        assertEquals(1, r.assetsCopied)
        assertContentEquals(new, fs.readFileBytes("$dst/assets/a-$hash8.png"))
        assertContentEquals(old, fs.readFileBytes("$dst/assets/a.png"))
    }

    @Test
    fun `identical bytes are not copied and link is unchanged`() {
        fs.seed("$src/assets/a.png", new)
        fs.seed("$dst/assets/a.png", new)
        fs.writes = 0
        val r = copier.copy(page("![x](../assets/a.png)"), src, dst).ok()
        assertEquals("![x](../assets/a.png)", r.page.blocks.single().content)
        assertEquals(0, r.assetsCopied)
        assertEquals(0, r.assetsRenamed)
        assertEquals(0, fs.writes)
    }

    @Test
    fun `missing source asset is a warning and the page is still returned`() {
        val r = copier.copy(page("![x](../assets/gone.png) text"), src, dst).ok()
        assertEquals("![x](../assets/gone.png) text", r.page.blocks.single().content)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.single().contains("gone.png"))
        assertEquals(0, r.assetsCopied)
    }

    @Test
    fun `new asset is copied under the same name and a re-run is idempotent`() {
        fs.seed("$src/assets/b.png", new)
        val first = copier.copy(page("![](../assets/b.png)"), src, dst).ok()
        assertEquals(1, first.assetsCopied)
        val second = copier.copy(first.page, src, dst).ok()
        assertEquals(0, second.assetsCopied)
        assertEquals(first.page, second.page)
    }

    @Test
    fun `rename is idempotent when the hashed file already exists`() {
        fs.seed("$src/assets/a.png", new)
        fs.seed("$dst/assets/a.png", old)
        copier.copy(page("![](../assets/a.png)"), src, dst).ok()
        fs.writes = 0
        val r = copier.copy(page("![](../assets/a.png)"), src, dst).ok()
        assertEquals("![](../assets/a-$hash8.png)", r.page.blocks.single().content)
        assertEquals(0, r.assetsCopied)
        assertEquals(1, r.assetsRenamed)
        assertEquals(0, fs.writes)
    }

    @Test
    fun `dry run counts without writing`() {
        fs.seed("$src/assets/a.png", new)
        fs.seed("$dst/assets/a.png", old)
        fs.writes = 0
        val r = copier.copy(page("![](../assets/a.png)"), src, dst, dryRun = true).ok()
        assertEquals(1, r.assetsRenamed)
        assertEquals(0, fs.writes)
        assertFalse(fs.fileExists("$dst/assets/a-$hash8.png"))
    }

    @Test
    fun `traversal out of assets is refused with a warning and nothing is read or written outside`() {
        fs.seed("$src/secret.txt", new)
        val r = copier.copy(page("![](../assets/../../secret.txt)"), src, dst).ok()
        assertEquals(1, r.warnings.size)
        assertEquals(0, r.assetsCopied)
        assertFalse(fs.fileExists("$dst/secret.txt"))
    }

    @Test
    fun `nested directories, children and a repeated link are handled once`() {
        fs.seed("$src/assets/sub/c.png", new)
        fs.seed("$dst/assets/sub/c.png", old)
        val p = MergePage("P", blocks = listOf(MergeBlock("u", "![](../assets/sub/c.png)", children = listOf(MergeBlock("v", "again ![](../assets/sub/c.png)")))))
        val r = copier.copy(p, src, dst).ok()
        assertEquals(1, r.assetsCopied)
        assertEquals(1, r.assetsRenamed)
        assertEquals("again ![](../assets/sub/c-$hash8.png)", r.page.blocks.single().children.single().content)
    }

    @Test
    fun `write failure is a typed Left`() {
        fs.seed("$src/assets/a.png", new)
        fs.failWrites = true
        val r = copier.copy(page("![](../assets/a.png)"), src, dst)
        assertIs<DomainError.FileSystemError.WriteFailed>((r as Either.Left).value)
    }

    @Test
    fun `assetRefs lists links`() {
        assertEquals(listOf("a.png", "d/b.pdf"), copier.assetRefs("![x](../assets/a.png) [doc](../assets/d/b.pdf) [web](http://x)"))
    }
}
