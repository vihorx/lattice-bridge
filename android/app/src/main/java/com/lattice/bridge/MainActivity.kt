package com.lattice.bridge

import android.Manifest
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.camera.SettingsDefinitions
import dji.common.flightcontroller.CompassCalibrationState
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager

class MainActivity : AppCompatActivity() {

    private val sender = UdpSender()
    private val tel = Telemetry()
    private lateinit var statusView: TextView
    private lateinit var prefs: SharedPreferences
    private var listenersAttached = false

    private lateinit var hudBat: TextView
    private lateinit var hudSats: TextView
    private lateinit var hudAlt: TextView
    private lateinit var hudSpeed: TextView
    private lateinit var hudDist: TextView
    private lateinit var compassWarning: TextView
    private lateinit var videoPlaceholder: TextView
    private lateinit var videoSurface: TextureView
    private lateinit var gimbalSeek: SeekBar
    private lateinit var gimbalPitchLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var debugBox: View
    private lateinit var toggleDebug: Button

    private var codecManager: DJICodecManager? = null
    private var videoDataListener: VideoFeeder.VideoDataListener? = null
    private var pendingSurface: SurfaceTexture? = null
    private var pendingW: Int = 0
    private var pendingH: Int = 0
    private var firstFrameLogged = false
    private var firstBitmapLogged = false
    private var frameCount = 0L
    private var yolo: Yolo? = null
    private var cameraRef: dji.sdk.camera.Camera? = null
    private var exposureControlsInitialized = false
    private var compassCalibrating = false
    private var compassControlsInitialized = false
    private var isoIdx = 0
    private var shutIdx = 0
    private var expMode = SettingsDefinitions.ExposureMode.PROGRAM
    private val isoList = listOf(
        "AUTO" to SettingsDefinitions.ISO.AUTO,
        "100" to SettingsDefinitions.ISO.ISO_100,
        "200" to SettingsDefinitions.ISO.ISO_200,
        "400" to SettingsDefinitions.ISO.ISO_400,
        "800" to SettingsDefinitions.ISO.ISO_800,
        "1600" to SettingsDefinitions.ISO.ISO_1600,
        "3200" to SettingsDefinitions.ISO.ISO_3200
    )
    private val shutList = listOf(
        "1/8000" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_8000,
        "1/4000" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_4000,
        "1/2000" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_2000,
        "1/1000" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_1000,
        "1/500" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_500,
        "1/240" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_240,
        "1/120" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_120,
        "1/60" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_60,
        "1/30" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_30,
        "1/15" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_15,
        "1/8" to SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_8
    )

    private val ZOOM_MIN_TENTHS = 240
    private val ZOOM_MAX_TENTHS = 480

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingGimbalPitch: Float? = null
    private var pendingZoomTenths: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immersive fullscreen — pre nego sto se layout napravi, kaze sistemu da ne pravi insete
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        enterImmersiveMode()
        prefs = getSharedPreferences("lattice", MODE_PRIVATE)

        statusView = findViewById(R.id.statusView)
        statusView.movementMethod = ScrollingMovementMethod()

        hudBat = findViewById(R.id.hudBat)
        hudSats = findViewById(R.id.hudSats)
        hudAlt = findViewById(R.id.hudAlt)
        hudSpeed = findViewById(R.id.hudSpeed)
        hudDist = findViewById(R.id.hudDist)
        compassWarning = findViewById(R.id.compassWarning)
        videoPlaceholder = findViewById(R.id.videoPlaceholder)
        videoSurface = findViewById(R.id.videoSurface)
        gimbalSeek = findViewById(R.id.gimbalSeek)
        gimbalPitchLabel = findViewById(R.id.gimbalPitchLabel)
        zoomSeek = findViewById(R.id.zoomSeek)
        zoomLabel = findViewById(R.id.zoomLabel)
        debugBox = findViewById(R.id.debugBox)
        toggleDebug = findViewById(R.id.toggleDebug)

