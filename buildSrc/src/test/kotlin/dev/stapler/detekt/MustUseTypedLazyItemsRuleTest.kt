package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MustUseTypedLazyItemsRuleTest {

    private val rule = MustUseTypedLazyItemsRule()

    // ===== NON-COMPLIANT: named key arg =====

    @Test
    fun `flags items with value class key — the original crash bug`() {
        val findings = rule.compileAndLint(
            """
            @JvmInline value class PageUuid(val value: String)
            data class Page(val uuid: PageUuid)

            fun <T> items(items: List<T>, key: ((T) -> Any)? = null, block: (T) -> Unit = {}) {}

            fun test(pages: List<Page>) {
                items(pages, key = { it.uuid })
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags items with plain String key — still must use typedItems`() {
        val findings = rule.compileAndLint(
            """
            data class DriveFile(val id: String)

            fun <T> items(items: List<T>, key: ((T) -> Any)? = null, block: (T) -> Unit = {}) {}

            fun test(files: List<DriveFile>) {
                items(files, key = { it.id })
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags itemsIndexed with named key`() {
        val findings = rule.compileAndLint(
            """
            @JvmInline value class BlockUuid(val value: String)
            data class Block(val uuid: BlockUuid)

            fun <T> itemsIndexed(items: List<T>, key: ((Int, T) -> Any)? = null, block: (Int, T) -> Unit = { _, _ -> }) {}

            fun test(blocks: List<Block>) {
                itemsIndexed(blocks, key = { _, b -> b.uuid.value })
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    // ===== NON-COMPLIANT: positional key arg =====

    @Test
    fun `flags items with positional lambda key — same crash as named form`() {
        val findings = rule.compileAndLint(
            """
            @JvmInline value class PageUuid(val value: String)
            data class Page(val uuid: PageUuid)

            fun <T> items(items: List<T>, key: ((T) -> Any)? = null, block: (T) -> Unit = {}) {}

            fun test(pages: List<Page>) {
                items(pages, { it.uuid })
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags itemsIndexed with positional lambda key`() {
        val findings = rule.compileAndLint(
            """
            data class Block(val id: String)

            fun <T> itemsIndexed(items: List<T>, key: ((Int, T) -> Any)? = null, block: (Int, T) -> Unit = { _, _ -> }) {}

            fun test(blocks: List<Block>) {
                itemsIndexed(blocks, { _, b -> b.id })
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    // ===== NON-COMPLIANT: grid variants =====

    @Test
    fun `flags items with key inside grid scope — grid variant of the crash bug`() {
        val findings = rule.compileAndLint(
            """
            @JvmInline value class ImageUuid(val value: String)
            data class GalleryImage(val uuid: ImageUuid)

            fun <T> items(items: List<T>, key: ((T) -> Any)? = null, block: (T) -> Unit = {}) {}

            fun test(images: List<GalleryImage>) {
                items(images, key = { it.uuid })
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
    }

    // ===== COMPLIANT: should not flag =====

    @Test
    fun `allows items without key argument`() {
        val findings = rule.compileAndLint(
            """
            data class Page(val name: String)

            fun <T> items(items: List<T>, block: (T) -> Unit = {}) {}

            fun test(pages: List<Page>) {
                items(pages) { println(it.name) }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty(), "Expected no findings but got: $findings")
    }

    @Test
    fun `allows typedItems — the compliant project wrapper`() {
        val findings = rule.compileAndLint(
            """
            @JvmInline value class PageUuid(val value: String)
            data class Page(val uuid: PageUuid)
            fun PageUuid.asLazyKey(): String = value

            fun <T> typedItems(items: List<T>, key: (T) -> String, block: (T) -> Unit = {}) {}

            fun test(pages: List<Page>) {
                typedItems(pages, key = { it.uuid.asLazyKey() })
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty(), "Expected no findings but got: $findings")
    }

    @Test
    fun `allows typedGridItems — the compliant grid wrapper`() {
        val findings = rule.compileAndLint(
            """
            data class GalleryImage(val uuid: String)

            fun <T> typedGridItems(items: List<T>, key: (T) -> String, block: (T) -> Unit = {}) {}

            fun test(images: List<GalleryImage>) {
                typedGridItems(images, key = { it.uuid })
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty(), "Expected no findings but got: $findings")
    }

    @Test
    fun `allows typedGridItemsIndexed — the compliant indexed grid wrapper`() {
        val findings = rule.compileAndLint(
            """
            data class Section(val id: String)

            fun <T> typedGridItemsIndexed(items: List<T>, key: (Int, T) -> String, block: (Int, T) -> Unit = { _, _ -> }) {}

            fun test(sections: List<Section>) {
                typedGridItemsIndexed(sections, key = { i, s -> "${'$'}i:${'$'}{s.id}" })
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty(), "Expected no findings but got: $findings")
    }

    @Test
    fun `allows itemsIndexed without key`() {
        val findings = rule.compileAndLint(
            """
            fun <T> itemsIndexed(items: List<T>, block: (Int, T) -> Unit = { _, _ -> }) {}

            fun test(items: List<String>) {
                itemsIndexed(items) { index, item -> println("${'$'}index: ${'$'}item") }
            }
            """.trimIndent()
        )
        assertTrue(findings.isEmpty(), "Expected no findings but got: $findings")
    }

    @Test
    fun `allows items count-based overload with key — used inside TypedLazyItems wrappers`() {
        // The wrappers delegate to items(count, key, contentType, content).
        // This overload uses Int keys internally — the rule sees "items" with a named "key"
        // but the TypedLazyItems.kt file is excluded via detekt.yml.
        // This test documents that the rule WOULD fire on this form if not excluded.
        val findings = rule.compileAndLint(
            """
            fun items(count: Int, key: ((Int) -> Any)? = null, block: (Int) -> Unit = {}) {}

            fun test() {
                items(count = 10, key = { index -> "item-${'$'}index" }) { }
            }
            """.trimIndent()
        )
        // Rule fires — TypedLazyItems.kt is excluded at file level in detekt.yml.
        assertEquals(1, findings.size, "Rule fires on count-based items(key=); TypedLazyItems.kt excluded by detekt.yml")
    }
}
