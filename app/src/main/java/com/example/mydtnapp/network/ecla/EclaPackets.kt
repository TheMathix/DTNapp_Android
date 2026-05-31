// network/ecla/EclaPackets.kt
package com.example.mydtnapp.network.ecla

import com.google.gson.annotations.SerializedName

/**
 * The 5 ECLA packet types defined in dtn7-rs/doc/ecla.md.
 * All are JSON-encoded with a "type" discriminator field.
 */
sealed class EclaPacket {
    abstract val type: String
}

/** external -> dtnd. Must be the first packet after WebSocket connect. */
data class RegisterPacket(
    @SerializedName("name") val name: String,
    @SerializedName("enable_beacon") val enableBeacon: Boolean
) : EclaPacket() {
    @SerializedName("type") override val type: String = "Register"
}

/** dtnd -> external. Sent in response to a successful Register. */
data class RegisteredPacket(
    /** [scheme_code: Int, eid_string: String] */
    @SerializedName("eid") val eid: List<Any>,
    @SerializedName("nodeid") val nodeId: String
) : EclaPacket() {
    @SerializedName("type") override val type: String = "Registered"
}

/** dtnd -> external. Sent if registration fails. */
data class ErrorPacket(
    @SerializedName("reason") val reason: String
) : EclaPacket() {
    @SerializedName("type") override val type: String = "Error"
}

/**
 * Bidirectional. Carries a single bundle.
 * - data: base64 of CBOR-encoded bundle.
 * - src: when sending to dtnd, set to the peer that gave it to us.
 *        when receiving from dtnd, we set src=our node-id before sending over BLE.
 * - dst: the destination node's reachable address. We use dtn7 node IDs as addresses.
 */
data class ForwardDataPacket(
    @SerializedName("src") val src: String,
    @SerializedName("dst") val dst: String,
    @SerializedName("bundle_id") val bundleId: String,
    @SerializedName("data") val data: String
) : EclaPacket() {
    @SerializedName("type") override val type: String = "ForwardData"
}

/**
 * Bidirectional. dtn7 emits these periodically for peer discovery.
 * - eid: [scheme_code, eid_string] of the originating node
 * - addr: the originator's address in this transmission layer.
 *         when sending to peers, we overwrite this with our node-id so peers can route back.
 * - service_block: base64-encoded CBOR of advertised services. We pass it through opaquely.
 */
data class BeaconPacket(
    @SerializedName("eid") val eid: List<Any>,
    @SerializedName("addr") val addr: String?,
    @SerializedName("service_block") val serviceBlock: String
) : EclaPacket() {
    @SerializedName("type") override val type: String = "Beacon"
}
