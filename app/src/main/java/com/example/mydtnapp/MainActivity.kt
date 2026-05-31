package com.example.mydtnapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.database.sqlite.SQLiteDatabase
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mydtnapp.model.*
import com.example.mydtnapp.network.ApiClient
import com.example.mydtnapp.network.ble.BleCla
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface


class MainActivity : AppCompatActivity() {

    external fun startdaemon()

    private lateinit var btnRefreshBundles: Button
    private lateinit var btnSendAudio: Button
    private lateinit var btnSendTinyBundle: Button
    private lateinit var bundleAdapter: BundleAdapter

    private val audioInDir: File by lazy {
        File(filesDir, "audio_in").apply { mkdirs() }
    }

    private var mediaPlayer: MediaPlayer? = null

    private val bundlesMap = mutableMapOf<String, BundleInfo>()
    private val processedBundles = mutableSetOf<String>()
    private val POLL_INTERVAL = 5_000L

    private lateinit var db: SQLiteDatabase

    private lateinit var bleCla: BleCla

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            bleCla.start()
        } else {
            Log.w("DTN-MAIN", "BLE permissions denied")
            Toast.makeText(this, "BLE permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        copyConfigIfNeeded()

        val rv = findViewById<RecyclerView>(R.id.rvBundles)
        bundleAdapter = BundleAdapter(
            onDelete = { id ->
                lifecycleScope.launch { ApiClient.service.deleteBundle("delete?$id") }
            },
            onPlay = { path -> playAudio(path) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = bundleAdapter

        btnRefreshBundles = findViewById(R.id.btnRefreshBundles)
        btnRefreshBundles.setOnClickListener { refreshBundles() }
        refreshBundles()

        btnSendAudio = findViewById(R.id.btnSendAudio)
        btnSendAudio.setOnClickListener { sendTestAudio() }

        btnSendTinyBundle = findViewById(R.id.btnSendTinyBundle)
        btnSendTinyBundle.setOnClickListener { sendTinyBundle() }

        db = openOrCreateDatabase("dtndb", MODE_PRIVATE, null)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS acks (
                bundle_id TEXT PRIMARY KEY
            )
        """)

        lifecycleScope.launch(Dispatchers.IO) {
            startdaemon()
        }

        lifecycleScope.launch {
            delay(1_000)
            while (isActive) {
                try {
                    pollStatusBundles()
                } catch (e: Exception) {
                    Log.w("DTN-MAIN", "Erro no pollStatusBundles. ${e.localizedMessage}")
                }
                delay(POLL_INTERVAL)
            }
        }

        setupMulticast()

        lifecycleScope.launch {
            delay(3000)
            setupBleCla()
        }
    }

    private fun setupBleCla() {
        bleCla = BleCla(
            context = applicationContext,
            onStatus = { Log.d("DTN-BLE", it) }
        )

        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter { checkSelfPermission(it) != PERMISSION_GRANTED }
        if (missing.isEmpty()) bleCla.start()
        else blePermissionLauncher.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        if (::bleCla.isInitialized) {
            bleCla.stop()
        }
        super.onDestroy()
    }

    private fun saveAck(id: String) {
        db.execSQL("INSERT OR IGNORE INTO acks (bundle_id) VALUES (?)", arrayOf(id))
    }

    private fun ackExists(id: String): Boolean {
        val cursor = db.rawQuery("SELECT 1 FROM acks WHERE bundle_id = ?", arrayOf(id))
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    private fun extractRawId(bundleId: String): String? {
        val parts = bundleId.split("-")
        return if (parts.size >= 2) "-${parts[1]}" else null
    }

    private suspend fun pollStatusBundles() {
        val statusList = try {
            ApiClient.service.listBundleStatus()
        } catch (e: Exception) {
            Log.e("DTN-MAIN", "Erro ao listar bundles: ${e.message}")
            return
        }

        statusList.forEach { line ->
            val parts = line.trim().split(" ")
            if (parts.size < 4) {
                Log.w("DTN-MAIN", "Linha malformada: $line")
                return@forEach
            }

            val source = parts[0]
            val dest = parts[1]
            val rawId = parts[2]
            val bundleId = "$source-$rawId-0"

            // outgoing bundles transit the local storage, skip them.
            if (source == OUR_NODE_ID) return@forEach

            if (processedBundles.contains(rawId) || ackExists(rawId)) return@forEach

            if (dest.contains("/acks")) {
                val resp = try {
                    ApiClient.service.downloadBundle("download?$bundleId")
                } catch (e: Exception) {
                    Log.e("DTN-MAIN", "Erro ao baixar ACK $bundleId: ${e.message}")
                    return@forEach
                }

                if (!resp.isSuccessful) {
                    Log.w("DTN-MAIN", "Falha no download do ACK $bundleId: ${resp.code()}")
                    return@forEach
                }

                val rawBytes = resp.body()!!.bytes()
                val jsonStart = rawBytes.indexOf('{'.code.toByte())
                val jsonEnd = rawBytes.lastIndexOf('}'.code.toByte())

                if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                    Log.e("DTN-MAIN", "JSON malformado no ACK $bundleId")
                    return@forEach
                }

                val jsonBytes = rawBytes.sliceArray(jsonStart..jsonEnd)
                val jsonText = String(jsonBytes, Charsets.UTF_8).trim()
                Log.d("DTN-MAIN", "ACK $bundleId JSON extraído:\n$jsonText")

                try {
                    val ack = Gson().fromJson(jsonText, AckInfo::class.java)
                    val rawFromAck = extractRawId(ack.bundle_id)

                    if (rawFromAck != null) {
                        saveAck(rawFromAck)
                        processedBundles.add(rawId)

                        bundlesMap[ack.bundle_id]?.let { orig ->
                            bundlesMap[ack.bundle_id] = orig.copy(ackSent = true)
                        }

                        val bundleToDelete = ack.bundle_id

                        try {
                            ApiClient.service.deleteBundle("delete?$bundleToDelete")
                            Log.i("DTN-MAIN", "Bundle original $bundleToDelete deletado após ACK.")
                        } catch (e: Exception) {
                            Log.w("DTN-MAIN", "Erro ao deletar bundle $bundleToDelete: ${e.message}")
                        }

                    }

                } catch (e: Exception) {
                    Log.e("DTN-MAIN", "Erro ao interpretar JSON do ACK $bundleId", e)
                }

                return@forEach
            }

            if (dest.contains("/incoming")) {
                val resp = try {
                    ApiClient.service.downloadBundle("download?$bundleId")
                } catch (e: Exception) {
                    Log.e("DTN-MAIN", "Erro ao baixar bundle $bundleId: ${e.message}")
                    return@forEach
                }

                if (!resp.isSuccessful) {
                    Log.w("DTN-MAIN", "Falha no download de $bundleId: ${resp.code()}")
                    return@forEach
                }

                val rawBytes = resp.body()!!.bytes()
                val mp3Offset = findMp3Offset(rawBytes)
                if (mp3Offset >= 0) {
                    val file = File(audioInDir, "${rawId.trimStart('-')}.mp3")
                    file.writeBytes(rawBytes.copyOfRange(mp3Offset, rawBytes.size))
                    Log.i("DTN-MAIN", "Saved audio bundle $bundleId -> ${file.absolutePath} (${file.length()} bytes)")
                    processedBundles.add(rawId)
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Audio received: ${file.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@forEach
                }

                val jsonStart = rawBytes.indexOf('{'.code.toByte())
                val jsonEnd = rawBytes.lastIndexOf('}'.code.toByte())

                if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                    Log.e("DTN-MAIN", "Bundle $bundleId: not mp3, no JSON either")
                    return@forEach
                }

                val jsonBytes = rawBytes.sliceArray(jsonStart..jsonEnd)
                val jsonText = String(jsonBytes, Charsets.UTF_8).trim()
                Log.d("DTN-MAIN", "Bundle $bundleId JSON extraido:\n$jsonText")

                try {
                    val payload = Gson().fromJson(jsonText, IncomingPayload::class.java)
                    val birds = payload.detections.map { it.Com_Name }
                    val audioPath = saveBundleAudioIfPresent(rawId, payload.mp3_data)
                    val prevAck = bundlesMap[bundleId]?.ackSent ?: false
                    bundlesMap[bundleId] = BundleInfo(bundleId, birds, prevAck, audioPath)
                    processedBundles.add(rawId)
                } catch (e: Exception) {
                    Log.e("DTN-MAIN", "Erro ao interpretar JSON do bundle $bundleId", e)
                }

                return@forEach
            }
        }

        runOnUiThread {
            bundleAdapter.submitList(bundlesMap.values.toList())
        }
    }

    private fun refreshBundles() {
        lifecycleScope.launch {
            try {
                // futuro uso
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    "Erro ao carregar bundles: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                Log.e("DTN-MAIN", "refreshBundles failed", e)
            }
        }
    }

    private fun setupMulticast() {
        val group = InetAddress.getByName("224.0.0.1")
        val socket = MulticastSocket(9999)
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isUp && !iface.isLoopback && iface.supportsMulticast()) {
                socket.joinGroup(InetSocketAddress(group, 9999), iface)
                Log.i("DTN-KOTLIN", "Joined multicast on ${iface.displayName}")
                break
            }
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("myDtnMulticastLock")
        lock.setReferenceCounted(true)
        lock.acquire()
        Log.i("DTN-KOTLIN", "Multicast lock acquired")
    }

    private fun copyConfigIfNeeded() {
        val configFile = File(filesDir, "dtnd.toml")
        val assetBytes = assets.open("dtnd.toml").use { it.readBytes() }
        val currentBytes = if (configFile.exists()) configFile.readBytes() else ByteArray(0)
        if (!assetBytes.contentEquals(currentBytes)) {
            configFile.writeBytes(assetBytes)
            Log.i("DTN-KOTLIN", "Refreshed dtnd.toml")
        }
    }

    private fun saveBundleAudioIfPresent(rawId: String, mp3Base64: String?): String? {
        if (mp3Base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(mp3Base64, Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val file = File(audioInDir, "${rawId.trimStart('-')}.mp3")
            file.writeBytes(bytes)
            Log.i("DTN-MAIN", "Saved audio: ${file.name} (${bytes.size} B)")
            file.absolutePath
        } catch (e: Exception) {
            Log.e("DTN-MAIN", "Failed to decode mp3_data for $rawId", e)
            null
        }
    }

    private fun playAudio(path: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("DTN-MAIN", "Playback failed for $path", e)
            Toast.makeText(this, "Playback failed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Offset of an ID3v2 tag or MPEG frame sync inside [data], or -1. */
    private fun findMp3Offset(data: ByteArray): Int {
        for (i in 0..data.size - 3) {
            if (data[i] == 'I'.code.toByte()
                && data[i + 1] == 'D'.code.toByte()
                && data[i + 2] == '3'.code.toByte()) return i
        }
        for (i in 0..data.size - 2) {
            val b1 = data[i].toInt() and 0xFF
            val b2 = data[i + 1].toInt() and 0xFF
            if (b1 == 0xFF && (b2 and 0xE0) == 0xE0) return i
        }
        return -1
    }

    private fun sendTinyBundle() {
        lifecycleScope.launch {
            val bytes = "hello from phone @ ${System.currentTimeMillis()}".toByteArray()
            val body = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            try {
                val resp = ApiClient.service.sendBundle(
                    dst = "dtn://pi-collector/incoming", body = body
                )
                if (resp.isSuccessful) {
                    Log.i("DTN-MAIN", "Tiny bundle submitted (${bytes.size} B)")
                    Toast.makeText(this@MainActivity,
                        "Tiny bundle submitted", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w("DTN-MAIN", "sendBundle ${resp.code()} ${resp.message()}")
                    Toast.makeText(this@MainActivity,
                        "Send failed: ${resp.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("DTN-MAIN", "sendBundle threw", e)
            }
        }
    }

    // Splits the test mp3 into bundles small enough to fit the embedded
    // dtnd's loopback WS limit (~8 KB per frame). Each chunk gets a 16-byte
    // header: "DTNAUDIO" | audio_id (4) | idx (2) | total (2).
    private fun sendTestAudio() {
        lifecycleScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    assets.open("test.mp3").use { it.readBytes() }
                }
            } catch (e: Exception) {
                Log.e("DTN-MAIN", "Missing assets/test.mp3", e)
                Toast.makeText(this@MainActivity,
                    "Missing assets/test.mp3", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val chunkSize = 1024
            val totalChunks = (bytes.size + chunkSize - 1) / chunkSize
            val audioId = Random.nextInt()
            Log.i("DTN-MAIN", "Sending ${bytes.size} B as $totalChunks×$chunkSize chunks (id=$audioId)")

            for (idx in 0 until totalChunks) {
                val start = idx * chunkSize
                val end = minOf(start + chunkSize, bytes.size)
                val header = ByteBuffer.allocate(16)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put("DTNAUDIO".toByteArray(Charsets.US_ASCII))
                    .putInt(audioId)
                    .putShort(idx.toShort())
                    .putShort(totalChunks.toShort())
                    .array()
                val payload = header + bytes.copyOfRange(start, end)
                val body = payload.toRequestBody("application/octet-stream".toMediaTypeOrNull())

                try {
                    val resp = ApiClient.service.sendBundle(
                        dst = "dtn://pi-collector/incoming", body = body
                    )
                    if (!resp.isSuccessful) {
                        Log.w("DTN-MAIN", "Chunk $idx/$totalChunks: ${resp.code()}")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e("DTN-MAIN", "Chunk $idx threw", e)
                    return@launch
                }
                delay(150)
            }

            Toast.makeText(this@MainActivity,
                "Audio submitted in $totalChunks chunks", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        // Keep in sync with `nodeid` in assets/dtnd.toml.
        private const val OUR_NODE_ID = "dtn://android/"

        init {
            System.loadLibrary("dtnbridge")
        }
    }
}
