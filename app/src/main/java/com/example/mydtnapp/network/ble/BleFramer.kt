// network/ble/BleFramer.kt
package com.example.mydtnapp.network.ble

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny framing layer to send arbitrary-length payloads over BLE GATT writes,
 * which are capped at MTU-3 bytes per write (usually ~500 with negotiation).
 *
 * Each chunk on the wire is:
 *   [1 byte flags][payload bytes]
 * where flags is one of:
 *   FLAG_SINGLE — whole payload fits in one chunk
 *   FLAG_START  — first of multiple
 *   0x00        — middle chunk
 *   FLAG_END    — last of multiple
 *
 * Receivers concatenate chunks keyed by source device address, since multiple peers
 * may interleave writes on the same characteristic of our GATT server.
 */
object BleFramer {

    const val FLAG_START: Int = 0x01
    const val FLAG_END: Int = 0x02
    const val FLAG_SINGLE: Int = 0x04

    /**
     * Split [payload] into a list of on-wire chunks no larger than [maxChunkBytes] each
     * (including the 1-byte flags header).
     */
    fun fragment(payload: ByteArray, maxChunkBytes: Int): List<ByteArray> {
        require(maxChunkBytes >= 2) { "maxChunkBytes must leave room for header + 1 byte" }
        val maxPayloadPerChunk = maxChunkBytes - 1

        if (payload.size <= maxPayloadPerChunk) {
            val out = ByteArray(payload.size + 1)
            out[0] = FLAG_SINGLE.toByte()
            System.arraycopy(payload, 0, out, 1, payload.size)
            return listOf(out)
        }

        val chunks = ArrayList<ByteArray>()
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val take = minOf(maxPayloadPerChunk, payload.size - offset)
            val isLast = (offset + take >= payload.size)
            val flag = when {
                first -> FLAG_START
                isLast -> FLAG_END
                else -> 0
            }
            val chunk = ByteArray(take + 1)
            chunk[0] = flag.toByte()
            System.arraycopy(payload, offset, chunk, 1, take)
            chunks.add(chunk)
            offset += take
            first = false
        }
        return chunks
    }

    /**
     * Per-peer chunk reassembler. Thread-safe across peers; not safe for concurrent
     * writes from the same peer (which BLE doesn't do anyway — writes from one device
     * to the same characteristic are serialised by the stack).
     */
    class Reassembler {
        private val buffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

        /**
         * Feed an incoming chunk from [peerKey]. Returns the fully reassembled payload
         * when a complete message has been received, otherwise null.
         */
        fun feed(peerKey: String, chunk: ByteArray): ByteArray? {
            if (chunk.isEmpty()) return null
            val flag = chunk[0].toInt() and 0xFF
            val payload = if (chunk.size > 1) chunk.copyOfRange(1, chunk.size) else ByteArray(0)

            if (flag and FLAG_SINGLE != 0) {
                buffers.remove(peerKey)
                return payload
            }
            if (flag and FLAG_START != 0) {
                val buf = ByteArrayOutputStream()
                buf.write(payload)
                buffers[peerKey] = buf
                return null
            }
            val existing = buffers[peerKey] ?: return null
            existing.write(payload)
            if (flag and FLAG_END != 0) {
                buffers.remove(peerKey)
                return existing.toByteArray()
            }
            return null
        }

        fun forget(peerKey: String) {
            buffers.remove(peerKey)
        }
    }
}
