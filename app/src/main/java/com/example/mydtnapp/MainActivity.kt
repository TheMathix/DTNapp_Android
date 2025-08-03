package com.example.mydtnapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mydtnapp.model.*
import com.example.mydtnapp.network.ApiClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface


class MainActivity : AppCompatActivity() {

    external fun startdaemon()

    private lateinit var btnRefreshBundles: Button
    private lateinit var bundleAdapter: BundleAdapter

    private val bundlesMap = mutableMapOf<String, BundleInfo>()
    private val processedBundles = mutableSetOf<String>()
    private val POLL_INTERVAL = 5_000L

    private lateinit var db: SQLiteDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rv = findViewById<RecyclerView>(R.id.rvBundles)
        bundleAdapter = BundleAdapter { id ->
            lifecycleScope.launch { ApiClient.service.deleteBundle("delete?$id") }
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = bundleAdapter

        btnRefreshBundles = findViewById(R.id.btnRefreshBundles)
        btnRefreshBundles.setOnClickListener { refreshBundles() }
        refreshBundles()

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
        copyConfigIfNeeded()
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
            val rawId = parts[2]
            val bundleId = "$source-$rawId-0"

            if (processedBundles.contains(rawId) || ackExists(rawId)) {
                Log.d("DTN-MAIN", "Ignorando bundle já processado ou ack: $rawId")
                return@forEach
            }

            val resp = try {
                ApiClient.service.downloadBundle("download?$bundleId")
            } catch (e: Exception) {
                Log.e("DTN-MAIN", "Erro ao baixar $bundleId: ${e.message}")
                return@forEach
            }

            if (!resp.isSuccessful) {
                Log.w("DTN-MAIN", "Falha no download de $bundleId: ${resp.code()}")
                return@forEach
            }

            val rawBytes = resp.body()!!.bytes()
            val jsonStart = rawBytes.indexOf('{'.code.toByte())
            val jsonEnd = rawBytes.lastIndexOf('}'.code.toByte())

            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                Log.e("DTN-MAIN", "JSON malformado no bundle $bundleId")
                return@forEach
            }

            val jsonBytes = rawBytes.sliceArray(jsonStart..jsonEnd)
            val jsonText = String(jsonBytes, Charsets.UTF_8).trim()
            Log.d("DTN-MAIN", "Bundle $bundleId JSON extraído:\n$jsonText")

            try {
                if (jsonText.contains("\"detections\"")) {
                    val payload = Gson().fromJson(jsonText, IncomingPayload::class.java)
                    val birds = payload.detections.map { it.Com_Name }
                    val prevAck = bundlesMap[payload.bundle_id]?.ackSent ?: false
                    bundlesMap[payload.bundle_id] = BundleInfo(payload.bundle_id, birds, prevAck)
                    processedBundles.add(rawId)

                } else if (jsonText.contains("\"hash_id\"")) {
                    val ack = Gson().fromJson(jsonText, AckInfo::class.java)
                    bundlesMap[ack.bundle_id]?.let { orig ->
                        bundlesMap[ack.bundle_id] = orig.copy(ackSent = true)
                    }
                    saveAck(ack.bundle_id)
                    processedBundles.add(rawId)
                } else {
                    Log.w("DTN-MAIN", "Conteúdo desconhecido no bundle $bundleId")
                }
            } catch (e: Exception) {
                Log.e("DTN-MAIN", "Erro ao interpretar JSON do bundle $bundleId", e)
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
        if (!configFile.exists()) {
            assets.open("dtnd.toml").use { inp ->
                configFile.outputStream().use { out ->
                    inp.copyTo(out)
                }
            }
            Log.i("DTN-KOTLIN", "Copied dtnd.toml to ${configFile.absolutePath}")
        }
    }

    companion object {
        init {
            System.loadLibrary("dtnbridge")
        }
    }
}
