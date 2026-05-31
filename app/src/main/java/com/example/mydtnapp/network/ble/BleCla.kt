// network/ble/BleCla.kt
package com.example.mydtnapp.network.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import com.example.mydtnapp.network.ecla.BeaconPacket
import com.example.mydtnapp.network.ecla.EclaClient
import com.example.mydtnapp.network.ecla.EclaPacket
import com.example.mydtnapp.network.ecla.ForwardDataPacket
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Bluetooth-Low-Energy External Convergence Layer Agent for dtn7-rs.
 *
 * Architecture:
 *   - We connect to dtn7's ECLA WebSocket (via [EclaClient]) and register as "BleCla".
 *   - We become the *only* transmission layer between two phones running this app.
 *   - When dtn7 hands us a Beacon, we broadcast it to all peers we have BLE connections to.
 *   - When dtn7 hands us a ForwardData (a bundle to send), we look up the destination
 *     dtn7 node-id in our peer table and write the JSON to that peer over BLE.
 *   - When a peer writes a chunk to our GATT server, we reassemble it into a full
 *     JSON packet and hand it straight to dtn7 via ECLA.
 *
 * BLE roles run in parallel:
 *   - Peripheral: we advertise our [SERVICE_UUID] and run a GATT server exposing
 *     a single write-without-response RX characteristic [RX_CHAR_UUID].
 *   - Central: we scan for the same SERVICE_UUID, connect to discovered peers as
 *     a GATT client, negotiate the largest MTU we can, and try to upgrade the PHY
 *     to LE Coded (long range) on supporting devices.
 *
 * Addressing: dtn7 node-ids ARE the addresses on the BLE transport. Phone A's
 * outgoing beacons carry addr=A_nodeid; phone B's daemon records that and uses
 * dst=A_nodeid when later forwarding bundles to A. Our peer table maps dtn7
 * node-ids onto the BluetoothDevice instances that own them.
 */
