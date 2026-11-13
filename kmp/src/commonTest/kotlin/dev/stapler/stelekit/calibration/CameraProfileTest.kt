// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.LensCalibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.Settings
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Camera-profile learning, matching, self-check and storage. Distortion is synthetic: no real device numbers. */
class CameraProfileTest {
    private val w = 4000.0
    private val h = 3000.0
    private val halfDiag = hypot(w, h) / 2.0
    private val worldPxPerMeter = 4000.0 / 0.6
    private val sensor = ImageSensorData(
        focalLengthMm = 6.9,
        focalLength35mmEq = 24.0,
        cameraMake = "ExampleCo",
        cameraModel = "Phone 1",
    )

    /** Forward polynomial barrel (not the division model the code inverts), so a pass is not self-grading. */
    private fun distort(x: Double, y: Double, a: Double): NormalizedPoint {
        val dx = x - w / 2
        val dy = y - h / 2
        val f = 1 + a * (hypot(dx, dy) / halfDiag).let { it * it }
        return NormalizedPoint((w / 2 + dx * f) / w, (h / 2 + dy * f) / h)
    }

    private fun ticks(cx: Double, cy: Double, angle: Double, rulerM: Double, count: Int, a: Double): List<CalibrationTick> =
        (0 until count).map { i ->
            val frac = i.toDouble() / (count - 1)
            val d = (frac - 0.5) * rulerM * worldPxPerMeter
            val p = distort(cx + cos(angle) * d, cy + sin(angle) * d, a)
            CalibrationTick(p.x, p.y, frac * rulerM)
        }

    private fun spanning(a: Double, count: Int = 6) = ticks(2000.0, 1500.0, 0.3, 0.55, count, a)

    private fun autoCalibration() = assertNotNull(MultiPointCalibration.calibrate(spanning(-0.015), w, h, LensMode.Auto))

    private fun profile(
        id: String = "cam-1",
        make: String = "ExampleCo",
        model: String = "Phone 1",
        focal: Double = 6.9,
        eq: Double? = 24.0,
        aspect: Double = 0.75,
        k: Double = -0.012,
        createdAtMs: Long = 1L,
    ) = CameraProfile(
        id = id,
        label = "$make $model",
        match = CameraProfileMatch(make, model, focal, focalLength35mmEq = eq, aspect = aspect),
        k = k,
        source = CameraProfileSource(tickCount = 6, residualPercent = 0.1, createdAtMs = createdAtMs),
    )

    // ── learn ────────────────────────────────────────────────────────────────

    @Test
    fun learn_should_CreateProfileWithFittedK_When_AutoFitSpansFrameAndCameraIsKnown() {
        val cal = autoCalibration()
        val learned = CameraProfiles.learn(cal, sensor, w, h, "cam-1", 5L)
        val p = assertNotNull(learned.getOrNull())
        assertEquals(cal.lens!!.k, p.k)
        assertTrue(p.k < 0.0)
        assertEquals(0.75, p.match.aspect, 1e-9)
        assertEquals("ExampleCo", p.match.make)
    }

    @Test
    fun learn_should_Reject_When_KWasNotEstimatedByAuto() {
        val fixed = assertNotNull(MultiPointCalibration.calibrate(spanning(-0.015), w, h, LensMode.Fixed(-0.01)))
        assertEquals(ProfileRejection.NotLensFitted, CameraProfiles.learn(fixed, sensor, w, h, "x", 0L).leftOrNull())
        val plain = Calibration(method = CalibrationMethod.MANUAL_REFERENCE, pixelsPerMeter = 100.0)
        assertEquals(ProfileRejection.NotLensFitted, CameraProfiles.learn(plain, sensor, w, h, "x", 0L).leftOrNull())
    }

    @Test
    fun learn_should_Reject_When_CameraIdentityIsMissing() {
        val cal = autoCalibration()
        for (s in listOf(null, sensor.copy(cameraMake = null), sensor.copy(cameraModel = " "), sensor.copy(focalLengthMm = null))) {
            assertEquals(ProfileRejection.NoCameraIdentity, CameraProfiles.learn(cal, s, w, h, "x", 0L).leftOrNull())
        }
    }

    @Test
    fun learn_should_Reject_When_TicksAreFewShortOrDisagree() {
        val base = autoCalibration()
        val lens = base.lens!!
        val two = base.copy(lens = lens.copy(ticks = lens.ticks.take(2)))
        assertIs<ProfileRejection.TooFewTicks>(CameraProfiles.learn(two, sensor, w, h, "x", 0L).leftOrNull())
        val centre = lens.ticks.first()
        val short = base.copy(lens = lens.copy(ticks = List(3) { centre.copy(x = centre.x + it * 0.01) }))
        assertEquals(ProfileRejection.SpanTooShort, CameraProfiles.learn(short, sensor, w, h, "x", 0L).leftOrNull())
        val noisy = base.copy(lens = lens.copy(residualPercent = 2.0))
        assertEquals(ProfileRejection.ResidualTooHigh, CameraProfiles.learn(noisy, sensor, w, h, "x", 0L).leftOrNull())
    }

