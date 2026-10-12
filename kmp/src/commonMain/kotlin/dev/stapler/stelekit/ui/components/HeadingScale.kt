package dev.stapler.stelekit.ui.components

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.ui.WindowSizeClass

/** Font size and line height for one heading level. */
internal data class HeadingSpec(val fontSize: TextUnit, val lineHeight: TextUnit)

/**
 * Heading metrics against the 14sp block body. COMPACT (phones) is deliberately flat: little
 * of the page is visible, so large headings cost scroll space. MEDIUM/EXPANDED follow Logseq's
 * desktop proportions (H1 2x, H2 1.5x, H3 ~1.2x body). H5/H6 floor at body size for legibility,
 * unlike Logseq's sub-body sizes. `sp` already covers density and user font scale.
 */
internal fun headingSpec(level: Int, sizeClass: WindowSizeClass): HeadingSpec {
    val compact = sizeClass == WindowSizeClass.COMPACT
    return when (level.coerceIn(1, 6)) {
        1 -> if (compact) HeadingSpec(22.sp, 28.sp) else HeadingSpec(28.sp, 34.sp)
        2 -> if (compact) HeadingSpec(20.sp, 26.sp) else HeadingSpec(21.sp, 28.sp)
        3 -> if (compact) HeadingSpec(18.sp, 24.sp) else HeadingSpec(17.sp, 24.sp)
        4 -> HeadingSpec(16.sp, 22.sp)
        5 -> HeadingSpec(15.sp, 21.sp)
        else -> HeadingSpec(14.sp, 20.sp)
    }
}
