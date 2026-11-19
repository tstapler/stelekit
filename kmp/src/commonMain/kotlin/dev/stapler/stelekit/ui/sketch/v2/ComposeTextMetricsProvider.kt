// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.State
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/** The three bundled families of the style panel's "Hand-drawn / Normal / Code" set (OFL fonts, ADR-004). */
class SketchFontFamilies(val handDrawn: FontFamily, val normal: FontFamily, val code: FontFamily) {
    /** Maps an Excalidraw `fontFamily` id (1 Virgil, 5 Excalifont: hand-drawn; 3 Cascadia, 8 Comic Shanns: code; the rest: normal). */
    fun forExcalidrawId(id: Int): FontFamily = when (id) {
        1, 5 -> handDrawn
        3, 8 -> code
        else -> normal
    }
}

sealed interface SketchFontState {
    data object Loading : SketchFontState

    data class Loaded(val families: SketchFontFamilies) : SketchFontState

    data object Failed : SketchFontState
}

/**
 * Measures text with the BUNDLED fonts only (platform default fonts differ by up to 5 px across targets,
 * determinism-spike 0.1.3b). [fonts] is a Compose `State`, so a composition that reads [fontStatus] is invalidated
 * when the wasm font download finishes; until then [fontStatus] is not `Loaded` and layout must not persist a size.
 * [measurer] must be built with density 1 and font scale 1 so one sp is one scene unit.
 */
class ComposeTextMetricsProvider(
    private val measurer: TextMeasurer,
    private val fonts: State<SketchFontState>,
) : TextMetricsProvider {
    override val fontStatus: FontStatus
        get() = when (fonts.value) {
            SketchFontState.Loading -> FontStatus.Loading
            is SketchFontState.Loaded -> FontStatus.Loaded
            SketchFontState.Failed -> FontStatus.Failed
        }

    override fun lineHeight(style: TextStyle): Double {
        val result = measurer.measure(AnnotatedString(REFERENCE_LINE), composeStyle(style), softWrap = false, maxLines = 1)
        return (result.getLineBottom(0) - result.getLineTop(0)).toDouble()
    }

    override fun advance(text: String, style: TextStyle): Double {
        if (text.isEmpty()) return 0.0
        val result = measurer.measure(AnnotatedString(text), composeStyle(style), softWrap = false, maxLines = 1)
        return result.getLineRight(0).toDouble() - result.getLineLeft(0).toDouble()
    }

    /** Glyph coverage of the bundled files (cmap checked with fontTools): Basic Latin, Latin-1 and the common punctuation. */
    override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = COVERED_RANGES.any { codePoint in it }

    private fun composeStyle(style: TextStyle): ComposeTextStyle {
        val family = (fonts.value as? SketchFontState.Loaded)?.families?.forExcalidrawId(style.fontFamily)
        return ComposeTextStyle(fontFamily = family, fontSize = style.fontSize.sp)
    }

    private companion object {
        const val REFERENCE_LINE = "Ag"

        val COVERED_RANGES: List<IntRange> = listOf(
            0x20..0x7E, 0xA0..0xFF, 0x2013..0x2014, 0x2018..0x2019, 0x201C..0x201D, 0x2022..0x2022, 0x2026..0x2026,
            0x20AC..0x20AC,
        )
    }
}
