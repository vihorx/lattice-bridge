package com.lattice.bridge

import org.json.JSONArray
import org.json.JSONObject

data class Telemetry(
    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var alt: Double = 0.0,
    var acYaw: Double = 0.0,
    var acPitch: Double = 0.0,
    var acRoll: Double = 0.0,
    var gYaw: Double = 0.0,
    var gPitch: Double = 0.0,
    var gRoll: Double = 0.0,
    var focalMm: Double = 4.3,
    var speed: Double = 0.0,
    var sats: Int = 0,
    var battery: Int = 0,
    var hasFix: Boolean = false,
    var compassError: Boolean = false,
    var homeLat: Double = 0.0,
    var homeLon: Double = 0.0,
    var distanceFromHome: Double = 0.0,
    var detections: List<Detection> = emptyList()
) {
    // Sanitizes Double for JSON: NaN/Infinity -> 0.0, since JSON spec disallows them.
    // DJI MSDK returns NaN for GPS coords when no fix, attitude angles when IMU not ready, etc.
    private fun safe(v: Double): Double = if (v.isNaN() || v.isInfinite()) 0.0 else v

    fun toJson(): String = JSONObject().apply {
        put("lat", safe(lat))
        put("lon", safe(lon))
        put("alt", safe(alt))
        put("ac_yaw", safe(acYaw))
        put("ac_pitch", safe(acPitch))
        put("ac_roll", safe(acRoll))
        put("g_yaw", safe(gYaw))
        put("g_pitch", safe(gPitch))
        put("g_roll", safe(gRoll))
        put("focal_mm", safe(focalMm))
        put("speed", safe(speed))
        put("sats", sats)
        put("battery", battery)
        put("has_fix", hasFix)
        put("compass_error", compassError)
        put("home_lat", safe(homeLat))
        put("home_lon", safe(homeLon))
        put("distance_from_home", safe(distanceFromHome))

        // Stage 4A korak 2: YOLO detekcije, normalized 0..1 koordinate u original bitmap space-u.
        // Server skalira u FOV math i racuna lat/lon preko ground_intersection().
        val detsArray = JSONArray()
        for (d in detections) {
            detsArray.put(JSONObject().apply {
                put("nx", safe(d.nx.toDouble()))
                put("ny", safe(d.ny.toDouble()))
                put("nw", safe(d.nw.toDouble()))
                put("nh", safe(d.nh.toDouble()))
                put("conf", safe(d.confidence.toDouble()))
                put("label", d.label)
            })
        }
        put("detections", detsArray)
    }.toString()
}


// Stage 4A korak 3: Haversine distance za dva GPS para. R = 6371000m, vraca metre.
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val sinDPhi2 = Math.sin(dPhi / 2)
    val sinDLambda2 = Math.sin(dLambda / 2)
    val a = sinDPhi2 * sinDPhi2 + Math.cos(phi1) * Math.cos(phi2) * sinDLambda2 * sinDLambda2
    return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
