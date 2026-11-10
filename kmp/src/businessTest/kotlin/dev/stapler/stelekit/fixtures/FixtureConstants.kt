package dev.stapler.stelekit.fixtures

import dev.stapler.stelekit.units.Rational

/** Single source of every closet fixture value (`FC.*` in the home-sketch plan); inches unless noted. */
object FC {
    const val scaleElevation = 11.06382978723404
    const val scaleSection = 34.22885572139303

    val opening = Rational(136)
    val zoneLeftLong = Rational(535, 16) // 33 7/16
    val zoneLeftShort = Rational(45, 2) // 22 1/2
    val zoneShelves = Rational(387, 16) // 24 3/16
    val zoneRightShort = Rational(45, 2)
    val zoneRightLong = Rational(267, 8) // 33 3/8
    val zoneMiddle = Rational(1107, 16) // 69 3/16

    val zoneLeftLongTableMutated = Rational(267, 8) // 33 3/8
    val cabinetHeightA = Rational(693, 16) // 43 5/16
    val cabinetHeightB = Rational(327, 8) // 40 7/8
    val usableCabinetHeight = Rational(185, 8) // 23 1/8
    val shelfPitch = Rational(41, 5) // 8.2
    val carrierCounts = listOf(8, 10, 12)

    const val lossyDecimalA = "43.31"
    const val fractionA = "43 5/16"
    const val lossyDecimalB = "23.12"
    const val fractionB = "23 1/8"
    val labelPrecision = Rational(1, 200)

    const val rodLabelMutated = "37\" Rod"
    val hangA = Rational(39)
    val hangSum = Rational(497, 8) // 62 1/8
    val studs: List<Rational> = (0..8).map { Rational(3, 2).add(Rational(16L * it)).getOrNull()!! } // 1.5 .. 129.5
    val rodClearances = listOf(5, 3, 8)
}