    // ── match ────────────────────────────────────────────────────────────────

    @Test
    fun match_should_FindProfile_When_EveryFieldAgrees_IgnoringCaseAndSmallFocalDrift() {
        val p = profile()
        val photo = sensor.copy(cameraMake = " exampleco ", focalLengthMm = 6.9 + 0.1)
        assertEquals(p, CameraProfiles.match(listOf(p), photo, 4000.0, 3000.0))
        assertEquals(p, CameraProfiles.match(listOf(p), photo, 3000.0, 4000.0), "portrait of the same aspect still matches")
        assertEquals(p, CameraProfiles.match(listOf(p), photo, 2000.0, 1500.0), "same aspect at lower resolution matches")
    }

    @Test
    fun match_should_ReturnNull_When_AnyFieldDiffers() {
        val p = listOf(profile())
        assertNull(CameraProfiles.match(p, sensor.copy(cameraMake = "Other"), w, h))
        assertNull(CameraProfiles.match(p, sensor.copy(cameraModel = "Phone 2"), w, h))
        assertNull(CameraProfiles.match(p, sensor.copy(focalLengthMm = 2.2), w, h), "different lens")
        assertNull(CameraProfiles.match(p, sensor.copy(focalLength35mmEq = 48.0), w, h), "digital zoom/crop")
        assertNull(CameraProfiles.match(p, sensor, 4000.0, 2250.0), "16:9 crop of a 4:3 profile")
    }

    @Test
    fun match_should_ReturnNull_When_PhotoHasNoCameraIdentity() {
        val p = listOf(profile())
        assertNull(CameraProfiles.match(p, null, w, h))
        assertNull(CameraProfiles.match(p, sensor.copy(focalLengthMm = null), w, h))
        assertNull(CameraProfiles.match(p, ImageSensorData(), w, h))
    }

    @Test
    fun match_should_PreferNewest_And_NotDependOnListOrder() = runTest {
        checkAll(Arb.int(0, 5)) { shift ->
            val profiles = listOf(
                profile(id = "a", createdAtMs = 1), profile(id = "b", createdAtMs = 9), profile(id = "c", createdAtMs = 4),
                profile(id = "d", model = "Phone 2", createdAtMs = 99),
            )
            val rotated = profiles.drop(shift % profiles.size) + profiles.take(shift % profiles.size)
            assertEquals("b", CameraProfiles.match(rotated, sensor, w, h)?.id)
        }
    }

    // ── resolve / calibrate ──────────────────────────────────────────────────

    @Test
    fun resolve_should_Apply_When_ProfileKMatchesTheTicks() {
        val learned = assertNotNull(CameraProfiles.learn(autoCalibration(), sensor, w, h, "cam-1", 1L).getOrNull())
        val otherPhoto = ticks(2200.0, 1300.0, -0.5, 0.5, 5, -0.015)
        assertIs<ProfileOutcome.Applied>(CameraProfiles.resolve(learned, otherPhoto, w, h))
    }

    @Test
    fun resolve_should_Reject_When_ProfileMakesTheRulerLessStraight() {
        val wrong = profile(k = 0.05)
        assertIs<ProfileOutcome.Rejected>(CameraProfiles.resolve(wrong, spanning(-0.015), w, h))
    }

    @Test
    fun resolve_should_ReportNoProfile_When_NoneGiven() {
        assertEquals(ProfileOutcome.NoProfile, CameraProfiles.resolve(null, spanning(-0.015), w, h))
    }

    @Test
    fun calibrate_should_SeedKFromProfile_And_SnapshotItsIdentity_When_ProfileApplies() {
        val learned = assertNotNull(CameraProfiles.learn(autoCalibration(), sensor, w, h, "cam-1", 1L).getOrNull())
        val cal = assertNotNull(MultiPointCalibration.calibrate(spanning(-0.015, 4), w, h, LensMode.Profile, learned))
        assertEquals(learned.k, cal.lens!!.k)
        assertEquals("cam-1", cal.lens!!.profileId)
        assertEquals(learned.label, cal.lens!!.profileLabel)
        assertFalse(cal.lens!!.kEstimated, "a profile-seeded k must not be re-learned as a new profile")
    }

