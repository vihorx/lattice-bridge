package com.lattice.overlay.projection

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class HeadingFilter(private val windowSize: Int = 15) {
    private val sinBuf = DoubleArray(windowSize)
    private val cosBuf = DoubleArray(windowSize)
    private var idx = 0
    private var filled = 0

    fun update(headingDeg: Double): Double {
        val rad = Math.toRadians(headingDeg)
        sinBuf[idx] = sin(rad)
        cosBuf[idx] = cos(rad)
        idx = (idx + 1) % windowSize
        if (filled < windowSize) filled++

        var sinSum = 0.0
        var cosSum = 0.0
        for (i in 0 until filled) {
            sinSum += sinBuf[i]
            cosSum += cosBuf[i]
        }
        val meanRad = atan2(sinSum / filled, cosSum / filled)
        return (Math.toDegrees(meanRad) + 360.0) % 360.0
    }

    fun reset() {
        idx = 0
        filled = 0
    }
}
