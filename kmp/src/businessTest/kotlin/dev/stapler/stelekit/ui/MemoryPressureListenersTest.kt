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

    @Test
    fun listeners_should_DeliverLevelAndKeepNoArgOverload_When_Registered() {
        val listeners = MemoryPressureListeners()
        val levels = mutableListOf<MemoryPressureLevel>()
        var noArg = 0
        listeners.registerLevelListener { levels += it }
        listeners.register { noArg++ }

        listeners.notifyListeners(MemoryPressureLevel.RunningCritical)
        listeners.notifyListeners()

        assertEquals(listOf(MemoryPressureLevel.RunningCritical, MemoryPressureLevel.Moderate), levels)
        assertEquals(2, noArg)
    }

    @Test
    fun levelUnregister_removesOnlyThatListener() {
        val listeners = MemoryPressureListeners()
        var a = 0
        var b = 0
        val unregisterA = listeners.registerLevelListener { a++ }
        listeners.registerLevelListener { b++ }

        unregisterA()
        listeners.notifyListeners(MemoryPressureLevel.Complete)

        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test
    fun hostTrimMapping_should_MapAndroidTrimLevelsToTypedLevels() {
        assertEquals(MemoryPressureLevel.Moderate, MemoryPressureLevel.fromAndroidTrimLevel(5))
        assertEquals(MemoryPressureLevel.RunningLow, MemoryPressureLevel.fromAndroidTrimLevel(10))
        assertEquals(MemoryPressureLevel.RunningCritical, MemoryPressureLevel.fromAndroidTrimLevel(15))
        assertEquals(MemoryPressureLevel.Moderate, MemoryPressureLevel.fromAndroidTrimLevel(20))
        assertEquals(MemoryPressureLevel.Moderate, MemoryPressureLevel.fromAndroidTrimLevel(60))
        assertEquals(MemoryPressureLevel.Complete, MemoryPressureLevel.fromAndroidTrimLevel(80))
    }

    @Test
    fun viewModelOnMemoryPressure_should_ForwardTypedLevelToEveryExistingCaller() {
        val harness = StelekitViewModelTestHarness()
        val seen = mutableListOf<MemoryPressureLevel>()
        var legacy = 0
        harness.viewModel.memoryPressureListeners.registerLevelListener { seen += it }
        harness.viewModel.memoryPressureListeners.register { legacy++ }

        harness.viewModel.onMemoryPressure(MemoryPressureLevel.RunningCritical)
        harness.viewModel.onMemoryPressure()

        assertEquals(listOf(MemoryPressureLevel.RunningCritical, MemoryPressureLevel.Moderate), seen)
        assertEquals(2, legacy)
    }
}
