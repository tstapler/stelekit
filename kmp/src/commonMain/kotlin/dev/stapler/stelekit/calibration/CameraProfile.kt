// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.ImageSensorData
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Which photos a [CameraProfile] applies to. Optional fields are only compared when both sides have them. */
@Serializable
data class CameraProfileMatch(
    val make: String,
    val model: String,
    val focalLengthMm: Double,
    val focalLengthToleranceMm: Double = CameraProfiles.DEFAULT_FOCAL_TOLERANCE_MM,
    val focalLength35mmEq: Double? = null,
    /** Short side divided by long side of the photo the profile was learned from (4:3 = 0.75). */
    val aspect: Double,
)

/** Where a profile came from; profiles are learned from saved JPEGs, never from Camera2 distortion data (ADR-001). */
@Serializable
data class CameraProfileSource(
    val kind: String = CameraProfiles.SOURCE_RULER_FIT,
    val tickCount: Int,
    val residualPercent: Double,
    val createdAtMs: Long,
)

/**
 * A learned radial-distortion coefficient for one camera/lens (ADR-001). [k] is the division-model
 * coefficient of [RadialLens], normalised by the half-diagonal so it holds at any resolution of the same aspect.
 */
@Serializable
data class CameraProfile(
    val profileVersion: Int = CameraProfiles.VERSION,
    val id: String,
    val label: String,
    val match: CameraProfileMatch,
    val k: Double,
    val source: CameraProfileSource,
)

/** Why a fit was not good enough to become a profile; [message] is user-facing. */
sealed class ProfileRejection(val message: String) {
    data object NotLensFitted :
        ProfileRejection("Profiles are learned from an automatic lens fit: choose Auto and mark the ruler.")

    data object NoCameraIdentity :
        ProfileRejection("This photo has no camera make, model and focal length, so a profile could not be matched later.")

    data class TooFewTicks(val have: Int) :
        ProfileRejection("Mark at least ${CameraProfiles.MIN_TICKS} ruler ticks to learn a profile (have $have).")

    data object SpanTooShort :
        ProfileRejection("The ruler spans less than half the photo; a short ruler cannot pin down lens distortion.")

    data object ResidualTooHigh :
        ProfileRejection("Ruler marks still disagree after correction; re-tap the off ticks before saving a profile.")
}

/** What [CameraProfiles.resolve] decided for a set of ticks. */
sealed interface ProfileOutcome {
    data object NoProfile : ProfileOutcome
    data class Applied(val profile: CameraProfile) : ProfileOutcome
    data class Rejected(val profile: CameraProfile) : ProfileOutcome
}

/** Matching, learning and self-check for [CameraProfile]s. Pure; storage is [CameraProfileStore]. */
object CameraProfiles {
    const val VERSION = 1
    const val SOURCE_RULER_FIT = "ruler-fit"
    const val DEFAULT_FOCAL_TOLERANCE_MM = 0.15

    const val MIN_TICKS = 3
    const val MIN_SPAN_FRACTION = 0.5
    const val MAX_RESIDUAL_PERCENT = 0.5
    private const val ASPECT_TOLERANCE = 0.01
    private const val EQ_TOLERANCE_MM = 1.0

    /** ADR-001 self-check: drop a profile that fits the ticks worse than no correction by more than 20%. */
    private const val SELF_CHECK_WORSENING = 1.2

    /** Below one pixel of leftover error the cost ratio is tap noise, not evidence against the profile. */
    private const val NOISE_FLOOR_PX = 1.0

    fun aspectOf(widthPx: Double, heightPx: Double): Double = min(widthPx, heightPx) / max(widthPx, heightPx)

    /**
     * The newest profile whose every field matches the photo's EXIF identity and pixel size, else null.
     * Never a nearest guess: missing make, model or focal length on the photo means no match.
     */
    fun match(profiles: List<CameraProfile>, sensor: ImageSensorData?, widthPx: Double, heightPx: Double): CameraProfile? {
        val make = sensor?.cameraMake?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val model = sensor.cameraModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val focal = sensor.focalLengthMm?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val aspect = aspectOf(widthPx, heightPx)
        return profiles.filter { p ->
            val m = p.match
            m.make.trim().equals(make, ignoreCase = true) &&
                m.model.trim().equals(model, ignoreCase = true) &&
                abs(m.focalLengthMm - focal) <= m.focalLengthToleranceMm &&
                abs(m.aspect - aspect) <= ASPECT_TOLERANCE &&
                eqCompatible(m.focalLength35mmEq, sensor.focalLength35mmEq)
        }.maxByOrNull { it.source.createdAtMs }
    }

