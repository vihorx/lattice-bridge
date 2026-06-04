package com.lattice.bridge

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpSender {
    @Volatile var latest: Telemetry = Telemetry()
    private var job: Job? = null
    private var socket: DatagramSocket? = null

    fun start(host: String, port: Int = 14550, hz: Int = 10) {
        stop()
        val periodMs = 1000L / hz
        socket = DatagramSocket()
        val addr = InetAddress.getByName(host)

        job = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "UDP sender -> $host:$port at $hz Hz")
            while (isActive) {
                try {
                    val payload = latest.toJson().toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(payload, payload.size, addr, port)
                    socket?.send(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "send failed: ${e.message}")
                }
                delay(periodMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
    }

    companion object { private const val TAG = "UdpSender" }
}
