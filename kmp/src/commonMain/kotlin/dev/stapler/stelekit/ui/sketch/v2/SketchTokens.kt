// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchA11yStrings.Token as T

/** Text needs 4.5:1 (WCAG 1.4.3), everything else the editor draws needs 3:1 (WCAG 1.4.11). */
enum class TokenKind(val bar: Double) {
    Text(4.5),
    NonText(FocusRingContrast.NON_TEXT_BAR),
}

/** One foreground drawn on one background by the v2 editor; [name] is what an audit failure cites (UX-46, UX-89). */
data class TokenPair(val name: String, val kind: TokenKind, val foreground: Color, val background: Color) {
    val ratio: Double get() = FocusRingContrast.ratio(foreground, background)
    val passes: Boolean get() = ratio >= kind.bar
}

/**
 * The colour pairs the v2 editor draws (Task 3.6.6a, UX-46, UX-89), read from a Material colour scheme so the same list
 * audits the light, dark, stone and high-contrast token sets. Text pairs are what labels, hints, buttons, the toast lane and
 * the walkthrough popover show; non-text pairs are handles, glyphs, panel strokes and the focus ring's highlight.
 */
object SketchTokens {
    fun pairs(scheme: ColorScheme): List<TokenPair> = text(scheme) + nonText(scheme)

    private fun text(c: ColorScheme) = listOf(
        TokenPair(T.TEXT_ON_CANVAS, TokenKind.Text, c.onBackground, c.background),
        TokenPair(T.TEXT_ON_SURFACE, TokenKind.Text, c.onSurface, c.surface),
        TokenPair(T.HINT_ON_CANVAS, TokenKind.Text, c.onSurfaceVariant, c.background),
        TokenPair(T.HINT_ON_SURFACE, TokenKind.Text, c.onSurfaceVariant, c.surface),
        TokenPair(T.TEXT_ON_PANEL, TokenKind.Text, c.onSurfaceVariant, c.surfaceVariant),
        TokenPair(T.PRIMARY_ON_SURFACE, TokenKind.Text, c.primary, c.surface),
        TokenPair(T.PRIMARY_ON_PANEL, TokenKind.Text, c.primary, c.surfaceVariant),
        TokenPair(T.TEXT_ON_PRIMARY, TokenKind.Text, c.onPrimary, c.primary),
        TokenPair(T.TOAST_AND_POPOVER, TokenKind.Text, c.inverseOnSurface, c.inverseSurface),
        TokenPair(T.ERROR_TEXT, TokenKind.Text, c.error, c.surface),
    )

    private fun nonText(c: ColorScheme) = listOf(
        TokenPair(T.HANDLE_AND_GLYPH, TokenKind.NonText, c.onSurface, c.surface),
        TokenPair(T.GLYPH_ON_PANEL, TokenKind.NonText, c.onSurfaceVariant, c.surfaceVariant),
        TokenPair(T.STROKE_ON_SURFACE, TokenKind.NonText, c.outline, c.surface),
        TokenPair(T.STROKE_ON_PANEL, TokenKind.NonText, c.outline, c.surfaceVariant),
        TokenPair(T.RING_HIGHLIGHT, TokenKind.NonText, c.primary, c.surface),
        TokenPair(T.RING_INNER, TokenKind.NonText, c.onSurface, c.surface),
        TokenPair(T.RING_OUTER, TokenKind.NonText, c.inverseOnSurface, c.inverseSurface),
    )

    /** The pairs of [scheme] that fall below their bar. */
    fun failing(scheme: ColorScheme): List<TokenPair> = pairs(scheme).filterNot { it.passes }

    /** The pairs of [scheme] that fall below their bar, as "scheme: name 2.9:1 < 4.5:1" lines (empty when all pass). */
    fun failures(schemeName: String, scheme: ColorScheme): List<String> =
        failing(scheme).map { "$schemeName: ${it.name} ${formatted(it.ratio)}:1 < ${formatted(it.kind.bar)}:1" }

    private fun formatted(ratio: Double): String = ((ratio * RATIO_SCALE).toInt() / RATIO_SCALE).toString()

    private const val RATIO_SCALE = 100.0

    /** The high-contrast token set: pure black on pure white (or the reverse) with saturated accents of at least 7:1. */
    fun highContrast(dark: Boolean): ColorScheme = if (dark) HIGH_CONTRAST_DARK else HIGH_CONTRAST_LIGHT

    private val HIGH_CONTRAST_LIGHT = lightColorScheme(
        primary = Color(0xFF0000B3), onPrimary = Color.White, background = Color.White, onBackground = Color.Black,
        surface = Color.White, onSurface = Color.Black, surfaceVariant = Color.White, onSurfaceVariant = Color.Black,
        outline = Color.Black, error = Color(0xFF9B0000), inverseSurface = Color.Black, inverseOnSurface = Color.White,
    )

    private val HIGH_CONTRAST_DARK = darkColorScheme(
        primary = Color(0xFF9DC4FF), onPrimary = Color.Black, background = Color.Black, onBackground = Color.White,
        surface = Color.Black, onSurface = Color.White, surfaceVariant = Color.Black, onSurfaceVariant = Color.White,
        outline = Color.White, error = Color(0xFFFF9C9C), inverseSurface = Color.White, inverseOnSurface = Color.Black,
    )
}