    /** A digital zoom shows as a changed 35 mm-equivalent focal length even when the lens focal length is unchanged. */
    private fun eqCompatible(profileEq: Double?, photoEq: Double?): Boolean =
        profileEq == null || photoEq == null || abs(profileEq - photoEq) <= EQ_TOLERANCE_MM

    /** Builds a profile from an applied, auto-fitted ruler [calibration], or says why it is not trustworthy enough. */
    fun learn(
        calibration: Calibration,
        sensor: ImageSensorData?,
        widthPx: Double,
        heightPx: Double,
        id: String,
        nowMs: Long,
    ): Either<ProfileRejection, CameraProfile> {
        val lens = calibration.lens
        if (lens == null || !lens.kEstimated || lens.k == 0.0) return ProfileRejection.NotLensFitted.left()
        val make = sensor?.cameraMake?.trim()?.takeIf { it.isNotEmpty() }
        val model = sensor?.cameraModel?.trim()?.takeIf { it.isNotEmpty() }
        val focal = sensor?.focalLengthMm?.takeIf { it.isFinite() && it > 0.0 }
        if (make == null || model == null || focal == null) return ProfileRejection.NoCameraIdentity.left()
        if (lens.ticks.size < MIN_TICKS) return ProfileRejection.TooFewTicks(lens.ticks.size).left()
        val xs = lens.ticks.map { it.x * widthPx }
        val ys = lens.ticks.map { it.y * heightPx }
        if (hypot(xs.max() - xs.min(), ys.max() - ys.min()) < MIN_SPAN_FRACTION * max(widthPx, heightPx)) {
            return ProfileRejection.SpanTooShort.left()
        }
        if (lens.residualPercent > MAX_RESIDUAL_PERCENT) return ProfileRejection.ResidualTooHigh.left()
        return CameraProfile(
            id = id,
            label = "$make $model, ${focal}mm",
            match = CameraProfileMatch(
                make = make,
                model = model,
                focalLengthMm = focal,
                focalLength35mmEq = sensor.focalLength35mmEq,
                aspect = aspectOf(widthPx, heightPx),
            ),
            k = lens.k,
            source = CameraProfileSource(tickCount = lens.ticks.size, residualPercent = lens.residualPercent, createdAtMs = nowMs),
        ).right()
    }

    /** True when saving [a] should replace [b]: same camera, lens and framing. */
    fun sameCamera(a: CameraProfile, b: CameraProfile): Boolean =
        a.match.make.trim().equals(b.match.make.trim(), ignoreCase = true) &&
            a.match.model.trim().equals(b.match.model.trim(), ignoreCase = true) &&
            abs(a.match.focalLengthMm - b.match.focalLengthMm) <= a.match.focalLengthToleranceMm &&
            abs(a.match.aspect - b.match.aspect) <= ASPECT_TOLERANCE

    /**
     * Decides whether [profile] may seed k for [ticks]. With 3+ ticks, a profile that fits worse than k = 0
     * by more than 20% (and leaves a visible residual) is rejected, so a stale profile cannot hurt a photo.
     */
    fun resolve(profile: CameraProfile?, ticks: List<CalibrationTick>, widthPx: Double, heightPx: Double): ProfileOutcome {
        if (profile == null) return ProfileOutcome.NoProfile
        if (ticks.size < MIN_TICKS) return ProfileOutcome.Applied(profile)
        val withProfile = MultiPointCalibration.fitScale(ticks, profile.k, widthPx, heightPx)
            ?: return ProfileOutcome.Rejected(profile)
        val without = MultiPointCalibration.fitScale(ticks, 0.0, widthPx, heightPx) ?: return ProfileOutcome.Applied(profile)
        val worse = withProfile.cost > without.cost * SELF_CHECK_WORSENING && withProfile.maxResidualPx >= NOISE_FLOOR_PX
        return if (worse) ProfileOutcome.Rejected(profile) else ProfileOutcome.Applied(profile)
    }
}
