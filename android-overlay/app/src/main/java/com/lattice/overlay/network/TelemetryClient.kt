package com.lattice.overlay.network

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

class TelemetryClient(private val serverUrl: String) {

    companion object {
        private const val TAG = "TelemetryClient"
    }

    private var socket: Socket? = null

    var onConnect: (() -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var onConnectError: ((String) -> Unit)? = null
    var onTelemetry: ((JSONObject) -> Unit)? = null

    fun connect() {
        try {
            val opts = IO.Options().apply {
                reconnection = true
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                transports = arrayOf("websocket", "polling")
            }
            socket = IO.socket(serverUrl, opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.i(TAG, "connected to $serverUrl")
                onConnect?.invoke()
            }
            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.i(TAG, "disconnected")
                onDisconnect?.invoke()
            }
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val msg = args.joinToString { it?.toString() ?: "null" }
                Log.e(TAG, "connect error: $msg")
                onConnectError?.invoke(msg)
            }
            socket?.on("telemetry") { args ->
                if (args.isNotEmpty()) {
                    val payload = args[0] as? JSONObject
                    payload?.let { onTelemetry?.invoke(it) }
                }
            }

            socket?.connect()
            Log.i(TAG, "connect() called for $serverUrl")
        } catch (e: Exception) {
            Log.e(TAG, "connect setup failed: ${e.message}", e)
        }
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
    }

    fun isConnected(): Boolean = socket?.connected() == true
}