        val hostInput: EditText = findViewById(R.id.hostInput)
        hostInput.setText(prefs.getString("host", "192.168.1.11"))

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            val host = hostInput.text.toString().trim()
            prefs.edit().putString("host", host).apply()
            sender.start(host)
            log("UDP sender started -> $host:14550")
        }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            sender.stop()
            log("UDP sender stopped")
        }

        toggleDebug.setOnClickListener {
            if (statusView.visibility == View.VISIBLE) {
                statusView.visibility = View.GONE
                toggleDebug.text = "+"
            } else {
                statusView.visibility = View.VISIBLE
                toggleDebug.text = "-"
            }
        }

        setupVideoSurface()
        setupGimbalSlider()
        setupZoomSlider()
        startUiUpdater()

        requestPermissionsAndRegister()
    }

    private fun enterImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Kad korisnik dovuce iz vrha pa baci, vrati immersive
        if (hasFocus) enterImmersiveMode()
    }

    // ---------- Video pipeline ----------

    private fun setupVideoSurface() {
        videoSurface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                log("video surface ready: ${w}x${h}")
                pendingSurface = s
                pendingW = w
                pendingH = h
                tryStartVideo()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                log("video surface resized: ${w}x${h}")
                codecManager?.onSurfaceSizeChanged(w, h, 0)
            }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                log("video surface destroyed")
                stopVideo()
                pendingSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    runOnUiThread { videoPlaceholder.visibility = View.GONE }
                    log("FIRST VIDEO FRAME rendered")
                }
            }
        }
    }

    private fun tryStartVideo() {
        if (codecManager != null) return
        val surface = pendingSurface ?: return
        val product = DJISDKManager.getInstance().product ?: return
        if (product !is Aircraft) return
        if (product.camera == null) return

        try {
            log("creating DJICodecManager (${pendingW}x${pendingH})")
            codecManager = DJICodecManager(applicationContext, surface, pendingW, pendingH)
        } catch (t: Throwable) {
            log("DJICodecManager init FAILED: ${t.message}")
            return
        }

        // NAPOMENA: setYuvDataCallback blokira render na MSDK 4.16.4 / Mavic 2 Zoom.
        // Stage 4A koristi getBitmap() na timer za YOLO frame access umesto YUV callback-a.
        // Test: 28.05.2026, YUV setup -> "WAITING FOR VIDEO FEED" (render mrtav).

        // Bitmap timer skeleton (Stage 4A korak 1, drugi pokusaj):
        // getBitmap() povlaci RGB frame sinhrono iz decoder-a. Cilj: validirati da
        // ne lomi render. YOLO inference ce kasnije ici ovde umesto samog loga.
        val bitmapTick = object : Runnable {
            override fun run() {
                val cm = codecManager ?: return
                try {
                    cm.getBitmap { bmp ->
                        if (bmp == null) return@getBitmap
                        if (!firstBitmapLogged) {
                            firstBitmapLogged = true
                            runOnUiThread {
                                log("Bitmap OK: ${bmp.width}x${bmp.height}, YOLO ${if (yolo == null) "loading..." else "ready"}")
                            }
                        }
                        yolo?.detectAsync(bmp) { dets ->
                            // Stage 4A korak 2: gurni detekcije u telemetriju, sledeci UDP tick ih nosi.
                            tel.detections = dets
                            push()
                            if (dets.isNotEmpty()) {
                                val top = dets.take(3).joinToString(", ") { d ->
                                    "${d.label}@${(d.nx*100).toInt()},${(d.ny*100).toInt()}=${String.format("%.2f", d.confidence)}"
                                }
                                runOnUiThread { log("YOLO[${dets.size}] $top") }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (!firstBitmapLogged) {
                        firstBitmapLogged = true
                        runOnUiThread { log("Bitmap FAILED: ${t.message}") }
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(bitmapTick, 1000)
        log("bitmap timer started (500ms)")

        // YOLO model load na background thread-u - 12 MB tflite + Interpreter init
        // moze trajati 1-3 sekunde. Glavni thread se ne blokira.
        Thread {
            try {
                runOnUiThread { log("YOLO loading model...") }
                yolo = Yolo(applicationContext)
                runOnUiThread { log("YOLO ready") }
            } catch (t: Throwable) {
                runOnUiThread { log("YOLO load FAILED: ${t.message}") }
            }
        }.start()

        try {
            val listener = VideoFeeder.VideoDataListener { bytes, size ->
                codecManager?.sendDataToDecoder(bytes, size)
                frameCount++
            }
            videoDataListener = listener
            VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(listener)
            log("video listener attached. Waiting for first frame...")
        } catch (t: Throwable) {
            log("video listener attach FAILED: ${t.message}")
        }
    }

    private fun stopVideo() {
        try {
            videoDataListener?.let {
                VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(it)
            }
        } catch (t: Throwable) {
            Log.w("Lattice", "removeVideoDataListener: ${t.message}")
        }
        videoDataListener = null
        try {
            codecManager?.cleanSurface()
            codecManager?.destroyCodec()
        } catch (t: Throwable) {
            Log.w("Lattice", "codec cleanup: ${t.message}")
        }
        codecManager = null
        firstFrameLogged = false
    }

    // ---------- Gimbal slider ----------

    private fun setupGimbalSlider() {
        gimbalSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = (progress - 90).toFloat()
                gimbalPitchLabel.text = "${pitch.toInt()}°"
                if (fromUser) pendingGimbalPitch = pitch
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun applyGimbalPitch(pitch: Float) {
        val product = DJISDKManager.getInstance().product ?: return
        if (product !is Aircraft) return
        val gimbal = product.gimbal ?: return
        try {
            val rotation = Rotation.Builder()
                .pitch(pitch)
                .mode(RotationMode.ABSOLUTE_ANGLE)
                .time(0.5)
                .build()
            gimbal.rotate(rotation) { err: DJIError? ->
                if (err != null) log("gimbal rotate err: $err")
            }
        } catch (t: Throwable) {
            log("gimbal rotate exception: ${t.message}")
        }
    }

    // ---------- Zoom slider ----------

    private fun setupZoomSlider() {
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val equivTenths = ZOOM_MIN_TENTHS + progress
                val zoomX = equivTenths / 240.0
                zoomLabel.text = String.format("%.1fx", zoomX)
                if (fromUser) pendingZoomTenths = equivTenths
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun applyZoom(equivTenths: Int) {
        val product = DJISDKManager.getInstance().product ?: return
        if (product !is Aircraft) return
        val camera = product.camera ?: return
        if (!camera.isHybridZoomSupported) return
        try {
            camera.setHybridZoomFocalLength(equivTenths) { err: DJIError? ->
                if (err != null) log("zoom set err: $err")
            }
        } catch (t: Throwable) {
            log("zoom set exception: ${t.message}")
        }
    }

    // ---------- UI updater ----------

    private fun startUiUpdater() {
        val tick = object : Runnable {
            override fun run() {
                hudBat.text = "BAT ${tel.battery}%"
                hudSats.text = "SAT ${tel.sats}"
                hudAlt.text = String.format("ALT %.1f m", tel.alt)
                hudSpeed.text = String.format("SPD %.1f m/s", tel.speed)

                compassWarning.visibility = if (tel.compassError) View.VISIBLE else View.GONE

                pendingGimbalPitch?.let {
                    applyGimbalPitch(it); pendingGimbalPitch = null
                }
                pendingZoomTenths?.let {
                    applyZoom(it); pendingZoomTenths = null
                }

                handler.postDelayed(this, 200)
            }
        }
        handler.post(tick)
    }

    // ---------- DJI SDK lifecycle ----------

    private fun requestPermissionsAndRegister() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        ActivityCompat.requestPermissions(this, perms, 1)
        log("Registering with DJI server (needs internet)...")
        DJISDKManager.getInstance().registerApp(applicationContext, sdkManagerCallback)
    }

    private val sdkManagerCallback = object : DJISDKManager.SDKManagerCallback {
        override fun onRegister(error: DJIError?) {
            if (error == DJISDKError.REGISTRATION_SUCCESS) {
                log("DJI registration OK. Connecting to product...")
                DJISDKManager.getInstance().startConnectionToProduct()
            } else {
                log("DJI registration FAILED: $error")
            }
        }

        override fun onProductDisconnect() {
            log("product disconnected")
            listenersAttached = false
            stopVideo()
        }

        override fun onProductConnect(product: BaseProduct?) {
            log("product connected: model=${product?.model} class=${product?.javaClass?.simpleName}")
            tryAttachListeners()
        }

        override fun onProductChanged(p: BaseProduct?) {
            log("product changed: model=${p?.model}")
            tryAttachListeners()
        }
        override fun onComponentChange(
            key: BaseProduct.ComponentKey?, oldC: BaseComponent?, newC: BaseComponent?
        ) {
            log("component change: $key  old=${oldC?.javaClass?.simpleName} new=${newC?.javaClass?.simpleName}")
            tryAttachListeners()
        }
        override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {}
        override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
    }

    private fun tryAttachListeners() {
        if (listenersAttached) {
            tryStartVideo()
            return
        }
        val product = DJISDKManager.getInstance().product ?: return
        if (product !is Aircraft) return
        if (product.flightController == null) return
        listenersAttached = true
        attachListeners(product)
        tryStartVideo()
    }

    private fun attachListeners(aircraft: Aircraft) {
        log("attaching listeners to ${aircraft.model}")

        try {
            aircraft.flightController?.setStateCallback { state ->
                try {
                    val loc = state.aircraftLocation
                    tel.lat = loc.latitude
                    tel.lon = loc.longitude
                    tel.alt = loc.altitude.toDouble()
                    // HEADING FIX 02.06.2026: state.attitude.yaw je gyro-fused atitudinal yaw,
                    // ne compass heading. DJI GO 4 cita compass.getHeading() (0-360 magnetnim severom).
                    // Validirano: DJI GO 4 je pokazivao tacan heading kad Bridge nije.
                    val compassHdg = aircraft.flightController?.compass?.heading?.toDouble()
                    tel.acYaw = compassHdg ?: ((state.attitude.yaw + 360.0) % 360.0)
                    tel.acPitch = state.attitude.pitch
                    tel.acRoll = state.attitude.roll
                    tel.sats = state.satelliteCount
                    tel.hasFix = state.isHomeLocationSet
                    // Compass status NIJE na FlightControllerState (samo isIMUPreheating).
                    // Pravi izvor: FlightController.getCompass().hasError() (MSDK 4.16.4, iz dji-sdk-provided).
                    tel.compassError = aircraft.flightController?.compass?.hasError() ?: false
                    val vx = state.velocityX.toDouble()
                    val vy = state.velocityY.toDouble()
                    val vz = state.velocityZ.toDouble()
                    tel.speed = Math.sqrt(vx*vx + vy*vy + vz*vz)
                    // Stage 4A korak 3: home location za live distance HUD.
                    // state.homeLocation je sinhroni getter; isHomeLocationSet=true tek nakon
                    // prvog GPS lock-a + uzletanja drone-a.
                    val home = state.homeLocation
                    if (home != null && state.isHomeLocationSet) {
                        tel.homeLat = home.latitude
                        tel.homeLon = home.longitude
                        tel.distanceFromHome = haversineMeters(tel.lat, tel.lon, tel.homeLat, tel.homeLon)
                        runOnUiThread {
                            hudDist.text = "DIST %.1f m".format(tel.distanceFromHome)
                        }
                    }
                    push()
                } catch (t: Throwable) {
                    Log.e("Lattice", "FC callback error: ${t.message}")
                }
            }
            log("  flightController listener OK")
        } catch (t: Throwable) {
            log("  flightController listener FAILED: ${t.message}")
        }

        try {
            aircraft.gimbal?.setStateCallback { gs ->
                try {
                    // Mavic 2 Zoom gimbal yaw je locked na aircraft body (gimbal pomera samo
                    // pitch i roll). Ako MSDK vraca gimbal.yaw kao apsolutni heading, sabranje sa
                    // acYaw u server-side formuli camera_heading = (acYaw+gYaw)%360 daje duplo brojanje.
                    tel.gYaw = 0.0
                    tel.gPitch = gs.attitudeInDegrees.pitch.toDouble()
                    tel.gRoll = gs.attitudeInDegrees.roll.toDouble()
                    push()
                } catch (t: Throwable) {
                    Log.e("Lattice", "Gimbal callback error: ${t.message}")
                }
            }
            log("  gimbal listener OK")
        } catch (t: Throwable) {
            log("  gimbal listener FAILED: ${t.message}")
        }

        try {
            val camera = aircraft.camera
            if (camera != null && camera.isHybridZoomSupported) {
                camera.getHybridZoomFocalLength(object : CommonCallbacks.CompletionCallbackWith<Int> {
                    override fun onSuccess(value: Int) { updateFocalFromEquivTenths(value) }
                    override fun onFailure(err: DJIError?) {}
                })
                startZoomPoller(camera)
                log("  camera hybrid zoom OK")
            } else {
                log("  camera does not support hybrid zoom. Using fixed focal.")
                tel.focalMm = 10.26
            }
        } catch (t: Throwable) {
            log("  camera setup FAILED: ${t.message}")
        }

        try {
            val cam = aircraft.camera
            cam?.setExposureMode(SettingsDefinitions.ExposureMode.PROGRAM) { err1 ->
                if (err1 == null) runOnUiThread { log("  exposure mode -> AUTO") }
                else runOnUiThread { log("  exposure mode err: $err1") }
            }
            cam?.setExposureCompensation(SettingsDefinitions.ExposureCompensation.N_0_0) { err2 ->
                if (err2 == null) runOnUiThread { log("  exposure comp -> 0.0") }
                else runOnUiThread { log("  exposure comp err: $err2") }
            }
        } catch (t: Throwable) {
            log("  exposure setup FAILED: ${t.message}")
        }

        // Cuvamo camera referenc za UI dugmad (ISO/SHUT/EXP)
        cameraRef = aircraft.camera
        runOnUiThread {
            if (!exposureControlsInitialized) {
                exposureControlsInitialized = true
                val expModeBtn = findViewById<android.widget.Button>(R.id.expModeBtn)
                val isoBtn = findViewById<android.widget.Button>(R.id.isoBtn)
                val shutBtn = findViewById<android.widget.Button>(R.id.shutBtn)

                fun setExpMode(mode: SettingsDefinitions.ExposureMode) {
                    expMode = mode
                    cameraRef?.setExposureMode(mode) { err ->
                        runOnUiThread {
                            if (err == null) {
                                expModeBtn.text = if (mode == SettingsDefinitions.ExposureMode.PROGRAM) "AUTO" else "MAN"
                            } else log("exp mode err: $err")
                        }
                    }
                }

                expModeBtn.setOnClickListener {
                    setExpMode(if (expMode == SettingsDefinitions.ExposureMode.PROGRAM)
                        SettingsDefinitions.ExposureMode.MANUAL
                    else
                        SettingsDefinitions.ExposureMode.PROGRAM)
                }

                isoBtn.setOnClickListener {
                    isoIdx = (isoIdx + 1) % isoList.size
                    val (label, value) = isoList[isoIdx]
                    if (value != SettingsDefinitions.ISO.AUTO && expMode != SettingsDefinitions.ExposureMode.MANUAL) {
                        setExpMode(SettingsDefinitions.ExposureMode.MANUAL)
                    }
                    cameraRef?.setISO(value) { err ->
                        runOnUiThread {
                            if (err == null) isoBtn.text = "ISO $label"
                            else log("ISO err: $err")
                        }
                    }
                }

                shutBtn.setOnClickListener {
                    shutIdx = (shutIdx + 1) % shutList.size
                    val (label, value) = shutList[shutIdx]
                    if (expMode != SettingsDefinitions.ExposureMode.MANUAL) {
                        setExpMode(SettingsDefinitions.ExposureMode.MANUAL)
                    }
                    cameraRef?.setShutterSpeed(value) { err ->
                        runOnUiThread {
                            if (err == null) shutBtn.text = "SHUT $label"
                            else log("shut err: $err")
                        }
                    }
                }

                // Kompas kalibracija - klik pokrece DJI startCalibration. Korisnik mora da
                // rotira dron horizontalno 360, pa vertikalno 360. Log prati progres preko CompassCallback.
                val calBtn = findViewById<android.widget.Button>(R.id.calBtn)
                if (!compassControlsInitialized) {
                    compassControlsInitialized = true
                    val compass = aircraft.flightController?.compass
                    compass?.setCalibrationStateCallback { state ->
                        runOnUiThread {
                            when (state) {
                                CompassCalibrationState.HORIZONTAL -> { calBtn.text = "CAL H"; log("CAL: rotiraj HORIZONTALNO 360°") }
                                CompassCalibrationState.VERTICAL -> { calBtn.text = "CAL V"; log("CAL: rotiraj VERTIKALNO 360° (nos dole)") }
                                CompassCalibrationState.SUCCESSFUL -> { calBtn.text = "CAL OK"; compassCalibrating = false; log("CAL: USPESNO"); calBtn.postDelayed({ calBtn.text = "CAL" }, 3000) }
                                CompassCalibrationState.FAILED -> { calBtn.text = "CAL X"; compassCalibrating = false; log("CAL: NEUSPELO, probaj opet"); calBtn.postDelayed({ calBtn.text = "CAL" }, 3000) }
                                else -> { if (!compassCalibrating) calBtn.text = "CAL" }
                            }
                        }
                    }
                    calBtn.setOnClickListener {
                        if (compassCalibrating) {
                            // Abort u toku
                            compass?.stopCalibration { err ->
                                runOnUiThread {
                                    compassCalibrating = false
                                    calBtn.text = "CAL"
                                    if (err != null) log("CAL stop err: $err")
                                    else log("CAL: prekinuto")
                                }
                            }
                        } else {
                            compass?.startCalibration { err ->
                                runOnUiThread {
                                    if (err == null) {
                                        compassCalibrating = true
                                        calBtn.text = "CAL…"
                                        log("CAL: poceo. PRVO HORIZONTALNO 360°, pa VERTIKALNO 360°. Daleko od metala!")
                                    } else {
                                        log("CAL start err: $err")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        try {
            aircraft.battery?.setStateCallback { bs ->
                try {
                    tel.battery = bs.chargeRemainingInPercent
                    push()
                } catch (t: Throwable) {
                    Log.e("Lattice", "Battery callback error: ${t.message}")
                }
            }
            log("  battery listener OK")
        } catch (t: Throwable) {
            log("  battery listener FAILED: ${t.message}")
        }
    }

    private fun updateFocalFromEquivTenths(equivTenths: Int) {
        val equivMm = equivTenths / 10.0
        val actualMm = equivMm / 5.62
        tel.focalMm = actualMm
        runOnUiThread {
            val progress = (equivTenths - ZOOM_MIN_TENTHS).coerceIn(0, ZOOM_MAX_TENTHS - ZOOM_MIN_TENTHS)
            if (Math.abs(zoomSeek.progress - progress) > 5) {
                zoomSeek.progress = progress
            }
        }
    }

    private fun startZoomPoller(camera: dji.sdk.camera.Camera) {
        val pollHandler = android.os.Handler(mainLooper)
        val poll = object : Runnable {
            override fun run() {
                camera.getHybridZoomFocalLength(object : CommonCallbacks.CompletionCallbackWith<Int> {
                    override fun onSuccess(value: Int) { updateFocalFromEquivTenths(value) }
                    override fun onFailure(err: DJIError?) {}
                })
                pollHandler.postDelayed(this, 500)
            }
        }
        pollHandler.post(poll)
    }

    private fun push() {
        sender.latest = tel.copy()
    }

    private fun log(s: String) {
        Log.i("Lattice", s)
        runOnUiThread {
            statusView.append("$s\n")
        }
    }

    override fun onDestroy() {
        sender.stop()
        stopVideo()
        handler.removeCallbacksAndMessages(null)
        yolo?.close()
        yolo = null
        super.onDestroy()
    }
}
