package com.lattice.unified

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lattice.unified.bridge.BridgeService
import com.lattice.unified.bridge.BridgeState
import com.lattice.unified.overlay.OverlayView
import com.lattice.unified.overlay.network.TelemetryClient
import com.lattice.unified.overlay.projection.CompassBias
import com.lattice.unified.overlay.projection.GpsToScreen
import com.lattice.unified.overlay.projection.HeadingFilter
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class AppMode { OVERLAY, BRIDGE }

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var previewView: PreviewView
    private lateinit var droneVideoView: TextureView
    private lateinit var statusText: TextView
    private lateinit var sockText: TextView
    private lateinit var bridgeText: TextView
    private lateinit var modeBtn: Button
    private lateinit var overlayView: OverlayView
    private lateinit var calBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var biasText: TextView
    private lateinit var calBanner: TextView
    private lateinit var overlayBottomBar: LinearLayout
    private lateinit var droneHud: LinearLayout
    private lateinit var hudBat: TextView
    private lateinit var hudSats: TextView
    private lateinit var hudAlt: TextView
    private lateinit var hudSpeed: TextView
    private lateinit var hudDist: TextView
    private lateinit var compassWarning: TextView
    private lateinit var bridgeControls: LinearLayout
    private lateinit var gimbalSeek: SeekBar
    private lateinit var gimbalPitchLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView

    private val rotationMatrix = FloatArray(9)
    private val cameraMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val cameraOrientation = FloatArray(3)
    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var headingAccuracy: Int = -1
    private var filteredHeading: Double = 0.0

    private var lastGps: Location? = null
    private var lastPayload: JSONObject? = null

    private lateinit var telemetryClient: TelemetryClient
    private lateinit var prefs: SharedPreferences
    private lateinit var compassBias: CompassBias
    private lateinit var headingFilter: HeadingFilter
    private var lastDetCount = 0
    private var totalDetections = 0
    private var lastTelemetryMs = 0L
    private var calibrationMode = false

    private var bridgeService: BridgeService? = null
    private var bridgeBound = false
    private var currentMode: AppMode = AppMode.OVERLAY

    private val screenW: Int get() = resources.displayMetrics.widthPixels
    private val screenH: Int get() = resources.displayMetrics.heightPixels

    private val ZOOM_MIN_TENTHS = 240
    private val ZOOM_MAX_TENTHS = 480

    private val redrawHandler = Handler(Looper.getMainLooper())
    private var redrawScheduled = false
    private var permsGranted = false
    private var lastHudUpdateMs = 0L

    companion object {
        private const val TAG = "Overlay"
        private const val PREFS_NAME = "lattice_overlay"
        private const val KEY_MAC_IP = "mac_ip"
        private const val KEY_MODE = "app_mode"
        private const val DEFAULT_MAC_IP = "192.168.1.11"
        private const val SERVER_PORT = 5000
        private const val REQ_PERMISSIONS = 100
        private const val REDRAW_INTERVAL_MS = 16L
        private const val HUD_INTERVAL_MS = 33L

        private const val HFOV_DEG = 76.0
        private const val VFOV_DEG = 60.0
        private const val OPERATOR_HEIGHT_M = 1.7

        private val NEEDED = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private val bridgeConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = service as BridgeService.LocalBinder
            bridgeService = b.getService()
            bridgeBound = true
            Log.i(TAG, "Bridge service bound")
            observeBridge()
            if (currentMode == AppMode.BRIDGE && droneVideoView.isAvailable) {
                bridgeService?.setVideoSurface(
                    droneVideoView.surfaceTexture,
                    droneVideoView.width, droneVideoView.height
                )
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            bridgeBound = false
            Log.w(TAG, "Bridge service disconnected")
        }
    }

    private val redrawTick = object : Runnable {
        override fun run() {
            if (!redrawScheduled) return
            try {
                refreshSockText()
                if (currentMode == AppMode.OVERLAY) {
                    lastPayload?.let { payload ->
                        val markers = buildMarkersFromPayload(payload)
                        overlayView.setMarkers(markers)
                    }
                    updateUi()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "redrawTick: ${t.message}", t)
            }
            redrawHandler.postDelayed(this, REDRAW_INTERVAL_MS)
        }
    }

    private fun startRedrawTick() {
        if (redrawScheduled) return
        redrawScheduled = true
        redrawHandler.post(redrawTick)
    }

    private fun stopRedrawTick() {
        redrawScheduled = false
        redrawHandler.removeCallbacks(redrawTick)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        droneVideoView = findViewById(R.id.droneVideoView)
        statusText = findViewById(R.id.statusText)
        sockText = findViewById(R.id.sockText)
        bridgeText = findViewById(R.id.bridgeText)
        modeBtn = findViewById(R.id.modeBtn)
        overlayView = findViewById(R.id.overlayView)
        calBtn = findViewById(R.id.calBtn)
        resetBtn = findViewById(R.id.resetBtn)
        biasText = findViewById(R.id.biasText)
        calBanner = findViewById(R.id.calBanner)
        overlayBottomBar = findViewById(R.id.overlayBottomBar)
        droneHud = findViewById(R.id.droneHud)
        hudBat = findViewById(R.id.hudBat)
        hudSats = findViewById(R.id.hudSats)
        hudAlt = findViewById(R.id.hudAlt)
        hudSpeed = findViewById(R.id.hudSpeed)
        hudDist = findViewById(R.id.hudDist)
        compassWarning = findViewById(R.id.compassWarning)
        bridgeControls = findViewById(R.id.bridgeControls)
        gimbalSeek = findViewById(R.id.gimbalSeek)
        gimbalPitchLabel = findViewById(R.id.gimbalPitchLabel)
        zoomSeek = findViewById(R.id.zoomSeek)
        zoomLabel = findViewById(R.id.zoomLabel)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        compassBias = CompassBias(prefs)
        headingFilter = HeadingFilter()

        val initialIp = prefs.getString(KEY_MAC_IP, DEFAULT_MAC_IP) ?: DEFAULT_MAC_IP
        telemetryClient = TelemetryClient("http://$initialIp:$SERVER_PORT")

        currentMode = try {
            AppMode.valueOf(prefs.getString(KEY_MODE, AppMode.OVERLAY.name) ?: AppMode.OVERLAY.name)
        } catch (e: Exception) { AppMode.OVERLAY }

        sockText.setOnClickListener { showIpDialog() }
        calBtn.setOnClickListener { onCalButtonClick() }
        resetBtn.setOnClickListener { onResetButtonClick() }
        modeBtn.setOnClickListener { toggleMode() }

        overlayView.setOnTouchListener { _, event ->
            if (calibrationMode && event.action == MotionEvent.ACTION_DOWN) {
                performCalibration(event.x, event.y)
                true
            } else false
        }

        setupDroneVideoSurface()
        setupGimbalSlider()
        setupZoomSlider()
        updateBiasDisplay()
        applyRequestedOrientation()
        applyModeVisibility()
        wireTelemetryClient()

        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, bridgeConn, Context.BIND_AUTO_CREATE)

        val missing = NEEDED.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            ActivityCompat.requestPermissions(this, NEEDED, REQ_PERMISSIONS)
        } else {
            startAll()
        }
    }

    private fun toggleMode() {
        val prev = currentMode
        try {
            currentMode = if (currentMode == AppMode.OVERLAY) AppMode.BRIDGE else AppMode.OVERLAY
            Log.i(TAG, "toggleMode: $prev -> $currentMode")
            prefs.edit().putString(KEY_MODE, currentMode.name).apply()
            applyRequestedOrientation()
            applyModeVisibility()
            modeBtn.bringToFront()
            window.decorView.requestLayout()

            if (currentMode == AppMode.BRIDGE) {
                if (droneVideoView.isAvailable) {
                    bridgeService?.setVideoSurface(
                        droneVideoView.surfaceTexture,
                        droneVideoView.width, droneVideoView.height
                    )
                }
            } else {
                bridgeService?.setVideoSurface(null, 0, 0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "toggleMode FAILED at $prev -> $currentMode: ${t.message}", t)
        }
    }

    private fun applyRequestedOrientation() {
        requestedOrientation = if (currentMode == AppMode.BRIDGE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}, mode=$currentMode")
        try {
            applyModeVisibility()
            modeBtn.bringToFront()
        } catch (t: Throwable) {
            Log.e(TAG, "onConfigurationChanged reapply FAILED: ${t.message}", t)
        }
    }

    private fun applyModeVisibility() {
        val overlay = currentMode == AppMode.OVERLAY
        modeBtn.text = if (overlay) "OVERLAY" else "BRIDGE"

        droneVideoView.visibility = if (overlay) View.GONE else View.VISIBLE
        overlayView.visibility = if (overlay) View.VISIBLE else View.GONE
        statusText.visibility = if (overlay) View.VISIBLE else View.GONE
        overlayBottomBar.visibility = if (overlay) View.VISIBLE else View.GONE

        droneHud.visibility = if (overlay) View.GONE else View.VISIBLE
        bridgeControls.visibility = if (overlay) View.GONE else View.VISIBLE
        if (overlay) compassWarning.visibility = View.GONE
    }

    private fun setupDroneVideoSurface() {
        droneVideoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                Log.i(TAG, "drone surface ready: ${w}x${h}")
                if (currentMode == AppMode.BRIDGE) {
                    bridgeService?.setVideoSurface(s, w, h)
                }
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                Log.i(TAG, "drone surface resized: ${w}x${h}")
                if (currentMode == AppMode.BRIDGE) {
                    bridgeService?.updateSurfaceSize(w, h)
                }
            }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                Log.i(TAG, "drone surface destroyed")
                bridgeService?.setVideoSurface(null, 0, 0)
                return true
            }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun setupGimbalSlider() {
        gimbalSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = (progress - 90).toFloat()
                gimbalPitchLabel.text = "${pitch.toInt()}°"
                if (fromUser) bridgeService?.applyGimbalPitch(pitch)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupZoomSlider() {
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val equivTenths = ZOOM_MIN_TENTHS + progress
                val zoomX = equivTenths / 240.0
                zoomLabel.text = "%.1fx".format(zoomX)
                if (fromUser) bridgeService?.applyZoom(equivTenths)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun observeBridge() {
        val svc = bridgeService ?: return
        lifecycleScope.launch {
            svc.state.collect { st ->
                bridgeText.text = when (st) {
                    BridgeState.STARTING -> "Bridge: starting"
                    BridgeState.STUB -> "Bridge: stub"
                    BridgeState.DRONE_OK -> "Bridge: drone OK"
                    BridgeState.NO_RC -> "Bridge: no RC"
                    BridgeState.ERROR -> "Bridge: error"
                }
                bridgeText.setTextColor(when (st) {
                    BridgeState.DRONE_OK -> Color.GREEN
                    BridgeState.NO_RC, BridgeState.ERROR -> Color.RED
                    else -> Color.YELLOW
                })
            }
        }
        lifecycleScope.launch {
            svc.telemetry.collect { t ->
                if (currentMode != AppMode.BRIDGE) return@collect
                val now = System.currentTimeMillis()
                if (now - lastHudUpdateMs < HUD_INTERVAL_MS) return@collect
                lastHudUpdateMs = now
                hudBat.text = "BAT ${t.battery}%"
                hudSats.text = "SAT ${t.sats}"
                hudAlt.text = "ALT %.1f m".format(t.alt)
                hudSpeed.text = "SPD %.1f m/s".format(t.speed)
                hudDist.text = "DIST %.1f m".format(t.distanceFromHome)
                compassWarning.visibility = if (t.compassError) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onCalButtonClick() {
        if (calibrationMode) { exitCalibrationMode(); return }
        val payload = lastPayload
        if (payload == null || lastGps == null) {
            Toast.makeText(this, "Need drone telemetry + phone GPS first", Toast.LENGTH_SHORT).show()
            return
        }
        val droneLat = payload.optDouble("lat", Double.NaN)
        val droneLon = payload.optDouble("lon", Double.NaN)
        if (droneLat.isNaN() || droneLon.isNaN()) {
            Toast.makeText(this, "Payload has no drone lat/lon", Toast.LENGTH_SHORT).show()
            return
        }
        enterCalibrationMode()
    }

    private fun onResetButtonClick() {
        compassBias.reset()
        updateBiasDisplay()
        Toast.makeText(this, "Compass bias reset to 0", Toast.LENGTH_SHORT).show()
    }

    private fun enterCalibrationMode() {
        calibrationMode = true
        calBanner.visibility = View.VISIBLE
        calBtn.text = "CANCEL"
    }

    private fun exitCalibrationMode() {
        calibrationMode = false
        calBanner.visibility = View.GONE
        calBtn.text = "CAL DRONE"
    }

    private fun performCalibration(tapX: Float, tapY: Float) {
        val payload = lastPayload ?: return
        val phoneGps = lastGps ?: return
        val droneLat = payload.optDouble("lat", Double.NaN)
        val droneLon = payload.optDouble("lon", Double.NaN)
        if (droneLat.isNaN() || droneLon.isNaN()) return

        val phoneHeadingMeasured = filteredHeading
        val newBias = compassBias.calibrate(
            tapX, screenW,
            phoneGps.latitude, phoneGps.longitude,
            droneLat, droneLon,
            phoneHeadingMeasured, HFOV_DEG
        )

        exitCalibrationMode()
        updateBiasDisplay()
        Toast.makeText(this, "Calibrated. Bias: %+.1f°".format(newBias), Toast.LENGTH_LONG).show()
    }

    private fun updateBiasDisplay() {
        biasText.text = "bias: %+.1f°".format(compassBias.getBias())
    }

    private fun showIpDialog() {
        val currentIp = prefs.getString(KEY_MAC_IP, DEFAULT_MAC_IP) ?: DEFAULT_MAC_IP
        val edit = EditText(this).apply {
            setText(currentIp)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            hint = "e.g. 192.168.1.11 or 10.x.y.z"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Mac server IP")
            .setMessage("SocketIO :$SERVER_PORT  +  UDP :14550")
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
        bridgeService?.setHost(ip)
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
            runOnUiThread {
                lastPayload = payload
                val detsArr = payload.optJSONArray("detections")
                lastDetCount = detsArr?.length() ?: 0
                totalDetections += lastDetCount
                lastTelemetryMs = System.currentTimeMillis()
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
        val phoneHeading = compassBias.apply(filteredHeading)
        val phonePitch = Math.toDegrees(cameraOrientation[1].toDouble())

        val droneLat = payload.optDouble("lat", Double.NaN)
        val droneLon = payload.optDouble("lon", Double.NaN)
        val droneAlt = payload.optDouble("alt", 0.0)
        if (!droneLat.isNaN() && !droneLon.isNaN()) {
            val proj = GpsToScreen.project(
                droneLat, droneLon, droneAlt,
                phoneLat, phoneLon, phoneAlt,
                phoneHeading, phonePitch,
                HFOV_DEG, VFOV_DEG, screenW, screenH
            )
            if (proj.inView) {
                out.add(OverlayView.Marker(
                    xPx = proj.xPx, yPx = proj.yPx,
                    label = "drone %.0fm".format(proj.distanceM),
                    radiusPx = 36f
                ))
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
                    HFOV_DEG, VFOV_DEG, screenW, screenH
                )
                if (proj.inView) {
                    out.add(OverlayView.Marker(
                        xPx = proj.xPx, yPx = proj.yPx,
                        label = "%s %.0fm".format(label, proj.distanceM),
                        radiusPx = 24f
                    ))
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
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
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
        permsGranted = true
        startCamera()
        startSensors()
        startGps()
        startRedrawTick()
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
        acc?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
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
                    rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_MINUS_Z, cameraMatrix
                )
                SensorManager.getOrientation(cameraMatrix, cameraOrientation)
                val rawCam = (Math.toDegrees(cameraOrientation[0].toDouble()) + 360.0) % 360.0
                filteredHeading = headingFilter.update(rawCam)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel[0] = event.values[0]
                lastAccel[1] = event.values[1]
                lastAccel[2] = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro[0] = event.values[0]
                lastGyro[1] = event.values[1]
                lastGyro[2] = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) headingAccuracy = accuracy
    }

    override fun onLocationChanged(location: Location) {
        lastGps = location
    }

    private fun updateUi() {
        val rawHeading = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
        val correctedHeading = compassBias.apply(filteredHeading)
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
            H raw:    %6.1f° [%s]
            H filt:   %6.1f°
            H corr:   %6.1f°
            P cam:    %+6.1f°  R: %+6.1f°
            Acc:      %s
            Gyr:      %s
            GPS:      %s
        """.trimIndent().format(
            rawHeading, accLabel, filteredHeading, correctedHeading,
            camPitchDeg, rollDeg, accStr, gyroStr, gpsStr
        )
    }

    override fun onResume() {
        super.onResume()
        if (permsGranted) {
            startSensors()
            startGps()
            startRedrawTick()
        }
    }

    override fun onPause() {
        super.onPause()
        stopRedrawTick()
        sensorManager.unregisterListener(this)
        try { locationManager.removeUpdates(this) } catch (se: SecurityException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRedrawTick()
        telemetryClient.disconnect()
        if (bridgeBound) {
            unbindService(bridgeConn)
            bridgeBound = false
        }
    }
}