class BleCla(
    private val context: Context,
    private val onStatus: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "BleCla"

        /** Random 128-bit UUIDs — both phones must use the same constants. */
        val SERVICE_UUID: UUID = UUID.fromString("5b1e93fe-7eaa-4f5c-93b1-bdc0a1f1d703")
        val RX_CHAR_UUID: UUID = UUID.fromString("5b1e93fe-7eaa-4f5c-93b1-bdc0a1f1d704")

        /** Try to negotiate the largest GATT MTU the platform allows. */
        private const val PREFERRED_MTU = 517

        /** Default ATT MTU minus 3 bytes of overhead — used until MTU exchange completes. */
        private const val DEFAULT_PAYLOAD_PER_WRITE = 20
    }

    // ------------------------------------------------------------------
    // ECLA wiring
    // ------------------------------------------------------------------

    private val ecla = EclaClient(
        claName = EclaClient.DEFAULT_CLA_NAME,
        enableBeacon = true,
        onRegistered = { nodeId -> handleRegistered(nodeId) },
        onForwardData = { pkt -> handleForwardDataFromDaemon(pkt) },
        onBeacon = { pkt -> handleBeaconFromDaemon(pkt) },
        onError = { reason -> report("ECLA error: $reason") },
        onStatus = { report(it) }
    )

    // LONG_OR_DOUBLE keeps integers inside heterogeneous arrays as Long so
    // BeaconPacket.eid survives gson round-trips intact.
    private val gson = GsonBuilder()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    private fun normalizeAddr(addr: String?): String? =
        addr?.replace(Regex(":\\d+$"), "")

    @Volatile private var ourNodeId: String? = null
    @Volatile private var started: Boolean = false

    private val workerThread = HandlerThread("BleCla").apply { start() }
    private val handler = Handler(workerThread.looper)

    // ------------------------------------------------------------------
    // BLE state
    // ------------------------------------------------------------------

    private val btManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val btAdapter: BluetoothAdapter? get() = btManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    /** Per-peer chunk reassembly for our GATT server's RX characteristic. */
    private val rxReassembler = BleFramer.Reassembler()

    /** All peers we've initiated a GATT client connection to, keyed by BT device address. */
    private val peersByDevice = ConcurrentHashMap<String, PeerConnection>()

    /**
     * Peers we know how to address by dtn7 node-id (populated when we receive a Beacon
     * over BLE from a peer). Same PeerConnection instances as in [peersByDevice].
     */
    private val peersByNodeId = ConcurrentHashMap<String, PeerConnection>()

    private inner class PeerConnection(
        val device: BluetoothDevice,
        @Volatile var gatt: BluetoothGatt
    ) {
        @Volatile var mtu: Int = 23
        @Volatile var nodeId: String? = null
        @Volatile var rxChar: BluetoothGattCharacteristic? = null
        @Volatile var ready: Boolean = false

        fun payloadPerWrite(): Int = (mtu - 3).coerceAtLeast(DEFAULT_PAYLOAD_PER_WRITE)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true

        if (btAdapter == null) {
            report("This device has no Bluetooth adapter")
            return
        }
        if (btAdapter?.isEnabled != true) {
            report("Bluetooth is off — turn it on for BLE DTN")
            // Carry on anyway — adapter state can change while we run; the OS will deny ops.
        }

        // ECLA first — once registered we know our node-id, and only then is it useful
        // to advertise (because outgoing beacons need our node-id as `addr`).
        ecla.start()
    }

    fun stop() {
        if (!started) return
        started = false

        stopAdvertising()
        stopScanning()
        closeGattServer()
        peersByDevice.values.forEach { safeClose(it) }
        peersByDevice.clear()
        peersByNodeId.clear()
        ecla.stop()
    }

    private fun handleRegistered(nodeId: String) {
        ourNodeId = nodeId
        report("ECLA up. node-id='$nodeId'. Bringing BLE stack up.")
        handler.post {
            openGattServer()
            startAdvertising()
            startScanning()
        }
    }

    // ------------------------------------------------------------------
    // ECLA → BLE
    // ------------------------------------------------------------------

    private fun handleForwardDataFromDaemon(pkt: ForwardDataPacket) {
        val target = peersByNodeId[normalizeAddr(pkt.dst)]
        if (target == null) {
            // We may not have heard a beacon from this peer yet, or they're out of range.
            // dtn7 will retry via its janitor mechanism, so a drop here is fine.
            Log.w(TAG, "No BLE peer for dst=${pkt.dst} — dropping")
            return
        }
        val outgoing = pkt.copy(src = ourNodeId ?: pkt.src)
        sendJsonToPeer(target, outgoing)
    }

    private fun handleBeaconFromDaemon(pkt: BeaconPacket) {
        // Stamp our address so peers can route bundles back at us.
        val outgoing = pkt.copy(addr = ourNodeId)
        val targets = peersByDevice.values.filter { it.ready }
        if (targets.isEmpty()) return
        for (peer in targets) {
            sendJsonToPeer(peer, outgoing)
        }
    }

    private fun sendJsonToPeer(peer: PeerConnection, pkt: EclaPacket) {
        if (!peer.ready || peer.rxChar == null) {
            Log.w(TAG, "Peer not ready, dropping outgoing packet")
            return
        }
        val payload = gson.toJson(pkt).toByteArray(Charsets.UTF_8)
        val chunks = BleFramer.fragment(payload, peer.payloadPerWrite())
        for (chunk in chunks) {
            writeChunkWithRetry(peer, chunk)
        }
    }

    @SuppressLint("MissingPermission") // gated by manifest + runtime check before start()
    private fun writeChunkWithRetry(peer: PeerConnection, chunk: ByteArray) {
        val char = peer.rxChar ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = peer.gatt.writeCharacteristic(
                char,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            if (result != BluetoothStatusCodes.SUCCESS) {
                Log.w(TAG, "writeCharacteristic returned $result")
            }
        } else {
            @Suppress("DEPRECATION")
            char.value = chunk
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            val accepted = peer.gatt.writeCharacteristic(char)
            if (!accepted) {
                Log.w(TAG, "writeCharacteristic queue rejected chunk")
            }
        }
    }

    // ------------------------------------------------------------------
    // BLE → ECLA
    // ------------------------------------------------------------------

    private fun handleIncomingJson(from: BluetoothDevice, jsonBytes: ByteArray) {
        val text = String(jsonBytes, Charsets.UTF_8)
        val obj = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from peer ${from.address}: $text"); return
        }
        when (obj.get("type")?.asString) {
            "Beacon" -> {
                val pkt = gson.fromJson(obj, BeaconPacket::class.java)
                val peerAddr = normalizeAddr(pkt.addr)
                if (peerAddr != null) {
                    val pc = peersByDevice[from.address]
                    if (pc != null) {
                        pc.nodeId = peerAddr
                        peersByNodeId[peerAddr] = pc
                        report("Peer learned: $peerAddr -> ${from.address}")
                    }
                }
                ecla.sendBeaconToDaemon(pkt)
            }
            "ForwardData" -> {
                val pkt = gson.fromJson(obj, ForwardDataPacket::class.java)
                ecla.sendForwardDataToDaemon(pkt)
            }
            else -> Log.w(TAG, "Unknown packet from peer: $text")
        }
    }

    // ------------------------------------------------------------------
    // Peripheral side: GATT server + advertising
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openGattServer() {
        if (gattServer != null) return
        val server = btManager.openGattServer(context, gattServerCallback) ?: run {
            report("openGattServer returned null"); return
        }
        gattServer = server

        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(rxChar)
        server.addService(service)
        report("GATT server up with RX characteristic")
    }

    @SuppressLint("MissingPermission")
    private fun closeGattServer() {
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Just log — peer's writes will arrive on onCharacteristicWriteRequest
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                report("GATT server: incoming connection from ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxReassembler.forget(device.address)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                val assembled = rxReassembler.feed(device.address, value)
                if (assembled != null) {
                    try {
                        handleIncomingJson(device, assembled)
                    } catch (e: Exception) {
                        Log.e(TAG, "handleIncomingJson failed", e)
                    }
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (advertiser != null) return
        val adv = btAdapter?.bluetoothLeAdvertiser ?: run {
            report("BLE advertising not supported on this device"); return
        }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        adv.startAdvertising(settings, data, advertiseCallback)
        report("BLE advertising started")
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            report("BLE advertising failed: $errorCode")
        }
    }

    // ------------------------------------------------------------------
    // Central side: scanner + GATT client connections
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (scanner != null) return
        val s = btAdapter?.bluetoothLeScanner ?: run {
            report("BLE scanner not available"); return
        }
        scanner = s
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        s.startScan(listOf(filter), settings, scanCallback)
        report("BLE scanning for peers")
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (peersByDevice.containsKey(device.address)) return
            // NOTE: ideally we'd use a tie-breaker (lower-addressed peer initiates) to avoid
            // both phones opening parallel GATT client connections to each other. But Android
            // hides the local BT MAC since API 23 (returns 02:00:00:00:00:00), so we can't
            // reliably do that. Two parallel connections is wasteful but functional — dtn7
            // dedupes bundles by bundle-id at the routing layer, so duplicate deliveries are
            // a no-op. TODO: encode a random session-id into the advertisement service data
            // and use that for the tie-breaker.
            report("Discovered peer ${device.address}; initiating GATT")
            val gatt = device.connectGatt(
                context,
                /* autoConnect = */ false,
                gattClientCallback,
                BluetoothDevice.TRANSPORT_LE
            ) ?: return
            peersByDevice[device.address] = PeerConnection(device, gatt)
        }

        override fun onScanFailed(errorCode: Int) {
            report("BLE scan failed: $errorCode")
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            val peer = peersByDevice[addr]
            if (peer == null) {
                try { gatt.close() } catch (_: Exception) {}
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                report("GATT client connected to $addr")
                peer.gatt = gatt
                gatt.requestMtu(PREFERRED_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                report("GATT client disconnected from $addr")
                peer.ready = false
                peer.nodeId?.let { peersByNodeId.remove(it) }
                peersByDevice.remove(addr)
                try { gatt.close() } catch (_: Exception) {}
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val peer = peersByDevice[gatt.device.address] ?: return
            peer.mtu = mtu
            report("MTU=$mtu with ${gatt.device.address}")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val peer = peersByDevice[gatt.device.address] ?: return
            val service = gatt.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_CHAR_UUID)
            if (rx == null) {
                report("Peer ${gatt.device.address} missing our RX characteristic")
                try { gatt.disconnect() } catch (_: Exception) {}
                return
            }
            peer.rxChar = rx
            peer.ready = true
            report("Peer ${gatt.device.address} ready")

            // Try to upgrade to BLE 5 Long Range PHY for extra outdoor reach.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    gatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_CODED,
                        BluetoothDevice.PHY_LE_CODED,
                        BluetoothDevice.PHY_OPTION_S8
                    )
                } catch (_: Throwable) {
                    // unsupported on this device — stays on 1M PHY, still functional
                }
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            report("PHY tx=$txPhy rx=$rxPhy (LE_CODED=3) with ${gatt.device.address}")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun safeClose(peer: PeerConnection) {
        try { peer.gatt.disconnect() } catch (_: Exception) {}
        try { peer.gatt.close() } catch (_: Exception) {}
    }

    private fun report(msg: String) {
        Log.i(TAG, msg)
        onStatus(msg)
    }
}
