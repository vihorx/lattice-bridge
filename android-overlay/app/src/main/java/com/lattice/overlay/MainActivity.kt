package com.lattice.overlay

import android.Manifest
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lattice.overlay.network.TelemetryClient
import com.lattice.overlay.projection.GpsToScreen
import org.json.JSONObject

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var sockText: TextView
    private lateinit var overlayView: OverlayView

    private val rotationMatrix = FloatArray(9)
    private val cameraMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val cameraOrientation = FloatArray(3)
    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var headingAccuracy: Int = -1

    private var lastGps: Location? = null
    private var lastPayload: JSONObject? = null

    private lateinit var telemetryClient: TelemetryClient
    private lateinit var prefs: SharedPreferences
    private var lastDetCount = 0
    private var totalDetections = 0
    private var lastTelemetryMs = 0L

    private val screenW: Int by lazy { resources.displayMetrics.widthPixels }
    private val screenH: Int by lazy { resources.displayMetrics.heightPixels }

    companion object {
        private const val TAG = "Overlay"
        private const val PREFS_NAME = "lattice_overlay"
        private const val KEY_MAC_IP = "mac_ip"
        private const val DEFAULT_MAC_IP = "192.168.1.11"
        private const val SERVER_PORT = 5000
        private const val REQ_PERMISSIONS = 100

        private const val HFOV_DEG = 76.0
        private const val VFOV_DEG = 60.0
        private const val OPERATOR_HEIGHT_M = 1.7

        private val NEEDED = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        sockText = findViewById(R.id.sockText)
        overlayView = findViewById(R.id.overlayView)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val initialIp = prefs.getString(KEY_MAC_IP, DEFAULT_MAC_IP) ?: DEFAULT_MAC_IP
        telemetryClient = TelemetryClient("http://$initialIp:$SERVER_PORT")

        sockText.setOnClickListener { showIpDialog() }

        wireTelemetryClient()

        val missing = NEEDED.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            ActivityCompat.requestPermissions(this, NEEDED, REQ_PERMISSIONS)
        } else {
            startAll()
        }
    }

    private fun showIpDialog() {
        val currentIp = prefs.getString(KEY_MAC_IP, DEFAULT_MAC_IP) ?: DEFAULT_MAC_IP
        val edit = EditText(this).apply {
            setText(currentIp)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            hint = "e.g. 192.168.1.11 or 10.152.113.113"
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Mac server IP")
            .setMessage("Server listens on port $SERVER_PORT.\nTap SOCK badge to change later.")
            .setView(edit)
            .setPositiveButton("Save & reconnect") { _, _ ->
                val newIp = edit.text.toString().trim()
                if (newIp.isNotEmpty() && newIp != currentIp) {
                    prefs.edit().putString(KEY_MAC_IP, newIp).apply()
                    reconnectTo(newIp)
                    Toast.makeText(this, "Reconnecting to $newIp", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun reconnectTo(ip: String) {
        telemetryClient.disconnect()
        telemetryClient.setUrl("http://$ip:$SERVER_PORT")
        wireTelemetryClient()
    }

    private fun wireTelemetryClient() {
        sockText.text = "SOCK: connecting..."
        sockText.setTextColor(Color.YELLOW)

        telemetryClient.onConnect = {
            runOnUiThread {
                sockText.setTextColor(Color.GREEN)
                refreshSockText()
            }
        }
        telemetryClient.onDisconnect = {
            runOnUiThread {
                sockText.setTextColor(Color.RED)
                sockText.text = "SOCK: down\ntap to set IP"
            }
        }
        telemetryClient.onConnectError = { msg ->
            Log.e(TAG, "sock connect error: $msg")
            runOnUiThread {
                sockText.setTextColor(Color.RED)
                sockText.text = "SOCK: err\ntap to set IP"
            }
        }
        telemetryClient.onTelemetry = { payload ->
            lastPayload = payload
            val detsArr = payload.optJSONArray("detections")
            lastDetCount = detsArr?.length() ?: 0
            totalDetections += lastDetCount
            lastTelemetryMs = System.currentTimeMillis()

            val markers = buildMarkersFromPayload(payload)
            runOnUiThread {
                refreshSockText()
                overlayView.setMarkers(markers)
            }
        }

        telemetryClient.connect()
    }

    private fun buildMarkersFromPayload(payload: JSONObject): List<OverlayView.Marker> {
        val out = mutableListOf<OverlayView.Marker>()
        val phoneGps = lastGps ?: return out
        if (headingAccuracy < 0) return out

        val phoneLat = phoneGps.latitude
        val phoneLon = phoneGps.longitude
        val phoneAlt = OPERATOR_HEIGHT_M
        val phoneHeading = (Math.toDegrees(cameraOrientation[0].toDouble()) + 360.0) % 360.0
        val phonePitch = Math.toDegrees(cameraOrientation[1].toDouble())

        val droneLat = payload.optDouble("lat", Double.NaN)
        val droneLon = payload.optDouble("lon", Double.NaN)
        val droneAlt = payload.optDouble("alt", 0.0)
        if (!droneLat.isNaN() && !droneLon.isNaN()) {
            val proj = GpsToScreen.project(
                droneLat, droneLon, droneAlt,
                phoneLat, phoneLon, phoneAlt,
                phoneHeading, phonePitch,
                HFOV_DEG, VFOV_DEG,
                screenW, screenH
            )
            if (proj.inView) {
                out.add(
                    OverlayView.Marker(
                        xPx = proj.xPx,
                        yPx = proj.yPx,
                        label = "drone %.0fm".format(proj.distanceM),
                        radiusPx = 36f
                    )
                )
            }
        }

        val detsArr = payload.optJSONArray("detections")
        if (detsArr != null) {
            for (i in 0 until detsArr.length()) {
                val det = detsArr.optJSONObject(i) ?: continue
                val dLat = det.optDouble("lat", Double.NaN)
                val dLon = det.optDouble("lon", Double.NaN)
                if (dLat.isNaN() || dLon.isNaN()) continue
                val label = det.optString("label", "?")
                val proj = GpsToScreen.project(
                    dLat, dLon, 0.0,
                    phoneLat, phoneLon, phoneAlt,
                    phoneHeading, phonePitch,
                    HFOV_DEG, VFOV_DEG,
                    screenW, screenH
                )
                if (proj.inView) {
                    out.add(
                        OverlayView.Marker(
                            xPx = proj.xPx,
                            yPx = proj.yPx,
                            label = "%s %.0fm".format(label, proj.distanceM),
                            radiusPx = 24f
                        )
                    )
                }
            }
        }
        return out
    }

    private fun refreshSockText() {
        val ip = prefs.getString(KEY_MAC_IP, DEFAULT_MAC_IP) ?: DEFAULT_MAC_IP
        val connected = telemetryClient.isConnected()
        if (connected) {
            val ageMs = if (lastTelemetryMs > 0) System.currentTimeMillis() - lastTelemetryMs else -1L
            val ageStr = if (ageMs < 0) "no msgs" else "${ageMs}ms ago"
            sockText.text = "SOCK: $ip\nlast: $ageStr\nDET: $lastDetCount (Σ$totalDetections)"
        } else {
            sockText.text = "SOCK: $ip down\ntap to change"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startAll()
            } else {
                statusText.text = "Permissions denied. Restart and allow CAMERA + LOCATION."
            }
        }
    }

    private fun startAll() {
        startCamera()
        startSensors()
        startGps()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                Log.i(TAG, "Camera bound to lifecycle")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startSensors() {
        val rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rv?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        acc?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun startGps() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 500L, 0f, this)
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "GPS permission missing", se)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_MINUS_Z,
                    cameraMatrix
                )
                SensorManager.getOrientation(cameraMatrix, cameraOrientation)

                lastPayload?.let { payload ->
                    val markers = buildMarkersFromPayload(payload)
                    overlayView.setMarkers(markers)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> lastAccel = event.values.clone()
            Sensor.TYPE_GYROSCOPE -> lastGyro = event.values.clone()
        }
        updateUi()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            headingAccuracy = accuracy
        }
    }

    override fun onLocationChanged(location: Location) {
        lastGps = location
        updateUi()
    }

    private fun updateUi() {
        val rawHeading = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
        val camHeading = (Math.toDegrees(cameraOrientation[0].toDouble()) + 360.0) % 360.0
        val rollDeg = Math.toDegrees(orientation[2].toDouble())
        val camPitchDeg = Math.toDegrees(cameraOrientation[1].toDouble())

        val accStr = "%+6.2f %+6.2f %+6.2f".format(lastAccel[0], lastAccel[1], lastAccel[2])
        val gyroStr = "%+6.3f %+6.3f %+6.3f".format(lastGyro[0], lastGyro[1], lastGyro[2])

        val gpsStr = lastGps?.let {
            "%.6f, %.6f  acc %.1fm".format(it.latitude, it.longitude, it.accuracy)
        } ?: "no fix yet"

        val accLabel = when (headingAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNREL"
            else -> "n/a"
        }

        statusText.text = """
            H raw: %6.1f° [%s]
            H cam: %6.1f°
            P cam: %+6.1f°  R: %+6.1f°
            Acc:   %s
            Gyr:   %s
            GPS:   %s
        """.trimIndent().format(
            rawHeading, accLabel, camHeading,
            camPitchDeg, rollDeg, accStr, gyroStr, gpsStr
        )
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (se: SecurityException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        telemetryClient.disconnect()
    }
}
