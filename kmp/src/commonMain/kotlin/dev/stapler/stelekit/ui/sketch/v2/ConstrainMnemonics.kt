// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

/**
 * The Constrain menu entries and their mnemonics (Story 3.6.1, UX-111, D-8): C Coincident, H Horizontal, V Vertical, L
 * Parallel, P Perpendicular, E Equal, T Tangent, S Symmetric, F Fixed, D Distance, A Angle, R Radius/Diameter and X Reference
 * toggle. The declaration order is the menu order.
 */
enum class ConstrainMenuItem(val mnemonic: Char, val label: String) {
    Coincident('C', "Coincident"),
    Horizontal('H', "Horizontal"),
    Vertical('V', "Vertical"),
    Parallel('L', "Parallel"),
    Perpendicular('P', "Perpendicular"),
    Equal('E', "Equal"),
    Tangent('T', "Tangent"),
    Symmetric('S', "Symmetric"),
    Fixed('F', "Fixed"),
    Distance('D', "Distance"),
    Angle('A', "Angle"),
    RadiusOrDiameter('R', "Radius/Diameter"),
    ReferenceToggle('X', "Reference"),
}

/**
 * Menu navigation, not canvas single-key shortcuts: the menu's own key handler calls [pick] while the menu is open, and
 * [KeyDispatcher] never maps these letters (C is the Arc tool on the canvas, H the Hand tool).
 */
object ConstrainMnemonics {
    /** The enabled item a typed character picks, or null when the character is no mnemonic or its item is disabled. */
    fun pick(typed: String, enabled: Set<ConstrainMenuItem>): ConstrainMenuItem? =
        ConstrainMenuItem.entries.firstOrNull { it.mnemonic.toString().equals(typed, ignoreCase = true) && it in enabled }

    /** Where focus lands when the menu opens: the first enabled item, or null when none is enabled. */
    fun firstFocus(enabled: Set<ConstrainMenuItem>): ConstrainMenuItem? = ConstrainMenuItem.entries.firstOrNull { it in enabled }
}
