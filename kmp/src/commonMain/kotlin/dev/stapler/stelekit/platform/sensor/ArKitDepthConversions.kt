package dev.stapler.stelekit.platform.sensor

/**
 * Pure conversion functions for ARKit depth/confidence values → DepthFrame contract.
 *
 * Kept in commonMain so they can be unit-tested in commonTest without iOS platform dependencies.
 *
 * DepthFrame contract:
 *   - depthMapMm  : values in millimetres (uint16 on ARCore; ARKit metres × 1000)
 *   - confidenceMap: raw [0–255] (ARCore raw byte; ARKit level × 85)
 */

/** ARKit gives depth in metres; convert to mm for the DepthFrame contract. */
internal fun arKitMetresToMm(metres: Float): Float = metres * 1000f

/**
 * Maps an ARConfidenceLevel integer (0=low, 1=medium, 2=high) to a raw [0–255] confidence byte.
 *
 * Output: 85, 170, 255 for levels 0, 1, 2 respectively.
 * Levels outside [0,2] are clamped before conversion.
 */
internal fun arKitConfidenceLevelToRaw(level: Int): Float = (level.coerceIn(0, 2) + 1) * 85f

/**
 * Default confidence value when no ARKit confidence map is available.
 * Approximates 85% of maximum (217 / 255 ≈ 0.851).
 */
internal const val ARKIT_CONFIDENCE_DEFAULT = 217f
