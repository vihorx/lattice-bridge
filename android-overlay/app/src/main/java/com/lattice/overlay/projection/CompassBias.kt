package com.lattice.overlay.projection

import android.content.SharedPreferences

class CompassBias(private val prefs: SharedPreferences) {
    companion object {
        private const val KEY_BIAS = "compass_bias_deg"
    }

    fun getBias(): Double = prefs.getFloat(KEY_BIAS, 0f).toDouble()

    fun setBias(biasDeg: Double) {
        prefs.edit().putFloat(KEY_BIAS, biasDeg.toFloat()).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_BIAS).apply()
    }

    fun apply(rawHeadingDeg: Double): Double {
        return (rawHeadingDeg + getBias() + 360.0) % 360.0
    }

    fun calibrate(
        tapXPx: Float, screenWidthPx: Int,
        phoneLat: Double, phoneLon: Double,
        droneLat: Double, droneLon: Double,
        phoneHeadingMeasured: Double, hfovDeg: Double
    ): Double {
        val bearingTrue = Bearing.bearingDeg(phoneLat, phoneLon, droneLat, droneLon)
        val tapHeadingOffset = ((tapXPx / screenWidthPx) - 0.5) * hfovDeg
        val phoneHeadingTrue = bearingTrue - tapHeadingOffset
        val biasDeg = Bearing.normalizeDeg(phoneHeadingTrue - phoneHeadingMeasured)
        setBias(biasDeg)
        return biasDeg
    }
}
