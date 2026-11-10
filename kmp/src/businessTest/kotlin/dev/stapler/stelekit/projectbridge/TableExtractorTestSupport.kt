package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.TableAlias

/** Registry for the closet fixture's tables; keys are invented, aliases match the sanitised table text. */
internal object ClosetTableRegistry {
    private fun length(key: String, row: String, header: String) =
        DimensionEntry(key, DimensionKind.LENGTH, key, DimensionAliases(table = listOf(TableAlias(row, header))))

    val registry = DimensionRegistry(
        dimensions = listOf(
            length("opening", "Opening width", "Value"),
            length("height.rear", "Height at rear", "Value"),
            length("height.front", "Height at front", "Value"),
            length("depth.usable", "Usable depth", "Value"),
            length("zone.left-long.width", "Left - long hang*", "Width"),
            length("zone.left-short.width", "Left - short hang", "Width"),
            length("zone.shelves.width", "Shelves*", "Width"),
            length("zone.right-short.width", "Right - short hang", "Width"),
            length("zone.right-long.width", "Right - long hang*", "Width"),
            length("rod.left-long", "Left - long hang*", "Rod height"),
            length("rod.right-long", "Right - long hang*", "Rod height"),
            DimensionEntry("bracket.count", DimensionKind.COUNT, "Brackets", DimensionAliases(table = listOf(TableAlias("Brackets", "Qty")))),
        ),
    )
}

internal fun closetTablesMarkdown(): String =
    checkNotNull(ClosetTableRegistry::class.java.classLoader.getResourceAsStream("home-sketch/closet/closet-tables.md")) {
        "closet-tables.md missing from the test classpath"
    }.bufferedReader().use { it.readText() }
