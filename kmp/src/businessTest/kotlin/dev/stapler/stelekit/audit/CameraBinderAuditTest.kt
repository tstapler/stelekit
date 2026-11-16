// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringAudit
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** I-E4-06 (Story 4.6): one owner of the camera selector literal, and no camera binder tears down another's use cases. */
class CameraBinderAuditTest {
    private val androidMain: Map<String, String> =
        WiringInputs.productionSources.filterKeys { it.startsWith("androidMain/") }

    private fun code(entry: Map.Entry<String, String>) = WiringAudit.stripCommentsAndStrings(entry.value)

    @Test
    fun defaultBackCameraLiteral_should_AppearOnlyInCameraXBackend() {
        assertTrue(androidMain.isNotEmpty(), "androidMain sources must be loaded")
        val holders = androidMain.filter { "DEFAULT_BACK_CAMERA" in code(it) }.keys.map { it.substringAfterLast('/') }
        assertEquals(listOf("CameraXBackend.kt"), holders)
    }

    @Test
    fun cameraBinders_should_NeverCallUnbindAll() {
        val binderFiles = setOf(
            "CameraXBackend.kt", "CameraXLensCatalog.kt", "AndroidCameraProvider.kt", "AndroidCameraPreviewBinder.kt",
            "AndroidCameraFrameSource.kt", "CameraViewfinderDialog.android.kt",
        )
        val found = androidMain.filter { it.key.substringAfterLast('/') in binderFiles }
        assertEquals(binderFiles, found.keys.map { it.substringAfterLast('/') }.toSet(), "every binder source must be audited")
        val offenders = found.filter { Regex("""\bunbindAll\s*\(""").containsMatchIn(code(it)) }.keys
        assertTrue(offenders.isEmpty(), "unbindAll() would tear down another binder's use cases: $offenders")
    }
}
