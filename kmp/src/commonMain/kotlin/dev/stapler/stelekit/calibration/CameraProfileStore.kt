// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.platform.Settings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Device-local store of learned [CameraProfile]s in [Settings] (ADR-001: a profile belongs to a camera, not
 * to a graph, so no database table). Unreadable or invalid entries are skipped, never fatal.
 */
class CameraProfileStore(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<CameraProfile> {
        if (!settings.containsKey(KEY)) return emptyList()
        val array = runCatching { json.parseToJsonElement(settings.getString(KEY, "")) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull(::parseOrNull)
    }

    /** Stores [profile], replacing any profile for the same camera, lens and framing. */
    fun save(profile: CameraProfile) {
        val kept = all().filterNot { it.id == profile.id || CameraProfiles.sameCamera(it, profile) }
        settings.putString(KEY, json.encodeToString(ListSerializer(CameraProfile.serializer()), kept + profile))
    }

    fun delete(id: String) {
        val kept = all().filterNot { it.id == id }
        settings.putString(KEY, json.encodeToString(ListSerializer(CameraProfile.serializer()), kept))
    }

    private fun parseOrNull(element: JsonElement): CameraProfile? =
        runCatching { json.decodeFromJsonElement(CameraProfile.serializer(), element) }.getOrNull()?.takeIf(::isValid)

    private fun isValid(p: CameraProfile): Boolean =
        p.profileVersion <= CameraProfiles.VERSION &&
            p.id.isNotBlank() &&
            p.k.isFinite() && kotlin.math.abs(p.k) <= RadialLens.MAX_ABS_K &&
            p.match.make.isNotBlank() && p.match.model.isNotBlank() &&
            p.match.focalLengthMm.isFinite() && p.match.focalLengthMm > 0.0 &&
            p.match.aspect in 0.0..1.0 && p.match.aspect > 0.0

    companion object {
        const val KEY = "measurement.cameraProfiles"
    }
}
