// network/ecla/EclaClient.kt
package com.example.mydtnapp.network.ecla

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client for the dtn7-rs ECLA endpoint (/ws/ecla).
 *
 * start() opens the socket, sends Register, then invokes onRegistered when
 * the daemon acknowledges. Auto-reconnects between start() and stop().
 * The CLA name must match across every dtn7 instance that wants to peer.
 */
class EclaClient(
    private val claName: String = DEFAULT_CLA_NAME,
    private val enableBeacon: Boolean = true,
    private val daemonUrl: String = DEFAULT_URL,
    private val onRegistered: (nodeId: String) -> Unit = {},
    private val onForwardData: (ForwardDataPacket) -> Unit = {},
    private val onBeacon: (BeaconPacket) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "EclaClient"
        const val DEFAULT_CLA_NAME = "BleCla"
        const val DEFAULT_URL = "ws://127.0.0.1:3000/ws/ecla"
        private const val RECONNECT_DELAY_MS = 3000L
    }

    // LONG_OR_DOUBLE preserves integer-typed numbers inside heterogeneous
    // arrays (BeaconPacket.eid is [u8, String]); the default policy would
    // coerce them to Double and dtn7-rs's serde_json would reject them.
    private val gson = GsonBuilder()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    // Pings off: dtn7-rs 0.21.0 panics on non-text WS frames.
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val running = AtomicBoolean(false)

    @Volatile private var nodeId: String? = null

    /** Returns the daemon's node-id once registration completes, or null before that. */
    fun nodeId(): String? = nodeId

    fun start() {
        if (running.getAndSet(true)) return
        connect()
    }

    fun stop() {
        running.set(false)
        try { ws?.close(1000, "stopping") } catch (_: Exception) {}
        ws = null
    }

    /** Send a Beacon packet to dtnd — used when a peer's beacon arrived over BLE. */
    fun sendBeaconToDaemon(pkt: BeaconPacket) = sendJson(pkt)

    /** Send a ForwardData packet to dtnd — used when a bundle arrived over BLE. */
    fun sendForwardDataToDaemon(pkt: ForwardDataPacket) = sendJson(pkt)

    private fun sendJson(pkt: EclaPacket) {
        val json = gson.toJson(pkt)
        val socket = ws
        if (socket == null) {
            Log.w(TAG, "Drop outbound ECLA packet — not connected: $json")
            return
        }
        val accepted = socket.send(json)
        if (!accepted) Log.w(TAG, "WS send queue rejected: $json")
    }

    private fun connect() {
        if (!running.get()) return

        val request = Request.Builder().url(daemonUrl).build()
        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("ECLA WS open — registering as '$claName'")
                val reg = RegisterPacket(name = claName, enableBeacon = enableBeacon)
                webSocket.send(gson.toJson(reg))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("ECLA WS closed ($code $reason)")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("ECLA WS failure: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        Thread {
            try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: InterruptedException) {}
            if (running.get()) {
                onStatus("Reconnecting ECLA WS…")
                connect()
            }
        }.start()
    }

    private fun handleMessage(text: String) {
        val obj = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from dtnd: $text")
            return
        }
        when (obj.get("type")?.asString) {
            "Registered" -> {
                val pkt = gson.fromJson(obj, RegisteredPacket::class.java)
                nodeId = pkt.nodeId
                onStatus("ECLA registered: nodeid=${pkt.nodeId}")
                onRegistered(pkt.nodeId)
            }
            "Error" -> {
                val pkt = gson.fromJson(obj, ErrorPacket::class.java)
                onStatus("ECLA error: ${pkt.reason}")
                onError(pkt.reason)
            }
            "ForwardData" -> {
                val pkt = gson.fromJson(obj, ForwardDataPacket::class.java)
                onForwardData(pkt)
            }
            "Beacon" -> {
                val pkt = gson.fromJson(obj, BeaconPacket::class.java)
                onBeacon(pkt)
            }
            else -> Log.w(TAG, "Unknown ECLA packet type: $text")
        }
    }
}
