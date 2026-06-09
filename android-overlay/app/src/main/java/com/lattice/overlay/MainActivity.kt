package com.lattice.overlay

import android.Manifest
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
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lattice.overlay.network.TelemetryClient

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

    private val telemetryClient = TelemetryClient(MAC_URL)
    private var lastDetCount = 0
    private var totalDetections = 0
    private var lastTelemetryMs = 0L

    companion object {
        private const val TAG = "Overlay"
        private const val MAC_URL = "http://10.152.113.113:5000"
        private const val REQ_PERMISSIONS = 100
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
                sockText.text = "SOCK: down"
            }
        }
        telemetryClient.onConnectError = { msg ->
            Log.e(TAG, "sock connect error: $msg")
            runOnUiThread {
                sockText.setTextColor(Color.RED)
                sockText.text = "SOCK: err"
            }
        }
        telemetryClient.onTelemetry = { payload ->
            val detsArr = payload.optJSONArray("detections")
            lastDetCount = detsArr?.length() ?: 0
            totalDetections += lastDetCount
            lastTelemetryMs = System.currentTimeMillis()
            Log.i(TAG, "telemetry: dets=$lastDetCount total=$totalDetections payload=$payload")
            runOnUiThread { refreshSockText() }
        }

        telemetryClient.connect()
    }

    private fun refreshSockText() {
        val connected = telemetryClient.isConnected()
        if (connected) {
            val ageMs = if (lastTelemetryMs > 0) System.currentTimeMillis() - lastTelemetryMs else -1L
            val ageStr = if (ageMs < 0) "no msgs" else "${ageMs}ms ago"
            sockText.text = "SOCK: OK\nlast: $ageStr\nDET: $lastDetCount (Σ$totalDetections)"
        } else {
            sockText.text = "SOCK: down"
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
        Log.i(TAG, "Sensors: rv=${rv != null} acc=${acc != null} gyro=${gyro != null}")
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
        val pitchDeg = Math.toDegrees(orientation[1].toDouble())
        val rollDeg = Math.toDegrees(orientation[2].toDouble())
        val camPitchDeg = Math.toDegrees(cameraOrientation[1].toDouble())

        val accStr = "%+6.2f %+6.2f %+6.2f".format(lastAccel[0], lastAccel[1], lastAccel[2])
        val gyroStr = "%+6.3f %+6.3f %+6.3f".format(lastGyro[0], lastGyro[1], lastGyro[2])

        val gpsStr = lastGps?.let {
            "%.6f, %.6f  acc %.1fm  alt %.1fm".format(it.latitude, it.longitude, it.accuracy, it.altitude)
        } ?: "no fix yet"

        val accLabel = when (headingAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "n/a"
        }

        statusText.text = """
            Heading raw:  %6.1f°  [%s]
            Heading cam:  %6.1f°  (AR portrait)
            Pitch raw:    %+6.1f°
            Pitch cam:    %+6.1f°
            Roll:         %+6.1f°
            Accel:        %s m/s²
            Gyro:         %s rad/s
            GPS:          %s
        """.trimIndent().format(
            rawHeading, accLabel, camHeading,
            pitchDeg, camPitchDeg, rollDeg,
            accStr, gyroStr, gpsStr
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
