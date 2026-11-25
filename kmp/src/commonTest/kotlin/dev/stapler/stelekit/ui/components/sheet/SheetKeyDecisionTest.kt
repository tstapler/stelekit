// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.table.Chord
import dev.stapler.stelekit.table.GridCommand
import dev.stapler.stelekit.table.GridMode
import dev.stapler.stelekit.table.TableShortcutMap
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 5.3a: the key decision is a thin, total view of `TableShortcutMap` (ADR-009). */
class SheetKeyDecisionTest {
    @Test
    fun everyBinding_should_DecideItsOwnCommand_In_ItsMode() {
        TableShortcutMap.bindings.forEach { b ->
            assertEquals(
                SheetKeyDecision.Run(TableShortcutMap.lookup(b.chord, b.mode)!!),
                decideSheetKey(b.chord, b.mode, composing = false),
                "${b.mode} ${b.chord}",
            )
        }
    }

    @Test
    fun composing_should_PassEveryChordToTheIme() {
        TableShortcutMap.bindings.forEach { b ->
            assertEquals(SheetKeyDecision.PassToIme, decideSheetKey(b.chord, b.mode, composing = true), "${b.mode} ${b.chord}")
        }
    }

    @Test
    fun printableKey_should_TypeOnlyInNavigateWithoutModifier() {
        assertEquals(SheetKeyDecision.TypeChar("x"), decideSheetKey(Chord("X"), GridMode.Navigate, false, printable = "x"))
        assertEquals(SheetKeyDecision.NotHandled, decideSheetKey(Chord("Q", mod = true), GridMode.Navigate, false, printable = "q"))
        assertEquals(SheetKeyDecision.NotHandled, decideSheetKey(Chord("X"), GridMode.Edit, false, printable = "x"))
    }

    @Test
    fun editMode_should_ConsumeOnlyTheEditKeys() {
        assertEquals(SheetKeyDecision.NotHandled, decideSheetKey(Chord("ArrowDown"), GridMode.Edit, false))
        assertEquals(SheetKeyDecision.NotHandled, decideSheetKey(Chord("C", mod = true), GridMode.Edit, false))
        assertEquals(SheetKeyDecision.Run(GridCommand.InsertLineBreak), decideSheetKey(Chord("Enter", alt = true), GridMode.Edit, false))
    }
}