    @Test
    fun calibrate_should_FallBackToPerPhotoFit_When_NoProfileOrSelfCheckFails() {
        val ticks = spanning(-0.015)
        val none = assertNotNull(MultiPointCalibration.calibrate(ticks, w, h, LensMode.Profile, null))
        assertTrue(none.lens!!.kEstimated && none.lens!!.k < 0.0 && none.lens!!.profileId == null)
        val rejected = assertNotNull(MultiPointCalibration.calibrate(ticks, w, h, LensMode.Profile, profile(k = 0.05)))
        assertTrue(rejected.lens!!.kEstimated && rejected.lens!!.k < 0.0 && rejected.lens!!.profileId == null)
    }

    @Test
    fun calibrate_should_IgnoreProfile_When_UserChoseAutoOrOff() {
        val p = profile(k = -0.03)
        val auto = assertNotNull(MultiPointCalibration.calibrate(spanning(-0.015), w, h, LensMode.Auto, p))
        assertNull(auto.lens!!.profileId)
        val off = assertNotNull(MultiPointCalibration.calibrate(spanning(-0.015), w, h, LensMode.Off, p))
        assertEquals(0.0, off.lens!!.k)
    }

    @Test
    fun profile_should_BeatPerPhotoFit_When_NewPhotoHasOnlyTwoTicks() {
        val a = -0.015
        val learned = assertNotNull(CameraProfiles.learn(autoCalibration(), sensor, w, h, "cam-1", 1L).getOrNull())
        val twoTicks = ticks(2000.0, 2600.0, 0.0, 0.30, 2, a)
        val auto = assertNotNull(MultiPointCalibration.calibrate(twoTicks, w, h, LensMode.Auto))
        assertEquals(0.0, auto.lens!!.k, "two ticks cannot estimate k, so Auto leaves it at 0")
        val seeded = assertNotNull(MultiPointCalibration.calibrate(twoTicks, w, h, LensMode.Profile, learned))

        fun knifeCm(cal: Calibration): Double {
            val ends = listOf(-0.5, 0.5).map {
                val d = it * 0.18 * worldPxPerMeter
                distort(2000.0 + d, 1100.0, a)
            }
            return LensMeasurement.distanceMeters(ends[0], ends[1], cal, w, h)!! * 100
        }
        val errAuto = abs(knifeCm(auto) - 18.0)
        val errSeeded = abs(knifeCm(seeded) - 18.0)
        assertTrue(errSeeded < errAuto, "profile-seeded error $errSeeded cm should beat uncorrected $errAuto cm")
    }

    // ── store ────────────────────────────────────────────────────────────────

    private class MapSettings : Settings {
        val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }

    @Test
    fun store_should_RoundTripProfiles() {
        val settings = MapSettings()
        assertEquals(emptyList(), CameraProfileStore(settings).all())
        val p = profile()
        CameraProfileStore(settings).save(p)
        assertEquals(listOf(p), CameraProfileStore(settings).all())
    }

    @Test
    fun store_should_ReplaceProfileForSameCamera_And_KeepOtherLenses() {
        val store = CameraProfileStore(MapSettings())
        store.save(profile(id = "old", k = -0.01))
        store.save(profile(id = "tele", focal = 9.0, eq = 70.0))
        store.save(profile(id = "new", k = -0.02, createdAtMs = 2))
        assertEquals(setOf("tele", "new"), store.all().map { it.id }.toSet())
        assertEquals(-0.02, store.all().first { it.id == "new" }.k)
        store.delete("tele")
        assertEquals(listOf("new"), store.all().map { it.id })
    }

    @Test
    fun store_should_SkipInvalidEntries_And_SurviveCorruptText() {
        val settings = MapSettings()
        val store = CameraProfileStore(settings)
        settings.putString(CameraProfileStore.KEY, "not json")
        assertEquals(emptyList(), store.all())
        store.save(profile(id = "good"))
        val huge = """{"id":"huge","label":"x","match":{"make":"a","model":"b","focalLengthMm":5.0,"aspect":0.75},"k":-5.0,""" +
            """"source":{"tickCount":3,"residualPercent":0.1,"createdAtMs":1}}"""
        val withBad = settings.getString(CameraProfileStore.KEY, "").removeSuffix("]") + ",$huge,{\"id\":\"bad\"}]"
        settings.putString(CameraProfileStore.KEY, withBad)
        assertEquals(listOf("good"), store.all().map { it.id })
    }

    @Test
    fun lensCalibration_should_StillDecode_When_ProfileFieldsAreAbsent() {
        val old = """{"k":-0.01,"ticks":[],"residualPercent":0.2,"kEstimated":true}"""
        val decoded = assertNotNull(dev.stapler.stelekit.model.LensCalibrationCodec.decode(old))
        assertNull(decoded.profileId)
        val withProfile = LensCalibration(k = -0.01, profileId = "cam-1", profileLabel = "L")
        assertEquals(withProfile, dev.stapler.stelekit.model.LensCalibrationCodec.decode(dev.stapler.stelekit.model.LensCalibrationCodec.encode(withProfile)))
    }
}
