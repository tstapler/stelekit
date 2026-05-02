package dev.stapler.stelekit.integration

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.parser.MarkdownParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.time.Clock

/**
 * Reproduces the symptom: today's journal page row is saved with is_content_loaded=1
 * but no blocks appear in the database.
 *
 * Steps mirrored from GraphLoader.parseAndSavePage() / processParsedBlocks():
 *  1. Call MarkdownParser.parsePage() on the raw file content
 *  2. Print the block count and each block's content
 *  3. Try to construct Block(...) objects exactly as processParsedBlocks() does
 *     and catch any validation exception
 */
class JournalParseReproTest {

    // Raw content of /home/tstapler/Documents/personal-wiki/logseq/journals/2026_04_11.md
    // Pasted verbatim so the test is self-contained and reproducible.
    private val journalContent = """
- Reading about [[Book Scanning]] https://github.com/ad-si/awesome-scanning
- Reading about [[Ground Cover]]for [[USDA Zone 9b]] [[Needs Synthesis]]-Portland Nursery
	- https://plants4home.com/products/herniaria-rupturewort?variant=51216513564888&country=US&currency=USD&utm_medium=product_sync&utm_source=google&utm_content=sag_organic&utm_campaign=sag_organic&gad_source=1&gad_campaignid=23711443654&gclid=CjwKCAjw4ufOBhBkEiwAfuC7-Q1ZrY0RI8FOnRyw_KYJIuWaGE5H7XArp6N5Pc29TxWXuc_ws2juDRoCS2oQAvD_BwE
	- Leptinella squalida
	- Native Full-Shade Ground Covers (Best for PNW)
	- Redwood Sorrel (Oxalis oregana): Spreads well, evergreen, shade-tolerant, and drought-tolerant once established.
	- Wild Ginger (Asarum caudatum): Heart-shaped leaves, evergreen, thrives in moist, rich soil.
	- Foamflower (Tiarella trifoliata / cordifolia): Evergreen with nice texture, good for deep shade.
	- Bunchberry (Cornus canadensis): Creeping, loves moist, acidic shady spots.
	- Inside-out Flower (Vancouveria hexandra): Delicate-looking but tough native for dry shade.
	- False Lily-of-the-Valley (Maianthemum dilatatum): Spreads well, thrives in shady understories.
	- Piggyback Plant (Tolmiea menziesii): Thrives in shady, moist dells
	- Non-Native & Tough Shade Ground Covers
	- Japanese Spurge (Pachysandra terminalis): Excellent for dry shade, forms dense, dark green, evergreen mats.
	- Barrenwort (Epimedium spp.): Highly drought-tolerant once established, shade-loving, and hardy.
	- Sweetbox (Sarcococca hookeriana var. humilis): Low, evergreen shrub that spreads, fragrant flowers.
	- Bigroot Geranium (Geranium macrorrhizum): Tough, weed-smothering, handles dry shade.
		- [[Full Shade]] [[Moss]]
		- [[Sheet Moss]] and [[Carpet Moss]]
-
- Listening to the [[Book]] [[The Developing Mind by Daniel J Sielgel]]
	- The [[Embodied Brain]] is the collection of the [[Brain]] and the [[Endrocrine System]], and [[Nervous System]]
	-
	-
	-
	-
	-
	- [[Parenting]] has significant affects on outcomes even after factoring in the effects of [[Genetics]]
""".trimIndent()

    @Test
    fun `parse journal file and report block count and content`() {
        println("\n=== STEP 1: Parsing journal content (length=${journalContent.length}) ===")

        val parser = MarkdownParser()
        val parsedPage = parser.parsePage(journalContent)

        println("Top-level block count: ${parsedPage.blocks.size}")
        println()

        fun printBlocks(blocks: List<ParsedBlock>, indent: Int = 0) {
            blocks.forEachIndexed { idx, block ->
                val prefix = "  ".repeat(indent)
                println("${prefix}[$idx] level=${block.level} content=${block.content.take(120).replace("\n", "\\n")}")
                if (block.children.isNotEmpty()) {
                    printBlocks(block.children, indent + 1)
                }
            }
        }

        printBlocks(parsedPage.blocks)

        println("\n=== STEP 2: Constructing Block(...) objects as processParsedBlocks() does ===")

        val now = Clock.System.now()
        val fakePageUuid = "aaaaaaaa-0000-0000-0000-000000000000"
        val fakePath = "/graph/journals/2026_04_11.md"

        var blockConstructionFailure: Exception? = null
        var blocksConstructed = 0

        fun tryConstructBlocks(blocks: List<ParsedBlock>, parentUuid: String?, baseLevel: Int) {
            blocks.forEachIndexed { index, parsedBlock ->
                val blockUuid = "bbbbbbbb-0000-0000-0000-" + "%012d".format(blocksConstructed)
                val mergedProperties = parsedBlock.properties.toMutableMap()
                parsedBlock.scheduled?.let { mergedProperties["scheduled"] = it }
                parsedBlock.deadline?.let { mergedProperties["deadline"] = it }

                println("  Constructing block #$blocksConstructed: content=${parsedBlock.content.take(80).replace("\n", "\\n")}")

                try {
                    Block(
                        uuid = blockUuid,
                        pageUuid = fakePageUuid,
                        parentUuid = parentUuid,
                        leftUuid = null,
                        content = parsedBlock.content,
                        level = baseLevel,
                        position = index,
                        createdAt = now,
                        updatedAt = now,
                        version = 0L,
                        properties = mergedProperties,
                        isLoaded = true
                    )
                    blocksConstructed++

                    if (parsedBlock.children.isNotEmpty()) {
                        tryConstructBlocks(parsedBlock.children, blockUuid, baseLevel + 1)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    blockConstructionFailure = e
                    println("  EXCEPTION constructing block #$blocksConstructed: ${e::class.simpleName}: ${e.message}")
                    println("  Full content that caused failure (${parsedBlock.content.length} chars):")
                    parsedBlock.content.forEachIndexed { i, c ->
                        if (c.code in 0x00..0x1F || c.code in 0x80..0x9F) {
                            println("    char[$i] = 0x${c.code.toString(16).padStart(4, '0')} (CONTROL)")
                        }
                    }
                    println("  Stack trace:")
                    e.printStackTrace()
                }
            }
        }

        tryConstructBlocks(parsedPage.blocks, parentUuid = null, baseLevel = 1)

        println("\n=== SUMMARY ===")
        println("Total blocks successfully constructed: $blocksConstructed")
        if (blockConstructionFailure != null) {
            println("FIRST construction exception: ${blockConstructionFailure!!::class.simpleName}: ${blockConstructionFailure!!.message}")
        } else {
            println("No construction exceptions.")
        }
    }
}
