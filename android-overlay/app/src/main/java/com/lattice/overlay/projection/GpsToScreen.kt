package com.lattice.overlay.projection

import kotlin.math.abs
import kotlin.math.atan

data class Projection(
    val xPx: Float,
    val yPx: Float,
    val distanceM: Double,
    val inView: Boolean,
    val headingOffsetDeg: Double,
    val pitchOffsetDeg: Double
)

object GpsToScreen {
    fun project(
        detLat: Double, detLon: Double, detAlt: Double,
        phoneLat: Double, phoneLon: Double, phoneAlt: Double,
        phoneHeadingDeg: Double, phonePitchDeg: Double,
        hfovDeg: Double, vfovDeg: Double,
        screenWidthPx: Int, screenHeightPx: Int
    ): Projection {
        val horizDist = Bearing.haversineMeters(phoneLat, phoneLon, detLat, detLon)
        val bearing = Bearing.bearingDeg(phoneLat, phoneLon, detLat, detLon)

        val heightDiff = detAlt - phoneAlt
        val verticalAngleDeg = if (horizDist > 0.5) {
            Math.toDegrees(atan(heightDiff / horizDist))
        } else 0.0

        val headingOffset = Bearing.normalizeDeg(bearing - phoneHeadingDeg)
        val pitchOffset = verticalAngleDeg - phonePitchDeg

        val inView = abs(headingOffset) < hfovDeg / 2.0 &&
                     abs(pitchOffset) < vfovDeg / 2.0

        val screenXNorm = 0.5 + (headingOffset / hfovDeg)
        val screenYNorm = 0.5 - (pitchOffset / vfovDeg)

        return Projection(
            xPx = (screenXNorm * screenWidthPx).toFloat(),
            yPx = (screenYNorm * screenHeightPx).toFloat(),
            distanceM = horizDist,
            inView = inView,
            headingOffsetDeg = headingOffset,
            pitchOffsetDeg = pitchOffset
        )
    }
}
