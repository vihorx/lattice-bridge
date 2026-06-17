package com.lattice.unified.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.error.DJISDKError
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "lattice_bridge_channel"
        private const val PREFS_NAME = "lattice_overlay"
        private const val KEY_HOST = "mac_ip"
        private const val DEFAULT_HOST = "192.168.1.11"
        private const val DEFAULT_W = 1280
        private const val DEFAULT_H = 720
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow(BridgeState.STARTING)
    val state: StateFlow<BridgeState> = _state
    private val _droneConnected = MutableStateFlow(false)
    val droneConnected: StateFlow<Boolean> = _droneConnected
    private val _udpSendCount = MutableStateFlow(0L)
    val udpSendCount: StateFlow<Long> = _udpSendCount
    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry

    private val tel = Telemetry()
    private val sender = UdpSender()
    private var yolo: Yolo? = null

    private var codecManager: DJICodecManager? = null
    private var videoListener: VideoFeeder.VideoDataListener? = null
    private var offscreenTexture: SurfaceTexture? = null

    private var externalSurface: SurfaceTexture? = null
    private var externalW: Int = DEFAULT_W
    private var externalH: Int = DEFAULT_H

    private var listenersAttached = false
    private var currentHost: String = DEFAULT_HOST
    private var frameCount = 0L
    private var lastFrameLogMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var bitmapTickActive = false
    private var lastTelemetryEmitMs = 0L

    inner class LocalBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Lattice Bridge starting..."))
        _state.value = BridgeState.STARTING

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentHost = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        sender.start(currentHost)
        Log.i(TAG, "UDP sender -> $currentHost:14550")

        loadYoloAsync()

        try {
            DJISDKManager.getInstance().registerApp(applicationContext, sdkCallback)
            Log.i(TAG, "DJI SDK registerApp called")
        } catch (e: Throwable) {
            Log.e(TAG, "DJI registerApp threw: ${e.message}", e)
            _state.value = BridgeState.ERROR
        }
    }

    fun setHost(host: String) {
        if (host == currentHost) return
        Log.i(TAG, "setHost: $currentHost -> $host")
        currentHost = host
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOST, host).apply()
        try {
            sender.stop()
            sender.start(host)
        } catch (t: Throwable) {
            Log.e(TAG, "sender restart failed: ${t.message}", t)
        }
        updateNotification()
    }

    fun setVideoSurface(surface: SurfaceTexture?, w: Int, h: Int) {
        handler.post {
            if (surface !== null && surface === externalSurface) {
                Log.i(TAG, "setVideoSurface: same surface, size only ${w}x${h}")
                try { codecManager?.onSurfaceSizeChanged(w, h, 0) } catch (t: Throwable) {}
                if (w > 0) externalW = w
                if (h > 0) externalH = h
                return@post
            }
            Log.i(TAG, "setVideoSurface: ${if (surface == null) "OFFSCREEN" else "EXTERNAL"} ${w}x${h} -> codec REBUILD")
            externalSurface = surface
            externalW = if (w > 0) w else DEFAULT_W
            externalH = if (h > 0) h else DEFAULT_H
            stopVideo()
            tryStartVideo()
        }
    }

    fun updateSurfaceSize(w: Int, h: Int) {
        handler.post {
            try {
                codecManager?.onSurfaceSizeChanged(w, h, 0)
                if (w > 0) externalW = w
                if (h > 0) externalH = h
                Log.i(TAG, "updateSurfaceSize: ${w}x${h}")
            } catch (t: Throwable) {
                Log.w(TAG, "updateSurfaceSize failed: ${t.message}")
            }
        }
    }

    fun applyGimbalPitch(pitch: Float) {
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
                if (err != null) Log.w(TAG, "gimbal rotate err: $err")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "gimbal rotate ex: ${t.message}", t)
        }
    }

    fun applyZoom(equivTenths: Int) {
        val product = DJISDKManager.getInstance().product ?: return
        if (product !is Aircraft) return
        val camera = product.camera ?: return
        if (!camera.isHybridZoomSupported) return
        try {
            camera.setHybridZoomFocalLength(equivTenths) { err: DJIError? ->
                if (err != null) Log.w(TAG, "zoom set err: $err")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "zoom set ex: ${t.message}", t)
        }
    }

    private fun loadYoloAsync() {
        Thread {
            try {
                Log.i(TAG, "YOLO loading model...")
                yolo = Yolo(applicationContext)
                Log.i(TAG, "YOLO ready")
            } catch (t: Throwable) {
                Log.e(TAG, "YOLO load FAILED: ${t.message}", t)
            }
        }.start()
    }

    private val sdkCallback = object : DJISDKManager.SDKManagerCallback {
        override fun onRegister(error: DJIError?) {
            if (error == DJISDKError.REGISTRATION_SUCCESS) {
                Log.i(TAG, "DJI registration OK")
                try {
                    DJISDKManager.getInstance().startConnectionToProduct()
                } catch (e: Throwable) {
                    Log.e(TAG, "startConnectionToProduct threw: ${e.message}", e)
                }
            } else {
                Log.e(TAG, "DJI registration FAILED: $error")
                _state.value = BridgeState.ERROR
                updateNotification()
            }
        }

        override fun onProductDisconnect() {
            Log.w(TAG, "product disconnected")
            listenersAttached = false
            _droneConnected.value = false
            _state.value = BridgeState.NO_RC
            updateNotification()
            stopVideo()
        }

        override fun onProductConnect(product: BaseProduct?) {
            Log.i(TAG, "product connected: ${product?.model}")
            tryAttachListeners()
        }

        override fun onProductChanged(product: BaseProduct?) {
            Log.i(TAG, "product changed: ${product?.model}")
            tryAttachListeners()
        }

        override fun onComponentChange(
            key: BaseProduct.ComponentKey?,
            oldComponent: BaseComponent?,
            newComponent: BaseComponent?
        ) {
            Log.i(TAG, "component change: $key")
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
        _droneConnected.value = true
        _state.value = BridgeState.DRONE_OK
        updateNotification()
        tryStartVideo()
    }

    private fun attachListeners(aircraft: Aircraft) {
        Log.i(TAG, "attaching listeners to ${aircraft.model}")

        try {
            aircraft.flightController?.setStateCallback { st ->
                try {
                    val loc = st.aircraftLocation
                    tel.lat = loc.latitude
                    tel.lon = loc.longitude
                    tel.alt = loc.altitude.toDouble()
                    val compassHdg = aircraft.flightController?.compass?.heading?.toDouble()
                    tel.acYaw = compassHdg ?: ((st.attitude.yaw + 360.0) % 360.0)
                    tel.acPitch = st.attitude.pitch
                    tel.acRoll = st.attitude.roll
                    tel.sats = st.satelliteCount
                    tel.hasFix = st.isHomeLocationSet
                    tel.compassError = aircraft.flightController?.compass?.hasError() ?: false
                    val vx = st.velocityX.toDouble()
                    val vy = st.velocityY.toDouble()
                    val vz = st.velocityZ.toDouble()
                    tel.speed = Math.sqrt(vx * vx + vy * vy + vz * vz)
                    val home = st.homeLocation
                    if (home != null && st.isHomeLocationSet) {
                        tel.homeLat = home.latitude
                        tel.homeLon = home.longitude
                        tel.distanceFromHome = haversineMeters(tel.lat, tel.lon, tel.homeLat, tel.homeLon)
                    }
                    push()
                } catch (t: Throwable) {
                    Log.e(TAG, "FC cb: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "FC attach FAILED: ${t.message}", t)
        }

        try {
            aircraft.gimbal?.setStateCallback { gs ->
                try {
                    tel.gYaw = 0.0
                    tel.gPitch = gs.attitudeInDegrees.pitch.toDouble()
                    tel.gRoll = gs.attitudeInDegrees.roll.toDouble()
                    push()
                } catch (t: Throwable) {
                    Log.e(TAG, "gimbal cb: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "gimbal attach FAILED: ${t.message}", t)
        }

        try {
            val camera = aircraft.camera
            if (camera != null && camera.isHybridZoomSupported) {
                camera.getHybridZoomFocalLength(object : CommonCallbacks.CompletionCallbackWith<Int> {
                    override fun onSuccess(value: Int) { updateFocalFromEquivTenths(value) }
                    override fun onFailure(err: DJIError?) {}
                })
                startZoomPoller(camera)
            } else {
                tel.focalMm = 10.26
            }
        } catch (t: Throwable) {
            Log.e(TAG, "camera setup FAILED: ${t.message}", t)
        }

        try {
            val cam = aircraft.camera
            cam?.setExposureMode(SettingsDefinitions.ExposureMode.PROGRAM) { err ->
                if (err != null) Log.w(TAG, "exposure mode err: $err")
            }
            cam?.setExposureCompensation(SettingsDefinitions.ExposureCompensation.N_0_0) { err ->
                if (err != null) Log.w(TAG, "exposure comp err: $err")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "exposure setup FAILED: ${t.message}", t)
        }

        try {
            aircraft.battery?.setStateCallback { bs ->
                try {
                    tel.battery = bs.chargeRemainingInPercent
                    push()
                } catch (t: Throwable) {
                    Log.e(TAG, "battery cb: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "battery attach FAILED: ${t.message}", t)
        }
    }

    private fun updateFocalFromEquivTenths(equivTenths: Int) {
        val equivMm = equivTenths / 10.0
        val actualMm = equivMm / 5.62
        tel.focalMm = actualMm
    }

    private fun startZoomPoller(camera: dji.sdk.camera.Camera) {
        val poll = object : Runnable {
            override fun run() {
                try {
                    camera.getHybridZoomFocalLength(object : CommonCallbacks.CompletionCallbackWith<Int> {
                        override fun onSuccess(value: Int) { updateFocalFromEquivTenths(value) }
                        override fun onFailure(err: DJIError?) {}
                    })
                } catch (t: Throwable) {}
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(poll)
    }

    private fun tryStartVideo() {
        if (codecManager != null) return
        val product = DJISDKManager.getInstance().product ?: return
        if (product !is Aircraft) return
        if (product.camera == null) return

        val surface: SurfaceTexture
        val w: Int
        val h: Int

        if (externalSurface != null) {
            surface = externalSurface!!
            w = externalW
            h = externalH
            Log.i(TAG, "video target: EXTERNAL surface (${w}x${h})")
        } else {
            try {
                val tex = SurfaceTexture(0)
                tex.setDefaultBufferSize(DEFAULT_W, DEFAULT_H)
                offscreenTexture = tex
                surface = tex
                w = DEFAULT_W
                h = DEFAULT_H
                Log.i(TAG, "video target: OFFSCREEN (${w}x${h})")
            } catch (t: Throwable) {
                Log.e(TAG, "offscreen surface create FAILED: ${t.message}", t)
                return
            }
        }

        try {
            codecManager = DJICodecManager(applicationContext, surface, w, h)
        } catch (t: Throwable) {
            Log.e(TAG, "DJICodecManager init FAILED: ${t.message}", t)
            return
        }

        try {
            val listener = VideoFeeder.VideoDataListener { bytes, size ->
                codecManager?.sendDataToDecoder(bytes, size)
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFrameLogMs > 2000) {
                    lastFrameLogMs = now
                    Log.i(TAG, "video frames received: total=$frameCount (last 2s window)")
                }
            }
            videoListener = listener
            VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(listener)
            Log.i(TAG, "video listener ATTACHED (surface=${if (externalSurface != null) "EXTERNAL" else "OFFSCREEN"})")
        } catch (t: Throwable) {
            Log.e(TAG, "video listener attach FAILED: ${t.message}", t)
        }

        if (!bitmapTickActive) {
            bitmapTickActive = true
            handler.postDelayed(bitmapTick, 1000)
        }
    }

    private val bitmapTick = object : Runnable {
        override fun run() {
            val cm = codecManager
            if (cm == null) {
                bitmapTickActive = false
                return
            }
            if (externalSurface == null) {
                try {
                    cm.getBitmap { bmp ->
                        if (bmp != null) {
                            yolo?.detectAsync(bmp) { dets ->
                                tel.detections = dets
                                push()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "bitmap tick: ${t.message}")
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun stopVideo() {
        try {
            videoListener?.let {
                VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "removeVideoDataListener: ${t.message}")
        }
        videoListener = null
        try {
            codecManager?.cleanSurface()
            codecManager?.destroyCodec()
        } catch (t: Throwable) {
            Log.w(TAG, "codec cleanup: ${t.message}")
        }
        codecManager = null
        try {
            offscreenTexture?.release()
        } catch (t: Throwable) {}
        offscreenTexture = null
    }

    private fun push() {
        val snap = tel.copy()
        sender.latest = snap
        _udpSendCount.value = _udpSendCount.value + 1
        val now = System.currentTimeMillis()
        if (now - lastTelemetryEmitMs > 100L) {
            lastTelemetryEmitMs = now
            _telemetry.value = snap
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        handler.removeCallbacksAndMessages(null)
        stopVideo()
        try { sender.stop() } catch (t: Throwable) {}
        try { yolo?.close() } catch (t: Throwable) {}
        yolo = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Lattice Bridge", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lattice Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    private fun updateNotification() {
        val text = when (_state.value) {
            BridgeState.STARTING -> "Lattice Bridge starting..."
            BridgeState.STUB -> "Lattice Bridge (stub)"
            BridgeState.DRONE_OK -> "Drone OK · UDP -> $currentHost"
            BridgeState.NO_RC -> "No RC connected"
            BridgeState.ERROR -> "Error - check logcat"
        }
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(text))
        } catch (t: Throwable) {}
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sLat = Math.sin(dLat / 2)
        val sLon = Math.sin(dLon / 2)
        val a = sLat * sLat +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sLon * sLon
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}

enum class BridgeState {
    STARTING,
    STUB,
    DRONE_OK,
    NO_RC,
    ERROR
}
