// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryPressureListenersTest {
    @Test
    fun notifyListeners_invokesEveryRegisteredListener() {
        val listeners = MemoryPressureListeners()
        var a = 0
        var b = 0
        listeners.register { a++ }
        listeners.register { b++ }

        listeners.notifyListeners()

        assertEquals(1, a)
        assertEquals(1, b)
    }

    @Test
    fun unregister_removesOnlyThatListener() {
        val listeners = MemoryPressureListeners()
        var a = 0
        var b = 0
        val unregisterA = listeners.register { a++ }
        listeners.register { b++ }

        unregisterA()
        listeners.notifyListeners()

        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test
    fun throwingListener_doesNotStopTheOthers() {
        val listeners = MemoryPressureListeners()
        var reached = 0
        listeners.register { error("boom") }
        listeners.register { reached++ }

        listeners.notifyListeners()

        assertEquals(1, reached)
    }

    @Test
    fun viewModelOnMemoryPressure_notifiesRegisteredListeners() {
        val harness = StelekitViewModelTestHarness()
        var notified = 0
        harness.viewModel.memoryPressureListeners.register { notified++ }

        harness.viewModel.onMemoryPressure()

        assertEquals(1, notified, "the ViewModel's one added line must fan out to the registry")
    }
}
